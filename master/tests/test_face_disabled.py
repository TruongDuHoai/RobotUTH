"""Face recognition master switch (FACE_ENABLED, disabled 2026-10-05).

Contract: while FACE_ENABLED=false every /api/attendance/face/* endpoint
fails closed with 503 and never touches the face service; flipping the flag
back to true restores the routes.
"""

from fastapi import FastAPI
from fastapi.testclient import TestClient

from server.api import attendance as attendance_api


def make_client(monkeypatch, enabled: bool):
    monkeypatch.setattr(attendance_api, "FACE_ENABLED", enabled)
    app = FastAPI()
    app.include_router(attendance_api.router)
    return TestClient(app)


def test_face_endpoints_503_when_disabled(monkeypatch):
    client = make_client(monkeypatch, enabled=False)

    calls = [
        ("POST", "/api/attendance/face/detect", {"image_base64": "x"}),
        ("POST", "/api/attendance/face/enroll", {"name": "a", "image_base64": "x"}),
        ("POST", "/api/attendance/face/recognize", {"image_base64": "x"}),
        ("GET", "/api/attendance/face/history", None),
        ("GET", "/api/attendance/face/list", None),
        ("DELETE", "/api/attendance/face/some-id", None),
    ]
    for method, path, body in calls:
        resp = client.request(method, path, json=body)
        assert resp.status_code == 503, f"{method} {path} -> {resp.status_code}"
        assert "disabled" in resp.json()["detail"].lower()


def test_face_disabled_never_loads_service(monkeypatch):
    """The gate must fire before get_face_service() is reached."""
    def boom():
        raise AssertionError("face service must not be touched while disabled")

    monkeypatch.setattr(attendance_api, "get_face_service", boom)
    client = make_client(monkeypatch, enabled=False)
    resp = client.post("/api/attendance/face/detect", json={"image_base64": "x"})
    assert resp.status_code == 503


def test_face_list_works_when_enabled(monkeypatch):
    class FakeStore:
        def list_all(self):
            return []

    monkeypatch.setattr(attendance_api, "get_face_store", lambda: FakeStore())
    client = make_client(monkeypatch, enabled=True)
    resp = client.get("/api/attendance/face/list")
    assert resp.status_code == 200
    assert resp.json() == {"success": True, "faces": [], "count": 0}
