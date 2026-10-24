"""
LLM Service - OpenAI-compatible API wrapper
"""

import json
import logging
import re
import time
from typing import Any, Dict, List, Optional

import requests

from ..runtime_settings import get_llm_settings

logger = logging.getLogger(__name__)


_REASONING_BLOCK_RE = re.compile(
    r"<(?:think|thinking|reasoning|思考)>.*?</(?:think|thinking|reasoning|思考)>",
    re.IGNORECASE | re.DOTALL,
)
_UNCLOSED_REASONING_RE = re.compile(
    r"<(?:think|thinking|reasoning|思考)>.*$",
    re.IGNORECASE | re.DOTALL,
)


def sanitize_assistant_content(text: str) -> str:
    """Return user-facing content without model chain-of-thought blocks."""
    cleaned = _REASONING_BLOCK_RE.sub("", text or "")
    cleaned = _UNCLOSED_REASONING_RE.sub("", cleaned)
    if cleaned.lstrip().lower().startswith(("thinking process:", "reasoning process:")):
        return ""
    return cleaned.strip()


def build_chat_payload(
    model: str,
    messages: List[Dict[str, str]],
    *,
    max_tokens: int = 256,
    reasoning_effort: Optional[str] = None,
    stream: bool = False,
) -> Dict[str, Any]:
    payload: Dict[str, Any] = {
        "model": model,
        "messages": messages,
        "max_tokens": max_tokens,
        "stream": stream,
    }
    if reasoning_effort:
        payload["reasoning_effort"] = reasoning_effort
    return payload


def _message_text(message: Dict[str, Any]) -> str:
    if not isinstance(message, dict):
        return ""
    content = message.get("content")
    if isinstance(content, str) and content.strip():
        return sanitize_assistant_content(content)
    # reasoning_content is private chain-of-thought metadata. Never promote it
    # to the answer when the provider omitted final user-facing content.
    return ""


def _extract_leading_json(text: str) -> Optional[Dict[str, Any]]:
    """Parse the first JSON object when extra SSE suffixes follow (9router quirk)."""
    stripped = (text or "").lstrip()
    if not stripped.startswith("{"):
        return None
    try:
        data, _ = json.JSONDecoder().raw_decode(stripped)
    except json.JSONDecodeError:
        return None
    return data if isinstance(data, dict) else None


def _parse_sse_chat_response(text: str) -> Dict[str, Any]:
    content_parts: List[str] = []
    reasoning_parts: List[str] = []
    last_chunk: Optional[Dict[str, Any]] = None

    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line.startswith("data:"):
            continue
        data = line[5:].strip()
        if not data or data == "[DONE]":
            continue
        try:
            chunk = json.loads(data)
        except json.JSONDecodeError:
            continue
        last_chunk = chunk
        for choice in chunk.get("choices") or []:
            if not isinstance(choice, dict):
                continue
            delta = choice.get("delta") or {}
            message = choice.get("message") or {}
            for key, bucket in (
                ("content", content_parts),
                ("reasoning_content", reasoning_parts),
            ):
                piece = delta.get(key)
                if piece is None and isinstance(message, dict):
                    piece = message.get(key)
                if isinstance(piece, str) and piece:
                    bucket.append(piece)

    content = "".join(content_parts).strip()
    content = sanitize_assistant_content(content)

    if last_chunk is not None:
        result = dict(last_chunk)
        choices = result.get("choices")
        if isinstance(choices, list) and choices:
            choice = dict(choices[0]) if isinstance(choices[0], dict) else {}
            message = dict(choice.get("message") or {})
            if content:
                message["content"] = content
            choice["message"] = message
            choices[0] = choice
            result["choices"] = choices
        return result

    return {
        "choices": [{"message": {"role": "assistant", "content": content}}],
    }


def parse_chat_completion_response(response: requests.Response) -> Dict[str, Any]:
    content_type = (response.headers.get("content-type") or "").lower()
    text = response.text or ""
    stripped = text.lstrip()

    # 9router may return `{...}data: [DONE]` with Content-Type: text/event-stream.
    if stripped.startswith("{"):
        data = _extract_leading_json(text)
        if data and (data.get("choices") or data.get("object") == "chat.completion"):
            return data

    if "text/event-stream" in content_type or stripped.startswith("data:"):
        sse_data = _parse_sse_chat_response(text)
        if extract_chat_content(sse_data):
            return sse_data
        data = _extract_leading_json(text)
        if data:
            return data
        return sse_data

    data = _extract_leading_json(text)
    if data:
        return data

    if stripped.startswith("data:"):
        return _parse_sse_chat_response(text)

    return response.json()


def extract_chat_content(data: Dict[str, Any]) -> str:
    choices = data.get("choices") or []
    if not choices:
        return ""
    choice = choices[0] if isinstance(choices[0], dict) else {}
    message = choice.get("message") or {}
    if isinstance(message, dict):
        text = _message_text(message)
        if text:
            return text
    delta = choice.get("delta") or {}
    if isinstance(delta, dict):
        text = _message_text(delta)
        if text:
            return text
    return ""


class LLMUnavailable(Exception):
    """The upstream LLM could not produce an answer.

    Raised instead of returning a polite apology string, so API layers can
    report ``success: false`` and clients never mistake the error text for
    a real answer (read aloud by the robot). Carries the short,
    user-displayable message in ``user_message``.
    """

    def __init__(self, user_message: str, *, detail: str = ""):
        super().__init__(user_message)
        self.user_message = user_message
        self.detail = detail


