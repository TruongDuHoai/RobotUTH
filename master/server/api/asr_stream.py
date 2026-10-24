"""
Streaming ASR relay: ``/ws/asr/{session_id}``.

The client streams raw 16 kHz mono PCM16 in ~100 ms binary WebSocket frames.
The server runs the *same* energy endpointer the Android client used to run
(constants ported 1:1 from ``AudioPipeline.kt``): when a spoken segment ends on
``segment_silence_ms`` of trailing silence, the segment is wrapped as WAV and
decoded through ``transcribe_audio_bytes`` — i.e. through the exact batch
provider that the HTTP route uses. Every segment therefore gets full
bidirectional CTC decoding: **streaming capture, batch accuracy, still exactly
one model running.** (Word-level causal streaming would mean the Nemotron
``/v1/realtime`` relay — deliberately deferred: measured −8 WER points.)

Protocol (JSON text frames, plus raw binary from the client):

  client -> server
    binary            PCM16 mono 16k frames
    {"type":"stop"}   end the current turn (push-to-talk)

  server -> client
    {"type":"hello","provider":…,"segment_silence_ms":…}
    {"type":"partial","index":i,"text":…,"transcript_raw":…,"duration_s":…}
    {"type":"final","success":true,"text":…,"transcript_raw":…,
                   "segments":n,"duration_s":…,"dropped_segments":[…],
                   "session_id":…,"provider":…}
    {"type":"error","detail":…}

Auth follows the robot-link rule: ``BaseHTTPMiddleware`` never sees WebSockets,
so the token (``?token=`` or ``Authorization: Bearer``) is checked **inside the
handler** before ``accept()``; a bad token gets ``4401``.

A turn's segments may overlap the next turn's speech — the decoder runs as
independent tasks, so a slow backend never stalls the mic.
"""

from __future__ import annotations

import asyncio
import io
import json
import logging
import time
import wave
from typing import Any, Dict, Optional, Set

import numpy as np
from fastapi import APIRouter, HTTPException, WebSocket, WebSocketDisconnect

from ..config import (
    ASR_PROVIDER,
    ASR_WS_MAX_SESSION_S,
    ASR_WS_MAX_SESSIONS,
    ASR_WS_MIN_SPEECH_MS,
    ASR_WS_PRE_ROLL_MS,
    ASR_WS_SEGMENT_SILENCE_MS,
    ASR_WS_TURN_END,
    ASR_WS_TURN_SILENCE_MS,
    ROBOT_API_TOKENS,
)
from ..middleware.robot_api_auth import is_robot_request_authorized
from .asr import transcribe_audio_bytes

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/ws", tags=["asr-stream"])

SAMPLE_RATE = 16000
BYTES_PER_SAMPLE = 2
# 1:1 with AudioPipeline.kt hasSpeechContent(): 20 ms frames, RMS >= 80,
# peak >= 500 short-circuits, speech-frame ratio >= 0.08 gate.
VAD_FRAME_SAMPLES = SAMPLE_RATE * 20 // 1000
VAD_PEAK_THRESHOLD = 500
VAD_RMS_THRESHOLD = 80.0
VAD_SPEECH_FRAME_RATIO = 0.08
# Same floor Android uses before sending an utterance at all.
MIN_TURN_AUDIO_MS = 400.0
# Host asr_gpu_server rejects > ASR_MAX_DURATION_S (30 s) — force-close a
# segment a bit earlier and let the *next* decode cover the boundary silence.
MAX_SEGMENT_S = 29.0
DECODE_TIMEOUT_S = 60.0
DECODE_RETRIES = 2
RETRY_DELAY_S = 0.4
POLL_TIMEOUT_S = 0.5  # receive poll: drives auto-turn + session caps
IDLE_CLOSE_S = 30.0  # no frames at all for this long -> close

_active_sessions = 0
_sessions_lock = asyncio.Lock()


def _authorized(websocket: WebSocket) -> bool:
    token = websocket.query_params.get("token")
    if token:
        authorization = f"Bearer {token}"
    else:
        authorization = websocket.headers.get("authorization")
    return is_robot_request_authorized(authorization, ROBOT_API_TOKENS)


def _has_speech(frame: bytes) -> bool:
    """Python mirror of AudioPipeline.hasSpeechContent (raw PCM16)."""
    usable = len(frame) - (len(frame) % BYTES_PER_SAMPLE)
    if usable == 0:
        return False
    samples = np.frombuffer(frame[:usable], dtype="<i2").astype(np.int32)
    peak = int(np.max(np.abs(samples)))
    if peak >= VAD_PEAK_THRESHOLD:
        return True
    n_frames = samples.size // VAD_FRAME_SAMPLES
    if n_frames == 0:
        return peak > 100
    frames = samples[: n_frames * VAD_FRAME_SAMPLES].reshape(n_frames, -1).astype(np.float64)
    rms = np.sqrt(np.mean(frames * frames, axis=1))
    return float(np.count_nonzero(rms >= VAD_RMS_THRESHOLD)) / n_frames >= VAD_SPEECH_FRAME_RATIO


