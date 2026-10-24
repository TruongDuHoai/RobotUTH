"""Initialize knowledge base database (pgvector RAG tables)."""

import psycopg

from ..config import (
    POSTGRES_DB_KNOWLEDGE,
    POSTGRES_HOST,
    POSTGRES_PASSWORD,
    POSTGRES_PORT,
    POSTGRES_USER,
)
from ..knowledge.access_schema import ensure_access_schema
from ..knowledge.schema import init_schema


def init_knowledge_db() -> None:
    print(f"Initializing knowledge database: {POSTGRES_DB_KNOWLEDGE}")

    try:
        conn = psycopg.connect(
            host=POSTGRES_HOST,
            port=POSTGRES_PORT,
            user=POSTGRES_USER,
            password=POSTGRES_PASSWORD,
            dbname="postgres",
            autocommit=True,
        )
        conn.execute(f'CREATE DATABASE "{POSTGRES_DB_KNOWLEDGE}"')
        conn.close()
        print(f"Database {POSTGRES_DB_KNOWLEDGE} created")
    except psycopg.errors.DuplicateDatabase:
        print(f"Database {POSTGRES_DB_KNOWLEDGE} already exists")
    except Exception as exc:
        print(f"Error creating database: {exc}")
        raise

    init_schema()
    default_department = ensure_access_schema()
    print(f"Knowledge schema initialized successfully (default department id={default_department})")


if __name__ == "__main__":
    init_knowledge_db()
