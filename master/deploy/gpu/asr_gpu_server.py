#!/usr/bin/env python3
"""
Native GPU ASR server — NVIDIA Parakeet CTC 0.6B Vietnamese.

Model: https://huggingface.co/nvidia/parakeet-ctc-0.6b-Vietnamese
NeMo ID: nvidia/parakeet-ctc-0.6b-vi

Android endpoint: POST /asr/ctc/chunk
"""

import argparse
import asyncio
import io
import logging
import os
import sys
import time
import wave
from pathlib import Path
from typing import Any, Dict, Optional, Tuple

import numpy as np
import torch
import uvicorn
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

# Allow `from asr_postprocess import …` when run as deploy/gpu/asr_gpu_server.py
_SCRIPTS_DIR = Path(__file__).resolve().parent
if str(_SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS_DIR))

from asr_postprocess import postprocess_transcript  # noqa: E402

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger("asr_gpu_server")

app = FastAPI(title="Robotuth ASR GPU - Parakeet CTC Vietnamese")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

MODEL = None
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
SAMPLE_RATE = 16000
# Whole-utterance from Android can be up to ~20s; allow headroom.
MAX_AUDIO_DURATION_S = float(os.getenv("ASR_MAX_DURATION_S", "30"))
MIN_AUDIO_DURATION_S = 0.1
HF_MODEL_ID = "nvidia/parakeet-ctc-0.6b-vi"

# Pre-tune audio cleanup (trim silence + edge pad for CTC)
ASR_TRIM_SILENCE = os.getenv("ASR_TRIM_SILENCE", "true").lower() in (
    "1",
    "true",
    "yes",
    "on",
)
ASR_TRIM_PAD_MS = float(os.getenv("ASR_TRIM_PAD_MS", "150"))
# Phone speech tails can fall below 0.01 even when the utterance is clear.
# Keep a low gate so trimming removes room silence without cutting final syllables.
ASR_TRIM_RMS = float(os.getenv("ASR_TRIM_RMS", "0.003"))
ASR_TRIM_FRAME_MS = float(os.getenv("ASR_TRIM_FRAME_MS", "20"))
# T400 can only transcribe one utterance safely.  This is a bounded FIFO wait
# list: one running job plus at most one waiting job by default.
ASR_MAX_QUEUED_JOBS = max(1, int(os.getenv("ASR_MAX_QUEUED_JOBS", "2")))
_inference_lock = asyncio.Lock()
_queue_lock = asyncio.Lock()
_queued_jobs = 0


def load_model(model_path: Optional[str] = None):
    global MODEL
    import nemo.collections.asr as nemo_asr

    # Load on CPU first so peak VRAM stays low on 4GB GPUs (T400), then FP16 → CUDA.
    if model_path and Path(model_path).exists():
        logger.info("Loading model from file (CPU): %s", model_path)
        MODEL = nemo_asr.models.EncDecCTCModelBPE.restore_from(
            model_path, map_location="cpu"
        )
    else:
        logger.info("Loading model from HuggingFace (CPU): %s", HF_MODEL_ID)
        MODEL = nemo_asr.models.ASRModel.from_pretrained(HF_MODEL_ID, map_location="cpu")

    MODEL = MODEL.float().cpu()
    if DEVICE == "cuda":
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
        MODEL = MODEL.half().to(DEVICE)
        torch.cuda.empty_cache()
        alloc = torch.cuda.memory_allocated() / 1024**3
        free, total = torch.cuda.mem_get_info()
        logger.info(
            "GPU memory after load: allocated=%.2f GB free=%.2f/%.2f GB",
            alloc,
            free / 1024**3,
            total / 1024**3,
        )
    else:
        MODEL = MODEL.to(DEVICE)
    MODEL.eval()
    logger.info("Parakeet CTC ready on %s (max_audio=%.1fs)", DEVICE, MAX_AUDIO_DURATION_S)


def _run_inference(audio_array: np.ndarray):
    """Run the blocking NeMo call away from FastAPI's event loop."""
    with torch.no_grad():
        if DEVICE == "cuda":
            with torch.cuda.amp.autocast():
                return MODEL.transcribe([audio_array], batch_size=1)
        return MODEL.transcribe([audio_array], batch_size=1)


async def _claim_queue_slot() -> bool:
    global _queued_jobs
    async with _queue_lock:
        if _queued_jobs >= ASR_MAX_QUEUED_JOBS:
            return False
        _queued_jobs += 1
        return True


async def _release_queue_slot() -> None:
    global _queued_jobs
    async with _queue_lock:
        _queued_jobs = max(0, _queued_jobs - 1)


