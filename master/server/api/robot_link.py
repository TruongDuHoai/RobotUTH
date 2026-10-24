"""Robot link — realtime WebSocket relay between operators and robots.

Topology (see remote-control spec): the robot dials OUT to the server; the
browser dials the server; the browser never reaches the robot directly.

Auth lives in the WS handlers, NOT in middleware: RobotApiAuthMiddleware is a
BaseHTTPMiddleware subclass, which only runs for scope "http" — WebSocket
connections bypass it entirely.
Token comes from the "Authorization" header (OkHttp websockets can send
headers) or a `?token=` query param; empty ROBOT_API_TOKENS keeps LAN dev open.

Protocol (kept small): register, heartbeat, position, move, stop, navigate,
stop_navigation, navigation_status, status, pose_status, error; map management: map_start_create,
map_stop_create, map_switch, map_remove, map_list, place_list, current_map, place_add,
place_delete → replies map_operation_status / map_list / place_list /
current_map. On register the robot may attach map/places/battery/speed_limits/readiness; the
server relays them in register_ack and caches the latest advertised/status metadata
so a late-connecting operator's hello carries the robot's current state. The server relays without
transforming payloads; manual-control state machines (watchdog, speed clamp) run on
the robot.
"""

import asyncio
import itertools
import logging

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from ..config import ROBOT_API_TOKENS
from ..middleware.robot_api_auth import is_robot_request_authorized

router = APIRouter(prefix="/ws", tags=["robot-link"])
logger = logging.getLogger(__name__)

# In-memory connection state (single server process — see spec on scaling)
robots: dict[str, WebSocket] = {}
robot_operators: dict[str, set[WebSocket]] = {}
control_lease: dict[str, str] = {}  # robot_id -> operator_id
# Latest robot-advertised metadata for late-connecting operators. Cleared only
# when the active socket disconnects (not when a superseded handler winds down).
robot_state: dict[str, dict] = {}
REGISTER_STATE_KEYS = ("map", "places", "battery", "speed_limits", "readiness")
STATUS_STATE_KEYS = ("battery", "readiness")

OPERATOR_TYPES = {
    "move", "stop", "navigate", "stop_navigation", "drive_to",
    # map management (console Map manager card; reply types below)
    "map_start_create", "map_stop_create", "map_switch", "map_remove",
    "map_list", "place_list", "current_map", "place_add", "place_delete",
}
ROBOT_TYPES = {
    "position", "navigation_status", "stopped", "ack", "error",
    "register", "heartbeat", "status", "pose_status",
    # map management replies
    "map_operation_status", "map_list", "place_list", "current_map",
}
# Read-only map queries skip the control lease so viewers can browse; every
# mutation still requires the lease like move/navigate do.
MAP_READ_TYPES = {"map_list", "place_list", "current_map"}

_lease_lock = asyncio.Lock()
_operator_ids = itertools.count(1)


def _authorized(websocket: WebSocket) -> bool:
    token = websocket.query_params.get("token")
    if token:
        authorization = f"Bearer {token}"
    else:
        authorization = websocket.headers.get("authorization")
    return is_robot_request_authorized(authorization, ROBOT_API_TOKENS)


async def _safe_send(ws: WebSocket, payload: dict) -> bool:
    try:
        await ws.send_json(payload)
        return True
    except Exception:
        return False


async def _to_operator(robot_id: str, payload: dict) -> None:
    for op in list(robot_operators.get(robot_id, ())):
        if not await _safe_send(op, payload):
            robot_operators.get(robot_id, set()).discard(op)


