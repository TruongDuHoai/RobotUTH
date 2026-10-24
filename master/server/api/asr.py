"""
ASR API - Parakeet CTC chunk endpoint for Android client.

When ASR_BACKEND_URL is set (Docker mode), requests are proxied to the native GPU
ASR service on the host. Otherwise uses the in-process NeMo service.

Also provides optional LLM transcript correction (POST /api/asr/correct, /correct).
"""

import json
import logging
import os
import time
from typing import Any, Dict, Optional

import httpx
import numpy as np
from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from ..config import (
    ASR_BACKEND_URL,
    ASR_DICT_ENABLED,
    ASR_LLM_CORRECT,
    ASR_LLM_CORRECT_MAX_TOKENS,
    ASR_LLM_CORRECT_TIMEOUT_S,
    ASR_PROVIDER,
    ASR_TRIM_PAD_MS,
    ASR_TRIM_RMS,
    ASR_TRIM_SILENCE,
    DEEPGRAM_API_KEY,
    DEEPGRAM_API_URL,
    DEEPGRAM_KEYTERMS,
    DEEPGRAM_LANGUAGE,
    DEEPGRAM_MODEL,
    DEEPGRAM_SMART_FORMAT,
    DEEPGRAM_TIMEOUT_SECONDS,
    NEMOTRON_API_KEY,
    NEMOTRON_API_URL,
    NEMOTRON_LANGUAGE,
    NEMOTRON_MODEL,
    NEMOTRON_TIMEOUT_SECONDS,
)
from ..services.asr_postprocess import postprocess_transcript

logger = logging.getLogger(__name__)

router = APIRouter(tags=["asr"])

SAMPLE_RATE = 16000
# Align with host asr_gpu_server (whole-utterance ~20s + headroom)
MAX_AUDIO_DURATION_S = 30.0
MIN_AUDIO_DURATION_S = 0.1
ASR_TRIM_FRAME_MS = float(os.getenv("ASR_TRIM_FRAME_MS", "20"))


class CorrectRequest(BaseModel):
    text: str = Field(..., min_length=0)
    # Android sends "id"; also accept utterance_id
    id: Optional[int] = None
    utterance_id: Optional[int] = None


async def probe_asr_backend() -> dict:
    """Live health of the host ASR service on :8002 (or local in-process mode)."""
    if ASR_PROVIDER == "deepgram":
        configured = bool(DEEPGRAM_API_KEY)
        return {
            "available": configured,
            "mode": "cloud",
            "provider": "deepgram",
            "model": DEEPGRAM_MODEL,
            "language": DEEPGRAM_LANGUAGE,
            "keyterm_count": len(DEEPGRAM_KEYTERMS),
            "message": "Deepgram configured" if configured else "DEEPGRAM_API_KEY is missing",
        }

    if not ASR_BACKEND_URL:
        try:
            from ..services.stt import get_asr_service

            svc = get_asr_service()
            loaded = bool(getattr(svc, "model", None) or getattr(svc, "is_ready", True))
            return {
                "available": True,
                "mode": "local",
                "provider": "parakeet",
                "model_loaded": loaded,
                "backend_url": None,
                "message": "In-process ASR",
                "trim_silence": ASR_TRIM_SILENCE,
                "dict_enabled": ASR_DICT_ENABLED,
                "llm_correct": ASR_LLM_CORRECT,
            }
        except Exception as exc:
            return {
                "available": False,
                "mode": "local",
                "model_loaded": False,
                "backend_url": None,
                "error": str(exc),
            }

    base = ASR_BACKEND_URL.rstrip("/")
    url = f"{base}/health"
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.get(url)
        body = {}
        try:
            body = resp.json() if resp.content else {}
        except Exception:
            body = {"raw": (resp.text or "")[:200]}
        ok = resp.status_code == 200 and (
            body.get("status") in ("ok", "healthy", "ready")
            or body.get("model_loaded") is True
            or body.get("available") is True
            or (resp.status_code == 200 and not body)
        )
        return {
            "available": bool(ok),
            "mode": "proxy",
            "provider": ASR_PROVIDER,
            "status_code": resp.status_code,
            "backend_url": base,
            "model_loaded": body.get("model_loaded"),
            "model": body.get("model"),
            "device": body.get("device"),
            "cuda_available": body.get("cuda_available"),
            "vram_mb": body.get("vram_mb"),
            "trim_silence": body.get("trim_silence", ASR_TRIM_SILENCE),
            "dict_enabled": ASR_DICT_ENABLED,
            "llm_correct": ASR_LLM_CORRECT,
            "detail": body,
            "message": "ASR backend ready" if ok else "ASR backend unhealthy",
        }
    except httpx.RequestError as exc:
        logger.warning("ASR health probe failed: %s", exc)
        return {
            "available": False,
            "mode": "proxy",
            "backend_url": base,
            "model_loaded": False,
            "error": str(exc),
            "message": "ASR backend unreachable",
        }


