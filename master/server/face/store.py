"""
Face Store - PostgreSQL + pgvector
"""

import logging
import uuid
from typing import Dict, List, Optional

import numpy as np
import psycopg
from psycopg.rows import dict_row

from ..config import FACE_EMBEDDING_DIM, POSTGRES_DSN_FACE

logger = logging.getLogger(__name__)


class FaceStore:
    """PostgreSQL + pgvector store for face embeddings"""

    def __init__(self, dsn: str = None, table: str = "face_embeddings", dim: int = None):
        self.dsn = dsn or POSTGRES_DSN_FACE
        self.table = table
        self.dim = dim or FACE_EMBEDDING_DIM

    def init_schema(self) -> None:
        """Create pgvector extension/table/index if needed"""
        with psycopg.connect(self.dsn, autocommit=True) as conn:
            conn.execute("CREATE EXTENSION IF NOT EXISTS vector")
            conn.execute('CREATE EXTENSION IF NOT EXISTS "uuid-ossp"')
            conn.execute(
                f"""
                CREATE TABLE IF NOT EXISTS {self.table} (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    user_id TEXT,
                    embedding VECTOR({self.dim}) NOT NULL,
                    is_active BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """
            )
            conn.execute(
                f"""
                CREATE INDEX IF NOT EXISTS {self.table}_hnsw_cos_idx
                ON {self.table}
                USING hnsw (embedding vector_cosine_ops)
                """
            )
            conn.execute(
                f"""
                CREATE TABLE IF NOT EXISTS attendance_records (
                    record_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                    face_id TEXT REFERENCES {self.table}(id) ON DELETE CASCADE,
                    checkin_time TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """
            )
        logger.info(f"FaceStore schema ready: table={self.table} dim={self.dim}")

    def enroll(self, name: str, embedding: list, user_id: Optional[str] = None) -> str:
        face_id = str(uuid.uuid4())[:12]
        vec = self._to_vector_literal(embedding)

        with psycopg.connect(self.dsn, autocommit=True) as conn:
            conn.execute(
                f"""
                INSERT INTO {self.table} (id, name, user_id, embedding)
                VALUES (%s, %s, %s, %s::vector)
                """,
                (face_id, name, user_id, vec),
            )

        logger.info(f"Enrolled face id={face_id} name={name}")
        return face_id

    def recognize(self, embedding: list, top_k: int, threshold: float) -> Optional[Dict]:
        vec = self._to_vector_literal(embedding)

        with psycopg.connect(self.dsn, row_factory=dict_row) as conn:
            rows = conn.execute(
                f"""
                SELECT id, name, user_id, created_at,
                       1 - (embedding <=> %s::vector) AS confidence
                FROM {self.table}
                WHERE is_active = TRUE
                ORDER BY embedding <=> %s::vector
                LIMIT %s
                """,
                (vec, vec, top_k),
            ).fetchall()

        if not rows:
            return None

        best = rows[0]
        confidence = float(best["confidence"])

        if confidence < threshold:
            return None

        return {
            "id": best["id"],
            "name": best["name"],
            "user_id": best.get("user_id"),
            "confidence": round(confidence, 4),
        }

    def list_all(self, limit: int = 1000) -> List[Dict]:
        with psycopg.connect(self.dsn, row_factory=dict_row) as conn:
            rows = conn.execute(
                f"""
                SELECT id, name, user_id, created_at
                FROM {self.table}
                WHERE is_active = TRUE
                ORDER BY created_at DESC
                LIMIT %s
                """,
                (limit,),
            ).fetchall()

        return [
            {
                "id": r["id"],
                "name": r["name"],
                "user_id": r.get("user_id"),
                "created_at": r["created_at"].isoformat() if r.get("created_at") else None,
            }
            for r in rows
        ]

    def delete(self, face_id: str) -> bool:
        with psycopg.connect(self.dsn, autocommit=True) as conn:
            cur = conn.execute(
                f"UPDATE {self.table} SET is_active = FALSE, updated_at = NOW() WHERE id = %s",
                (face_id,),
            )
        return cur.rowcount > 0

    def count(self) -> int:
        with psycopg.connect(self.dsn) as conn:
            return int(
                conn.execute(f"SELECT COUNT(*) FROM {self.table} WHERE is_active = TRUE").fetchone()[0]
            )

    def log_attendance(self, face_id: str, cooldown_minutes: int = 0) -> str:
        with psycopg.connect(self.dsn, autocommit=True) as conn:
            if cooldown_minutes > 0:
                has_recent = conn.execute(
                    """
                    SELECT 1 FROM attendance_records
                    WHERE face_id = %s AND checkin_time > NOW() - CAST(%s || ' minutes' AS INTERVAL)
                    LIMIT 1
                    """,
                    (face_id, str(cooldown_minutes)),
                ).fetchone()
                if has_recent:
                    logger.info(f"Skipping duplicate attendance log for face_id={face_id} due to {cooldown_minutes}m cooldown")
                    return "cooldown"

            row = conn.execute(
                """
                INSERT INTO attendance_records (face_id)
                VALUES (%s)
                RETURNING record_id
                """,
                (face_id,),
            ).fetchone()
        return str(row[0]) if row else ""

    def get_attendance_history(self, limit: int = 100) -> List[Dict]:
        with psycopg.connect(self.dsn, row_factory=dict_row) as conn:
            rows = conn.execute(
                f"""
                SELECT r.record_id, r.face_id, r.checkin_time, f.name, f.user_id
                FROM attendance_records r
                JOIN {self.table} f ON r.face_id = f.id
                ORDER BY r.checkin_time DESC
                LIMIT %s
                """,
                (limit,),
            ).fetchall()
        return [
            {
                "record_id": str(r["record_id"]),
                "face_id": r["face_id"],
                "name": r["name"],
                "user_id": r.get("user_id"),
                "checkin_time": r["checkin_time"].isoformat() if r.get("checkin_time") else None,
            }
            for r in rows
        ]

    @staticmethod
    def _to_vector_literal(embedding: list) -> str:
        arr = np.array(embedding, dtype=np.float32)
        norm = np.linalg.norm(arr) + 1e-8
        arr = arr / norm
        return "[" + ",".join(f"{float(x):.8f}" for x in arr.tolist()) + "]"


_face_store: Optional[FaceStore] = None


def get_face_store() -> FaceStore:
    global _face_store
    if _face_store is None:
        _face_store = FaceStore()
        _face_store.init_schema()
    return _face_store