@router.websocket("/robot/{robot_id}")
async def robot_socket(websocket: WebSocket, robot_id: str):
    if not _authorized(websocket):
        logger.warning("robot WS auth failure robot_id=%s", robot_id)
        await websocket.close(code=4401)
        return
    await websocket.accept()
    logger.info("robot connected robot_id=%s", robot_id)

    old = robots.get(robot_id)
    if old is not None and old is not websocket:
        await _safe_send(old, {"type": "error", "code": "superseded"})
        try:
            await old.close()
        except Exception:
            pass
    robots[robot_id] = websocket

    try:
        while True:
            msg = await websocket.receive_json()
            if not isinstance(msg, dict):
                continue
            mtype = msg.get("type")
            if mtype == "heartbeat":
                continue
            if mtype == "register":
                ack = {"type": "register_ack", "robot_id": robot_id}
                # pass through optional robot-provided state so the console
                # renders real values before any polling round-trips
                state = robot_state.setdefault(robot_id, {})
                for key in REGISTER_STATE_KEYS:
                    if key in msg:
                        ack[key] = msg[key]
                        state[key] = msg[key]
                await _to_operator(robot_id, ack)
                continue
            if mtype in ROBOT_TYPES:
                if mtype == "status":
                    state = robot_state.setdefault(robot_id, {})
                    for key in STATUS_STATE_KEYS:
                        if key in msg:
                            state[key] = msg[key]
                await _to_operator(robot_id, msg)
    except WebSocketDisconnect:
        pass
    finally:
        active_disconnect = robots.get(robot_id) is websocket
        if active_disconnect:
            del robots[robot_id]
            robot_state.pop(robot_id, None)
            async with _lease_lock:
                control_lease.pop(robot_id, None)
            await _to_operator(robot_id, {"type": "error", "code": "robot_offline"})
        logger.info("robot disconnected robot_id=%s active=%s", robot_id, active_disconnect)


@router.websocket("/operator/{robot_id}")
async def operator_socket(websocket: WebSocket, robot_id: str):
    if not _authorized(websocket):
        logger.warning("operator WS auth failure robot_id=%s", robot_id)
        await websocket.close(code=4401)
        return
    await websocket.accept()
    operator_id = f"op{next(_operator_ids)}"
    robot_operators.setdefault(robot_id, set()).add(websocket)
    logger.info("operator connected robot_id=%s operator_id=%s",
                robot_id, operator_id)
    hello = {"type": "hello", "operator_id": operator_id,
             "robot_online": robot_id in robots}
    cached_state = robot_state.get(robot_id, {})
    for key in REGISTER_STATE_KEYS:
        if key in cached_state:
            hello[key] = cached_state[key]
    await _safe_send(websocket, hello)

    async def release_and_stop():
        if control_lease.get(robot_id) == operator_id:
            del control_lease[robot_id]
            logger.info("control released robot_id=%s operator_id=%s "
                        "(disconnect)", robot_id, operator_id)
            robot = robots.get(robot_id)
            if robot is not None:
                await _safe_send(robot, {"type": "stop",
                                         "reason": "operator_disconnected"})

    try:
        while True:
            msg = await websocket.receive_json()
            if not isinstance(msg, dict):
                continue
            mtype = msg.get("type")

            if mtype == "take_control":
                async with _lease_lock:
                    holder = control_lease.get(robot_id)
                    if holder is None or holder == operator_id:
                        control_lease[robot_id] = operator_id
                        granted = True
                    else:
                        granted = False
                if granted:
                    logger.info("control acquired robot_id=%s operator_id=%s",
                                robot_id, operator_id)
                    await _safe_send(websocket, {"type": "control_granted"})
                else:
                    await _safe_send(websocket, {
                        "type": "error", "code": "lease_held",
                        "detail": f"another operator ({holder}) holds control"})
                continue

            if mtype == "release_control":
                await release_and_stop()
                await _safe_send(websocket, {"type": "control_released"})
                continue

            if mtype in OPERATOR_TYPES:
                if mtype not in MAP_READ_TYPES and \
                        control_lease.get(robot_id) != operator_id:
                    await _safe_send(websocket, {
                        "type": "error", "code": "not_controller"})
                    continue
                robot = robots.get(robot_id)
                if robot is None:
                    await _safe_send(websocket, {"type": "error",
                                                 "code": "robot_offline"})
                    continue
                if not await _safe_send(robot, msg):
                    await _safe_send(websocket, {"type": "error",
                                                 "code": "robot_offline"})
                continue
            # unknown / viewer messages: ignored, connection stays up
    except WebSocketDisconnect:
        pass
    finally:
        await release_and_stop()
        robot_operators.get(robot_id, set()).discard(websocket)
        if not robot_operators.get(robot_id):
            robot_operators.pop(robot_id, None)
        logger.info("operator disconnected robot_id=%s operator_id=%s",
                    robot_id, operator_id)
