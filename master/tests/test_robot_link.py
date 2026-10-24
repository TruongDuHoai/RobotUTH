import pytest
from fastapi import FastAPI
from starlette.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from server.api import robot_link


@pytest.fixture()
def client(monkeypatch):
    monkeypatch.setattr(robot_link, "ROBOT_API_TOKENS", ("test-token",))
    robot_link.robots.clear()
    robot_link.robot_operators.clear()
    robot_link.control_lease.clear()
    robot_link.robot_state.clear()
    app = FastAPI()
    app.include_router(robot_link.router)
    with TestClient(app) as test_client:
        yield test_client
    robot_link.robots.clear()
    robot_link.robot_operators.clear()
    robot_link.control_lease.clear()
    robot_link.robot_state.clear()


def ws_path(role, robot_id="robot-1", token="test-token"):
    return f"/ws/{role}/{robot_id}?token={token}"


def test_websocket_auth_accepts_query_and_header_and_rejects_bad_token(client):
    with client.websocket_connect(ws_path("operator")) as operator:
        assert operator.receive_json()["type"] == "hello"

    with client.websocket_connect(
        "/ws/operator/robot-1",
        headers={"Authorization": "Bearer test-token"},
    ) as operator:
        assert operator.receive_json()["type"] == "hello"

    with pytest.raises(WebSocketDisconnect) as exc_info:
        with client.websocket_connect(ws_path("operator", token="wrong")):
            pass
    assert exc_info.value.code == 4401


def test_control_lease_is_exclusive_and_required_for_mutations(client):
    with client.websocket_connect(ws_path("robot")) as robot:
        with client.websocket_connect(ws_path("operator")) as first:
            first.receive_json()
            with client.websocket_connect(ws_path("operator")) as second:
                second.receive_json()

                first.send_json({"type": "take_control"})
                assert first.receive_json() == {"type": "control_granted"}

                second.send_json({"type": "take_control"})
                denied = second.receive_json()
                assert denied["type"] == "error"
                assert denied["code"] == "lease_held"

                second.send_json({"type": "map_switch", "map": "floor-2"})
                assert second.receive_json() == {
                    "type": "error", "code": "not_controller"
                }

                first.send_json({"type": "map_switch", "map": "floor-2"})
                assert robot.receive_json() == {
                    "type": "map_switch", "map": "floor-2"
                }


def test_map_reads_do_not_require_lease_but_mutations_do(client):
    with client.websocket_connect(ws_path("robot")) as robot:
        with client.websocket_connect(ws_path("operator")) as operator:
            operator.receive_json()

            for message_type in ("map_list", "place_list", "current_map"):
                request = {"type": message_type, "request": {"refresh": True}}
                operator.send_json(request)
                assert robot.receive_json() == request

            operator.send_json({"type": "place_delete", "name": "Library"})
            assert operator.receive_json() == {
                "type": "error", "code": "not_controller"
            }


def test_controller_disconnect_sends_stop_and_releases_lease(client):
    with client.websocket_connect(ws_path("robot")) as robot:
        with client.websocket_connect(ws_path("operator")) as operator:
            hello = operator.receive_json()
            operator.send_json({"type": "take_control"})
            assert operator.receive_json() == {"type": "control_granted"}
            operator_id = hello["operator_id"]
            assert robot_link.control_lease["robot-1"] == operator_id

        assert robot.receive_json() == {
            "type": "stop", "reason": "operator_disconnected"
        }
        assert "robot-1" not in robot_link.control_lease


def test_stop_navigation_is_lease_gated_and_relayed_unchanged(client):
    with client.websocket_connect(ws_path("robot")) as robot:
        with client.websocket_connect(ws_path("operator")) as operator:
            operator.receive_json()
            request = {
                "type": "stop_navigation",
                "command_id": "stop-17",
                "reason": {"code": "operator_request", "force": False},
            }

            operator.send_json(request)
            assert operator.receive_json() == {
                "type": "error", "code": "not_controller"
            }
            operator.send_json({"type": "take_control"})
            assert operator.receive_json() == {"type": "control_granted"}
            operator.send_json(request)
            assert robot.receive_json() == request


def test_robot_structured_outcome_passes_through_unchanged(client):
    with client.websocket_connect(ws_path("robot")) as robot:
        with client.websocket_connect(ws_path("operator")) as operator:
            operator.receive_json()
            payload = {
                "type": "error",
                "command_id": "nav-42",
                "operation": "navigate",
                "code": "action_refused",
                "phase": "submit",
                "rc": -7,
                "vendor_symbol": "ACTION_RESPONSE_SEND_STOP_CMD_ERROR",
                "detail": "vendor rejected action",
            }
            robot.send_json(payload)
            assert operator.receive_json() == payload


def test_late_operator_receives_cached_robot_readiness(client):
    readiness = {
        "ready": False,
        "core_service": "disconnected",
        "localization": "unlocalized",
    }
    speed_limits = {"linear_min": 0.1, "linear_max": 1.2}
    with client.websocket_connect(ws_path("robot")) as robot:
        robot.send_json({
            "type": "register",
            "robot_id": "robot-1",
            "readiness": readiness,
            "speed_limits": speed_limits,
        })
        with client.websocket_connect(ws_path("operator")) as operator:
            hello = operator.receive_json()
            assert hello["robot_online"] is True
            assert hello["readiness"] == readiness
            assert hello["speed_limits"] == speed_limits


def test_active_robot_disconnect_releases_lease(client):
    with client.websocket_connect(ws_path("operator")) as operator:
        operator.receive_json()
        with client.websocket_connect(ws_path("robot")):
            operator.send_json({"type": "take_control"})
            assert operator.receive_json() == {"type": "control_granted"}
            assert "robot-1" in robot_link.control_lease
        assert operator.receive_json() == {"type": "error", "code": "robot_offline"}
        assert "robot-1" not in robot_link.control_lease


def test_replaced_robot_does_not_emit_false_offline(client):
    with client.websocket_connect(ws_path("operator")) as operator:
        operator.receive_json()
        with client.websocket_connect(ws_path("robot")) as first:
            with client.websocket_connect(ws_path("robot")) as second:
                assert robot_link.robots["robot-1"] is not first
                second.send_json({"type": "status", "readiness": {"core_service": "connected"}})
                assert operator.receive_json() == {
                    "type": "status", "readiness": {"core_service": "connected"}
                }
