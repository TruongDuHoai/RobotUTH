"""LLMService failure contract: retry transient 5xx, raise LLMUnavailable.

Regression test for the U4 bug where an upstream 503 came back as
``success: true`` with a polite apology string that the robot read aloud.
"""

import json

import pytest
import requests

from server.services import llm
from server.services.llm import LLMService, LLMUnavailable


def make_service(monkeypatch):
    monkeypatch.setattr(llm, "_RETRY_BACKOFF_S", (0, 0))
    return LLMService(api_base="http://llm-test/v1", api_key="k", model="robot")


def json_response(payload, status=200):
    resp = requests.Response()
    resp.status_code = status
    resp.headers["Content-Type"] = "application/json"
    resp._content = json.dumps(payload).encode()
    resp.url = "http://llm-test/v1/chat/completions"
    return resp


def ok_response(text="Xin chào!"):
    return json_response({"choices": [{"message": {"role": "assistant", "content": text}}]})


def test_success_returns_content(monkeypatch):
    svc = make_service(monkeypatch)
    monkeypatch.setattr(llm.requests, "post", lambda *a, **k: ok_response())
    assert svc.chat([{"role": "user", "content": "hi"}]) == "Xin chào!"


def test_retries_503_then_succeeds(monkeypatch):
    svc = make_service(monkeypatch)
    calls = []

    def fake_post(*args, **kwargs):
        calls.append(1)
        if len(calls) < 3:
            return json_response({"error": "busy"}, status=503)
        return ok_response()

    monkeypatch.setattr(llm.requests, "post", fake_post)
    assert svc.chat([{"role": "user", "content": "hi"}]) == "Xin chào!"
    assert len(calls) == 3


def test_404_fails_fast_without_retry(monkeypatch):
    svc = make_service(monkeypatch)
    calls = []

    def fake_post(*args, **kwargs):
        calls.append(1)
        return json_response({"error": {"code": "model_not_found"}}, status=404)

    monkeypatch.setattr(llm.requests, "post", fake_post)
    with pytest.raises(LLMUnavailable) as excinfo:
        svc.chat([{"role": "user", "content": "hi"}])
    assert len(calls) == 1
    assert excinfo.value.user_message


def test_gives_up_after_max_attempts(monkeypatch):
    svc = make_service(monkeypatch)
    calls = []

    def fake_post(*args, **kwargs):
        calls.append(1)
        return json_response({"error": "down"}, status=503)

    monkeypatch.setattr(llm.requests, "post", fake_post)
    with pytest.raises(LLMUnavailable):
        svc.chat([{"role": "user", "content": "hi"}])
    assert len(calls) == 3


def test_timeout_raises(monkeypatch):
    svc = make_service(monkeypatch)

    def fake_post(*args, **kwargs):
        raise requests.exceptions.Timeout("slow")

    monkeypatch.setattr(llm.requests, "post", fake_post)
    with pytest.raises(LLMUnavailable) as excinfo:
        svc.chat([{"role": "user", "content": "hi"}])
    assert "chậm" in excinfo.value.user_message


def test_empty_content_raises_instead_of_apology_string(monkeypatch):
    svc = make_service(monkeypatch)
    monkeypatch.setattr(
        llm.requests, "post", lambda *a, **k: ok_response(text="   ")
    )
    with pytest.raises(LLMUnavailable):
        svc.chat([{"role": "user", "content": "hi"}])
