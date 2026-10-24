"""Robot feedback tickets."""

from datetime import datetime
from typing import Optional
from uuid import uuid4

import psycopg
from fastapi import APIRouter, HTTPException
from psycopg.rows import dict_row
from pydantic import BaseModel, Field

from ..config import POSTGRES_DSN_FACE

router = APIRouter(prefix="/api/issues", tags=["issues"])


class IssueCreate(BaseModel):
    identity: str = Field(min_length=4, max_length=32)
    detail: str = Field(min_length=5, max_length=4000)
    other_notes: str = Field(default="", max_length=2000)


class IssueUpdate(BaseModel):
    answer: Optional[str] = Field(default=None, max_length=4000)
    handler: Optional[str] = Field(default=None, max_length=200)
    status: Optional[str] = Field(default=None, max_length=64)
    status_note: Optional[str] = Field(default=None, max_length=2000)


def _schema() -> None:
    with psycopg.connect(POSTGRES_DSN_FACE, autocommit=True) as conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS support_issues (
                id UUID PRIMARY KEY, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                identity TEXT NOT NULL, detail TEXT NOT NULL, other_notes TEXT NOT NULL DEFAULT '',
                answer TEXT NOT NULL DEFAULT '', handler TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'Mới', status_note TEXT NOT NULL DEFAULT '',
                updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )
        """)


def _row(row: dict) -> dict:
    return {key: value.isoformat() if isinstance(value, datetime) else str(value) if key == "id" else value for key, value in row.items()}


@router.post("")
async def create_issue(request: IssueCreate):
    _schema()
    issue_id = uuid4()
    with psycopg.connect(POSTGRES_DSN_FACE, autocommit=True, row_factory=dict_row) as conn:
        row = conn.execute(
            """INSERT INTO support_issues (id, identity, detail, other_notes)
               VALUES (%s, %s, %s, %s) RETURNING *""",
            (issue_id, request.identity.strip(), request.detail.strip(), request.other_notes.strip()),
        ).fetchone()
    return {"success": True, "issue": _row(row)}


@router.get("")
async def list_issues(limit: int = 100):
    _schema()
    with psycopg.connect(POSTGRES_DSN_FACE, row_factory=dict_row) as conn:
        rows = conn.execute("SELECT * FROM support_issues ORDER BY created_at DESC LIMIT %s", (min(max(limit, 1), 500),)).fetchall()
    return {"issues": [_row(row) for row in rows]}


@router.put("/{issue_id}")
async def update_issue(issue_id: str, request: IssueUpdate):
    _schema()
    values = request.model_dump(exclude_none=True)
    if not values:
        raise HTTPException(422, "No update fields")
    columns = ", ".join(f"{key} = %s" for key in values)
    with psycopg.connect(POSTGRES_DSN_FACE, autocommit=True, row_factory=dict_row) as conn:
        row = conn.execute(
            f"UPDATE support_issues SET {columns}, updated_at = NOW() WHERE id = %s RETURNING *",
            (*values.values(), issue_id),
        ).fetchone()
    if not row:
        raise HTTPException(404, "Issue not found")
    return {"success": True, "issue": _row(row)}
