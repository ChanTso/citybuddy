"""Deterministic LiteLLM-compatible fixture for agent boundary evidence."""

from __future__ import annotations

import argparse
import asyncio
import json
from collections import Counter
from typing import Any

import uvicorn
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

app = FastAPI(docs_url=None, redoc_url=None)
counts: Counter[str] = Counter()


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
        "unsafe-action-claim",
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


@app.post("/v1/chat/completions")
async def complete(request: Request) -> JSONResponse:
    payload = await request.json()
    model = payload.get("model") if isinstance(payload, dict) else None
    messages = payload.get("messages") if isinstance(payload, dict) else None
    if not isinstance(model, str) or not isinstance(messages, list):
        return JSONResponse(status_code=400, content={"error": "invalid request"})
    user_messages = [
        item.get("content")
        for item in messages
        if isinstance(item, dict) and item.get("role") == "user"
    ]
    if len(user_messages) != 1 or not isinstance(user_messages[0], str):
        return JSONResponse(status_code=400, content={"error": "invalid messages"})
    selected = scenario(user_messages[0])
    counts[f"{selected}:total"] += 1
    counts[f"{selected}:{model}"] += 1
    has_tool_feedback = any(
        isinstance(item, dict) and item.get("role") == "tool" for item in messages
    )

    if model == "support-reranker-standard":
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

    if selected == "transient-retry" and counts[f"{selected}:{model}"] == 1:
        return JSONResponse(status_code=503, content={"error": "transient"})
    if selected == "provider-failure":
        return JSONResponse(status_code=400, content={"error": "terminal"})
    if selected == "disconnect-slow":
        await asyncio.sleep(0.2)
        return JSONResponse(content=response_message("The bounded response completed safely."))
    if selected == "unsafe-action-claim":
        return JSONResponse(content=response_message("Your refund has been issued."))
    if selected in {"same-tier-fallback", "circuit-fail"} and model.endswith("primary"):
        return JSONResponse(status_code=503, content={"error": "transient"})
    if selected == "budget-exhaustion":
        return JSONResponse(content=tool_message("unknown.tool", "{}"))
    if selected.startswith("retrieval-") and not has_tool_feedback:
        tool_arguments: dict[str, str] = {"query": user_messages[0]}
        if selected == "retrieval-sufficient":
            tool_arguments["rewrite"] = "delivery guide"
        return JSONResponse(
            content=tool_message(
                "knowledge.search",
                json.dumps(tool_arguments, separators=(",", ":")),
            )
        )
    if selected in {"tool-success", "tool-timeout"} and not has_tool_feedback:
        product_id = "timeout-product" if selected == "tool-timeout" else "product-1"
        return JSONResponse(
            content=tool_message("catalog.product.get", f'{{"productId":"{product_id}"}}')
        )
    if selected == "tool-malformed" and not has_tool_feedback:
        return JSONResponse(
            content=tool_message(
                "catalog.product.get",
                '{"productId":"product-1","scope":"catalog:*"}',
            ),
        )
    if selected == "tool-unknown" and not has_tool_feedback:
        return JSONResponse(content=tool_message("model.selected.tool", "{}"))
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


@app.get("/fixture/counts")
def fixture_counts() -> dict[str, int]:
    return dict(counts)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    uvicorn.run(app, host="127.0.0.1", port=args.port, access_log=False)


if __name__ == "__main__":
    main()
