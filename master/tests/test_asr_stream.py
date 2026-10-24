"""Streaming ASR relay tests: /ws/asr/{session_id}.

The batch provider call is swapped for a scripted fake, so these tests exercise
the relay's own behavior — auth, segmentation (the ported AudioPipeline VAD),
ordering, stop/auto turn-end, retry/coalesce, empty finals — without an ASR
backend. The untouched HTTP-path tests (test_asr_nemotron.py) still cover the
provider functions themselves.
"""

import inspect
import io
import json
import sys
import time
import wave as wave_mod
from pathlib import Path

import numpy as np
import pytest
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from server.api import asr_stream  # noqa: E402

SR = 16000
TOKEN = "test-token"


def speech_frame(ms: float = 100.0, amp: int = 1500) -> bytes:
    """440 Hz tone: peak >> 500 -> speech in the AudioPipeline gate."""
    n = int(SR * ms / 1000)
    t = np.arange(n) / SR
    return (amp * np.sin(2 * np.pi * 440 * t)).astype("<i2").tobytes()


def silence_frame(ms: float = 100.0) -> bytes:
    return b"\x00\x00" * int(SR * ms / 1000)


def wav_pcm(data: bytes) -> bytes:
    with wave_mod.open(io.BytesIO(data), "rb") as wf:
        return wf.readframes(wf.getnframes())


def _completed(text: str) -> dict:
    return {
        "success": True,
        "transcript": text,
        "transcript_raw": text,
        "duration_seconds": 1.5,
        "processing_time_seconds": 0.3,
        "session_id": None,
        "provider": "parakeet",
    }


def make_client(monkeypatch, decode_script=None, *, provider="parakeet"):
    """Patch tokens/slot/provider and install a scripted fake decode.

    decode_script(session_id) may return a dict or a coroutine (raising
    HTTPException works in both forms). Returns the `recorded` dict accumulating
    ('calls': list of (session_id, wav_bytes)).
    """
    monkeypatch.setattr(asr_stream, "ROBOT_API_TOKENS", (TOKEN,))
    monkeypatch.setattr(asr_stream, "ASR_PROVIDER", provider)
    asr_stream._active_sessions = 0
    recorded: dict = {"calls": []}

    async def _fake_transcribe(audio: bytes, session_id, *, filename: str = "segment.wav"):
        recorded["calls"].append((session_id, audio))
        if decode_script is None:
            return _completed(f"seg{len(recorded['calls'])}")
        out = decode_script(session_id)
        if inspect.isawaitable(out):
            out = await out
        return out

    monkeypatch.setattr(asr_stream, "transcribe_audio_bytes", _fake_transcribe)
    return recorded


def app() -> FastAPI:
    a = FastAPI()
    a.include_router(asr_stream.router)
    return a


def connect(tc: TestClient, extra: str = "", token: str = TOKEN):
    return tc.websocket_connect(f"/ws/asr/ses_test?token={token}{extra}")


# ---------------------------------------------------------------- auth


def test_missing_token_rejected_before_accept(monkeypatch):
    monkeypatch.setattr(asr_stream, "ROBOT_API_TOKENS", (TOKEN,))
    asr_stream._active_sessions = 0
    with TestClient(app()) as tc:
        with pytest.raises(WebSocketDisconnect) as exc:
            with tc.websocket_connect("/ws/asr/ses_x"):
                pass
        assert exc.value.code == 4401


def test_wrong_token_rejected(monkeypatch):
    monkeypatch.setattr(asr_stream, "ROBOT_API_TOKENS", (TOKEN,))
    asr_stream._active_sessions = 0
    with TestClient(app()) as tc:
        with pytest.raises(WebSocketDisconnect) as exc:
            with connect(tc, token="nope"):
                pass
        assert exc.value.code == 4401


# ---------------------------------------------------------------- happy path


