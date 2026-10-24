"""File parsing helpers for knowledge ingestion.

PDF path:
  1. PyPDF2 text extract (per-page)
  2. If text density is low (scanned / hybrid) and OCR_BACKEND_URL is set,
     POST the PDF to host PP-OCRv6 medium service (deploy/gpu/ocr_gpu_server.py).
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import List, Tuple

import requests

from ..config import OCR_BACKEND_URL, OCR_MIN_TEXT_CHARS, OCR_TIMEOUT_SECONDS

logger = logging.getLogger(__name__)

# Fail fast when OCR host is down; keep long read timeout for multi-page jobs.
OCR_CONNECT_TIMEOUT = float(__import__("os").getenv("OCR_CONNECT_TIMEOUT", "5"))


class ParserService:
    SUPPORTED_EXTENSIONS = {".pdf", ".docx", ".txt"}

    def parse_bytes(self, filename: str, content: bytes) -> str:
        suffix = Path(filename).suffix.lower()
        if suffix == ".txt":
            return self._parse_txt(content)
        if suffix == ".pdf":
            return self._parse_pdf(content, filename=filename)
        if suffix == ".docx":
            return self._parse_docx(content)
        raise ValueError(f"Unsupported file type: {suffix}")

    def _parse_txt(self, content: bytes) -> str:
        for encoding in ("utf-8", "cp1258", "utf-16"):
            try:
                return content.decode(encoding).strip()
            except UnicodeDecodeError:
                continue
        return content.decode("utf-8", errors="ignore").strip()

    def _needs_ocr(self, page_texts: List[str], combined: str) -> bool:
        """Density-aware trigger: total short OR many sparse pages."""
        n_pages = max(len(page_texts), 1)
        total = len(combined.strip())
        if total < OCR_MIN_TEXT_CHARS:
            return True
        # Hybrid scanned PDFs: cover/page numbers extract text but body is image.
        chars_per_page = total / n_pages
        if chars_per_page < OCR_MIN_TEXT_CHARS:
            return True
        sparse = sum(1 for p in page_texts if len((p or "").strip()) < OCR_MIN_TEXT_CHARS)
        if n_pages >= 2 and sparse / n_pages >= 0.5:
            return True
        return False

    def _parse_pdf(self, content: bytes, filename: str = "document.pdf") -> str:
        page_texts, text = self._parse_pdf_pypdf2_pages(content)
        if not self._needs_ocr(page_texts, text):
            return text

        if not OCR_BACKEND_URL:
            logger.warning(
                "PDF needs OCR (chars=%s pages=%s threshold=%s) but OCR_BACKEND_URL unset; "
                "using PyPDF2 only (filename=%s)",
                len(text),
                len(page_texts),
                OCR_MIN_TEXT_CHARS,
                filename,
            )
            return text

        ocr_text, ocr_meta = self._ocr_via_backend(content, filename=filename)
        if ocr_text and len(ocr_text) > len(text):
            logger.info(
                "OCR fallback used for %s: pypdf2=%s chars -> ocr=%s chars meta=%s",
                filename,
                len(text),
                len(ocr_text),
                ocr_meta,
            )
            return ocr_text

        if not text and not ocr_text:
            logger.warning(
                "PDF produced empty text after PyPDF2 + OCR: %s meta=%s",
                filename,
                ocr_meta,
            )
        elif ocr_meta.get("error") or ocr_meta.get("warning"):
            logger.warning(
                "OCR weak/failed for %s; keeping PyPDF2 (%s chars). meta=%s",
                filename,
                len(text),
                ocr_meta,
            )
        return ocr_text or text

    def _parse_pdf_pypdf2_pages(self, content: bytes) -> Tuple[List[str], str]:
        try:
            from PyPDF2 import PdfReader
        except ImportError as exc:
            raise RuntimeError("PyPDF2 is required to parse PDF files") from exc

        import io

        reader = PdfReader(io.BytesIO(content))
        pages: List[str] = []
        for page in reader.pages:
            pages.append(page.extract_text() or "")
        combined = "\n\n".join(pages).strip()
        return pages, combined

    def _ocr_via_backend(self, content: bytes, filename: str) -> Tuple[str, dict]:
        url = f"{OCR_BACKEND_URL}/ocr"
        meta: dict = {"ocr_attempted": True}
        try:
            response = requests.post(
                url,
                files={"file": (filename or "document.pdf", content, "application/pdf")},
                timeout=(OCR_CONNECT_TIMEOUT, OCR_TIMEOUT_SECONDS),
            )
            response.raise_for_status()
            payload = response.json()
        except requests.RequestException as exc:
            logger.error("OCR backend request failed (%s): %s", url, exc)
            meta["error"] = str(exc)
            meta["ocr_ok"] = False
            return "", meta
        except ValueError as exc:
            logger.error("OCR backend returned non-JSON: %s", exc)
            meta["error"] = "non_json_response"
            meta["ocr_ok"] = False
            return "", meta

        text = ""
        if isinstance(payload, dict):
            text = (payload.get("text") or "").strip()
            meta["pages"] = payload.get("pages")
            meta["lines"] = payload.get("lines")
            meta["warning"] = payload.get("warning")
            success = payload.get("success", True)
            meta["ocr_ok"] = bool(success and text)
            if not success and not text:
                logger.error("OCR backend reported failure: %s", payload)
                meta["error"] = payload.get("warning") or "ocr_failed"
        else:
            meta["ocr_ok"] = False
            meta["error"] = "unexpected_payload"
        return text, meta

    def _parse_docx(self, content: bytes) -> str:
        try:
            from docx import Document
        except ImportError as exc:
            raise RuntimeError("python-docx is required to parse DOCX files") from exc

        import io

        document = Document(io.BytesIO(content))
        paragraphs = [p.text.strip() for p in document.paragraphs if p.text.strip()]
        return "\n\n".join(paragraphs).strip()


def build_qna_text(question: str, answer: str) -> str:
    return (
        "Loại tài liệu: Q&A RobotUTH\n\n"
        f"Câu hỏi: {question.strip()}\n"
        f"Trả lời: {answer.strip()}\n"
    )
