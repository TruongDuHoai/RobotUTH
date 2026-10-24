from contextlib import contextmanager

import pytest

from server.knowledge import access_schema, rbac
from server.knowledge.access_schema import BACKFILL_DOCUMENTS, DDL_STATEMENTS

ACTOR_DEPT = 7
OTHER_DEPT = 8


def actor(role, department_id=ACTOR_DEPT):
    return rbac.Actor(user_id=1, username="u", role=role, department_id=department_id)


# Expected scope per action, keyed by the role that gets it. Anything not listed
# is denied for that role.
MATRIX = {
    rbac.DEPARTMENT_CREATE:  {rbac.ADMIN: rbac.ALL},
    rbac.DEPARTMENT_LIST:    {rbac.ADMIN: rbac.ALL, rbac.EDITOR: rbac.OWN, rbac.VIEWER: rbac.OWN},
    rbac.USER_MANAGE:        {rbac.ADMIN: rbac.ALL},
    rbac.ROBOT_ASSIGN:       {rbac.ADMIN: rbac.ALL},
    rbac.DOCUMENT_UPLOAD:    {rbac.ADMIN: rbac.ALL, rbac.EDITOR: rbac.OWN},
    rbac.DOCUMENT_LIST:      {rbac.ADMIN: rbac.ALL, rbac.EDITOR: rbac.OWN, rbac.VIEWER: rbac.OWN},
    rbac.DOCUMENT_DELETE:    {rbac.ADMIN: rbac.ALL, rbac.EDITOR: rbac.OWN},
    rbac.DOCUMENT_REPROCESS: {rbac.ADMIN: rbac.ALL, rbac.EDITOR: rbac.OWN},
    rbac.INSTRUCTION_READ:   {rbac.ADMIN: rbac.ALL, rbac.EDITOR: rbac.OWN, rbac.VIEWER: rbac.OWN},
    rbac.INSTRUCTION_WRITE:  {rbac.ADMIN: rbac.ALL, rbac.EDITOR: rbac.OWN},
    rbac.PROMPT_PREVIEW:     {rbac.ADMIN: rbac.ALL, rbac.EDITOR: rbac.OWN, rbac.VIEWER: rbac.OWN},
    rbac.QUERY_TEST:         {rbac.ADMIN: rbac.ALL, rbac.EDITOR: rbac.OWN, rbac.VIEWER: rbac.OWN},
    rbac.BUNDLE_PUBLISH:     {rbac.ADMIN: rbac.ALL, rbac.EDITOR: rbac.OWN},
    rbac.BUNDLE_ROLLBACK:    {rbac.ADMIN: rbac.ALL, rbac.EDITOR: rbac.OWN},
    rbac.AUDIT_READ:         {rbac.ADMIN: rbac.ALL, rbac.EDITOR: rbac.OWN},
}


def test_matrix_covers_every_declared_action():
    assert set(MATRIX) == set(rbac.ACTIONS), "an action exists without a ruling, or vice versa"


@pytest.mark.parametrize("action", sorted(rbac.ACTIONS))
@pytest.mark.parametrize("role", sorted(rbac.ROLES))
def test_scope_table_matches_matrix(role, action):
    assert rbac.SCOPE[action][role] == MATRIX[action].get(role)


@pytest.mark.parametrize("action", sorted(rbac.ACTIONS))
@pytest.mark.parametrize("role", sorted(rbac.ROLES))
def test_own_scope_never_opens_another_department(role, action):
    allowed = rbac.can(role, action, ACTOR_DEPT, OTHER_DEPT)
    if role != rbac.ADMIN:
        assert not allowed, f"{role} reached {action} in a foreign department"


@pytest.mark.parametrize("action", sorted(rbac.ACTIONS))
def test_admin_reaches_every_action(action):
    assert rbac.can(rbac.ADMIN, action, None, OTHER_DEPT)


def test_editor_keeps_own_department():
    assert rbac.can(rbac.EDITOR, rbac.DOCUMENT_UPLOAD, ACTOR_DEPT, ACTOR_DEPT)


VIEWER_READ_ONLY = {
    rbac.DEPARTMENT_LIST, rbac.DOCUMENT_LIST, rbac.INSTRUCTION_READ,
    rbac.PROMPT_PREVIEW, rbac.QUERY_TEST,
}


def test_viewer_only_reads():
    for action in sorted(rbac.ACTIONS):
        assert rbac.can(rbac.VIEWER, action, ACTOR_DEPT, ACTOR_DEPT) == (action in VIEWER_READ_ONLY)


def test_scoped_role_without_department_is_denied():
    assert not rbac.can(rbac.EDITOR, rbac.DOCUMENT_LIST, None, ACTOR_DEPT)
    assert not rbac.can(rbac.EDITOR, rbac.DOCUMENT_LIST, None, None)