# HTTP statuses worth one more attempt: rate limits and transient upstream
# failures (9router combo backends flap between healthy and 503). 4xx other
# than 429 is a config error (bad model/key) — fail fast so ops sees it.
_RETRYABLE_STATUS = frozenset({429, 502, 503, 504})
_MAX_ATTEMPTS = 3
# Sleeps between attempts; tests monkeypatch this to (0, 0).
_RETRY_BACKOFF_S = (1.0, 2.0)


class LLMService:
    """LLM wrapper via OpenAI-compatible API"""

    def __init__(
        self,
        api_base: Optional[str] = None,
        api_key: Optional[str] = None,
        model: Optional[str] = None,
        reasoning_effort: Optional[str] = None,
    ):
        settings = get_llm_settings()
        self.api_base = api_base or settings["api_base"]
        self.api_key = api_key if api_key is not None else settings.get("api_key", "")
        self.model = model or settings["model"]
        self.reasoning_effort = reasoning_effort if reasoning_effort is not None else settings.get("reasoning_effort", "")
        # Knowledge system prompts can be large; upstream models (9router) may be slow.
        self.timeout = float(settings.get("timeout") or 120)

    @property
    def headers(self) -> Dict[str, str]:
        h = {"Content-Type": "application/json"}
        if self.api_key:
            h["Authorization"] = f"Bearer {self.api_key}"
        return h

    def generate(
        self,
        prompt: str,
        max_tokens: int = 256,
        system_prompt: Optional[str] = None,
    ) -> str:
        messages: List[Dict[str, str]] = []
        if system_prompt:
            messages.append({"role": "system", "content": system_prompt})
        messages.append({"role": "user", "content": prompt})
        return self.chat(messages, max_tokens=max_tokens)

    def chat(
        self,
        messages: List[Dict[str, str]],
        max_tokens: int = 256,
    ) -> str:
        """Return the assistant text, or raise LLMUnavailable.

        Transient upstream failures (429/502/503/504) are retried with
        backoff; anything else fails fast. Callers must catch
        LLMUnavailable — never let its ``user_message`` pass as an answer.
        """
        start_time = time.time()

        payload = build_chat_payload(
            self.model,
            messages,
            max_tokens=max_tokens,
            reasoning_effort=self.reasoning_effort or None,
            stream=False,
        )
        url = f"{self.api_base.rstrip('/')}/chat/completions"

        response = self._post_with_retry(url, payload)
        content = self._extract_content(response)

        processing_time = time.time() - start_time
        logger.info(f"LLM generation completed in {processing_time:.2f}s: '{content[:80]}...'")

        return content

    def _post_with_retry(self, url: str, payload: Dict[str, Any]):
        """POST once per attempt; retry only transient 429/5xx, else raise."""
        for attempt in range(1, _MAX_ATTEMPTS + 1):
            try:
                response = requests.post(
                    url,
                    headers=self.headers,
                    json=payload,
                    timeout=self.timeout,
                )
                response.raise_for_status()
                return response
            except requests.exceptions.HTTPError as exc:
                status = exc.response.status_code if exc.response is not None else None
                if status in _RETRYABLE_STATUS and attempt < _MAX_ATTEMPTS:
                    wait = _RETRY_BACKOFF_S[min(attempt - 1, len(_RETRY_BACKOFF_S) - 1)]
                    logger.warning(
                        "LLM transient %s (attempt %d/%d) | model=%s — retry in %.0fs",
                        status, attempt, _MAX_ATTEMPTS, self.model, wait,
                    )
                    time.sleep(wait)
                    continue
                raise LLMUnavailable(
                    "Xin lỗi, hệ thống AI đang bận. Vui lòng thử lại sau.",
                    detail=f"HTTP {status}: {exc}",
                ) from exc
            except requests.exceptions.ConnectionError as exc:
                raise LLMUnavailable(
                    "Xin lỗi, hệ thống AI đang bận. Vui lòng thử lại sau.",
                    detail=f"Cannot connect to LLM API at {self.api_base}: {exc}",
                ) from exc
            except requests.exceptions.Timeout as exc:
                raise LLMUnavailable(
                    "Xin lỗi, phản hồi hơi chậm. Bạn có thể hỏi lại không?",
                    detail=f"LLM request timeout after {self.timeout}s: {exc}",
                ) from exc
        raise AssertionError("unreachable: retry loop always returns or raises")

    def _extract_content(self, response) -> str:
        try:
            data = parse_chat_completion_response(response)
            content = extract_chat_content(data)
        except Exception as exc:
            logger.error(f"LLM error: {exc}")
            raise LLMUnavailable(
                "Có lỗi xảy ra. Xin lỗi vì sự bất tiện này.",
                detail=f"unparseable upstream response: {exc}",
            ) from exc
        if not content:
            logger.warning(
                "LLM returned reasoning without final user-facing content | model=%s",
                self.model,
            )
            raise LLMUnavailable(
                "Xin lỗi, mô hình chưa tạo được câu trả lời cuối. Vui lòng thử lại."
            )
        return content

    def health_check(self) -> bool:
        try:
            url = f"{self.api_base.rstrip('/')}/models"
            response = requests.get(url, headers=self.headers, timeout=5)
            return response.status_code == 200
        except Exception as e:
            logger.error(f"LLM health check failed: {e}")
            return False


GemmaLLM = LLMService

_llm_service: Optional[LLMService] = None


def get_llm_service() -> LLMService:
    global _llm_service
    if _llm_service is None:
        _llm_service = LLMService()
    return _llm_service


def reload_llm_service() -> LLMService:
    global _llm_service
    _llm_service = LLMService()
    return _llm_service
