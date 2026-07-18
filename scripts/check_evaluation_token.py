"""Validate a real evaluation identity JWT against the published JWKS."""

import argparse
import json
from pathlib import Path
from typing import Any

import jwt


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--token-file", required=True, type=Path)
    parser.add_argument("--jwks-file", required=True, type=Path)
    parser.add_argument("--issuer", required=True)
    parser.add_argument("--audience", required=True)
    parser.add_argument("--token-type", required=True)
    parser.add_argument("--sandbox", required=True)
    parser.add_argument("--maximum-expiry", required=True, type=int)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    token = args.token_file.read_text(encoding="utf-8").strip()
    jwks: Any = json.loads(args.jwks_file.read_text(encoding="utf-8"))
    header = jwt.get_unverified_header(token)
    if header.get("alg") != "RS256" or not isinstance(header.get("kid"), str):
        raise ValueError("Unexpected JWT header")
    matching = [
        item
        for item in jwks.get("keys", [])
        if isinstance(item, dict) and item.get("kid") == header["kid"]
    ]
    if len(matching) != 1:
        raise ValueError("JWT signing key is not uniquely published")
    key = jwt.PyJWK.from_dict(matching[0]).key
    claims = jwt.decode(
        token,
        key,
        algorithms=["RS256"],
        audience=args.audience,
        issuer=args.issuer,
        options={"require": ["exp", "iat", "nbf", "iss", "aud", "sub", "jti"]},
    )
    if claims.get("token_type") != args.token_type:
        raise ValueError("Unexpected token type")
    if claims.get("sandbox") != args.sandbox:
        raise ValueError("Unexpected sandbox binding")
    if "eval_sandbox" in claims:
        raise ValueError("Legacy evaluation claim is not allowed")
    if not isinstance(claims.get("sub"), str) or not claims["sub"].startswith("eval-"):
        raise ValueError("Evaluation subject is not server owned")
    if claims["exp"] > args.maximum_expiry:
        raise ValueError("Token outlives its source provisioning record")
    forbidden = {
        "opaque_handle",
        "case_correlation",
        "test_user_label",
        "provision_idempotency_key",
        "revoke_idempotency_key",
        "credential",
        "password",
    }
    if forbidden & claims.keys():
        raise ValueError("Private provisioning metadata leaked into JWT")
    if args.token_type == "eval_direct_user":
        if claims.get("principal_state") != "ACTIVE":
            raise ValueError("Evaluation direct token is not active")
        if claims.get("permissions") != ["support:session:create", "support:chat"]:
            raise ValueError("Evaluation direct token permissions changed")
        if "act" in claims or "session" in claims or "scope" in claims:
            raise ValueError("Evaluation direct token carries delegated authority")
    elif args.token_type == "agent_obo":
        if claims.get("user_id") != claims["sub"]:
            raise ValueError("OBO user binding changed")
        if claims.get("scope") != "catalog:read" or claims.get("session") != "eval-session-1":
            raise ValueError("OBO scope or session binding changed")
        if claims.get("act") != {"azp": "agent-service"}:
            raise ValueError("OBO actor binding changed")
    args.output.write_text(
        json.dumps(
            {
                "subject": claims["sub"],
                "expiresAt": str(claims["exp"]),
                "sandbox": claims["sandbox"],
                "tokenType": claims["token_type"],
            },
            sort_keys=True,
        ),
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