@router.get("/asr/health")
async def asr_health():
    """Probe the host ASR service (:8002) or local in-process ASR for admin UI."""
    return await probe_asr_backend()


@router.post("/asr/ctc/chunk")
async def transcribe_chunk(
    audio: UploadFile = File(...),
    session_id: Optional[str] = Form(None),
):
    if ASR_PROVIDER == "deepgram":
        return await _transcribe_deepgram(audio, session_id)
    if ASR_PROVIDER == "nemotron":
        return await _transcribe_nemotron(audio, session_id)
    if ASR_BACKEND_URL:
        return await _proxy_to_backend(audio, session_id)

    return await _transcribe_local(audio, session_id)


class _BytesAudioFile:
    """Minimal UploadFile-shaped adapter for in-process reuse.

    Every provider handler only uses ``read()``, ``filename`` and
    ``content_type``, so the WS relay can hand it memory bytes and get the
    *exact* batch code path (same validation, dict, logging, status codes)
    instead of duplicating the dispatch logic.
    """

    def __init__(self, data: bytes, filename: str, content_type: str):
        self._data = data
        self.filename = filename
        self.content_type = content_type

    async def read(self) -> bytes:
        return self._data


async def transcribe_audio_bytes(
    audio: bytes,
    session_id: Optional[str],
    *,
    filename: str = "segment.wav",
) -> Dict[str, Any]:
    """Decode WAV bytes through the enabled batch provider and return the
    response body as a dict. ``HTTPException`` propagates as in HTTP."""
    response = await transcribe_chunk(
        audio=_BytesAudioFile(audio, filename, "audio/wav"),  # type: ignore[arg-type]
        session_id=session_id,
    )
    return json.loads(response.body)


async def _transcribe_deepgram(
    audio: UploadFile, session_id: Optional[str]
) -> JSONResponse:
    if not DEEPGRAM_API_KEY:
        raise HTTPException(status_code=503, detail="Deepgram ASR is not configured")

    file_bytes = await audio.read()
    if not file_bytes:
        raise HTTPException(status_code=400, detail="Empty audio upload")

    params = [
        ("model", DEEPGRAM_MODEL),
        ("language", DEEPGRAM_LANGUAGE),
        ("smart_format", str(DEEPGRAM_SMART_FORMAT).lower()),
    ]
    params.extend(("keyterm", term) for term in DEEPGRAM_KEYTERMS)
    headers = {
        "Authorization": f"Token {DEEPGRAM_API_KEY}",
        "Content-Type": audio.content_type or "audio/wav",
    }
    start = time.time()
    try:
        async with httpx.AsyncClient(timeout=DEEPGRAM_TIMEOUT_SECONDS) as client:
            response = await client.post(
                DEEPGRAM_API_URL,
                params=params,
                headers=headers,
                content=file_bytes,
            )
    except httpx.TimeoutException as exc:
        raise HTTPException(status_code=504, detail="Deepgram ASR timed out") from exc
    except httpx.RequestError as exc:
        logger.error("Deepgram ASR request failed: %s", exc)
        raise HTTPException(status_code=503, detail="Deepgram ASR unavailable") from exc

    if response.status_code >= 400:
        logger.warning("Deepgram ASR returned HTTP %s", response.status_code)
        status = response.status_code if response.status_code in (400, 401, 403, 429) else 502
        raise HTTPException(status_code=status, detail=f"Deepgram ASR HTTP {response.status_code}")

    try:
        body = response.json()
        alternative = body["results"]["channels"][0]["alternatives"][0]
    except (ValueError, KeyError, IndexError, TypeError) as exc:
        logger.error("Unexpected Deepgram response shape")
        raise HTTPException(status_code=502, detail="Invalid Deepgram ASR response") from exc

    transcript = (alternative.get("transcript") or "").strip()
    duration = float((body.get("metadata") or {}).get("duration") or 0.0)
    elapsed = time.time() - start
    return JSONResponse({
        "success": True,
        "transcript": transcript,
        "transcript_raw": transcript,
        "duration_seconds": round(duration, 2),
        "processing_time_seconds": round(elapsed, 3),
        "real_time_factor": round(elapsed / duration, 3) if duration > 0 else None,
        "session_id": session_id,
        "provider": "deepgram",
        "model": DEEPGRAM_MODEL,
        "language": DEEPGRAM_LANGUAGE,
        "confidence": alternative.get("confidence"),
        "request_id": (body.get("metadata") or {}).get("request_id"),
        "keyterm_count": len(DEEPGRAM_KEYTERMS),
    })


