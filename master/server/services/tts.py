"""
TTS Service - VieNeU Wrapper
"""

import logging
import time
from typing import Any, Dict, Optional

import requests

from ..config import OPENAI_API_BASE, OPENAI_API_KEY, TTS_ENABLED, TTS_MODEL, TTS_SERVICE_URL, TTS_TIMEOUT_SECONDS
from ..runtime_settings import get_tts_settings

logger = logging.getLogger(__name__)


class VieNeUTTSWrapper:
    """VieNeU TTS wrapper"""

    def __init__(
        self,
        service_url: Optional[str] = None,
        enabled: Optional[bool] = None,
        default_voice: Optional[str] = None,
    ):
        self.service_url = (service_url or TTS_SERVICE_URL).rstrip("/")
        self.enabled = enabled if enabled is not None else TTS_ENABLED
        settings = get_tts_settings()
        self.provider = settings["provider"]
        self.default_voice = default_voice or settings["default_voice"]
        self.timeout = TTS_TIMEOUT_SECONDS

    def synthesize(self, text: str, voice: Optional[str] = None) -> bytes:
        if not self.enabled:
            logger.warning("TTS is disabled")
            return b""

        start_time = time.time()
        if self.provider == "openai":
            headers = {"Authorization": f"Bearer {OPENAI_API_KEY}"} if OPENAI_API_KEY else {}
            try:
                response = requests.post(
                    f"{OPENAI_API_BASE.rstrip('/')}/audio/speech",
                    json={"model": TTS_MODEL, "input": text}, headers=headers, timeout=self.timeout,
                )
                response.raise_for_status()
                return response.content
            except Exception as exc:
                logger.error("OpenAI-compatible TTS error: %s", exc)
                return b""

        payload = {"text": text, "voice": voice or self.default_voice}

        try:
            response = requests.post(
                f"{self.service_url}/tts",
                json=payload,
                timeout=self.timeout,
            )
            response.raise_for_status()
            content_type = (response.headers.get("content-type") or "").lower()
            if "audio" in content_type:
                audio_data = response.content
            else:
                data = response.json()
                if not data.get("success"):
                    logger.error("TTS synthesis failed: %s", data.get("message", "Unknown error"))
                    return b""
                audio_data = data.get("audio", b"")
                if isinstance(audio_data, str):
                    import base64
                    audio_data = base64.b64decode(audio_data)

            processing_time = time.time() - start_time
            logger.info(
                "TTS synthesis completed in %.2fs (%s bytes)",
                processing_time,
                len(audio_data) if isinstance(audio_data, (bytes, bytearray)) else 0,
            )
            return audio_data if isinstance(audio_data, (bytes, bytearray)) else b""

        except requests.exceptions.ConnectionError:
            logger.error("Cannot connect to TTS service at %s", self.service_url)
            return b""

        except requests.exceptions.Timeout:
            logger.error("TTS request timeout")
            return b""

        except Exception as e:
            logger.error("TTS error: %s", e)
            return b""

    def list_voices(self) -> Dict[str, Any]:
        if self.provider == "openai":
            return {"presets": [], "cloned": []}
        try:
            response = requests.get(f"{self.service_url}/voices", timeout=8)
            response.raise_for_status()
            return response.json()
        except Exception as exc:
            logger.error("TTS list_voices failed: %s", exc)
            return {"presets": [], "cloned": [], "error": str(exc)}

    def health_check(self) -> bool:
        if not self.enabled:
            return False
        if self.provider == "openai":
            return bool(OPENAI_API_KEY)
        try:
            response = requests.get(f"{self.service_url}/health", timeout=5)
            if response.status_code != 200:
                return False
            data = response.json()
            return bool(data.get("model_loaded", data.get("status") == "ok"))
        except Exception as e:
            logger.error("TTS health check failed: %s", e)
            return False


_tts_service: Optional[VieNeUTTSWrapper] = None


def get_tts_service() -> VieNeUTTSWrapper:
    global _tts_service
    if _tts_service is None:
        _tts_service = VieNeUTTSWrapper()
    return _tts_service


def reload_tts_service() -> VieNeUTTSWrapper:
    global _tts_service
    _tts_service = VieNeUTTSWrapper()
    return _tts_service