def _dc_remove(pcm: bytes) -> bytes:
    """Whole-segment mean subtraction (the batch path's per-utterance step,
    now per segment — same operation, segment granularity)."""
    if not pcm:
        return pcm
    samples = np.frombuffer(pcm, dtype="<i2")
    if samples.size == 0:
        return pcm
    centered = samples.astype(np.float64) - float(samples.mean())
    return np.clip(centered, -32768, 32767).astype("<i2").tobytes()


def _pcm_to_wav(pcm: bytes) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(BYTES_PER_SAMPLE)
        wf.setframerate(SAMPLE_RATE)
        wf.writeframes(pcm)
    return buf.getvalue()


def _query_float(websocket: WebSocket, name: str, default: float) -> float:
    raw = websocket.query_params.get(name)
    if raw is None:
        return default
    try:
        return float(raw)
    except ValueError:
        return default


class _Turn:
    """Per-turn bookkeeping for a session."""

    __slots__ = ("t0", "next_index", "texts", "raws", "durations", "dropped", "pending")

    def __init__(self) -> None:
        self.t0 = time.monotonic()
        self.next_index = 0
        self.texts: Dict[int, str] = {}
        self.raws: Dict[int, str] = {}
        self.durations: Dict[int, float] = {}
        self.dropped: list[int] = []
        self.pending: Set[asyncio.Task] = set()


class _Session:
    """Endpointer state for one WS connection (mirrors the Android loop)."""

    def __init__(self, session_id: str, segment_silence_ms: float, min_speech_ms: float,
                 pre_roll_bytes: int, turn_end: str, turn_silence_ms: float) -> None:
        self.session_id = session_id
        self.segment_silence_ms = segment_silence_ms
        self.min_speech_ms = min_speech_ms
        self.pre_roll_bytes = pre_roll_bytes
        self.turn_end = turn_end
        self.turn_silence_ms = turn_silence_ms

        self.pre_roll = bytearray()
        self.segment: Optional[bytearray] = None
        self.speech_ms = 0.0
        self.trailing_ms = 0.0
        self.turn = _Turn()
        self.last_segment_closed_monotonic: Optional[float] = None
        self.auto_turn_fired = False

    @property
    def recording(self) -> bool:
        return self.segment is not None


async def _decode_segment(websocket: WebSocket, sess: _Session, turn: _Turn, index: int, pcm: bytes) -> None:
    """Run one closed segment through the batch provider, then ``partial`` it out.

    Retries once on transient 502/503/504 (coalesce against the backend's
    bounded queue); a still-failing segment is reported as a dropped index but
    never aborts the stream — a missing clause beats a dead session.
    """
    wav = _pcm_to_wav(_dc_remove(pcm))
    result: Optional[Dict[str, Any]] = None
    detail = ""
    for attempt in range(1, DECODE_RETRIES + 1):
        try:
            result = await asyncio.wait_for(
                transcribe_audio_bytes(wav, f"{sess.session_id}#seg{index}"),
                timeout=DECODE_TIMEOUT_S,
            )
            break
        except HTTPException as exc:
            detail = f"{exc.status_code}: {exc.detail}"
            if exc.status_code not in (502, 503, 504) or attempt == DECODE_RETRIES:
                break
            logger.warning("ASR stream session=%s seg=%d attempt %d failed: %s",
                           sess.session_id, index, attempt, detail)
            await asyncio.sleep(RETRY_DELAY_S)
        except asyncio.TimeoutError:
            detail = "decode timeout"
            if attempt == DECODE_RETRIES:
                break
            await asyncio.sleep(RETRY_DELAY_S)
        except Exception as exc:  # never let a decoder task die silently
            detail = f"{type(exc).__name__}: {exc}"
            break

    if result is None:
        turn.dropped.append(index)
        logger.error("ASR stream session=%s dropped seg=%d (%s)", sess.session_id, index, detail)
        await _safe_send(websocket, {"type": "error", "detail": detail, "segment": index})
        return

    transcript = result.get("transcript") or ""
    turn.texts[index] = transcript
    turn.raws[index] = result.get("transcript_raw") or ""
    turn.durations[index] = float(result.get("duration_seconds") or 0.0)
    if transcript.strip():
        await _safe_send(websocket, {
            "type": "partial",
            "index": index,
            "text": transcript,
            "transcript_raw": turn.raws[index],
            "duration_s": turn.durations[index],
        })