def _wav_duration_seconds(file_bytes: bytes) -> float:
    """Length of the uploaded WAV, or 0.0 when it cannot be parsed.

    ``nemo-speech serve`` answers with nothing but ``{"text": ...}``, so every
    timing field in our response contract has to come from the upload itself.
    """
    import io
    import wave

    try:
        with wave.open(io.BytesIO(file_bytes), "rb") as wf:
            frames = wf.getnframes()
            rate = wf.getframerate()
            if frames > 0 and rate > 0:
                return frames / float(rate)
    except Exception:
        pass
    return 0.0


async def _transcribe_nemotron(
    audio: UploadFile, session_id: Optional[str]
) -> JSONResponse:
    """
    Nemotron streaming ASR over `nemo-speech serve` (OpenAI-compatible subset).

    The upload is forwarded byte-for-byte instead of being decoded, re-trimmed
    and re-encoded: the 18.62% WER baseline on tests/asr_golden was measured on
    exactly the raw whole-utterance files Android sends, and the model already
    answers "" on silence, so a server-side trim could only drift from what was
    measured. Dictionary post-processing still runs, which the benchmark did not.
    """
    if not ASR_BACKEND_URL:
        raise HTTPException(
            status_code=503, detail="ASR_BACKEND_URL is not configured for Nemotron"
        )

    file_bytes = await audio.read()
    if not file_bytes:
        raise HTTPException(status_code=400, detail="Empty audio upload")

    duration = _wav_duration_seconds(file_bytes)
    if duration > MAX_AUDIO_DURATION_S:
        # The cap is advisory here: the client enforces 20 s, and re-encoding
        # to truncate would diverge from the measured path.
        logger.warning(
            "session=%s audio %.1fs exceeds %.0fs cap",
            session_id,
            duration,
            MAX_AUDIO_DURATION_S,
        )

    url = f"{ASR_BACKEND_URL.rstrip('/')}{NEMOTRON_API_URL}"
    files = {
        "file": (
            audio.filename or "utterance.wav",
            file_bytes,
            audio.content_type or "audio/wav",
        )
    }
    data = {"model": NEMOTRON_MODEL, "language": NEMOTRON_LANGUAGE}
    headers = {"Authorization": f"Bearer {NEMOTRON_API_KEY}"} if NEMOTRON_API_KEY else {}

    start = time.time()
    try:
        async with httpx.AsyncClient(timeout=NEMOTRON_TIMEOUT_SECONDS) as client:
            response = await client.post(url, files=files, data=data, headers=headers)
    except httpx.TimeoutException as exc:
        raise HTTPException(status_code=504, detail="Nemotron ASR timed out") from exc
    except httpx.RequestError as exc:
        logger.error("Nemotron ASR request failed: %s", exc)
        raise HTTPException(status_code=503, detail="Nemotron ASR unavailable") from exc

    if response.status_code >= 400:
        logger.warning("Nemotron ASR returned HTTP %s", response.status_code)
        raise HTTPException(
            status_code=502, detail=f"Nemotron ASR HTTP {response.status_code}"
        )

    try:
        model_text = response.json().get("text") or ""
    except (ValueError, AttributeError) as exc:
        logger.error("Unexpected Nemotron response shape")
        raise HTTPException(
            status_code=502, detail="Invalid Nemotron ASR response"
        ) from exc

    processing_time = time.time() - start
    if not model_text.strip():
        # Empty is a valid answer, not a failure: the mic is open continuously,
        # and silence must not reach the chat LLM. Android drops this response
        # because `transcript` carries no letter or digit.
        logger.info("session=%s duration=%.2f no speech", session_id, duration)
        return JSONResponse({
            "success": True,
            "transcript": "",
            "transcript_raw": "",
            "duration_seconds": round(duration, 2),
            "processing_time_seconds": round(processing_time, 3),
            "real_time_factor": (
                round(processing_time / duration, 3) if duration > 0 else None
            ),
            "session_id": session_id,
            "provider": "nemotron",
            "model": NEMOTRON_MODEL,
            "language": NEMOTRON_LANGUAGE,
            "message": "No speech detected",
        })

    transcript, transcript_raw, dict_meta = postprocess_transcript(model_text)
    logger.info(
        "session=%s duration=%.2f dict=%s text='%s'",
        session_id,
        duration,
        dict_meta.get("dict_applied") or [],
        transcript[:120],
    )
    return JSONResponse({
        "success": True,
        "transcript": transcript,
        "transcript_raw": transcript_raw,
        "duration_seconds": round(duration, 2),
        "processing_time_seconds": round(processing_time, 3),
        "real_time_factor": (
            round(processing_time / duration, 3) if duration > 0 else None
        ),
        "session_id": session_id,
        "provider": "nemotron",
        "model": NEMOTRON_MODEL,
        "language": NEMOTRON_LANGUAGE,
        "dict": {
            "enabled": dict_meta.get("dict_enabled"),
            "applied": dict_meta.get("dict_applied") or [],
        },
    })


