"""
Nemotron ASR provider tests.

The point of these is the wire contract, not the model: `nemo-speech serve`
answers with nothing but {"text": ...}, while the Android client depends on
success / transcript / duration_seconds / processing_time_seconds. If those
two shapes ever drift apart the robot goes silent with no obvious cause, so
each one is pinned here.
"""

import io
import wave
from types import SimpleNamespace

import httpx
import pytest
from fastapi import FastAPI
from starlette.testclient import TestClient

from server.api import asr
from server.services.asr_postprocess import load_phrase_pairs


def make_wav(seconds: float = 1.0, rate: int = 16000) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(rate)
        wf.writeframes(b"\x00\x00" * int(rate * seconds))
    return buf.getvalue()


class _Response:
    def __init__(self, status_code: int = 200, payload=None):
        self.status_code = status_code
        self._payload = payload

    # probe_asr_backend() branches on these before calling json().
    @property
    def content(self):
        return b"{}" if self._payload is not None else b""

    @property
    def text(self):
        return "" if self._payload is None else str(self._payload)

    def json(self):
        if self._payload is None:
            raise ValueError("no JSON body")
        return self._payload


@pytest.fixture()
def backend(monkeypatch):
    """Replace the httpx module inside asr.py and capture what it sends."""
    calls = []
    state = {"response": _Response(payload={"text": "Xin chào."}), "error": None}

    class Client:
        def __init__(self, **kwargs):
            self.kwargs = kwargs

        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            return False

        async def post(self, url, files=None, data=None, headers=None):
            calls.append(
                {
                    "verb": "POST",
                    "url": url,
                    "files": files,
                    "data": data,
                    "headers": headers,
                    "timeout": self.kwargs.get("timeout"),
                }
            )
            if state["error"] is not None:
                raise state["error"]
            return state["response"]

        async def get(self, url, headers=None):
            calls.append({"verb": "GET", "url": url, "headers": headers})
            if state["error"] is not None:
                raise state["error"]
            return state["response"]

    class FakeHttpx:
        AsyncClient = Client
        # Keep the real classes so asr.py's except clauses still match.
        TimeoutException = httpx.TimeoutException
        RequestError = httpx.RequestError

    monkeypatch.setattr(asr, "httpx", FakeHttpx)
    monkeypatch.setattr(asr, "ASR_PROVIDER", "nemotron")
    monkeypatch.setattr(asr, "ASR_BACKEND_URL", "http://127.0.0.1:8002")
    monkeypatch.setattr(asr, "NEMOTRON_API_KEY", "")
    monkeypatch.setattr(asr, "NEMOTRON_LANGUAGE", "vi-VN")
    monkeypatch.setattr(asr, "NEMOTRON_MODEL", "nemotron-3.5-asr-streaming-0.6b")
    monkeypatch.setattr(asr, "NEMOTRON_TIMEOUT_SECONDS", 30.0)

    app = FastAPI()
    app.include_router(asr.router)
    client = TestClient(app)
    return SimpleNamespace(
        calls=calls,
        state=state,
        http=client,
        post=lambda **kwargs: client.post("/asr/ctc/chunk", **kwargs),
    )


def upload(backend, audio=None):
    # `audio or make_wav()` would treat b"" as absent, so check explicitly.
    if audio is None:
        audio = make_wav()
    return backend.post(
        files={"audio": ("utterance.wav", audio, "audio/wav")},
        data={"session_id": "ses_test"},
    )


def test_uploads_to_transcriptions_endpoint(backend):
    resp = upload(backend)
    assert resp.status_code == 200, resp.text

    call = backend.calls[0]
    assert call["url"] == "http://127.0.0.1:8002/v1/audio/transcriptions"
    assert call["data"] == {
        "model": "nemotron-3.5-asr-streaming-0.6b",
        "language": "vi-VN",
    }
    assert call["timeout"] == 30.0
    # Upload is forwarded byte-for-byte: no decode/re-trim/re-encode.
    assert call["files"]["file"][1] == make_wav()
    assert call["headers"] == {}


