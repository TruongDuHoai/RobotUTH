#!/usr/bin/env python3
"""Native VieNeu TTS server for the RobotUTH host.

Endpoints:
  GET  /health
  GET  /voices
  POST /tts          -> audio/wav (JSON body: text, voice?)
  POST /test         -> audio/wav (quick test phrase)
  POST /voices/clone -> register cloned voice (multipart)
  DELETE /voices/{name}
"""

from __future__ import annotations

import argparse
import io
import json
import logging
import re
import shutil
import time
from pathlib import Path
from typing import Any, Dict, Optional

import numpy as np
import soundfile as sf
import uvicorn
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response
from pydantic import BaseModel, Field

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger("tts_gpu_server")

BASE_DIR = Path(__file__).resolve().parent.parent
VOICES_DIR = BASE_DIR / "data" / "tts_voices"
VOICES_JSON = VOICES_DIR / "voices.json"
DEFAULT_TEST_TEXT = "Xin chào, tôi là RobotUTH. Rất vui được gặp bạn."

BACKBONE_REPO = "pnnbao-ump/VieNeu-TTS-0.3B"
TURBO_BACKBONE_REPO = "pnnbao-ump/VieNeu-TTS-v2-Turbo-GGUF"
# GGUF is lighter but VieNeu 3.x cannot load LoRA on quantized backbones.
GGUF_FILENAME = "VieNeu-TTS-0.3B-Q4_K_M.gguf"
LORA_REPO = "pnnbao-ump/VieNeu-TTS-0.3B-lora-ngoc-huyen"
DEFAULT_VOICE = "NgocHuyen"
TURBO_DEFAULT_VOICE = "Thục Đoan (Nữ - Miền Nam)"
VOICE_ALIASES = {
    "ngoc-huyen": "NgocHuyen",
    "ngoc_huyen": "NgocHuyen",
    "Ngoc": "Ngoc",
}
MAX_TEXT_CHARS = 500

app = FastAPI(title="Robotuth TTS GPU - VieNeu Ngọc Huyền")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

TTS_ENGINE = None
CLONED_VOICES: Dict[str, Dict[str, Any]] = {}


class TtsRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=MAX_TEXT_CHARS)
    voice: Optional[str] = None


class TestRequest(BaseModel):
    text: Optional[str] = Field(None, max_length=MAX_TEXT_CHARS)
    voice: Optional[str] = None


def _slug_name(value: str) -> str:
    slug = re.sub(r"[^a-zA-Z0-9_-]+", "_", (value or "").strip().lower())
    slug = slug.strip("_")
    if not slug:
        raise ValueError("voice name is required")
    return slug[:64]


def _ensure_voices_dir() -> None:
    VOICES_DIR.mkdir(parents=True, exist_ok=True)
    if not VOICES_JSON.exists():
        VOICES_JSON.write_text(json.dumps({"cloned": {}}, ensure_ascii=False, indent=2), encoding="utf-8")


def _load_cloned_voices() -> None:
    global CLONED_VOICES
    _ensure_voices_dir()
    try:
        data = json.loads(VOICES_JSON.read_text(encoding="utf-8"))
    except Exception as exc:
        logger.warning("Failed to read voices.json: %s", exc)
        data = {"cloned": {}}
    CLONED_VOICES = data.get("cloned") if isinstance(data.get("cloned"), dict) else {}
    logger.info("Loaded %s cloned voice(s)", len(CLONED_VOICES))