async def _proxy_to_backend(audio: UploadFile, session_id: Optional[str]) -> JSONResponse:
    file_bytes = await audio.read()
    files = {
        "audio": (
            audio.filename or "chunk.wav",
            file_bytes,
            audio.content_type or "audio/wav",
        )
    }
    data = {"session_id": session_id} if session_id else {}

    url = f"{ASR_BACKEND_URL.rstrip('/')}/asr/ctc/chunk"
    try:
        # Whole-utterance can be ~20–30s audio; allow slow GPU RTF
        async with httpx.AsyncClient(timeout=90.0) as client:
            resp = await client.post(url, files=files, data=data)
        try:
            body = resp.json()
        except Exception:
            raise HTTPException(status_code=502, detail=resp.text[:200])
        if isinstance(body, dict):
            body.setdefault("provider", "parakeet")
        return JSONResponse(status_code=resp.status_code, content=body)
    except httpx.RequestError as exc:
        logger.error("ASR backend unreachable at %s: %s", url, exc)
        raise HTTPException(status_code=503, detail=f"ASR backend unavailable: {exc}")


def _resample_linear(audio, orig_sr: int, target_sr: int):
    if orig_sr == target_sr or audio.size == 0:
        return audio.astype(np.float32, copy=False)
    duration = audio.shape[0] / float(orig_sr)
    target_len = max(1, int(round(duration * target_sr)))
    x_old = np.linspace(0.0, 1.0, num=audio.shape[0], endpoint=False)
    x_new = np.linspace(0.0, 1.0, num=target_len, endpoint=False)
    return np.interp(x_new, x_old, audio.astype(np.float64)).astype(np.float32)