def test_unknown_action_and_unknown_role_denied():
    assert rbac.can(rbac.ADMIN, "knowledge.nuke", None, None) is False
    assert rbac.can("superuser", rbac.DOCUMENT_UPLOAD, ACTOR_DEPT, ACTOR_DEPT) is False


def test_resolve_department_returns_own_for_scoped_actor():
    assert rbac.resolve_department(actor(rbac.EDITOR), rbac.DOCUMENT_LIST) == ACTOR_DEPT


def test_resolve_department_accepts_matching_target():
    assert rbac.resolve_department(actor(rbac.EDITOR), rbac.DOCUMENT_LIST, ACTOR_DEPT) == ACTOR_DEPT


def test_resolve_department_foreign_target_is_out_of_scope_not_forbidden():
    with pytest.raises(rbac.OutOfScope):
        rbac.resolve_department(actor(rbac.EDITOR), rbac.DOCUMENT_LIST, OTHER_DEPT)


def test_resolve_department_forbidden_before_scoping():
    with pytest.raises(rbac.PermissionDenied):
        rbac.resolve_department(actor(rbac.VIEWER), rbac.DOCUMENT_UPLOAD, ACTOR_DEPT)


def test_resolve_department_passes_admin_target_through():
    assert rbac.resolve_department(actor(rbac.ADMIN, None), rbac.DOCUMENT_LIST, OTHER_DEPT) == OTHER_DEPT
    assert rbac.resolve_department(actor(rbac.ADMIN, None), rbac.DEPARTMENT_CREATE) is None


def test_resolve_department_scoped_actor_missing_department_denied():
    with pytest.raises(rbac.PermissionDenied):
        rbac.resolve_department(actor(rbac.EDITOR, None), rbac.DOCUMENT_LIST)


def test_department_filter_is_none_only_for_admin():
    assert rbac.department_filter(actor(rbac.ADMIN, None)) is None
    assert rbac.department_filter(actor(rbac.EDITOR)) == ACTOR_DEPT
    with pytest.raises(rbac.PermissionDenied):
        rbac.department_filter(actor(rbac.VIEWER, None))


@pytest.mark.parametrize("statement", DDL_STATEMENTS)
def test_ddl_is_idempotent(statement):
    head = statement.strip().upper()
    assert "CREATE TABLE" not in head or "IF NOT EXISTS" in head
    assert "CREATE INDEX" not in head or "IF NOT EXISTS" in head
    assert "ADD COLUMN" not in head or "IF NOT EXISTS" in head


def test_document_backfill_touches_only_unscoped_rows():
    assert "WHERE department_id IS NULL" in BACKFILL_DOCUMENTS


def test_document_department_column_stays_nullable():
    """Ingest still inserts without a department — NOT NULL would break uploads."""
    column = next(s for s in DDL_STATEMENTS if "ADD COLUMN" in s)
    assert "NOT NULL" not in column


MIGRATED_DEPT = 42


@contextmanager
def _recording_connection(calls, fetchone_rows):
    class Cursor:
        rowcount = -1

        def __init__(self):
            self._sql = ""

        def execute(self, sql, params=None):
            self._sql = sql
            calls.append((" ".join(sql.split()), params))

        def fetchone(self):
            return fetchone_rows.get(self._sql.split()[0], None)

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

    class Connection:
        def cursor(self):
            return Cursor()

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

    yield Connection()


@pytest.fixture
def recorded(monkeypatch):
    calls = []

    def connect():
        return _recording_connection(calls, {"SELECT": {"id": MIGRATED_DEPT}})

    monkeypatch.setattr(access_schema, "get_connection", connect)
    return calls


def test_migration_runs_in_a_safe_order(recorded):
    assert access_schema.ensure_access_schema() == MIGRATED_DEPT
    sql = [statement for statement, _ in recorded]
    add_column = next(i for i, s in enumerate(sql) if "ADD COLUMN IF NOT EXISTS department_id" in s)
    upsert = next(i for i, s in enumerate(sql) if s.startswith("INSERT INTO departments"))
    backfill = next(i for i, s in enumerate(sql) if s.startswith("UPDATE knowledge_documents SET department_id"))
    assert add_column < upsert < backfill
    assert recorded[backfill][1] == (MIGRATED_DEPT,)


def test_migration_fails_loudly_without_default_department(recorded, monkeypatch):
    monkeypatch.setattr(
        access_schema, "get_connection",
        lambda: _recording_connection(recorded, {}),
    )
    with pytest.raises(RuntimeError, match="default department"):
        access_schema.ensure_access_schema()
