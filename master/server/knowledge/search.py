"""Knowledge answer + optional hybrid vector/keyword search.

Primary mode (default): put all uploaded documents into the system prompt
and ask the LLM. Hybrid RAG search remains available for diagnostics.
"""

import logging
import time
from typing import Dict, List

from ..config import (
    KNOWLEDGE_ANSWER_MAX_TOKENS,
    KNOWLEDGE_KEYWORD_WEIGHT,
    KNOWLEDGE_MIN_SCORE,
    KNOWLEDGE_SEMANTIC_WEIGHT,
    KNOWLEDGE_TOP_K,
    KNOWLEDGE_USE_SYSTEM_PROMPT,
)
from ..services.llm import LLMUnavailable, get_llm_service
from .documents import _vector_literal
from .embeddings import get_embedding_service
from .schema import get_connection, init_schema
from .system_prompt import build_system_prompt

logger = logging.getLogger(__name__)


class KnowledgeSearchService:
    def __init__(self):
        init_schema()
        self.embedding = get_embedding_service()

    def search(self, query: str, top_k: int = KNOWLEDGE_TOP_K) -> List[Dict]:
        query = query.strip()
        if not query:
            return []
        vector = self.embedding.embed_query(query)
        semantic_results = self._semantic_search(vector, top_k * 2)
        keyword_results = self._keyword_search(query, top_k * 2)
        return self._merge_results(semantic_results, keyword_results, top_k)

    def query(
        self,
        query: str,
        top_k: int = KNOWLEDGE_TOP_K,
        include_sources: bool = True,
        use_system_prompt: bool = None,
    ) -> Dict:
        """Answer using full system prompt (instruction + all docs) by default."""
        if use_system_prompt is None:
            use_system_prompt = KNOWLEDGE_USE_SYSTEM_PROMPT
        if use_system_prompt:
            return self.query_with_system_prompt(query, include_sources=include_sources)
        return self.query_with_rag(query, top_k=top_k, include_sources=include_sources)

    def query_with_system_prompt(self, query: str, include_sources: bool = True) -> Dict:
        """
        system_prompt = system_instruction + text of every uploaded file.
        user message = the question. LLM generates the answer.
        """
        start = time.time()
        query = (query or "").strip()
        if not query:
            return {
                "success": False,
                "answer": "Câu hỏi trống.",
                "sources": [],
                "results": [],
                "mode": "system_prompt",
                "latency_ms": 0,
            }

        built = build_system_prompt()
        system_prompt = built["system_prompt"]
        sources = [
            {
                "document_id": item["id"],
                "filename": item["filename"],
                "chars": item.get("chars"),
                "truncated": item.get("truncated", False),
            }
            for item in built.get("documents", [])
        ] if include_sources else []

        try:
            answer = get_llm_service().generate(
                query,
                max_tokens=KNOWLEDGE_ANSWER_MAX_TOKENS,
                system_prompt=system_prompt,
            )
        except Exception as exc:
            logger.exception("System-prompt knowledge answer failed")
            return {
                "success": False,
                "answer": f"Xin lỗi, không thể sinh câu trả lời: {exc}",
                "sources": sources,
                "results": [],
                "mode": "system_prompt",
                "system_prompt_chars": built["total_chars"],
                "document_count": built["document_count"],
                "truncated": built["truncated"],
                "latency_ms": int((time.time() - start) * 1000),
            }

        return {
            "success": True,
            "answer": answer,
            "sources": sources,
            "results": [],
            "mode": "system_prompt",
            "system_prompt_chars": built["total_chars"],
            "document_count": built["document_count"],
            "truncated": built["truncated"],
            "latency_ms": int((time.time() - start) * 1000),
        }

    def query_with_rag(
        self, query: str, top_k: int = KNOWLEDGE_TOP_K, include_sources: bool = True
    ) -> Dict:
        start = time.time()
        results = self.search(query, top_k=top_k)
        best_score = results[0]["score"] if results else 0
        if not results or best_score < KNOWLEDGE_MIN_SCORE:
            return {
                "success": True,
                "answer": "Tôi chưa có thông tin này trong tài liệu đã nạp.",
                "sources": [],
                "results": results if include_sources else [],
                "mode": "rag",
                "latency_ms": int((time.time() - start) * 1000),
            }

        context = self._build_context(results)
        prompt = self._build_prompt(query, context)
        try:
            answer = get_llm_service().generate(prompt, max_tokens=KNOWLEDGE_ANSWER_MAX_TOKENS)
        except LLMUnavailable as exc:
            logger.warning("RAG answer fell back to extractive: %s", exc.detail or exc.user_message)
            answer = self._fallback_answer(results)
        sources = [
            {
                "document_id": item["document_id"],
                "filename": item["filename"],
                "chunk_id": item["chunk_id"],
                "score": item["score"],
            }
            for item in results
        ] if include_sources else []
        return {
            "success": True,
            "answer": answer,
            "sources": sources,
            "results": results if include_sources else [],
            "mode": "rag",
            "latency_ms": int((time.time() - start) * 1000),
        }

    def _semantic_search(self, vector: List[float], limit: int) -> List[Dict]:
        with get_connection() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT c.id AS chunk_id, c.document_id, d.filename, c.chunk_index, c.content,
                           1 - (c.embedding <=> %s::vector) AS semantic_score
                    FROM knowledge_chunks c
                    JOIN knowledge_documents d ON d.id = c.document_id
                    WHERE c.embedding IS NOT NULL AND d.status = 'ready'
                    ORDER BY c.embedding <=> %s::vector
                    LIMIT %s
                    """,
                    (_vector_literal(vector), _vector_literal(vector), limit),
                )
                return cur.fetchall()

    def _keyword_search(self, query: str, limit: int) -> List[Dict]:
        with get_connection() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT c.id AS chunk_id, c.document_id, d.filename, c.chunk_index, c.content,
                           ts_rank_cd(c.content_tsv, plainto_tsquery('simple', %s)) AS keyword_score
                    FROM knowledge_chunks c
                    JOIN knowledge_documents d ON d.id = c.document_id
                    WHERE c.content_tsv @@ plainto_tsquery('simple', %s) AND d.status = 'ready'
                    ORDER BY keyword_score DESC
                    LIMIT %s
                    """,
                    (query, query, limit),
                )
                return cur.fetchall()

    def _merge_results(self, semantic_results: List[Dict], keyword_results: List[Dict], top_k: int) -> List[Dict]:
        merged: Dict[int, Dict] = {}
        for row in semantic_results:
            item = dict(row)
            item["semantic_score"] = float(item.get("semantic_score") or 0)
            item["keyword_score"] = 0.0
            merged[item["chunk_id"]] = item
        max_keyword = max((float(row.get("keyword_score") or 0) for row in keyword_results), default=0.0)
        for row in keyword_results:
            chunk_id = row["chunk_id"]
            normalized_keyword = float(row.get("keyword_score") or 0) / max_keyword if max_keyword else 0.0
            if chunk_id in merged:
                merged[chunk_id]["keyword_score"] = normalized_keyword
            else:
                item = dict(row)
                item["semantic_score"] = 0.0
                item["keyword_score"] = normalized_keyword
                merged[chunk_id] = item

        results = []
        for item in merged.values():
            item["score"] = (
                KNOWLEDGE_SEMANTIC_WEIGHT * float(item.get("semantic_score") or 0)
                + KNOWLEDGE_KEYWORD_WEIGHT * float(item.get("keyword_score") or 0)
            )
            results.append(item)
        results.sort(key=lambda item: item["score"], reverse=True)
        return results[:top_k]

    def _fallback_answer(self, results: List[Dict]) -> str:
        best = results[0]
        content = best.get("content", "").strip()
        marker = "Trả lời:"
        if marker in content:
            content = content.split(marker, 1)[1].strip()
        return f"{content} Nguồn: {best.get('filename', 'unknown')}"

    def _build_context(self, results: List[Dict]) -> str:
        parts = []
        for index, item in enumerate(results, 1):
            parts.append(f"[{index}] filename: {item['filename']}\ncontent: {item['content']}")
        return "\n\n".join(parts)

    def _build_prompt(self, query: str, context: str) -> str:
        return (
            "Bạn là RobotUTH, trợ lý tri thức của trường.\n"
            "Chỉ trả lời dựa trên NGỮ CẢNH được cung cấp.\n"
            "Nếu ngữ cảnh không đủ, nói: \"Tôi chưa có thông tin này trong tài liệu đã nạp.\"\n"
            "Trả lời ngắn gọn, tiếng Việt tự nhiên. Cuối câu trả lời liệt kê nguồn theo tên tài liệu.\n\n"
            f"NGỮ CẢNH:\n{context}\n\n"
            f"CÂU HỎI:\n{query}"
        )


_search_service = None


def get_search_service() -> KnowledgeSearchService:
    global _search_service
    if _search_service is None:
        _search_service = KnowledgeSearchService()
    return _search_service