def trim_silence_pad(
    audio: np.ndarray,
    *,
    sample_rate: int = SAMPLE_RATE,
    rms_thresh: float = ASR_TRIM_RMS,
    frame_ms: float = ASR_TRIM_FRAME_MS,
    pad_ms: float = ASR_TRIM_PAD_MS,
    enabled: bool = ASR_TRIM_SILENCE,
) -> tuple:
    """Mirror host asr_gpu_server.trim_silence_pad for local in-process path."""
    duration_before = float(len(audio) / sample_rate) if audio.size else 0.0
    meta: Dict[str, Any] = {
        "trim_enabled": enabled,
        "duration_before_trim": round(duration_before, 3),
        "trimmed_ms": 0.0,
        "pad_ms": pad_ms if enabled else 0.0,
        "all_silence": False,
    }
    if not enabled or audio.size == 0:
        return audio, meta

    frame_len = max(1, int(sample_rate * frame_ms / 1000.0))
    n = int(audio.shape[0])
    n_frames = n // frame_len
    if n_frames == 0:
        return audio, meta

    frames = audio[: n_frames * frame_len].reshape(n_frames, frame_len)
    rms = np.sqrt(np.mean(frames.astype(np.float64) ** 2, axis=1))
    speech = rms >= rms_thresh
    if not np.any(speech):
        meta["all_silence"] = True
        meta["trimmed_ms"] = round(duration_before * 1000.0, 1)
        return np.zeros(0, dtype=np.float32), meta

    first = int(np.argmax(speech))
    last = int(n_frames - 1 - np.argmax(speech[::-1]))
    pad_frames = int(round(pad_ms / frame_ms)) if frame_ms > 0 else 0
    start_f = max(0, first - pad_frames)
    end_f = min(n_frames - 1, last + pad_frames)
    start = start_f * frame_len
    end = min(n, (end_f + 1) * frame_len)
    trimmed = audio[start:end].astype(np.float32, copy=False)
    duration_after = float(len(trimmed) / sample_rate)
    meta["trimmed_ms"] = round(max(0.0, (duration_before - duration_after) * 1000.0), 1)
    meta["duration_after_trim"] = round(duration_after, 3)
    return trimmed, meta


async def _transcribe_local(audio: UploadFile, session_id: Optional[str]) -> JSONResponse:
    import io
    import wave

    from ..services.stt import get_asr_service

    start_time = time.time()
    file_bytes = await audio.read()
    if not file_bytes:
        raise HTTPException(status_code=400, detail="Empty audio upload")

    try:
        wf = wave.open(io.BytesIO(file_bytes), "rb")
        channels = wf.getnchannels()
        sr = wf.getframerate()
        sw = wf.getsampwidth()
        raw = wf.readframes(wf.getnframes())
        wf.close()
        if sw != 2:
            raise ValueError(f"Only 16-bit PCM supported locally, got {sw * 8}-bit")
        samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
        if channels > 1:
            if samples.size % channels != 0:
                samples = samples[: samples.size - (samples.size % channels)]
            samples = samples.reshape(-1, channels).mean(axis=1)
        audio_array = _resample_linear(samples, sr, SAMPLE_RATE) if sr != SAMPLE_RATE else samples
    except wave.Error as exc:
        raise HTTPException(
            status_code=400,
            detail=f"Invalid WAV: {exc}. Send WAV PCM 16-bit mono/stereo.",
        ) from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    duration_raw = len(audio_array) / SAMPLE_RATE
    truncated = False
    if duration_raw < MIN_AUDIO_DURATION_S:
        return JSONResponse({
            "success": True,
            "transcript": "",
            "transcript_raw": "",
            "duration_seconds": round(duration_raw, 2),
            "message": "Audio too short",
        })

    if duration_raw > MAX_AUDIO_DURATION_S:
        audio_array = audio_array[: int(SAMPLE_RATE * MAX_AUDIO_DURATION_S)]
        duration_raw = len(audio_array) / SAMPLE_RATE
        truncated = True

    audio_array, trim_meta = trim_silence_pad(audio_array)
    if trim_meta.get("all_silence") or len(audio_array) / SAMPLE_RATE < MIN_AUDIO_DURATION_S:
        return JSONResponse({
            "success": True,
            "transcript": "",
            "transcript_raw": "",
            "duration_seconds": round(duration_raw, 2),
            "message": "Audio silence after trim",
            "session_id": session_id,
            "truncated": truncated,
            "trim": trim_meta,
        })

    duration = len(audio_array) / SAMPLE_RATE

    try:
        asr = get_asr_service()
        model_text = asr.transcribe(audio_array)
    except Exception as exc:
        logger.error("Local ASR failed: %s", exc, exc_info=True)
        raise HTTPException(status_code=500, detail=f"Transcription failed: {exc}")

    transcript, transcript_raw, dict_meta = postprocess_transcript(
        model_text if isinstance(model_text, str) else str(model_text)
    )
    processing_time = time.time() - start_time
    rtf = processing_time / duration if duration > 0 else 0

    return JSONResponse({
        "success": True,
        "transcript": transcript,
        "transcript_raw": transcript_raw,
        "duration_seconds": round(duration, 2),
        "duration_before_trim": round(duration_raw, 2),
        "processing_time_seconds": round(processing_time, 3),
        "real_time_factor": round(rtf, 3),
        "session_id": session_id,
        "model": "parakeet-ctc-0.6b-vi",
        "provider": "parakeet",
        "truncated": truncated,
        "max_duration_seconds": MAX_AUDIO_DURATION_S,
        "trim": trim_meta,
        "dict": {
            "enabled": dict_meta.get("dict_enabled"),
            "applied": dict_meta.get("dict_applied") or [],
        },
    })