def _resample_linear(audio: np.ndarray, orig_sr: int, target_sr: int) -> np.ndarray:
    if orig_sr == target_sr or audio.size == 0:
        return audio.astype(np.float32, copy=False)
    duration = audio.shape[0] / float(orig_sr)
    target_len = max(1, int(round(duration * target_sr)))
    x_old = np.linspace(0.0, 1.0, num=audio.shape[0], endpoint=False)
    x_new = np.linspace(0.0, 1.0, num=target_len, endpoint=False)
    return np.interp(x_new, x_old, audio.astype(np.float64)).astype(np.float32)


def _to_mono_float32(raw: bytes, channels: int, sample_width: int) -> np.ndarray:
    if sample_width == 2:
        samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    elif sample_width == 1:
        # unsigned 8-bit PCM
        samples = (np.frombuffer(raw, dtype=np.uint8).astype(np.float32) - 128.0) / 128.0
    elif sample_width == 4:
        # try 32-bit float first
        samples = np.frombuffer(raw, dtype=np.float32).copy()
        peak = float(np.max(np.abs(samples))) if samples.size else 0.0
        if peak > 1.5:
            samples = np.frombuffer(raw, dtype=np.int32).astype(np.float32) / 2147483648.0
    else:
        raise ValueError(f"Unsupported sample width: {sample_width * 8}-bit")

    if channels > 1:
        if samples.size % channels != 0:
            samples = samples[: samples.size - (samples.size % channels)]
        samples = samples.reshape(-1, channels).mean(axis=1)
    return samples.astype(np.float32, copy=False)


def read_audio(file_bytes: bytes) -> Tuple[np.ndarray, dict]:
    """
    Decode WAV (preferred) to mono float32 @ 16 kHz.
    Resamples and downmixes when needed. Rejects silent garbage raw fallback.
    """
    meta = {"format": "unknown", "orig_sr": None, "channels": None, "resampled": False}

    try:
        wf = wave.open(io.BytesIO(file_bytes), "rb")
        channels = wf.getnchannels()
        sr = wf.getframerate()
        sw = wf.getsampwidth()
        nframes = wf.getnframes()
        raw = wf.readframes(nframes)
        wf.close()

        meta.update({"format": "wav", "orig_sr": sr, "channels": channels, "sample_width": sw})
        audio = _to_mono_float32(raw, channels, sw)
        if sr != SAMPLE_RATE:
            audio = _resample_linear(audio, sr, SAMPLE_RATE)
            meta["resampled"] = True
            logger.info("Resampled WAV %d Hz → %d Hz (ch=%d)", sr, SAMPLE_RATE, channels)
        return audio, meta
    except wave.Error as exc:
        # Do not silently reinterpret arbitrary bytes as PCM16 — that produced garbage.
        raise ValueError(
            f"Invalid or unsupported WAV ({exc}). "
            f"Send mono/stereo WAV PCM (16-bit preferred) at any sample rate; "
            f"server resamples to {SAMPLE_RATE} Hz."
        ) from exc


def trim_silence_pad(
    audio: np.ndarray,
    *,
    sample_rate: int = SAMPLE_RATE,
    rms_thresh: float = ASR_TRIM_RMS,
    frame_ms: float = ASR_TRIM_FRAME_MS,
    pad_ms: float = ASR_TRIM_PAD_MS,
    enabled: bool = ASR_TRIM_SILENCE,
) -> Tuple[np.ndarray, Dict[str, Any]]:
    """
    Drop leading/trailing low-energy frames; keep pad_ms around speech for CTC edges.
    Float32 mono in [-1, 1]. Does not peak-normalize.
    """
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
        # Too short to frame — leave as-is
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
    meta["speech_start_ms"] = round(start / sample_rate * 1000.0, 1)
    meta["speech_end_ms"] = round(end / sample_rate * 1000.0, 1)
    return trimmed, meta


@app.on_event("startup")
async def startup():
    model_path = app.state.model_path
    try:
        load_model(model_path)
    except Exception as exc:
        logger.error("Model load failed: %s", exc, exc_info=True)
        raise


