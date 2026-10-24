"""Bearer-token gate for the small API surface exposed to robots and admins.

Two layers decide whether a request needs the token:

* ``ROBOT_PATHS`` / ``ROBOT_PATH_PREFIXES`` — the robot-facing endpoints, gated
  on every method.
* ``ADMIN_WRITE_PREFIXES`` — the admin surface (knowledge base, settings). Only
  *mutations* are gated; reads stay open so the kiosk can render status without
  a token. The nginx gateway additionally restricts these paths to the LAN
  CIDR, so this is defence in depth: reaching the port from the LAN is no
  longer sufficient to change data.
"""

import secrets

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse

ROBOT_PATHS = frozenset({
    "/api/chat/query",
    "/api/tts/synthesize",
    "/api/issues",
    "/asr/ctc/chunk",
    "/api/attendance/face/detect",
    "/api/attendance/face/enroll",
    "/api/attendance/face/recognize",
})

# Prefix-gated paths: exact matching can't cover /api/issues/{issue_id}.
ROBOT_PATH_PREFIXES = ("/api/issues/",)

# Admin mutations require the token too. The gateway already limits these
# prefixes to the LAN; this makes the app itself refuse an unauthenticated
# write even from inside the LAN.
ADMIN_WRITE_PREFIXES = ("/api/knowledge", "/api/settings")
ADMIN_WRITE_METHODS = frozenset({"POST", "PUT", "PATCH", "DELETE"})


def requires_robot_token(method: str, path: str) -> bool:
    """Return True when ``method path`` must present a robot Bearer token."""
    if path in ROBOT_PATHS or path.startswith(ROBOT_PATH_PREFIXES):
        return True
    return method in ADMIN_WRITE_METHODS and path.startswith(ADMIN_WRITE_PREFIXES)


def is_robot_request_authorized(authorization: str | None, tokens: tuple[str, ...]) -> bool:
    """Accept an exact Bearer token; an empty configuration keeps LAN dev working."""
    if not tokens:
        return True
    if not authorization or not authorization.startswith("Bearer "):
        return False
    supplied = authorization[7:]
    return any(secrets.compare_digest(supplied, token) for token in tokens)


class RobotApiAuthMiddleware(BaseHTTPMiddleware):
    def __init__(self, app, *, tokens: tuple[str, ...]):
        super().__init__(app)
        self.tokens = tokens

    async def dispatch(self, request: Request, call_next):
        if requires_robot_token(request.method, request.url.path) and not is_robot_request_authorized(
            request.headers.get("Authorization"), self.tokens
        ):
            return JSONResponse(status_code=401, content={"detail": "Robot token required"})
        return await call_next(request)