def _llm_correct_text(text: str) -> tuple:
    """
    Low-token ASR correction via LLM. Fail-open: returns original on any error.
    Returns (corrected, method).
    """
    raw = (text or "").strip()
    if not raw:
        return "", "empty"

    # Dictionary first (cheap)
    dict_out, _, dict_meta = postprocess_transcript(raw)
    if not ASR_LLM_CORRECT:
        method = "dictionary" if dict_meta.get("dict_applied") else "passthrough"
        return dict_out, method

    from ..services.llm import get_llm_service

    system = (
        "Bạn là bộ sửa lỗi nhận dạng giọng nói (ASR) tiếng Việt. "
        "Chỉ sửa lỗi chính tả, dấu, từ nghe nhầm; giữ nguyên nghĩa và độ dài gần như nhau. "
        "Không thêm câu trả lời, không giải thích, không markdown. "
        "Chỉ trả về đoạn text đã sửa."
    )
    user = f"Sửa transcript ASR sau:\n{dict_out}"
    try:
        llm = get_llm_service()
        # Short timeout for correct path
        old_timeout = llm.timeout
        llm.timeout = ASR_LLM_CORRECT_TIMEOUT_S
        try:
            out = llm.chat(
                [
                    {"role": "system", "content": system},
                    {"role": "user", "content": user},
                ],
                max_tokens=ASR_LLM_CORRECT_MAX_TOKENS,
            )
        finally:
            llm.timeout = old_timeout
        cleaned = (out or "").strip()
        # llm.chat raises LLMUnavailable instead of returning apology
        # strings; only an empty result falls back (caught below as well).
        if not cleaned:
            logger.warning("LLM correct empty → dictionary/raw")
            return dict_out, "dictionary_fallback"
        # Strip accidental quotes
        if (cleaned.startswith('"') and cleaned.endswith('"')) or (
            cleaned.startswith("'") and cleaned.endswith("'")
        ):
            cleaned = cleaned[1:-1].strip()
        return cleaned, "llm+dictionary" if dict_meta.get("dict_applied") else "llm"
    except Exception as exc:
        logger.warning("LLM correct error (fail-open): %s", exc)
        return dict_out, "dictionary_fallback"


@router.post("/api/asr/correct")
@router.post("/correct")
async def correct_transcript(body: CorrectRequest):
    """
    Optional STT post-correct for admin/web (POST /api/asr/correct)
    and legacy clients (POST /correct).

    Always applies dictionary; LLM only when ASR_LLM_CORRECT=true.
    Fail-open: returns original (after dict) on LLM failure.
    """
    utterance_id = body.utterance_id if body.utterance_id is not None else body.id
    raw = body.text or ""
    start = time.time()
    corrected, method = _llm_correct_text(raw)
    elapsed = time.time() - start
    logger.info(
        "asr_correct method=%s id=%s ms=%.0f in='%s' out='%s'",
        method,
        utterance_id,
        elapsed * 1000,
        raw[:60],
        corrected[:60],
    )
    return {
        "corrected": corrected,
        "raw": raw,
        "method": method,
        "id": utterance_id,
        "llm_enabled": ASR_LLM_CORRECT,
        "processing_time_seconds": round(elapsed, 3),
    }