def test_response_keeps_contract_android_needs(backend):
    body = upload(backend).json()

    # ParakeetSttService reads exactly these; anything missing is a silent robot.
    for key in ("success", "transcript", "duration_seconds", "processing_time_seconds"):
        assert key in body, key
    assert body["success"] is True
    assert body["transcript"] == "Xin chào."
    assert body["provider"] == "nemotron"
    assert body["session_id"] == "ses_test"
    # serve returns no duration, so it must be derived from the WAV header.
    assert body["duration_seconds"] == 1.0
    assert body["real_time_factor"] == pytest.approx(
        body["processing_time_seconds"] / 1.0, abs=0.5
    )


@pytest.mark.parametrize("text", ["", "   ", "\n\t "])
def test_silence_is_success_with_empty_transcript(backend, text):
    """
    The mic is open continuously, so "no speech" must be a 200 with an empty
    transcript. Android drops it before chat (hasSpeechText gate); a 5xx here
    would instead surface as an error prompt to the visitor.
    """
    backend.state["response"] = _Response(payload={"text": text})
    resp = upload(backend)

    assert resp.status_code == 200
    body = resp.json()
    assert body["success"] is True
    assert body["transcript"] == ""
    assert body["transcript_raw"] == ""
    assert body["message"] == "No speech detected"
    # No dictionary noise on an empty answer.
    assert "dict" not in body


def test_dictionary_runs_on_nemotron_output(backend):
    """The benchmark scored raw model text; the shipped path also applies dict."""
    if not load_phrase_pairs():
        pytest.skip("ASR dictionary not available in this checkout")

    backend.state["response"] = _Response(payload={"text": "robotuth là trợ lý ảo"})
    body = upload(backend).json()

    assert body["transcript_raw"] == "robotuth là trợ lý ảo"
    assert body["transcript"] != body["transcript_raw"]
    assert "RobotUTH" in body["transcript"]
    assert body["dict"]["applied"]


def test_malformed_json_is_bad_gateway(backend):
    backend.state["response"] = _Response(status_code=200, payload=None)
    resp = upload(backend)
    assert resp.status_code == 502


def test_upstream_http_error_is_bad_gateway(backend):
    backend.state["response"] = _Response(status_code=500, payload={"error": "boom"})
    resp = upload(backend)
    assert resp.status_code == 502
    assert "500" in resp.json()["detail"]


def test_timeout_is_gateway_timeout(backend):
    backend.state["error"] = httpx.TimeoutException("slow")
    resp = upload(backend)
    assert resp.status_code == 504


def test_connection_error_is_unavailable(backend):
    backend.state["error"] = httpx.ConnectError("refused")
    resp = upload(backend)
    assert resp.status_code == 503


def test_empty_upload_is_rejected(backend):
    resp = upload(backend, audio=b"")
    assert resp.status_code == 400


def test_missing_backend_url_is_unavailable(backend, monkeypatch):
    monkeypatch.setattr(asr, "ASR_BACKEND_URL", "")
    resp = upload(backend)
    assert resp.status_code == 503


def test_bearer_sent_only_when_key_configured(backend, monkeypatch):
    monkeypatch.setattr(asr, "NEMOTRON_API_KEY", "secret-token")
    upload(backend)
    assert backend.calls[0]["headers"] == {"Authorization": "Bearer secret-token"}


def test_health_probe_reports_nemotron_backend_up(backend):
    """
    nemo-speech /health is {"status":"ok","version":"0.1.0"} — no model_loaded,
    which the old Parakeet probe leaned on. If this regresses the admin pill
    shows ASR down while transcription is actually working.
    """
    backend.state["response"] = _Response(status_code=200, payload={"status": "ok"})
    resp = backend.http.get("/asr/health")

    assert resp.status_code == 200
    body = resp.json()
    assert body["available"] is True
    assert body["provider"] == "nemotron"
    assert backend.calls[0]["url"] == "http://127.0.0.1:8002/health"


def test_health_probe_reports_down_on_unreachable(backend):
    backend.state["error"] = httpx.ConnectError("refused")
    body = backend.http.get("/asr/health").json()
    assert body["available"] is False
