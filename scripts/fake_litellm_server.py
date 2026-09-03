"""Deterministic LiteLLM-compatible fixture for agent boundary evidence."""

from __future__ import annotations

import argparse
import asyncio
import json
import re
from collections import Counter
from typing import Any

import httpx
import uvicorn
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response

app = FastAPI(docs_url=None, redoc_url=None)
ACTION_ORDER_PATTERN = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
WIRE_TOOL_NAME_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
CATALOG_PRODUCT_WIRE_NAME = "catalog_product_get"
KNOWLEDGE_SEARCH_WIRE_NAME = "knowledge_search"
REFUND_PREPARE_WIRE_NAME = "actions_refund_prepare"
counts: Counter[str] = Counter()
commerce_base_url = ""


def scenario(message: str) -> str:
    for value in (
        "tool-success",
        "tool-malformed",
        "tool-unknown",
        "tool-timeout",
        "budget-exhaustion",
        "transient-retry",
        "same-tier-fallback",
        "circuit-fail",
        "circuit-open",
        "circuit-recover",
        "provider-failure",
        "usage-fixture",
        "unsafe-action-claim",
        "action-prepare",
        "context-seed",
        "context-followup",
        "disconnect-slow",
        "retrieval-sufficient",
        "retrieval-insufficient",
        "retrieval-ambiguous",
        "retrieval-malformed",
        "retrieval-timeout",
        "retrieval-transient",
    ):
        if value in message:
            return value
    return "default"


def response_message(content: str) -> dict[str, object]:
    return {"choices": [{"message": {"content": content}}]}


def tool_message(name: str, arguments: str) -> dict[str, object]:
    return {
        "choices": [
            {
                "message": {
                    "content": None,
                    "tool_calls": [
                        {
                            "id": "server-owned-tool-call",
                            "type": "function",
                            "function": {"name": name, "arguments": arguments},
                        }
                    ],
                }
            }
        ]
    }


def valid_tool_schemas(value: object) -> bool:
    if not isinstance(value, list):
        return False
    for item in value:
        function = item.get("function") if isinstance(item, dict) else None
        name = function.get("name") if isinstance(function, dict) else None
        if not isinstance(name, str) or WIRE_TOOL_NAME_PATTERN.fullmatch(name) is None:
            return False
    return True


def valid_message_tool_names(value: object) -> bool:
    if not isinstance(value, list):
        return False
    for item in value:
        tool_calls = item.get("tool_calls") if isinstance(item, dict) else None
        if tool_calls is None:
            continue
        if not isinstance(tool_calls, list):
            return False
        for call in tool_calls:
            function = call.get("function") if isinstance(call, dict) else None
            name = function.get("name") if isinstance(function, dict) else None
            if not isinstance(name, str) or WIRE_TOOL_NAME_PATTERN.fullmatch(name) is None:
                return False
    return True


def current_support_message(messages: list[object]) -> str | None:
    if not messages or not isinstance(messages[0], dict) or messages[0].get("role") != "system":
        return None
    user_indexes = [
        index
        for index, item in enumerate(messages)
        if isinstance(item, dict) and item.get("role") == "user"
    ]
    if not user_indexes:
        return None
    current_index = user_indexes[-1]
    history = messages[1:current_index]
    if len(history) % 2 != 0:
        return None
    for index, item in enumerate(history):
        expected_role = "user" if index % 2 == 0 else "assistant"
        if (
            not isinstance(item, dict)
            or item.get("role") != expected_role
            or not isinstance(item.get("content"), str)
        ):
            return None
    current = messages[current_index]
    if not isinstance(current, dict) or not isinstance(current.get("content"), str):
        return None
    current_work = messages[current_index + 1 :]
    if len(current_work) % 2 != 0:
        return None
    for index, item in enumerate(current_work):
        expected_role = "assistant" if index % 2 == 0 else "tool"
        if not isinstance(item, dict) or item.get("role") != expected_role:
            return None
    return str(current["content"])