def _save_cloned_voices() -> None:
    _ensure_voices_dir()
    VOICES_JSON.write_text(
        json.dumps({"cloned": CLONED_VOICES}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def _wav_response(audio: np.ndarray, sample_rate: int) -> Response:
    buffer = io.BytesIO()
    sf.write(buffer, audio, sample_rate, format="WAV")
    return Response(content=buffer.getvalue(), media_type="audio/wav")


def _default_voice() -> str:
    return TURBO_DEFAULT_VOICE if getattr(app.state, "turbo", False) else DEFAULT_VOICE


def _normalize_voice_id(voice: Optional[str]) -> str:
    voice_id = (voice or _default_voice()).strip()
    if getattr(app.state, "turbo", False) and voice_id in VOICE_ALIASES:
        return TURBO_DEFAULT_VOICE
    return VOICE_ALIASES.get(voice_id, voice_id)


def load_engine(backbone_device: str = "cuda", use_gguf: bool = False, turbo: bool = False) -> None:
    global TTS_ENGINE
    from vieneu import Vieneu

    if turbo:
        logger.info("Loading VieNeu Turbo backbone=%s device=%s", TURBO_BACKBONE_REPO, backbone_device)
        TTS_ENGINE = Vieneu(mode="turbo", device=backbone_device)
        _load_cloned_voices()
        logger.info("TTS ready | mode=turbo | presets=%s", TTS_ENGINE.list_preset_voices())
        return

    logger.info("Loading VieNeu backbone=%s device=%s gguf=%s", BACKBONE_REPO, backbone_device, use_gguf)
    kwargs: Dict[str, Any] = {
        "mode": "standard",
        "backbone_repo": BACKBONE_REPO,
        "backbone_device": backbone_device,
        "codec_repo": "neuphonic/neucodec-onnx-decoder-int8",
        "codec_device": "cpu",
    }
    # Vieneu defaults to a v2 GGUF name — pass empty string to force PyTorch+LoRA.
    kwargs["gguf_filename"] = GGUF_FILENAME if use_gguf else ""
    engine = Vieneu(**kwargs)
    if use_gguf:
        # LoRA weights are incompatible with 0.3B (512 vs 768 hidden); load preset codes only.
        logger.warning(
            "GGUF mode: skipping LoRA weights — loading NgocHuyen preset from %s",
            LORA_REPO,
        )
        engine._load_voices(LORA_REPO, clear_existing=True)
    else:
        engine.load_lora_adapter(LORA_REPO)
    TTS_ENGINE = engine
    presets = engine.list_preset_voices()
    logger.info(
        "TTS ready | mode=%s | presets=%s",
        "gguf+preset" if use_gguf else f"lora:{LORA_REPO}",
        presets,
    )
    _load_cloned_voices()


def _list_preset_voices() -> list[dict[str, str]]:
    if TTS_ENGINE is None:
        return []
    return [
        {"id": voice_id, "label": label, "type": "preset"}
        for label, voice_id in TTS_ENGINE.list_preset_voices()
    ]


def _resolve_voice(voice: Optional[str]) -> tuple[Optional[dict], Optional[str], Optional[str]]:
    voice_id = _normalize_voice_id(voice)
    if voice_id in CLONED_VOICES:
        entry = CLONED_VOICES[voice_id]
        audio_path = VOICES_DIR / entry["ref_audio"]
        if not audio_path.exists():
            raise HTTPException(404, f"Cloned voice file missing: {voice_id}")
        return None, str(audio_path), entry.get("ref_text") or ""

    if TTS_ENGINE is None:
        raise HTTPException(503, "TTS model not loaded")
    try:
        preset = TTS_ENGINE.get_preset_voice(voice_id)
        return preset, None, None
    except Exception as exc:
        available = [v["id"] for v in _list_preset_voices()] + list(CLONED_VOICES.keys())
        raise HTTPException(404, f"Voice '{voice_id}' not found. Available: {available}") from exc


def _synthesize(text: str, voice: Optional[str] = None) -> tuple[np.ndarray, int]:
    if TTS_ENGINE is None:
        raise HTTPException(503, "TTS model not loaded")

    text = text.strip()
    if not text:
        raise HTTPException(422, "text is required")
    if len(text) > MAX_TEXT_CHARS:
        raise HTTPException(413, f"text too long (max {MAX_TEXT_CHARS} chars)")

    preset, ref_audio, ref_text = _resolve_voice(voice)
    start = time.time()
    if preset is not None:
        audio = TTS_ENGINE.infer(text, voice=preset)
    elif getattr(app.state, "turbo", False):
        audio = TTS_ENGINE.infer(text, ref_codes=TTS_ENGINE.encode_reference(ref_audio))
    else:
        audio = TTS_ENGINE.infer(text, ref_audio=ref_audio, ref_text=ref_text)
    logger.info("Synthesized %s chars with voice=%s in %.2fs", len(text), voice or _default_voice(), time.time() - start)
    return audio, TTS_ENGINE.sample_rate


@app.on_event("startup")
async def startup() -> None:
    device = app.state.backbone_device
    use_gguf = getattr(app.state, "use_gguf", False)
    load_engine(backbone_device=device, use_gguf=use_gguf, turbo=getattr(app.state, "turbo", False))


@app.get("/health")
async def health():
    vram_mb = None
    try:
        import torch

        if torch.cuda.is_available():
            vram_mb = round(torch.cuda.memory_allocated() / 1024**2, 1)
    except Exception:
        pass
    return {
        "status": "ok",
        "model_loaded": TTS_ENGINE is not None,
        "backbone": TURBO_BACKBONE_REPO if getattr(app.state, "turbo", False) else BACKBONE_REPO,
        "lora": None if (getattr(app.state, "use_gguf", False) or getattr(app.state, "turbo", False)) else LORA_REPO,
        "mode": "turbo" if getattr(app.state, "turbo", False) else ("gguf+preset" if getattr(app.state, "use_gguf", False) else "pytorch+lora"),
        "default_voice": _default_voice(),
        "preset_voices": _list_preset_voices(),
        "cloned_voices": [
            {"id": key, "label": value.get("description", key), "type": "cloned"}
            for key, value in CLONED_VOICES.items()
        ],
        "vram_mb": vram_mb,
    }


@app.get("/voices")
async def list_voices():
    return {
        "default_voice": _default_voice(),
        "presets": _list_preset_voices(),
        "cloned": [
            {
                "id": key,
                "label": value.get("description", key),
                "ref_text": value.get("ref_text", ""),
            }
            for key, value in CLONED_VOICES.items()
        ],
    }


@app.post("/tts")
async def synthesize(request: TtsRequest):
    audio, sample_rate = _synthesize(request.text, request.voice)
    return _wav_response(audio, sample_rate)


@app.post("/test")
async def test_voice(request: TestRequest):
    text = (request.text or DEFAULT_TEST_TEXT).strip()
    audio, sample_rate = _synthesize(text, request.voice)
    return _wav_response(audio, sample_rate)


@app.post("/voices/clone")
async def clone_voice(
    name: str = Form(...),
    ref_text: str = Form(...),
    description: str = Form(""),
    ref_audio: UploadFile = File(...),
):
    if TTS_ENGINE is None:
        raise HTTPException(503, "TTS model not loaded")

    voice_id = _slug_name(name)
    if voice_id in CLONED_VOICES:
        raise HTTPException(409, f"Voice '{voice_id}' already exists")

    ref_text = ref_text.strip()
    if len(ref_text) < 3:
        raise HTTPException(422, "ref_text is required (transcript of reference audio)")

    suffix = Path(ref_audio.filename or "clip.wav").suffix.lower() or ".wav"
    if suffix not in {".wav", ".mp3", ".m4a", ".ogg", ".flac"}:
        raise HTTPException(400, "Unsupported audio type")

    _ensure_voices_dir()
    target_name = f"{voice_id}{suffix}"
    target_path = VOICES_DIR / target_name
    with target_path.open("wb") as handle:
        shutil.copyfileobj(ref_audio.file, handle)

    try:
        if getattr(app.state, "turbo", False):
            TTS_ENGINE.infer("Kiểm tra giọng clone.", ref_codes=TTS_ENGINE.encode_reference(str(target_path)))
        else:
            TTS_ENGINE.infer("Kiểm tra giọng clone.", ref_audio=str(target_path), ref_text=ref_text)
    except Exception as exc:
        target_path.unlink(missing_ok=True)
        logger.exception("Clone validation failed for %s", voice_id)
        raise HTTPException(400, f"Invalid reference audio: {exc}") from exc

    CLONED_VOICES[voice_id] = {
        "description": description.strip() or voice_id,
        "ref_audio": target_name,
        "ref_text": ref_text,
    }
    _save_cloned_voices()
    return {
        "success": True,
        "voice": voice_id,
        "description": CLONED_VOICES[voice_id]["description"],
        "message": f"Đã lưu giọng clone '{voice_id}'",
    }


@app.delete("/voices/{voice_id}")
async def delete_voice(voice_id: str):
    voice_id = _slug_name(voice_id)
    entry = CLONED_VOICES.pop(voice_id, None)
    if not entry:
        raise HTTPException(404, f"Cloned voice '{voice_id}' not found")

    audio_path = VOICES_DIR / entry.get("ref_audio", "")
    if audio_path.exists():
        audio_path.unlink()
    _save_cloned_voices()
    return {"success": True, "voice": voice_id}


def main() -> None:
    parser = argparse.ArgumentParser(description="Robotuth VieNeu TTS server")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8004)
    parser.add_argument("--device", default="cuda", choices=["cuda", "cpu", "mps"])
    parser.add_argument(
        "--gguf",
        action="store_true",
        help="Use GGUF backbone (lower VRAM, no LoRA Ngoc Huyen)",
    )
    parser.add_argument("--turbo", action="store_true", help="Use VieNeu v2 Turbo GGUF")
    args = parser.parse_args()

    app.state.backbone_device = args.device
    app.state.use_gguf = args.gguf
    app.state.turbo = args.turbo
    logger.info("Starting TTS server on %s:%s (device=%s turbo=%s)", args.host, args.port, args.device, args.turbo)
    uvicorn.run(app, host=args.host, port=args.port, log_level="info")


if __name__ == "__main__":
    main()