def test_hello_then_segmented_partials_and_final_in_order(monkeypatch):
    results = ["Chào buổi sáng.", "Tôi cần chỉ đường."]
    counter = {"n": 0}

    async def decode(session_id):
        text = results[min(counter["n"], len(results) - 1)]
        counter["n"] += 1
        return _completed(text)

    recorded = make_client(monkeypatch, decode)
    with TestClient(app()) as tc:
        with connect(tc) as ws:
            hello = ws.receive_json()
            assert hello["type"] == "hello"
            assert hello["provider"] == "parakeet"

            # Segment 1: 1.4 s speech, 1.2 s silence (>=900 ms closes it)
            for _ in range(14):
                ws.send_bytes(speech_frame())
            for _ in range(12):
                ws.send_bytes(silence_frame())
            p1 = ws.receive_json()
            assert p1["type"] == "partial" and p1["index"] == 0
            assert p1["text"] == "Chào buổi sáng."

            # Segment 2: fresh buffer, ended by the stop control
            for _ in range(10):
                ws.send_bytes(speech_frame())
            ws.send_text(json.dumps({"type": "stop"}))
            p2 = ws.receive_json()
            assert p2["type"] == "partial" and p2["index"] == 1
            final = ws.receive_json()
            assert final["type"] == "final"
            assert final["success"] is True
            assert final["text"] == "Chào buổi sáng. Tôi cần chỉ đường."
            assert final["segments"] == 2
            assert final["dropped_segments"] == []
            assert final["provider"] == "parakeet"

    calls = recorded["calls"]
    assert [sid.rsplit("#", 1)[-1] for sid, _ in calls] == ["seg0", "seg1"]
    wav1, wav2 = calls[0][1], calls[1][1]
    assert wav1[:4] == b"RIFF" and wav2[:4] == b"RIFF"
    FRAME = 3200  # one 100 ms PCM16 frame
    pcm1, pcm2 = wav_pcm(wav1), wav_pcm(wav2)
    # Exactly 14 speech + 9 trailing-silence frames: the onset-duplication
    # regression (speech frame counted twice via pre-roll) would yield 24, and
    # it cost +5.7 WER points on the golden set — see AGENTS.md streaming note.
    assert len(pcm1) == 23 * FRAME
    assert pcm1[-9 * FRAME:] == b"\x00" * (9 * FRAME)
    assert any(pcm1[: 14 * FRAME])
    # pre-roll carries segment 1's last 3 silence frames into segment 2:
    # 3 silence + 10 speech; buffers never overlap beyond the pre-roll.
    assert len(pcm2) == 13 * FRAME
    assert pcm2[: 3 * FRAME] == b"\x00" * (3 * FRAME)
    assert any(pcm2[3 * FRAME:])


def test_speech_flushed_on_stop_even_without_endpoint_silence(monkeypatch):
    async def decode(session_id):
        return _completed("Ngắn.")

    recorded = make_client(monkeypatch, decode)
    with TestClient(app()) as tc:
        with connect(tc) as ws:
            ws.receive_json()  # hello
            for _ in range(5):  # 500 ms speech, zero trailing silence
                ws.send_bytes(speech_frame())
            ws.send_text(json.dumps({"type": "stop"}))
            msgs = [ws.receive_json(), ws.receive_json()]
            kinds = [m["type"] for m in msgs]
            assert set(kinds) == {"partial", "final"}
            assert kinds.index("partial") < kinds.index("final")
            final = msgs[kinds.index("final")]
            assert final["text"] == "Ngắn."
    assert len(recorded["calls"]) == 1


def test_sub_minimum_tail_discarded_and_final_keeps_closed_segments(monkeypatch):
    async def decode(session_id):
        return _completed("A")

    recorded = make_client(monkeypatch, decode)
    with TestClient(app()) as tc:
        with connect(tc) as ws:
            ws.receive_json()
            for _ in range(14):
                ws.send_bytes(speech_frame())
            for _ in range(12):
                ws.send_bytes(silence_frame())
            assert ws.receive_json()["type"] == "partial"
            ws.send_bytes(speech_frame(200.0))  # 200 ms tail < 400 ms floor
            ws.send_text(json.dumps({"type": "stop"}))
            final = ws.receive_json()
            assert final["type"] == "final"
            assert final["text"] == "A"
            assert final["segments"] == 1
    assert len(recorded["calls"]) == 1  # the short tail was never decoded


