"""Admin settings API — LLM model / endpoint / API key."""

import logging
from typing import Optional

import requests
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from ..runtime_settings import get_llm_settings, get_llm_settings_public, update_llm_settings
from ..services.llm import build_chat_payload, extract_chat_content, parse_chat_completion_response

router = APIRouter(prefix="/api/settings", tags=["settings"])
logger = logging.getLogger(__name__)


class LlmSettingsUpdate(BaseModel):
    api_base: str = Field(..., min_length=1)
    model: str = Field(..., min_length=1)
    api_key: Optional[str] = None
    reasoning_effort: Optional[str] = ""
    max_tokens: int = Field(256, ge=1, le=8192)


class LlmTestRequest(BaseModel):
    prompt: str = Field("Xin chào, hãy giới thiệu ngắn gọn về bản thân.", min_length=1)


@router.get("/llm")
async def get_llm_config():
    return {"success": True, "settings": get_llm_settings_public()}


@router.put("/llm")
async def put_llm_config(request: LlmSettingsUpdate):
    try:
        settings = update_llm_settings(request.model_dump())
        logger.info("LLM settings updated | model=%s | base=%s", settings["model"], settings["api_base"])
        return {"success": True, "settings": settings, "message": "Đã lưu cấu hình LLM"}
    except ValueError as exc:
        raise HTTPException(422, str(exc))
    except Exception as exc:
        logger.exception("LLM settings update failed")
        raise HTTPException(500, str(exc))


@router.post("/llm/test")
async def test_llm_config(request: LlmTestRequest):
    settings = get_llm_settings()
    headers = {"Content-Type": "application/json"}
    if settings.get("api_key"):
        headers["Authorization"] = f"Bearer {settings['api_key']}"

    payload = build_chat_payload(
        settings["model"],
        [{"role": "user", "content": request.prompt}],
        max_tokens=int(settings.get("max_tokens") or 256),
        reasoning_effort=settings.get("reasoning_effort") or None,
        stream=False,
    )

    base = settings["api_base"].rstrip("/")
    try:
        models_resp = requests.get(f"{base}/models", headers=headers, timeout=8)
        models_ok = models_resp.status_code == 200
        models_detail = models_resp.json() if models_ok else models_resp.text[:300]
    except Exception as exc:
        models_ok = False
        models_detail = str(exc)

    try:
        chat_resp = requests.post(
            f"{base}/chat/completions",
            headers=headers,
            json=payload,
            timeout=45,
        )
        if chat_resp.status_code != 200:
            return {
                "success": False,
                "models_reachable": models_ok,
                "models_detail": models_detail,
                "message": f"Chat completion failed: HTTP {chat_resp.status_code}",
                "detail": chat_resp.text[:500],
            }
        data = parse_chat_completion_response(chat_resp)
        content = extract_chat_content(data)
        return {
            "success": True,
            "models_reachable": models_ok,
            "response": content,
            "model": settings["model"],
            "api_base": settings["api_base"],
            "message": "Kết nối LLM thành công",
        }
    except requests.exceptions.ConnectionError as exc:
        return {
            "success": False,
            "models_reachable": models_ok,
            "message": f"Không kết nối được tới {settings['api_base']}",
            "detail": str(exc),
        }
    except Exception as exc:
        logger.exception("LLM test failed")
        return {
            "success": False,
            "models_reachable": models_ok,
            "message": "Lỗi khi gọi LLM",
            "detail": str(exc),
        }
