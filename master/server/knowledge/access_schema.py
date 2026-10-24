"""Schema for departments, accounts, published bundles and the audit trail.

Idempotent DDL, run from `server.db.init_knowledge_db` — deliberately NOT wired
into `schema.init_schema()`, which `build_system_prompt()` calls on every chat
turn (see the per-request DDL cost noted in AGENTS.md).
"""

from logging import getLogger
from typing import List

from .schema import get_connection

logger = getLogger(__name__)

DEFAULT_DEPARTMENT_CODE = "dao-tao"
DEFAULT_DEPARTMENT_NAME = "Phòng Đào tạo"

DDL_STATEMENTS: List[str] = [
    """
    CREATE TABLE IF NOT EXISTS departments (
        id SERIAL PRIMARY KEY,
        code TEXT NOT NULL UNIQUE,
        name TEXT NOT NULL,
        instruction TEXT,
        tts_voice TEXT,
        -- No FK: knowledge_bundles references departments, so a FK back would be circular.
        active_bundle_id INT,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS users (
        id SERIAL PRIMARY KEY,
        username TEXT NOT NULL UNIQUE,
        display_name TEXT NOT NULL,
        password_hash TEXT NOT NULL,
        role TEXT NOT NULL CHECK (role IN ('admin', 'editor', 'viewer')),
        department_id INT REFERENCES departments(id),
        is_active BOOLEAN NOT NULL DEFAULT TRUE,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        CHECK (role = 'admin' OR department_id IS NOT NULL)
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS robots (
        id SERIAL PRIMARY KEY,
        name TEXT NOT NULL UNIQUE,
        display_name TEXT,
        department_id INT REFERENCES departments(id),
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS knowledge_bundles (
        id SERIAL PRIMARY KEY,
        department_id INT NOT NULL REFERENCES departments(id) ON DELETE CASCADE,
        version INT NOT NULL,
        doc_ids INT[] NOT NULL DEFAULT '{}',
        prompt_chars INT NOT NULL DEFAULT 0,
        checksum TEXT NOT NULL,
        created_by INT REFERENCES users(id),
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        UNIQUE (department_id, version)
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS audit_log (
        id SERIAL PRIMARY KEY,
        user_id INT REFERENCES users(id),
        action TEXT NOT NULL,
        department_id INT REFERENCES departments(id),
        detail JSONB NOT NULL DEFAULT '{}',
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
    """,
    "CREATE INDEX IF NOT EXISTS idx_audit_log_department ON audit_log(department_id, created_at)",
    # Nullable on purpose: the ingest path still inserts documents without a
    # department. SET NOT NULL belongs to the slice that passes it, or uploads break.
    "ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS department_id INT REFERENCES departments(id)",
    "CREATE INDEX IF NOT EXISTS idx_knowledge_documents_department ON knowledge_documents(department_id)",
]

ENSURE_DEFAULT_DEPARTMENT = (
    "INSERT INTO departments (code, name) VALUES (%s, %s) "
    "ON CONFLICT (code) DO NOTHING"
)

BACKFILL_DOCUMENTS = (
    "UPDATE knowledge_documents SET department_id = %s WHERE department_id IS NULL"
)


def ensure_access_schema() -> int:
    """Create the tenancy tables, then return the default department id."""
    with get_connection() as conn:
        with conn.cursor() as cur:
            for statement in DDL_STATEMENTS:
                cur.execute(statement)
            cur.execute(ENSURE_DEFAULT_DEPARTMENT, (DEFAULT_DEPARTMENT_CODE, DEFAULT_DEPARTMENT_NAME))
            cur.execute("SELECT id FROM departments WHERE code = %s", (DEFAULT_DEPARTMENT_CODE,))
            row = cur.fetchone()
            default_id = row["id"] if row else None
            if default_id is None:
                raise RuntimeError(f"default department {DEFAULT_DEPARTMENT_CODE!r} missing after upsert")
            cur.execute(BACKFILL_DOCUMENTS, (default_id,))
            logger.info(
                "Access schema ready: default department id=%s, documents backfilled=%s",
                default_id,
                cur.rowcount,
            )
    return default_id