@app.post("/v1/chat/completions")
async def complete(request: Request) -> JSONResponse:
    payload = await request.json()
    model = payload.get("model") if isinstance(payload, dict) else None
    messages = payload.get("messages") if isinstance(payload, dict) else None
    tools = payload.get("tools") if isinstance(payload, dict) else None
    if (
        not isinstance(model, str)
        or not isinstance(messages, list)
        or not valid_message_tool_names(messages)
    ):
        return JSONResponse(status_code=400, content={"error": "invalid request"})
    if tools is not None and not valid_tool_schemas(tools):
        return JSONResponse(status_code=400, content={"error": "invalid tool schema"})
    user_messages = [
        item.get("content")
        for item in messages
        if isinstance(item, dict) and item.get("role") == "user"
    ]

    if model == "support-reranker-standard":
        if len(user_messages) != 1 or not isinstance(user_messages[0], str):
            return JSONResponse(status_code=400, content={"error": "invalid rerank messages"})
        try:
            rerank_request = json.loads(user_messages[0])
        except json.JSONDecodeError:
            return JSONResponse(status_code=400, content={"error": "invalid rerank input"})
        candidates = rerank_request.get("candidates") if isinstance(rerank_request, dict) else None
        if not isinstance(candidates, list) or not candidates:
            return JSONResponse(status_code=400, content={"error": "invalid candidates"})
        selected = scenario(str(rerank_request.get("query", "")))
        counts[f"{selected}:reranker"] += 1
        if selected == "retrieval-timeout":
            await asyncio.sleep(2.2)
        if selected == "retrieval-transient" and counts[f"{selected}:reranker"] == 1:
            return JSONResponse(status_code=503, content={"error": "transient"})
        if selected == "retrieval-malformed":
            scores: list[dict[str, object]] = [
                {"candidate_id": "not-in-the-fused-set", "score": 0.99}
            ]
        else:
            scores = []
            for rank, candidate in enumerate(candidates):
                candidate_id = candidate.get("candidateId") if isinstance(candidate, dict) else None
                if not isinstance(candidate_id, str):
                    return JSONResponse(status_code=400, content={"error": "invalid identity"})
                if selected == "retrieval-insufficient":
                    score = 0.7 - rank * 0.1
                elif selected == "retrieval-ambiguous":
                    score = 0.9 - rank * 0.1
                else:
                    score = max(0.1, 0.95 - rank * 0.25)
                scores.append({"candidate_id": candidate_id, "score": score})
        return JSONResponse(content=response_message(json.dumps({"scores": scores})))

    current_message = current_support_message(messages)
    if current_message is None:
        return JSONResponse(status_code=400, content={"error": "invalid messages"})
    selected = scenario(current_message)
    counts[f"{selected}:total"] += 1
    counts[f"{selected}:{model}"] += 1
    if selected == "usage-fixture":
        # Synthetic counts test transport and persistence, not real provider consumption.
        usage_response = response_message("Synthetic usage fixture completed.")
        usage_response["usage"] = {
            "prompt_tokens": 120,
            "completion_tokens": 8,
            "total_tokens": 128,
        }
        return JSONResponse(content=usage_response)
    has_tool_feedback = any(
        isinstance(item, dict) and item.get("role") == "tool" for item in messages
    )

    if selected == "transient-retry" and counts[f"{selected}:{model}"] == 1:
        return JSONResponse(status_code=503, content={"error": "transient"})
    if selected == "provider-failure":
        return JSONResponse(status_code=400, content={"error": "terminal"})
    if selected == "disconnect-slow":
        await asyncio.sleep(0.2)
        return JSONResponse(content=response_message("The bounded response completed safely."))
    if selected == "unsafe-action-claim":
        return JSONResponse(content=response_message("Your refund has been issued."))
    if selected == "context-seed":
        return JSONResponse(content=response_message("The session codeword is amber."))
    if selected == "context-followup":
        has_seed_pair = any(
            isinstance(messages[index], dict)
            and messages[index].get("role") == "user"
            and "context-seed" in str(messages[index].get("content"))
            and index + 1 < len(messages)
            and isinstance(messages[index + 1], dict)
            and messages[index + 1].get("role") == "assistant"
            and messages[index + 1].get("content") == "The session codeword is amber."
            for index in range(len(messages))
        )
        if not has_seed_pair:
            return JSONResponse(status_code=400, content={"error": "missing session context"})
        return JSONResponse(content=response_message("The session codeword is amber."))
    if selected in {"same-tier-fallback", "circuit-fail"} and model.endswith("primary"):
        return JSONResponse(status_code=503, content={"error": "transient"})
    if selected == "budget-exhaustion":
        return JSONResponse(content=tool_message("unknown_tool", "{}"))
    if selected == "action-prepare" and not has_tool_feedback:
        # A caller that owns a different order names it in the message; the fixture order stays
        # the default so existing scenarios are unaffected.
        order_match = ACTION_ORDER_PATTERN.search(current_message)
        return JSONResponse(
            content=tool_message(
                REFUND_PREPARE_WIRE_NAME,
                json.dumps(
                    {
                        "orderId": (
                            order_match.group(0)
                            if order_match
                            else "00000000-0000-0000-0000-000000000105"
                        ),
                        "amountMinor": 400,
                        "currency": "CNY",
                    },
                    separators=(",", ":"),
                ),
            )
        )
    if selected.startswith("retrieval-") and not has_tool_feedback:
        tool_arguments: dict[str, str] = {"query": current_message}
        if selected == "retrieval-sufficient":
            tool_arguments["rewrite"] = "delivery guide"
        return JSONResponse(
            content=tool_message(
                KNOWLEDGE_SEARCH_WIRE_NAME,
                json.dumps(tool_arguments, separators=(",", ":")),
            )
        )
    if selected in {"tool-success", "tool-timeout"} and not has_tool_feedback:
        product_id = "timeout-product" if selected == "tool-timeout" else "product-1"
        return JSONResponse(
            content=tool_message(CATALOG_PRODUCT_WIRE_NAME, f'{{"productId":"{product_id}"}}')
        )
    if selected == "tool-malformed" and not has_tool_feedback:
        return JSONResponse(
            content=tool_message(
                CATALOG_PRODUCT_WIRE_NAME,
                '{"productId":"product-1","scope":"catalog:*"}',
            ),
        )
    if selected == "tool-unknown" and not has_tool_feedback:
        return JSONResponse(content=tool_message("model_selected_tool", "{}"))
    if has_tool_feedback:
        counts[f"{selected}:feedback"] += 1
        return JSONResponse(content=response_message("The requested information is available."))
    return JSONResponse(content=response_message("The bounded support route completed safely."))


