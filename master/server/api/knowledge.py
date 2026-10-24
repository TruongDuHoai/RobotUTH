"""Knowledge API — system-prompt mode (instruction + all uploaded file texts)."""

import asyncio
import logging
from typing import Optional

from fastapi import APIRouter, File, HTTPException, UploadFile
from pydantic import BaseModel, Field

from ..config import KNOWLEDGE_ENABLED, KNOWLEDGE_MAX_UPLOAD_MB, KNOWLEDGE_UPLOAD_EXTENSIONS
from ..knowledge.documents import get_document_service
from ..knowledge.schema import init_schema
from ..knowledge.search import get_search_service
from ..knowledge.system_prompt import (
    build_system_prompt,
    get_system_instruction,
    reset_system_instruction,
    set_system_instruction,
)

router = APIRouter(prefix="/api/knowledge", tags=["knowledge"])
logger = logging.getLogger(__name__)


class TextIngestRequest(BaseModel):
    text: str = Field(..., min_length=1)
    source: Optional[str] = None


class QnaIngestRequest(BaseModel):
    question: str = Field(..., min_length=1)
    answer: str = Field(..., min_length=1)
    source: Optional[str] = None


class SearchRequest(BaseModel):
    query: str = Field(..., min_length=1)
    top_k: int = Field(5, ge=1, le=20)


class QueryRequest(BaseModel):
    query: str = Field(..., min_length=1)
    top_k: int = Field(5, ge=1, le=20)
    include_sources: bool = True
    use_system_prompt: bool = True


class SystemInstructionRequest(BaseModel):
    content: str = Field(..., min_length=1)


def _ensure_enabled():
    if not KNOWLEDGE_ENABLED:
        raise HTTPException(503, "Knowledge base is disabled")


def _safe_name(name: Optional[str], fallback: str) -> str:
    value = (name or fallback).strip()
    return value.replace("\\", "_").replace("/", "_") or fallback


def _validate_upload(filename: str, content: bytes) -> None:
    if not filename.lower().endswith(KNOWLEDGE_UPLOAD_EXTENSIONS):
        raise HTTPException(400, f"Unsupported file type. Allowed: {', '.join(KNOWLEDGE_UPLOAD_EXTENSIONS)}")
    max_bytes = KNOWLEDGE_MAX_UPLOAD_MB * 1024 * 1024
    if len(content) > max_bytes:
        raise HTTPException(413, f"File too large. Max: {KNOWLEDGE_MAX_UPLOAD_MB}MB")


@router.get("/health")
async def health():
    if not KNOWLEDGE_ENABLED:
        return {"available": False, "message": "Knowledge base is disabled"}
    try:
        init_schema()
        built = build_system_prompt()
        return {
            "available": True,
            "message": "Knowledge base is ready",
            "mode": "system_prompt",
            "document_count": built["document_count"],
            "system_prompt_chars": built["total_chars"],
        }
    except Exception as exc:
        logger.exception("Knowledge health check failed")
        return {"available": False, "error": str(exc)}


@router.get("/stats")
async def stats():
    _ensure_enabled()
    try:
        base = get_document_service().stats()
        built = build_system_prompt()
        base.update(
            {
                "system_prompt_chars": built["total_chars"],
                "instruction_chars": built["instruction_chars"],
                "documents_in_prompt": built["document_count"],
                "prompt_truncated": built["truncated"],
                "mode": "system_prompt",
            }
        )
        return base
    except Exception as exc:
        logger.exception("Knowledge stats failed")
        raise HTTPException(500, str(exc))


# ── System instruction (editable in web admin, like ChatUTH :9999) ──────────


@router.get("/system-instruction")
async def get_instruction():
    _ensure_enabled()
    content = get_system_instruction()
    return {
        "content": content,
        "chars": len(content),
    }


@router.put("/system-instruction")
async def put_instruction(request: SystemInstructionRequest):
    _ensure_enabled()
    try:
        content = set_system_instruction(request.content)
        return {"success": True, "content": content, "chars": len(content)}
    except ValueError as exc:
        raise HTTPException(400, str(exc))