@app.post("/asr/ctc/chunk")
async def transcribe_chunk(
    audio: UploadFile = File(...),
    session_id: Optional[str] = Form(None),
):
    if MODEL is None:
        raise HTTPException(status_code=503, detail="Model not loaded")

    start = time.time()
    file_bytes = await audio.read()
    if not file_bytes:
        raise HTTPException(status_code=400, detail="Empty audio upload")

    try:
        audio_array, meta = read_audio(file_bytes)
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
            "audio_meta": meta,
        })

    if duration_raw > MAX_AUDIO_DURATION_S:
        audio_array = audio_array[: int(SAMPLE_RATE * MAX_AUDIO_DURATION_S)]
        duration_raw = len(audio_array) / SAMPLE_RATE
        truncated = True
        logger.warning(
            "session=%s truncated to %.1fs (max=%.1fs)",
            session_id,
            duration_raw,
            MAX_AUDIO_DURATION_S,
        )

    audio_array, trim_meta = trim_silence_pad(audio_array)
    meta = {**meta, **trim_meta}

    if trim_meta.get("all_silence") or len(audio_array) / SAMPLE_RATE < MIN_AUDIO_DURATION_S:
        return JSONResponse({
            "success": True,
            "transcript": "",
            "transcript_raw": "",
            "duration_seconds": round(duration_raw, 2),
            "message": "Audio silence after trim",
            "session_id": session_id,
            "truncated": truncated,
            "audio_meta": meta,
            "trim": trim_meta,
        })

    duration = len(audio_array) / SAMPLE_RATE

    if not await _claim_queue_slot():
        logger.warning("session=%s rejected: ASR queue full", session_id)
        raise HTTPException(
            status_code=429,
            detail="ASR is busy; retry shortly",
            headers={"Retry-After": "5"},
        )
    try:
        async with _inference_lock:
            results = await asyncio.to_thread(_run_inference, audio_array)
    finally:
        await _release_queue_slot()

    first = results[0]
    model_text = first.text if hasattr(first, "text") else str(first)
    transcript, transcript_raw, dict_meta = postprocess_transcript(model_text)

    # ── QA post-process: sửa chính tả từng từ + quy tắc ngôn ngữ (tùy chọn) ──
    # Tự động bật; tắt bằng ASR_QA_POSTPROCESS=false nếu cần rollback nhanh.
    # Lớp này đến từ package asr_pipeline (deploy/gpu/asr_pipeline) — bổ sung cho
    # từ điển phrase-map domain đã có trong asr_postprocess.
    qa_meta = {"spelling_applied": [], "language_rules": False}
    if os.getenv("ASR_QA_POSTPROCESS", "true").lower() in ("1", "true", "yes", "on"):
        try:
            from pathlib import Path as _Path

            from asr_pipeline.spelling import apply_language_rules, correct_spelling, load_wordlist
            wl_path = os.getenv("ASR_SPELLCHECK_WORDLIST") or None
            wl = load_wordlist(_Path(wl_path) if wl_path else None)
            transcript, spell_applied = correct_spelling(transcript, wl)
            transcript = apply_language_rules(transcript)
            qa_meta["spelling_applied"] = spell_applied
            qa_meta["language_rules"] = True
        except Exception as exc:  # noqa: BLE001
            logger.warning("ASR QA postprocess skipped: %s", exc)

    processing_time = time.time() - start
    rtf = processing_time / duration if duration > 0 else 0

    logger.info(
        "session=%s duration=%.2fs (raw=%.2fs trim_ms=%.0f) rtf=%.2f truncated=%s "
        "dict=%s text='%s'",
        session_id,
        duration,
        duration_raw,
        float(trim_meta.get("trimmed_ms") or 0),
        rtf,
        truncated,
        bool(dict_meta.get("dict_applied")),
        transcript[:80],
    )

    return JSONResponse({
        "success": True,
        "transcript": transcript,
        "transcript_raw": transcript_raw,
        "duration_seconds": round(duration, 2),
        "duration_before_trim": round(duration_raw, 2),
        "processing_time_seconds": round(processing_time, 3),
        "real_time_factor": round(rtf, 3),
        "session_id": session_id,
        "model": HF_MODEL_ID,
        "device": DEVICE,
        "truncated": truncated,
        "max_duration_seconds": MAX_AUDIO_DURATION_S,
        "audio_meta": meta,
        "trim": trim_meta,
        "dict": {
            "enabled": dict_meta.get("dict_enabled"),
            "applied": dict_meta.get("dict_applied") or [],
        },
        "qa": {
            "spelling_applied": qa_meta.get("spelling_applied") or [],
            "language_rules": qa_meta.get("language_rules", False),
        },
    })


@app.get("/health")
async def health():
    vram = None
    if DEVICE == "cuda" and torch.cuda.is_available():
        vram = round(torch.cuda.memory_allocated() / 1024**2, 1)
    return {
        "status": "ok",
        "model_loaded": MODEL is not None,
        "model": HF_MODEL_ID,
        "device": DEVICE,
        "cuda_available": torch.cuda.is_available(),
        "vram_mb": vram,
        "max_duration_seconds": MAX_AUDIO_DURATION_S,
        "sample_rate": SAMPLE_RATE,
        "trim_silence": ASR_TRIM_SILENCE,
        "trim_pad_ms": ASR_TRIM_PAD_MS,
        "trim_rms": ASR_TRIM_RMS,
        "queued_jobs": _queued_jobs,
        "max_queued_jobs": ASR_MAX_QUEUED_JOBS,
    }


def main():
    parser = argparse.ArgumentParser(description="Robotuth Parakeet ASR GPU server")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8002)
    parser.add_argument("--model-path", default=None, help="Optional local .nemo file")
    args = parser.parse_args()

    app.state.model_path = args.model_path

    logger.info("Starting ASR GPU server on %s:%s (device=%s)", args.host, args.port, DEVICE)
    uvicorn.run(app, host=args.host, port=args.port, log_level="info")


if __name__ == "__main__":
    main()