async def _safe_send(websocket: WebSocket, payload: dict) -> bool:
    try:
        await websocket.send_json(payload)
        return True
    except Exception:
        return False


def _close_segment(sess: _Session, websocket: WebSocket) -> None:
    """Hand the buffer to a decoder task; mic capture continues immediately."""
    assert sess.segment is not None
    pcm = bytes(sess.segment)
    index = sess.turn.next_index
    sess.turn.next_index += 1
    sess.segment = None
    sess.speech_ms = 0.0
    sess.trailing_ms = 0.0
    sess.last_segment_closed_monotonic = time.monotonic()
    sess.auto_turn_fired = False
    task = asyncio.create_task(_decode_segment(websocket, sess, sess.turn, index, pcm))
    sess.turn.pending.add(task)
    task.add_done_callback(sess.turn.pending.discard)


async def _finish_turn(websocket: WebSocket, sess: _Session) -> None:
    """Drain this turn's decoders, concat segments in order, emit ``final``."""
    if sess.recording:
        if sess.speech_ms >= MIN_TURN_AUDIO_MS:
            _close_segment(sess, websocket)
        else:
            # Too short to be a real utterance — discard exactly like the
            # Android MIN_UTTERANCE gate does.
            sess.segment = None
            sess.speech_ms = 0.0
            sess.trailing_ms = 0.0

    turn = sess.turn
    if turn.pending:
        await asyncio.gather(*list(turn.pending), return_exceptions=True)

    indices = sorted(turn.texts)
    text = " ".join(turn.texts[i].strip() for i in indices).strip()
    raw = " ".join(turn.raws[i].strip() for i in indices).strip()
    payload: Dict[str, Any] = {
        "type": "final",
        "success": True,
        "text": text,
        "transcript_raw": raw,
        "segments": len(indices),
        "duration_s": round(sum(turn.durations.values()), 2),
        "dropped_segments": sorted(turn.dropped),
        "provider": ASR_PROVIDER,
        "session_id": sess.session_id,
        "processing_time_seconds": round(time.monotonic() - turn.t0, 3),
    }
    if not text:
        payload["message"] = "No speech detected"
        # An empty final mirrors the batch empty answer, never an error.
    logger.info(
        "ASR stream session=%s turn segs=%d dropped=%d wall=%.2fs text='%s'",
        sess.session_id, len(indices), len(turn.dropped),
        time.monotonic() - turn.t0, text[:120],
    )
    await _safe_send(websocket, payload)

    sess.turn = _Turn()
    sess.pre_roll.clear()


