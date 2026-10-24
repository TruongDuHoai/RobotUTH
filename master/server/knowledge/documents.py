"""Document ingestion service for fast knowledge base."""

import json
import logging
import re
from pathlib import Path
from typing import Dict, List, Optional

from ..config import KNOWLEDGE_CHUNK_OVERLAP, KNOWLEDGE_CHUNK_SIZE
from .embeddings import get_embedding_service
from .parser import ParserService, build_qna_text
from .schema import get_connection, init_schema

logger = logging.getLogger(__name__)


def _vector_literal(vector: List[float]) -> str:
    return "[" + ",".join(f"{value:.8f}" for value in vector) + "]"


class DocumentService:
    def __init__(self):
        init_schema()
        self.parser = ParserService()
        self.embedding = get_embedding_service()

    def list_documents(self) -> List[Dict]:
        with get_connection() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT id, filename, file_type, source, status, total_chunks, total_chars,
                           CASE WHEN full_text IS NULL THEN 0 ELSE length(full_text) END AS full_text_chars,
                           metadata, error_message, created_at, updated_at
                    FROM knowledge_documents
                    ORDER BY created_at DESC
                    """
                )
                return cur.fetchall()

    def get_document(self, document_id: int, include_chunks: bool = True) -> Optional[Dict]:
        with get_connection() as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT * FROM knowledge_documents WHERE id = %s", (document_id,))
                document = cur.fetchone()
                if not document:
                    return None
                if include_chunks:
                    cur.execute(
                        """
                        SELECT id, chunk_index, content, token_count, metadata, created_at
                        FROM knowledge_chunks
                        WHERE document_id = %s
                        ORDER BY chunk_index
                        """,
                        (document_id,),
                    )
                    document["chunks"] = cur.fetchall()
                # Prefer stored full_text; fall back to reassembled chunks
                if not (document.get("full_text") or "").strip() and document.get("chunks"):
                    document["full_text"] = "\n\n".join(
                        c.get("content", "") for c in document["chunks"] if c.get("content")
                    )
                return document

    def delete_document(self, document_id: int) -> bool:
        with get_connection() as conn:
            with conn.cursor() as cur:
                cur.execute("DELETE FROM knowledge_documents WHERE id = %s", (document_id,))
                return cur.rowcount > 0

    def ingest_file(self, filename: str, content: bytes, metadata: Optional[Dict] = None) -> Dict:
        file_type = Path(filename).suffix.lower().lstrip(".")
        document_id = self._create_document(filename, file_type, filename, metadata or {})
        try:
            text = self.parser.parse_bytes(filename, content)
            return self._ingest_text_for_document(document_id, text)
        except Exception as exc:
            self._mark_error(document_id, str(exc))
            raise

    def ingest_qna(self, question: str, answer: str, source: str = "robotuth_qna.txt") -> Dict:
        document_id = self._create_document(source, "qna", source, {"kind": "qna"})
        text = build_qna_text(question, answer)
        return self._ingest_text_for_document(document_id, text)

    def ingest_text(self, text: str, source: str = "robotuth_text.txt") -> Dict:
        document_id = self._create_document(source, "txt", source, {"kind": "text"})
        return self._ingest_text_for_document(document_id, text)

    def reprocess_document(self, document_id: int) -> Dict:
        document = self.get_document(document_id)
        if not document:
            raise ValueError("Document not found")
        combined = (document.get("full_text") or "").strip()
        if not combined:
            combined = "\n\n".join(chunk["content"] for chunk in document.get("chunks", []))
        if not combined.strip():
            raise ValueError("Document has no content to reprocess")
        with get_connection() as conn:
            with conn.cursor() as cur:
                cur.execute("DELETE FROM knowledge_chunks WHERE document_id = %s", (document_id,))
                cur.execute(
                    "UPDATE knowledge_documents SET status = 'processing', full_text = NULL, updated_at = NOW() WHERE id = %s",
                    (document_id,),
                )
        return self._ingest_text_for_document(document_id, combined)

    def stats(self) -> Dict:
        with get_connection() as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT COUNT(*) AS documents FROM knowledge_documents")
                documents = cur.fetchone()["documents"]
                cur.execute("SELECT COUNT(*) AS chunks FROM knowledge_chunks")
                chunks = cur.fetchone()["chunks"]
                cur.execute("SELECT COUNT(*) AS ready FROM knowledge_documents WHERE status = 'ready'")
                ready = cur.fetchone()["ready"]
                return {"documents": documents, "chunks": chunks, "ready_documents": ready}

    def _create_document(self, filename: str, file_type: str, source: str, metadata: Dict) -> int:
        with get_connection() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    INSERT INTO knowledge_documents (filename, file_type, source, metadata)
                    VALUES (%s, %s, %s, %s)
                    RETURNING id
                    """,
                    (filename, file_type, source, json.dumps(metadata)),
                )
                return cur.fetchone()["id"]

    def _ingest_text_for_document(self, document_id: int, text: str) -> Dict:
        # Keep paragraph breaks for system-prompt readability; collapse extreme whitespace
        clean_text = self._normalize_text_preserve_paragraphs(text)
        if not clean_text:
            raise ValueError("Document text is empty")
        chunks = self.chunk_text(clean_text)
        if not chunks:
            # Still allow short docs for system-prompt mode
            chunks = [clean_text] if len(clean_text) >= 1 else []
        if not chunks:
            raise ValueError("No valid chunks generated")

        try:
            vectors = self.embedding.embed_texts(chunks)
        except Exception as exc:
            logger.warning("Embedding failed (system-prompt mode still keeps full text): %s", exc)
            vectors = [None] * len(chunks)

        with get_connection() as conn:
            with conn.cursor() as cur:
                for index, (chunk, vector) in enumerate(zip(chunks, vectors)):
                    if vector is not None:
                        cur.execute(
                            """
                            INSERT INTO knowledge_chunks
                                (document_id, chunk_index, content, content_tsv, embedding, token_count)
                            VALUES
                                (%s, %s, %s, to_tsvector('simple', %s), %s::vector, %s)
                            """,
                            (
                                document_id,
                                index,
                                chunk,
                                chunk,
                                _vector_literal(vector),
                                self._estimate_tokens(chunk),
                            ),
                        )
                    else:
                        cur.execute(
                            """
                            INSERT INTO knowledge_chunks
                                (document_id, chunk_index, content, content_tsv, embedding, token_count)
                            VALUES
                                (%s, %s, %s, to_tsvector('simple', %s), NULL, %s)
                            """,
                            (document_id, index, chunk, chunk, self._estimate_tokens(chunk)),
                        )
                cur.execute(
                    """
                    UPDATE knowledge_documents
                    SET status = 'ready', total_chunks = %s, total_chars = %s,
                        full_text = %s, error_message = NULL, updated_at = NOW()
                    WHERE id = %s
                    RETURNING id, filename, file_type, status, total_chunks, total_chars
                    """,
                    (len(chunks), len(clean_text), clean_text, document_id),
                )
                row = cur.fetchone()
                if row is not None:
                    row["full_text_chars"] = len(clean_text)
                return row

    def _mark_error(self, document_id: int, message: str) -> None:
        with get_connection() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE knowledge_documents
                    SET status = 'error', error_message = %s, updated_at = NOW()
                    WHERE id = %s
                    """,
                    (message, document_id),
                )

    def chunk_text(self, text: str) -> List[str]:
        words = text.split()
        if not words:
            return []
        size = max(50, KNOWLEDGE_CHUNK_SIZE)
        overlap = min(KNOWLEDGE_CHUNK_OVERLAP, size // 2)
        chunks = []
        start = 0
        while start < len(words):
            end = min(start + size, len(words))
            chunk = " ".join(words[start:end]).strip()
            if len(chunk) >= 30:
                chunks.append(chunk)
            if end >= len(words):
                break
            start = end - overlap
        return chunks

    def _normalize_text(self, text: str) -> str:
        return re.sub(r"\s+", " ", text or "").strip()

    def _normalize_text_preserve_paragraphs(self, text: str) -> str:
        """Collapse spaces/tabs but keep blank-line paragraph breaks for system prompt."""
        if not text:
            return ""
        text = text.replace("\r\n", "\n").replace("\r", "\n")
        text = re.sub(r"[ \t]+", " ", text)
        text = re.sub(r"\n{3,}", "\n\n", text)
        return text.strip()

    def _estimate_tokens(self, text: str) -> int:
        return max(1, len(text.split()))


_document_service = None


def get_document_service() -> DocumentService:
    global _document_service
    if _document_service is None:
        _document_service = DocumentService()
    return _document_service
