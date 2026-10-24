"""Runtime LLM settings persisted on disk (overrides env defaults)."""

import json
import logging
import threading
from typing import Any, Dict

from .config import (
    BASE_DIR,
    OPENAI_API_BASE,
    OPENAI_API_KEY,
    OPENAI_MODEL,
    REASONING_EFFORT,
    TTS_PROVIDER,
    TTS_VOICE,
)

logger = logging.getLogger(__name__)

SETTINGS_PATH = BASE_DIR / "data" / "runtime_settings.json"
_LOCK = threading.Lock()
_MASK = "********"

_DEFAULTS: Dict[str, Any] = {
    "api_base": OPENAI_API_BASE,
    "api_key": OPENAI_API_KEY,
    "model": OPENAI_MODEL,
    "reasoning_effort": REASONING_EFFORT,
    "max_tokens": 256,
}


def _ensure_parent() -> None:
    SETTINGS_PATH.parent.mkdir(parents=True, exist_ok=True)


def _load_raw() -> Dict[str, Any]:
    if not SETTINGS_PATH.exists():
        return {}
    try:
        with SETTINGS_PATH.open("r", encoding="utf-8") as handle:
            data = json.load(handle)
        return data if isinstance(data, dict) else {}
    except Exception as exc:
        logger.warning("Failed to read runtime settings: %s", exc)
        return {}


def _save_raw(data: Dict[str, Any]) -> None:
    _ensure_parent()
    with SETTINGS_PATH.open("w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)


def get_llm_settings() -> Dict[str, Any]:
    """Effective LLM config: saved overrides merged on env defaults."""
    with _LOCK:
        saved = _load_raw().get("llm", {})
    merged = {**_DEFAULTS, **{k: v for k, v in saved.items() if v is not None}}
    return merged


def get_llm_settings_public() -> Dict[str, Any]:
    """Public view for admin UI — API key is masked."""
    settings = get_llm_settings()
    api_key = settings.get("api_key") or ""
    return {
        "api_base": settings["api_base"],
        "model": settings["model"],
        "reasoning_effort": settings.get("reasoning_effort") or "",
        "max_tokens": int(settings.get("max_tokens") or 256),
        "api_key_set": bool(api_key),
        "api_key_masked": _mask_api_key(api_key),
        "source": "runtime" if _load_raw().get("llm") else "env",
        "settings_path": str(SETTINGS_PATH),
    }


def update_llm_settings(payload: Dict[str, Any]) -> Dict[str, Any]:
    """Persist LLM overrides. Empty api_key or mask keeps existing key."""
    with _LOCK:
        raw = _load_raw()
        current = {**_DEFAULTS, **raw.get("llm", {})}

        api_base = (payload.get("api_base") or current["api_base"]).strip()
        model = (payload.get("model") or current["model"]).strip()
        reasoning_effort = (payload.get("reasoning_effort") if "reasoning_effort" in payload else current["reasoning_effort"]) or ""
        max_tokens = int(payload.get("max_tokens") if payload.get("max_tokens") is not None else current.get("max_tokens", 256))

        api_key_in = payload.get("api_key")
        if api_key_in is None or api_key_in == "" or api_key_in == _MASK:
            api_key = current.get("api_key", "")
        else:
            api_key = str(api_key_in).strip()

        if not api_base:
            raise ValueError("api_base is required")
        if not model:
            raise ValueError("model is required")
        if max_tokens < 1 or max_tokens > 8192:
            raise ValueError("max_tokens must be between 1 and 8192")

        raw["llm"] = {
            "api_base": api_base,
            "api_key": api_key,
            "model": model,
            "reasoning_effort": reasoning_effort,
            "max_tokens": max_tokens,
        }
        _save_raw(raw)

    _reload_services()
    return get_llm_settings_public()


def _mask_api_key(api_key: str) -> str:
    if not api_key:
        return ""
    if len(api_key) <= 8:
        return _MASK
    return f"{api_key[:4]}...{api_key[-4:]}"


def get_tts_settings() -> Dict[str, Any]:
    with _LOCK:
        saved = _load_raw().get("tts", {})
    return {
        "provider": saved.get("provider") or TTS_PROVIDER,
        "default_voice": (saved.get("default_voice") or TTS_VOICE).strip() or TTS_VOICE,
    }


def get_tts_settings_public() -> Dict[str, Any]:
    settings = get_tts_settings()
    return {
        **settings,
        "source": "runtime" if _load_raw().get("tts") else "env",
        "settings_path": str(SETTINGS_PATH),
    }


def update_tts_settings(payload: Dict[str, Any]) -> Dict[str, Any]:
    current = get_tts_settings()
    provider = (payload.get("provider") or current["provider"]).strip().lower()
    voice = (payload.get("default_voice") or current["default_voice"]).strip()
    if provider not in ("vieneu", "openai"):
        raise ValueError("provider must be 'vieneu' or 'openai'")
    if not voice:
        raise ValueError("default_voice is required")
    if len(voice) > 64:
        raise ValueError("default_voice is too long")

    with _LOCK:
        raw = _load_raw()
        raw["tts"] = {"provider": provider, "default_voice": voice}
        _save_raw(raw)

    _reload_tts_service()
    return get_tts_settings_public()


def _reload_services() -> None:
    from .services.llm import reload_llm_service

    reload_llm_service()
    _reload_tts_service()
    try:
        from .services.conversation import reset_pipeline

        reset_pipeline()
    except Exception as exc:
        logger.warning("Pipeline reset after settings change failed: %s", exc)


def _reload_tts_service() -> None:
    try:
        from .services.tts import reload_tts_service

        reload_tts_service()
    except Exception as exc:
        logger.warning("TTS service reload failed: %s", exc)