async def _handle_frame(websocket: WebSocket, sess: _Session, frame: bytes) -> None:
    frame_ms = (len(frame) // BYTES_PER_SAMPLE) * 1000.0 / SAMPLE_RATE
    speech = _has_speech(frame)

    if sess.segment is None:
        # Waiting for onset: keep only a pre-roll so a sentence never loses
        # its first syllable to the segment-silence gap before it. The current
        # frame is already inside pre_roll — starting the segment from it must
        # NOT append the frame again (a doubled 100 ms head stutters the first
        # syllable: measured +5 WER points on the golden set).
        sess.pre_roll.extend(frame)
        if len(sess.pre_roll) > sess.pre_roll_bytes:
            del sess.pre_roll[: len(sess.pre_roll) - sess.pre_roll_bytes]
        if speech:
            sess.segment = bytearray(sess.pre_roll)
            sess.pre_roll.clear()
            sess.speech_ms = frame_ms
            sess.trailing_ms = 0.0
            sess.turn.t0 = time.monotonic()
        return

    sess.segment.extend(frame)
    if speech:
        sess.speech_ms += frame_ms
        sess.trailing_ms = 0.0
    else:
        sess.trailing_ms += frame_ms

    if sess.speech_ms >= sess.min_speech_ms and sess.trailing_ms >= sess.segment_silence_ms:
        _close_segment(sess, websocket)
        return

    if sess.segment is not None and len(sess.segment) >= MAX_SEGMENT_S * SAMPLE_RATE * BYTES_PER_SAMPLE:
        logger.info("ASR stream session=%s segment hit %.0fs cap — force close",
                    sess.session_id, MAX_SEGMENT_S)
        _close_segment(sess, websocket)


def _auto_turn_due(sess: _Session) -> bool:
    if sess.turn_end != "auto" or sess.auto_turn_fired:
        return False
    if sess.recording or sess.turn.pending:
        return False
    if not sess.turn.texts and not sess.turn.dropped:
        return False  # nothing said this turn
    if sess.last_segment_closed_monotonic is None:
        return False
    return (time.monotonic() - sess.last_segment_closed_monotonic) * 1000.0 >= sess.turn_silence_ms


@router.websocket("/asr/{session_id}")
async def asr_stream(websocket: WebSocket, session_id: str) -> None:
    if not _authorized(websocket):
        await websocket.close(code=4401)
        return

    global _active_sessions
    async with _sessions_lock:
        if _active_sessions >= ASR_WS_MAX_SESSIONS:
            await websocket.close(code=1013)
            return
        _active_sessions += 1
    try:
        await websocket.accept()
        await _run_stream(websocket, session_id)
    except WebSocketDisconnect:
        pass
    except Exception:
        logger.exception("ASR stream session=%s crashed", session_id)
        try:
            await websocket.close(code=1011)
        except Exception:
            pass
    finally:
        async with _sessions_lock:
            _active_sessions -= 1


async def _run_stream(websocket: WebSocket, session_id: str) -> None:
    segment_silence = max(200.0, _query_float(websocket, "segment_silence_ms", ASR_WS_SEGMENT_SILENCE_MS))
    min_speech = max(0.0, _query_float(websocket, "min_speech_ms", ASR_WS_MIN_SPEECH_MS))
    pre_roll_ms = max(0.0, _query_float(websocket, "pre_roll_ms", ASR_WS_PRE_ROLL_MS))
    turn_end_param = (websocket.query_params.get("turn_end") or ASR_WS_TURN_END).strip().lower()
    turn_silence = max(0.0, _query_float(websocket, "turn_silence_ms", ASR_WS_TURN_SILENCE_MS))
    max_session_s = _query_float(websocket, "max_session_s", ASR_WS_MAX_SESSION_S)

    sess = _Session(
        session_id=session_id,
        segment_silence_ms=segment_silence,
        min_speech_ms=min_speech,
        pre_roll_bytes=int(pre_roll_ms * SAMPLE_RATE * BYTES_PER_SAMPLE / 1000),
        turn_end=turn_end_param if turn_end_param in ("manual", "auto") else "manual",
        turn_silence_ms=turn_silence,
    )

    await _safe_send(websocket, {
        "type": "hello",
        "provider": ASR_PROVIDER,
        "segment_silence_ms": sess.segment_silence_ms,
        "turn_end": sess.turn_end,
        "max_segment_s": MAX_SEGMENT_S,
    })

    session_started = time.monotonic()
    last_frame_monotonic = session_started

    # `websocket.receive()` must never be cancelled: under the TestClient's
    # blocking portal (and to stay safe across ASGI implementations generally)
    # cancellation deadlocks the app task. A single pump task owns receive()
    # and feeds an asyncio.Queue; the polling timeout applies to queue.get().
    inbox: asyncio.Queue = asyncio.Queue()

    async def _pump() -> None:
        try:
            while True:
                message = await websocket.receive()
                await inbox.put(message)
                if message.get("type") == "websocket.disconnect":
                    return
        except WebSocketDisconnect:
            await inbox.put({"type": "websocket.disconnect"})
        except Exception:  # transport died mid-stream — treat as disconnect
            await inbox.put({"type": "websocket.disconnect"})

    pump = asyncio.create_task(_pump())
    try:
        while True:
            try:
                message = await asyncio.wait_for(inbox.get(), timeout=POLL_TIMEOUT_S)
            except asyncio.TimeoutError:
                message = None
            if message is not None:
                if message["type"] == "websocket.disconnect":
                    break
                if message.get("bytes") is not None:
                    await _handle_frame(websocket, sess, message["bytes"])
                    last_frame_monotonic = time.monotonic()
                elif message.get("text") is not None:
                    try:
                        control = json.loads(message["text"])
                    except ValueError:
                        control = {}
                    if control.get("type") == "stop":
                        await _finish_turn(websocket, sess)
                        last_frame_monotonic = time.monotonic()

            now = time.monotonic()
            if _auto_turn_due(sess):
                sess.auto_turn_fired = True
                await _finish_turn(websocket, sess)
            if now - session_started > max_session_s:
                if sess.turn.texts or sess.recording:
                    await _finish_turn(websocket, sess)
                await _safe_send(websocket, {"type": "error", "detail": "session limit reached"})
                break
            if now - last_frame_monotonic > IDLE_CLOSE_S:
                if sess.turn.texts or sess.recording or sess.turn.pending:
                    await _finish_turn(websocket, sess)
                break
    finally:
        # Close wakes the pump (receive returns disconnect); give it a grace
        # window before the last-resort cancel.
        try:
            await websocket.close()
        except Exception:
            pass
        try:
            await asyncio.wait_for(asyncio.shield(pump), timeout=2.0)
        except Exception:
            pump.cancel()