@app.post("/internal/tools/catalog.product.get")
async def timeout_tool(request: Request) -> JSONResponse:
    payload: Any = await request.json()
    counts["timeout-tool:requests"] += 1
    if not isinstance(payload, dict) or payload.get("productId") != "timeout-product":
        return JSONResponse(status_code=400, content={"error": "invalid fixture"})
    await asyncio.sleep(2)
    return JSONResponse(status_code=504, content={"error": "late fixture"})


@app.post("/internal/tools/actions/prepare")
async def action_prepare_proxy(request: Request) -> Response:
    if not commerce_base_url:
        return JSONResponse(status_code=503, content={"error": "proxy is not configured"})
    forwarded_headers = {
        name: value
        for name, value in request.headers.items()
        if name
        in {
            "authorization",
            "content-type",
            "x-support-session-id",
            "x-eval-sandbox-id",
            "x-agent-trace-id",
            "x-agent-turn-id",
            "x-agent-operation-id",
        }
    }
    async with httpx.AsyncClient(timeout=5.0) as client:
        upstream = await client.post(
            f"{commerce_base_url}/internal/tools/actions/prepare",
            headers=forwarded_headers,
            content=await request.body(),
        )
    session_id = request.headers.get("x-support-session-id", "")
    counts[f"action-proxy:{session_id}"] += 1
    if counts["action-proxy:lost-after-upstream"] == 0:
        counts["action-proxy:lost-after-upstream"] += 1
        return JSONResponse(status_code=503, content={"error": "response lost after upstream"})
    return Response(
        content=upstream.content,
        status_code=upstream.status_code,
        media_type=upstream.headers.get("content-type", "application/json"),
    )


@app.get("/fixture/counts")
def fixture_counts() -> dict[str, int]:
    return dict(counts)


def main() -> None:
    global commerce_base_url
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--commerce-base-url", default="")
    args = parser.parse_args()
    commerce_base_url = args.commerce_base_url.rstrip("/")
    uvicorn.run(app, host="127.0.0.1", port=args.port, access_log=False)


if __name__ == "__main__":
    main()
