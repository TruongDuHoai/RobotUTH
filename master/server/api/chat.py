"""
Chat API — knowledge system-prompt Q&A (no LightRAG).

Path: system_instruction + uploaded knowledge texts → LLM.
Ingest goes through /api/knowledge/* (not this router).
"""

import asyncio
import logging
from typing import Optional

from fastapi import APIRouter, Body, HTTPException, Query
from pydantic import BaseModel, Field

from ..config import (
    KNOWLEDGE_ANSWER_MAX_TOKENS,
    KNOWLEDGE_ENABLED,
    KNOWLEDGE_USE_SYSTEM_PROMPT,
)
from ..knowledge.search import get_search_service
from ..knowledge.system_prompt import build_system_prompt
from ..services.llm import LLMUnavailable, get_llm_service

router = APIRouter(prefix="/api/chat", tags=["chat"])
logger = logging.getLogger(__name__)


class ChatQueryRequest(BaseModel):
    query: str = Field(..., min_length=1)
    # Kept for Android / old clients; ignored (no LightRAG modes).
    mode: Optional[str] = None
    include_references: bool = False
    include_chunk_content: bool = False


@router.post("/query")
async def chat_query(
    request: Optional[ChatQueryRequest] = Body(None),
    query: Optional[str] = Query(None),
    mode: Optional[str] = Query(None),
    include_references: bool = Query(False),
    include_chunk_content: bool = Query(False),
):
    del mode, include_chunk_content  # unused legacy params
    query_text = (request.query if request else query or "").strip()
    if not query_text:
        raise HTTPException(422, "query is required")

    want_sources = request.include_references if request else include_references
    logger.info("Chat query: %s", query_text[:120])

    if KNOWLEDGE_ENABLED and KNOWLEDGE_USE_SYSTEM_PROMPT:
        try:
            knowledge = await asyncio.to_thread(
                get_search_service().query_with_system_prompt,
                query_text,
                want_sources,
            )
            if knowledge.get("success") and (knowledge.get("answer") or "").strip():
                return {
                    "success": True,
                    "response": knowledge.get("answer", ""),
                    "contexts": knowledge.get("results", []),
                    "sources": knowledge.get("sources", []) if want_sources else [],
                    "mode": "knowledge_system_prompt",
                    "system_prompt_chars": knowledge.get("system_prompt_chars"),
                    "document_count": knowledge.get("document_count"),
                    "truncated": knowledge.get("truncated"),
                }
            logger.info("Knowledge system-prompt answer empty for: %s", query_text[:80])
        except Exception as exc:
            logger.warning("Knowledge system-prompt failed: %s", exc)
    elif KNOWLEDGE_ENABLED:
        try:
            knowledge = await asyncio.to_thread(
                get_search_service().query,
                query_text,
                5,
                want_sources,
                False,
            )
            if knowledge.get("success") and (knowledge.get("answer") or "").strip():
                return {
                    "success": True,
                    "response": knowledge.get("answer", ""),
                    "contexts": knowledge.get("results", []),
                    "sources": knowledge.get("sources", []) if want_sources else [],
                    "mode": knowledge.get("mode") or "knowledge",
                }
        except Exception as exc:
            logger.warning("Knowledge query failed: %s", exc)

    try:
        llm = get_llm_service()
        try:
            system_prompt = build_system_prompt()["system_prompt"]
        except Exception:
            system_prompt = (
                "Bạn là robotuth, một trợ lý AI tiếng Việt thân thiện và hữu ích.\n"
                "Hãy trả lời câu hỏi của người dùng một cách ngắn gọn, rõ ràng, có dấu câu đầy đủ."
            )

        response_text = await asyncio.to_thread(
            llm.generate,
            query_text,
            KNOWLEDGE_ANSWER_MAX_TOKENS,
            system_prompt,
        )
        return {
            "success": True,
            "response": response_text,
            "contexts": [],
            "sources": [],
            "mode": "direct_llm",
        }
    except LLMUnavailable as exc:
        # Upstream outage (5xx/429/timeout after retries, bad model/key).
        # success:false so clients never present user_message as an answer.
        logger.warning("LLM unavailable: %s", exc.detail or exc.user_message)
        return {
            "success": False,
            "message": exc.user_message,
            "response": "",
        }
    except Exception as exc:
        logger.exception("LLM generation failed")
        return {
            "success": False,
            "message": str(exc),
            "response": "",
        }


@router.get("/health")
async def health_check():
    """Chat health = knowledge system-prompt path availability."""
    info = {
        "available": bool(KNOWLEDGE_ENABLED),
        "mode": "knowledge_system_prompt" if KNOWLEDGE_USE_SYSTEM_PROMPT else "knowledge_rag",
        "knowledge_enabled": KNOWLEDGE_ENABLED,
        "use_system_prompt": KNOWLEDGE_USE_SYSTEM_PROMPT,
        "message": "Chat uses built-in knowledge (system prompt + LLM); LightRAG removed.",
    }
    if not KNOWLEDGE_ENABLED:
        info["message"] = "Knowledge disabled; chat falls back to direct LLM only."
        return info
    try:
        built = build_system_prompt()
        info.update(
            {
                "document_count": built.get("document_count"),
                "system_prompt_chars": built.get("total_chars"),
                "truncated": built.get("truncated"),
            }
        )
    except Exception as exc:
        info["available"] = True  # chat still works via LLM
        info["knowledge_error"] = str(exc)
    return info