@router.post("/system-instruction/reset")
async def reset_instruction():
    _ensure_enabled()
    content = reset_system_instruction()
    return {"success": True, "content": content, "chars": len(content)}


@router.get("/system-prompt")
async def get_full_system_prompt(preview: bool = True):
    """
    Preview the full system prompt that will be sent to the model:
    system_instruction + all uploaded document texts.
    """
    _ensure_enabled()
    built = build_system_prompt()
    # Large payloads: default preview truncates body for UI
    prompt = built["system_prompt"]
    if preview and len(prompt) > 20_000:
        built = dict(built)
        built["system_prompt"] = prompt[:20_000] + "\n\n…[preview truncated — full length " f"{len(prompt)} chars]\n"
        built["preview_truncated"] = True
    else:
        built["preview_truncated"] = False
    return built


# ── Documents ────────────────────────────────────────────────────────────────


@router.get("/documents")
async def list_documents():
    _ensure_enabled()
    return get_document_service().list_documents()


@router.post("/documents")
async def upload_document(file: UploadFile = File(...)):
    _ensure_enabled()
    filename = _safe_name(file.filename, "upload.txt")
    content = await file.read()
    _validate_upload(filename, content)
    try:
        # ingest_file (and optional OCR) is sync/CPU/IO-heavy — keep event loop free
        document = await asyncio.to_thread(
            get_document_service().ingest_file, filename, content
        )
        return {"success": True, "document": document}
    except Exception as exc:
        logger.exception("Knowledge upload failed: %s", filename)
        raise HTTPException(500, str(exc))


@router.get("/documents/{document_id}")
async def get_document(document_id: int):
    _ensure_enabled()
    document = get_document_service().get_document(document_id)
    if not document:
        raise HTTPException(404, "Document not found")
    return document


@router.delete("/documents/{document_id}")
async def delete_document(document_id: int):
    _ensure_enabled()
    deleted = get_document_service().delete_document(document_id)
    if not deleted:
        raise HTTPException(404, "Document not found")
    return {"success": True, "document_id": document_id}


@router.post("/documents/{document_id}/reprocess")
async def reprocess_document(document_id: int):
    _ensure_enabled()
    try:
        return {"success": True, "document": get_document_service().reprocess_document(document_id)}
    except ValueError as exc:
        raise HTTPException(404, str(exc))
    except Exception as exc:
        logger.exception("Knowledge reprocess failed")
        raise HTTPException(500, str(exc))


@router.post("/text")
async def ingest_text(request: TextIngestRequest):
    _ensure_enabled()
    source = _safe_name(request.source, "robotuth_text.txt")
    try:
        return {"success": True, "document": get_document_service().ingest_text(request.text, source)}
    except Exception as exc:
        logger.exception("Knowledge text ingest failed")
        raise HTTPException(500, str(exc))


@router.post("/qna")
async def ingest_qna(request: QnaIngestRequest):
    _ensure_enabled()
    source = _safe_name(request.source, "robotuth_qna.txt")
    try:
        return {"success": True, "document": get_document_service().ingest_qna(request.question, request.answer, source)}
    except Exception as exc:
        logger.exception("Knowledge Q&A ingest failed")
        raise HTTPException(500, str(exc))


@router.post("/search")
async def search(request: SearchRequest):
    """Optional hybrid chunk search (diagnostic). Primary Q&A uses system prompt."""
    _ensure_enabled()
    try:
        return {"success": True, "results": get_search_service().search(request.query, request.top_k)}
    except Exception as exc:
        logger.exception("Knowledge search failed")
        raise HTTPException(500, str(exc))


@router.post("/query")
async def query(request: QueryRequest):
    """
    Answer with system_prompt = instruction + all uploaded file texts.
    """
    _ensure_enabled()
    try:
        # LLM call is blocking HTTP — run off the event loop
        return await asyncio.to_thread(
            get_search_service().query,
            request.query,
            request.top_k,
            request.include_sources,
            request.use_system_prompt,
        )
    except Exception as exc:
        logger.exception("Knowledge query failed")
        raise HTTPException(500, str(exc))
