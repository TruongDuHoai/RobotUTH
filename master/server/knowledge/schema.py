"""Database schema for fast knowledge base."""

import logging
from contextlib import contextmanager
from typing import Iterator

import psycopg
from psycopg.rows import dict_row

from ..config import POSTGRES_DSN_KNOWLEDGE

logger = logging.getLogger(__name__)


@contextmanager
def get_connection() -> Iterator[psycopg.Connection]:
    conn = psycopg.connect(POSTGRES_DSN_KNOWLEDGE, row_factory=dict_row)
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def init_schema() -> None:
    """Create pgvector knowledge tables and indexes if missing."""
    core_statements = [
        "CREATE EXTENSION IF NOT EXISTS vector",
        """
        CREATE TABLE IF NOT EXISTS knowledge_documents (
            id SERIAL PRIMARY KEY,
            filename TEXT NOT NULL,
            file_type TEXT NOT NULL,
            source TEXT,
            status TEXT NOT NULL DEFAULT 'processing',
            total_chunks INT NOT NULL DEFAULT 0,
            total_chars INT NOT NULL DEFAULT 0,
            full_text TEXT,
            metadata JSONB NOT NULL DEFAULT '{}',
            error_message TEXT,
            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS knowledge_chunks (
            id SERIAL PRIMARY KEY,
            document_id INT NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
            chunk_index INT NOT NULL,
            content TEXT NOT NULL,
            content_tsv TSVECTOR,
            embedding VECTOR(1024),
            token_count INT NOT NULL DEFAULT 0,
            metadata JSONB NOT NULL DEFAULT '{}',
            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        )
        """,
        # full_text: complete parsed document for system-prompt mode
        "ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS full_text TEXT",
        "CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_tsv ON knowledge_chunks USING GIN (content_tsv)",
        "CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_document ON knowledge_chunks(document_id)",
        "CREATE INDEX IF NOT EXISTS idx_knowledge_documents_status ON knowledge_documents(status)",
    ]
    with get_connection() as conn:
        with conn.cursor() as cur:
            for statement in core_statements:
                cur.execute(statement)
            # ivfflat may fail on empty table / dim mismatch — use savepoint
            try:
                cur.execute("SAVEPOINT emb_idx")
                cur.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_embedding
                    ON knowledge_chunks USING ivfflat (embedding vector_cosine_ops) WITH (lists = 10)
                    """
                )
                cur.execute("RELEASE SAVEPOINT emb_idx")
            except Exception as exc:
                logger.warning("Knowledge embedding index skipped: %s", exc)
                try:
                    cur.execute("ROLLBACK TO SAVEPOINT emb_idx")
                except Exception:
                    pass
    logger.info("Knowledge schema initialized")
