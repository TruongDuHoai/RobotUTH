"""TTS proxy API — test voices, clone, synthesize via host VieNeU service."""

import logging
from typing import Optional

import requests
from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel, Field

from ..config import TTS_ENABLED, TTS_SERVICE_URL, TTS_TIMEOUT_SECONDS
from ..runtime_settings import get_tts_settings_public, update_tts_settings
from ..services.tts import get_tts_service, reload_tts_service

router = APIRouter(prefix="/api/tts", tags=["tts"])
logger = logging.getLogger(__name__)


class TtsSynthesizeRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=500)
    voice: Optional[str] = None


class TtsTestRequest(BaseModel):
    text: Optional[str] = Field(None, max_length=500)
    voice: Optional[str] = None


class TtsSettingsUpdate(BaseModel):
    provider: Optional[str] = Field(None, pattern="^(vieneu|openai)$")
    default_voice: Optional[str] = Field(None, min_length=1, max_length=64)


def _ensure_enabled() -> None:
    if not TTS_ENABLED:
        raise HTTPException(503, "TTS is disabled")


def _proxy_url(path: str) -> str:
    return f"{TTS_SERVICE_URL.rstrip('/')}{path}"


@router.get("/settings")
async def get_tts_config():
    return {"success": True, "settings": get_tts_settings_public()}


@router.put("/settings")
async def put_tts_config(request: TtsSettingsUpdate):
    try:
        settings = update_tts_settings(request.model_dump())
        reload_tts_service()
        logger.info("TTS settings updated | provider=%s default_voice=%s", settings["provider"], settings["default_voice"])
        return {
            "success": True,
            "settings": settings,
            "message": f"Đã đặt giọng mặc định: {settings['default_voice']}",
        }
    except ValueError as exc:
        raise HTTPException(422, str(exc))
    except Exception as exc:
        logger.exception("TTS settings update failed")
        raise HTTPException(500, str(exc))


@router.get("/health")
async def tts_health():
    get_tts_service()
    runtime = get_tts_settings_public()
    if get_tts_service().provider == "openai":
        return {
            "enabled": TTS_ENABLED,
            "reachable": get_tts_service().health_check(),
            "provider": "openai",
            "default_voice": runtime["default_voice"],
        }
    try:
        response = requests.get(_proxy_url("/health"), timeout=8)
        data = response.json() if response.status_code == 200 else {"error": response.text[:300]}
        return {
            "enabled": TTS_ENABLED,
            "reachable": response.status_code == 200,
            "default_voice": runtime["default_voice"],
            "voice_source": runtime["source"],
            "service_url": TTS_SERVICE_URL,
            "backend": data,
        }
    except Exception as exc:
        return {
            "enabled": TTS_ENABLED,
            "reachable": False,
            "default_voice": runtime["default_voice"],
            "voice_source": runtime["source"],
            "service_url": TTS_SERVICE_URL,
            "error": str(exc),
        }


@router.get("/voices")
async def list_voices():
    _ensure_enabled()
    return get_tts_service().list_voices()


@router.post("/synthesize")
async def synthesize(request: TtsSynthesizeRequest):
    _ensure_enabled()
    audio = get_tts_service().synthesize(request.text, request.voice)
    if not audio:
        raise HTTPException(502, "TTS synthesis failed or returned empty audio")
    return Response(content=audio, media_type="audio/mpeg" if get_tts_service().provider == "openai" else "audio/wav")


@router.post("/test")
async def test_voice(request: TtsTestRequest):
    _ensure_enabled()
    try:
        if get_tts_service().provider == "openai":
            audio = get_tts_service().synthesize(request.text or "Xin chào", request.voice)
            if not audio:
                raise HTTPException(502, "TTS synthesis failed or returned empty audio")
            return Response(content=audio, media_type="audio/mpeg")
        response = requests.post(
            _proxy_url("/test"),
            json={"text": request.text, "voice": request.voice or get_tts_service().default_voice},
            timeout=TTS_TIMEOUT_SECONDS,
        )
        if response.status_code != 200:
            raise HTTPException(response.status_code, response.text[:500])
        return Response(content=response.content, media_type="audio/wav")
    except HTTPException:
        raise
    except Exception as exc:
        logger.exception("TTS test proxy failed")
        raise HTTPException(502, str(exc))


@router.post("/voices/clone")
async def clone_voice(
    name: str = Form(...),
    ref_text: str = Form(...),
    description: str = Form(""),
    ref_audio: UploadFile = File(...),
):
    _ensure_enabled()
    try:
        content = await ref_audio.read()
        response = requests.post(
            _proxy_url("/voices/clone"),
            data={"name": name, "ref_text": ref_text, "description": description},
            files={
                "ref_audio": (
                    ref_audio.filename or "ref.wav",
                    content,
                    ref_audio.content_type or "audio/wav",
                )
            },
            timeout=max(TTS_TIMEOUT_SECONDS, 60),
        )
        if response.status_code >= 400:
            detail = response.json() if "application/json" in response.headers.get("content-type", "") else response.text
            raise HTTPException(response.status_code, detail)
        return response.json()
    except HTTPException:
        raise
    except Exception as exc:
        logger.exception("TTS clone proxy failed")
        raise HTTPException(502, str(exc))


@router.delete("/voices/{voice_id}")
async def delete_voice(voice_id: str):
    _ensure_enabled()
    try:
        response = requests.delete(_proxy_url(f"/voices/{voice_id}"), timeout=15)
        if response.status_code >= 400:
            raise HTTPException(response.status_code, response.text[:300])
        return response.json()
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(502, str(exc))
