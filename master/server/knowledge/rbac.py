"""Permission policy for department-scoped knowledge management.

Pure functions only — no DB, no FastAPI — so the whole matrix is unit-testable
and there is exactly one place where a permission question is answered.
"""

from dataclasses import dataclass
from typing import Dict, FrozenSet, Optional

ADMIN = "admin"
EDITOR = "editor"
VIEWER = "viewer"
ROLES = frozenset({ADMIN, EDITOR, VIEWER})

ALL = "all"
OWN = "own"

DEPARTMENT_CREATE = "department.create"
DEPARTMENT_LIST = "department.list"
USER_MANAGE = "user.manage"
ROBOT_ASSIGN = "robot.assign"
DOCUMENT_UPLOAD = "document.upload"
DOCUMENT_LIST = "document.list"
DOCUMENT_DELETE = "document.delete"
DOCUMENT_REPROCESS = "document.reprocess"
INSTRUCTION_READ = "instruction.read"
INSTRUCTION_WRITE = "instruction.write"
PROMPT_PREVIEW = "prompt.preview"
QUERY_TEST = "query.test"
BUNDLE_PUBLISH = "bundle.publish"
BUNDLE_ROLLBACK = "bundle.rollback"
AUDIT_READ = "audit.read"

ACTIONS: FrozenSet[str] = frozenset({
    DEPARTMENT_CREATE, DEPARTMENT_LIST, USER_MANAGE, ROBOT_ASSIGN,
    DOCUMENT_UPLOAD, DOCUMENT_LIST, DOCUMENT_DELETE, DOCUMENT_REPROCESS,
    INSTRUCTION_READ, INSTRUCTION_WRITE, PROMPT_PREVIEW, QUERY_TEST,
    BUNDLE_PUBLISH, BUNDLE_ROLLBACK, AUDIT_READ,
})

# "all" = every department, "own" = only the actor's department, None = denied.
SCOPE: Dict[str, Dict[str, Optional[str]]] = {
    DEPARTMENT_CREATE:    {ADMIN: ALL, EDITOR: None, VIEWER: None},
    DEPARTMENT_LIST:      {ADMIN: ALL, EDITOR: OWN,  VIEWER: OWN},
    USER_MANAGE:          {ADMIN: ALL, EDITOR: None, VIEWER: None},
    ROBOT_ASSIGN:         {ADMIN: ALL, EDITOR: None, VIEWER: None},
    DOCUMENT_UPLOAD:      {ADMIN: ALL, EDITOR: OWN,  VIEWER: None},
    DOCUMENT_LIST:        {ADMIN: ALL, EDITOR: OWN,  VIEWER: OWN},
    DOCUMENT_DELETE:      {ADMIN: ALL, EDITOR: OWN,  VIEWER: None},
    DOCUMENT_REPROCESS:   {ADMIN: ALL, EDITOR: OWN,  VIEWER: None},
    INSTRUCTION_READ:     {ADMIN: ALL, EDITOR: OWN,  VIEWER: OWN},
    INSTRUCTION_WRITE:    {ADMIN: ALL, EDITOR: OWN,  VIEWER: None},
    PROMPT_PREVIEW:       {ADMIN: ALL, EDITOR: OWN,  VIEWER: OWN},
    QUERY_TEST:           {ADMIN: ALL, EDITOR: OWN,  VIEWER: OWN},
    BUNDLE_PUBLISH:       {ADMIN: ALL, EDITOR: OWN,  VIEWER: None},
    BUNDLE_ROLLBACK:      {ADMIN: ALL, EDITOR: OWN,  VIEWER: None},
    AUDIT_READ:           {ADMIN: ALL, EDITOR: OWN,  VIEWER: None},
}


class PermissionDenied(Exception):
    """Role may not perform the action at all → HTTP 403."""

    def __init__(self, action: str):
        super().__init__(f"role may not perform {action}")
        self.action = action


class OutOfScope(Exception):
    """Action is allowed but the target belongs to another department.

    Callers must surface this as 404, not 403, so probing cannot confirm that an
    id exists in somebody else's department.
    """

    def __init__(self, action: str):
        super().__init__(f"{action} is outside the actor's department")
        self.action = action


@dataclass(frozen=True)
class Actor:
    user_id: int
    username: str
    role: str
    department_id: Optional[int]

    @property
    def is_admin(self) -> bool:
        return self.role == ADMIN


def scope_for(role: str, action: str) -> Optional[str]:
    return SCOPE.get(action, {}).get(role)


def can(
    role: str,
    action: str,
    actor_department_id: Optional[int] = None,
    target_department_id: Optional[int] = None,
) -> bool:
    rule = scope_for(role, action)
    if rule is None:
        return False
    if rule == ALL:
        return True
    if actor_department_id is None:
        return False
    if target_department_id is None:
        return True
    return target_department_id == actor_department_id


def resolve_department(
    actor: Actor,
    action: str,
    target_department_id: Optional[int] = None,
) -> Optional[int]:
    """Effective department a query must run against, or a raised exception.

    Non-admins never get to choose: a requested department is validated against
    their own, so a route that forwards a client-supplied id cannot leak data.
    """
    rule = scope_for(actor.role, action)
    if rule is None:
        raise PermissionDenied(action)
    if rule == ALL:
        return target_department_id
    if actor.department_id is None:
        raise PermissionDenied(action)
    if target_department_id is None:
        return actor.department_id
    if target_department_id != actor.department_id:
        raise OutOfScope(action)
    return target_department_id


def department_filter(actor: Actor) -> Optional[int]:
    """Row filter for repositories: None means no filter, and only admins get it."""
    if actor.is_admin:
        return None
    if actor.department_id is None:
        raise PermissionDenied("unscoped read")
    return actor.department_id