def test_pure_silence_never_decodes_empty_final(monkeypatch):
    async def decode(session_id):  # pragma: no cover - must not run
        raise AssertionError("silence must not reach the decoder")

    recorded = make_client(monkeypatch, decode)
    with TestClient(app()) as tc:
        with connect(tc) as ws:
            ws.receive_json()
            for _ in range(25):
                ws.send_bytes(silence_frame())
            ws.send_text(json.dumps({"type": "stop"}))
            final = ws.receive_json()
            assert final["type"] == "final"
            assert final["text"] == ""
            assert final["message"] == "No speech detected"
    assert recorded["calls"] == []


# ---------------------------------------------------------------- reliability


def test_transient_503_retried_then_partial_delivered(monkeypatch):
    state = {"n": 0}

    async def decode(session_id):
        state["n"] += 1
        if state["n"] == 1:
            raise HTTPException(status_code=503, detail="ASR busy")
        return _completed("Hồi phục.")

    monkeypatch.setattr(asr_stream, "RETRY_DELAY_S", 0.01)
    recorded = make_client(monkeypatch, decode)
    with TestClient(app()) as tc:
        with connect(tc) as ws:
            ws.receive_json()
            for _ in range(12):
                ws.send_bytes(speech_frame())
            for _ in range(12):
                ws.send_bytes(silence_frame())
            msg = ws.receive_json()
            assert msg["type"] == "partial", msg
            assert msg["text"] == "Hồi phục."
    assert state["n"] == 2
    assert len(recorded["calls"]) == 2
    # retry re-sends the identical audio, not a truncated one
    assert recorded["calls"][0][1] == recorded["calls"][1][1]


def test_persistent_failure_drops_segment_not_session(monkeypatch):
    async def decode(session_id):
        raise HTTPException(status_code=503, detail="ASR busy")

    monkeypatch.setattr(asr_stream, "RETRY_DELAY_S", 0.01)
    make_client(monkeypatch, decode)
    with TestClient(app()) as tc:
        with connect(tc) as ws:
            ws.receive_json()
            for _ in range(12):
                ws.send_bytes(speech_frame())
            for _ in range(12):
                ws.send_bytes(silence_frame())
            err = ws.receive_json()
            assert err["type"] == "error" and err["segment"] == 0
            # stream kept serving: another turn still completes (empty, dropped)
            ws.send_bytes(speech_frame())
            ws.send_text(json.dumps({"type": "stop"}))
            final = ws.receive_json()
            assert final["type"] == "final"
            assert final["dropped_segments"] == [0]
            assert final["text"] == ""


# ---------------------------------------------------------------- auto turn


def test_auto_turn_end_emits_final_without_stop(monkeypatch):
    async def decode(session_id):
        return _completed("Tự động.")

    make_client(monkeypatch, decode)
    with TestClient(app()) as tc:
        with connect(tc, extra="&turn_end=auto&turn_silence_ms=300") as ws:
            hello = ws.receive_json()
            assert hello["turn_end"] == "auto"
            for _ in range(12):
                ws.send_bytes(speech_frame())
            for _ in range(12):
                ws.send_bytes(silence_frame())
            assert ws.receive_json()["type"] == "partial"
            # no further client interaction — the turn closes on the clock
            final = ws.receive_json()
            assert final["type"] == "final"
            assert final["text"] == "Tự động."


# ---------------------------------------------------------------- session caps


def test_third_connection_rejected_when_slots_full(monkeypatch):
    make_client(monkeypatch)  # default scripted decode; never used here
    monkeypatch.setattr(asr_stream, "ASR_WS_MAX_SESSIONS", 1)
    with TestClient(app()) as tc:
        with connect(tc) as holder:
            assert holder.receive_json()["type"] == "hello"
            with pytest.raises(WebSocketDisconnect) as exc:
                with connect(tc) as second:
                    second.receive_json()
            assert exc.value.code == 1013
    assert asr_stream._active_sessions == 0


def test_idle_timeout_closes_session(monkeypatch):
    monkeypatch.setattr(asr_stream, "IDLE_CLOSE_S", 0.2)
    make_client(monkeypatch)
    with TestClient(app()) as tc:
        with connect(tc) as ws:
            assert ws.receive_json()["type"] == "hello"
            t0 = time.monotonic()
            with pytest.raises(WebSocketDisconnect):
                while True:
                    ws.receive_json()
            assert time.monotonic() - t0 < 5
    assert asr_stream._active_sessions == 0
