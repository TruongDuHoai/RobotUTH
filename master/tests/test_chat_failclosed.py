"""Fail-closed contract: an LLM outage must never look like an answer.

Covers the U4 fix — LLMUnavailable from any path surfaces as
``success: false`` (or a marked fallback), never as a readable reply.
"""

from fastapi import FastAPI
from fastapi.testclient import TestClient

from server.api import asr as asr_api
from server.api import chat as chat_api
from server.knowledge import search as search_mod
from server.services.llm import LLMUnavailable


def make_chat_client(monkeypatch, generate):
    class DeadService:
        def generate(self, *args, **kwargs):
            return generate()

    dead_search = type(
        "DeadSearch",
        (),
        {"query_with_system_prompt": lambda self, *a, **k: {"success": False}},
    )()

    monkeypatch.setattr(chat_api, "get_llm_service", lambda: DeadService())
    monkeypatch.setattr(chat_api, "get_search_service", lambda: dead_search)
    monkeypatch.setattr(
        chat_api, "build_system_prompt", lambda: {"system_prompt": "sys"}
    )
    app = FastAPI()
    app.include_router(chat_api.router)
    return TestClient(app)


def test_chat_llm_unavailable_is_fail_closed(monkeypatch):
    def boom():
        raise LLMUnavailable(
            "Xin lỗi, hệ thống AI đang bận. Vui lòng thử lại sau.",
            detail="HTTP 503",
        )

    client = make_chat_client(monkeypatch, boom)
    resp = client.post("/api/chat/query", json={"query": "xin chào"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["success"] is False
    assert body["response"] == ""
    assert "bận" in body["message"]


def test_chat_unexpected_error_is_fail_closed(monkeypatch):
    def boom():
        raise RuntimeError("null pointer in prompt builder")

    client = make_chat_client(monkeypatch, boom)
    body = client.post("/api/chat/query", json={"query": "xin chào"}).json()
    assert body["success"] is False
    assert body["response"] == ""


def test_rag_llm_outage_falls_back_to_extractive(monkeypatch):
    svc = search_mod.KnowledgeSearchService.__new__(
        search_mod.KnowledgeSearchService
    )
    svc.search = lambda query, top_k=5: [
        {
            "chunk_id": 1,
            "document_id": 2,
            "filename": "f.pdf",
            "content": "Thư viện mở cửa 7 giờ.",
            "score": 0.9,
        }
    ]

    class DeadService:
        def generate(self, *args, **kwargs):
            raise LLMUnavailable("busy", detail="HTTP 503")

    monkeypatch.setattr(search_mod, "get_llm_service", lambda: DeadService())
    out = svc.query_with_rag("thư viện mở cửa mấy giờ")
    assert out["success"] is True
    assert "Thư viện mở cửa 7 giờ." in out["answer"]
    assert "f.pdf" in out["answer"]


def test_asr_correct_llm_outage_fails_open(monkeypatch):
    class DeadService:
        timeout = 5

        def chat(self, *args, **kwargs):
            raise LLMUnavailable("busy", detail="HTTP 503")

    monkeypatch.setattr(asr_api, "ASR_LLM_CORRECT", True)
    monkeypatch.setattr(
        "server.services.llm.get_llm_service", lambda: DeadService()
    )
    text, method = asr_api._llm_correct_text("xin chào")
    assert method == "dictionary_fallback"
    assert "xin chào" in text
