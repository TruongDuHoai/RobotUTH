#!/usr/bin/env python3
"""
Native GPU OCR server — PaddleOCR PP-OCRv6 medium (det + rec).

Models (Hugging Face / PaddleX):
  - PaddlePaddle/PP-OCRv6_medium_det
  - PaddlePaddle/PP-OCRv6_medium_rec

Endpoints:
  GET  /health
  POST /ocr      multipart file (pdf/png/jpg/jpeg/webp/tif)
  POST /ocr/pdf  alias for PDF upload (same handler)

Used by knowledge parser when PyPDF2 extracts too little text
(OCR_BACKEND_URL, default host port 8003).
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import os
import tempfile
import threading
import time
from pathlib import Path
from typing import Any, List, Sequence, Tuple

import uvicorn
from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger("ocr_gpu_server")

DET_MODEL = os.getenv("OCR_DET_MODEL", "PP-OCRv6_medium_det")
REC_MODEL = os.getenv("OCR_REC_MODEL", "PP-OCRv6_medium_rec")
DEFAULT_DEVICE = os.getenv("OCR_DEVICE", "gpu:0")
PDF_RENDER_SCALE = float(os.getenv("OCR_PDF_SCALE", "1.5"))
MAX_PAGES = int(os.getenv("OCR_MAX_PAGES", "80"))
MAX_UPLOAD_MB = int(os.getenv("OCR_MAX_UPLOAD_MB", "25"))
MAX_UPLOAD_BYTES = MAX_UPLOAD_MB * 1024 * 1024
USE_TEXTLINE_ORI = os.getenv("OCR_USE_TEXTLINE_ORIENTATION", "false").lower() == "true"
MAX_PAGE_WIDTH = int(os.getenv("OCR_MAX_PAGE_WIDTH", "2000"))

IMAGE_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp", ".tif", ".tiff", ".bmp"}
PDF_SUFFIXES = {".pdf"}

app = FastAPI(title="Robotuth OCR GPU - PP-OCRv6 medium")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

OCR_ENGINE = None
DEVICE = DEFAULT_DEVICE
# Serialize GPU inference on T400 4GB (also protects against concurrent uploads).
_INFER_LOCK = threading.Lock()
_INFER_BUSY = False


def _resolve_device(requested: str) -> str:
    """Prefer GPU when available; fall back to CPU."""
    req = (requested or "gpu:0").strip().lower()
    if req in ("cpu", "cpu:0"):
        return "cpu"
    try:
        import paddle

        if paddle.device.is_compiled_with_cuda() and paddle.device.cuda.device_count() > 0:
            return req if req.startswith("gpu") else "gpu:0"
    except Exception as exc:
        logger.warning("Paddle CUDA probe failed: %s", exc)
    logger.warning("CUDA not available — using CPU for OCR")
    return "cpu"


def load_engine(device: str) -> None:
    global OCR_ENGINE, DEVICE
    from paddleocr import PaddleOCR

    DEVICE = _resolve_device(device)
    logger.info(
        "Loading PaddleOCR det=%s rec=%s device=%s textline_ori=%s",
        DET_MODEL,
        REC_MODEL,
        DEVICE,
        USE_TEXTLINE_ORI,
    )
    # Optional modules off by default to save VRAM on T400 4GB.
    OCR_ENGINE = PaddleOCR(
        text_detection_model_name=DET_MODEL,
        text_recognition_model_name=REC_MODEL,
        use_doc_orientation_classify=False,
        use_doc_unwarping=False,
        use_textline_orientation=USE_TEXTLINE_ORI,
        device=DEVICE,
    )
    logger.info("PP-OCRv6 medium ready on %s", DEVICE)


def _as_dict(obj: Any) -> dict:
    if obj is None:
        return {}
    if isinstance(obj, dict):
        return obj
    # PaddleX Result: prefer .json property (dict or {"res": ...})
    if hasattr(obj, "json"):
        try:
            data = obj.json
            if callable(data):
                data = data()
            if isinstance(data, dict):
                if "res" in data and isinstance(data["res"], dict):
                    return data["res"]
                return data
        except Exception:
            pass
    if hasattr(obj, "__dict__"):
        return {k: v for k, v in vars(obj).items() if not k.startswith("_")}
    return {}


def _collect_texts(obj: Any) -> List[str]:
    """Extract recognition lines from PaddleOCR 3.x result items."""
    texts: List[str] = []

    def add_line(value: Any) -> None:
        if value is None:
            return
        if isinstance(value, str):
            s = value.strip()
            if s:
                texts.append(s)
            return
        if isinstance(value, (list, tuple)):
            for item in value:
                add_line(item)

    if obj is None:
        return texts

    for attr in ("rec_texts", "texts", "text"):
        if hasattr(obj, attr):
            add_line(getattr(obj, attr))

    data = _as_dict(obj)
    for key in ("rec_texts", "texts", "text", "rec_text"):
        if key in data:
            add_line(data[key])

    for key in ("ocr_result", "parsing_res_list", "overall_ocr_res"):
        if key in data:
            nested = data[key]
            if isinstance(nested, dict):
                add_line(nested.get("rec_texts") or nested.get("texts"))
            elif isinstance(nested, list):
                for item in nested:
                    texts.extend(_collect_texts(item))

    # Legacy list-of-lines: [[[box], (text, score)], ...]
    if isinstance(obj, (list, tuple)) and obj and not texts:
        for line in obj:
            try:
                if isinstance(line, (list, tuple)) and len(line) >= 2:
                    maybe = line[1]
                    if isinstance(maybe, (list, tuple)) and maybe:
                        add_line(maybe[0])
                    else:
                        add_line(maybe)
            except Exception:
                continue

    return texts


def _has_detection_boxes(obj: Any) -> bool:
    data = _as_dict(obj)
    for key in ("dt_polys", "rec_boxes", "boxes", "dt_boxes"):
        val = data.get(key)
        if isinstance(val, (list, tuple)) and len(val) > 0:
            return True
        if hasattr(obj, key):
            attr = getattr(obj, key)
            if isinstance(attr, (list, tuple)) and len(attr) > 0:
                return True
    return False


def ocr_image_path(path: str) -> Tuple[str, int, bool]:
    """Returns (text, line_count, had_detections)."""
    if OCR_ENGINE is None:
        raise RuntimeError("OCR engine not loaded")
    with _INFER_LOCK:
        result = OCR_ENGINE.predict(path)
    lines: List[str] = []
    had_det = False
    if result is None:
        return "", 0, False
    items: Sequence[Any]
    if isinstance(result, (list, tuple)):
        items = result
    else:
        items = [result]
    for item in items:
        lines.extend(_collect_texts(item))
        if _has_detection_boxes(item):
            had_det = True
    cleaned: List[str] = []
    for line in lines:
        if not cleaned or cleaned[-1] != line:
            cleaned.append(line)
    return "\n".join(cleaned).strip(), len(cleaned), had_det


def _resize_if_needed(pil_image: Any) -> Any:
    w, h = pil_image.size
    if w <= MAX_PAGE_WIDTH:
        return pil_image
    ratio = MAX_PAGE_WIDTH / float(w)
    new_size = (MAX_PAGE_WIDTH, max(1, int(h * ratio)))
    return pil_image.resize(new_size)


def ocr_pdf_bytes(pdf_bytes: bytes) -> Tuple[str, int, int, bool]:
    """Stream page-by-page: render → OCR → discard (low RAM)."""
    import pypdfium2 as pdfium

    pdf = pdfium.PdfDocument(pdf_bytes)
    n_pages = len(pdf)
    if n_pages > MAX_PAGES:
        logger.warning("PDF has %s pages; truncating to OCR_MAX_PAGES=%s", n_pages, MAX_PAGES)
        n_pages = MAX_PAGES

    page_texts: List[str] = []
    total_lines = 0
    any_det = False
    try:
        for idx in range(n_pages):
            page = pdf[idx]
            try:
                bitmap = page.render(scale=PDF_RENDER_SCALE)
                pil_image = bitmap.to_pil()
                if pil_image.mode not in ("RGB", "L"):
                    pil_image = pil_image.convert("RGB")
                pil_image = _resize_if_needed(pil_image)
                with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as tmp:
                    tmp_path = tmp.name
                    pil_image.save(tmp_path, format="PNG")
                try:
                    text, n_lines, had_det = ocr_image_path(tmp_path)
                    total_lines += n_lines
                    any_det = any_det or had_det
                    if text:
                        page_texts.append(text)
                    logger.info(
                        "OCR page %s/%s lines=%s chars=%s det=%s",
                        idx + 1,
                        n_pages,
                        n_lines,
                        len(text),
                        had_det,
                    )
                finally:
                    try:
                        Path(tmp_path).unlink(missing_ok=True)
                    except Exception:
                        pass
                    del pil_image
            finally:
                page.close()
    finally:
        pdf.close()

    return "\n\n".join(page_texts).strip(), n_pages, total_lines, any_det


def ocr_image_bytes(image_bytes: bytes, suffix: str = ".png") -> Tuple[str, int, bool]:
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
        tmp.write(image_bytes)
        tmp_path = tmp.name
    try:
        return ocr_image_path(tmp_path)
    finally:
        try:
            Path(tmp_path).unlink(missing_ok=True)
        except Exception:
            pass


def _run_ocr_job(data: bytes, name: str) -> dict:
    """Sync worker: runs off the event loop via asyncio.to_thread."""
    suffix = Path(name).suffix.lower() or ".bin"
    if suffix in PDF_SUFFIXES or name.endswith(".pdf") or data[:4] == b"%PDF":
        text, pages, lines, had_det = ocr_pdf_bytes(data)
        kind = "pdf"
    elif suffix in IMAGE_SUFFIXES:
        text, lines, had_det = ocr_image_bytes(
            data, suffix=suffix if suffix in IMAGE_SUFFIXES else ".png"
        )
        pages = 1
        kind = "image"
    elif data[:4] == b"%PDF":
        text, pages, lines, had_det = ocr_pdf_bytes(data)
        kind = "pdf"
    else:
        text, lines, had_det = ocr_image_bytes(data, suffix=".png")
        pages = 1
        kind = "image"

    warning = None
    success = True
    if pages > 0 and lines == 0:
        if had_det:
            warning = "detections_present_but_no_text_extracted"
            success = False
        else:
            warning = "no_text_detected"
    return {
        "success": success,
        "text": text,
        "kind": kind,
        "pages": pages,
        "lines": lines,
        "chars": len(text),
        "warning": warning,
    }


@app.on_event("startup")
async def startup():
    device = getattr(app.state, "device", DEFAULT_DEVICE)
    try:
        # Load models off event loop so startup doesn't freeze health forever
        # if we later add readiness probes during load.
        await asyncio.to_thread(load_engine, device)
    except Exception as exc:
        logger.error("OCR model load failed: %s", exc, exc_info=True)
        raise


@app.get("/health")
async def health():
    cuda = False
    try:
        import paddle

        cuda = bool(paddle.device.is_compiled_with_cuda() and paddle.device.cuda.device_count() > 0)
    except Exception:
        pass
    return {
        "status": "ok" if OCR_ENGINE is not None else "loading",
        "model_loaded": OCR_ENGINE is not None,
        "busy": _INFER_BUSY,
        "det_model": DET_MODEL,
        "rec_model": REC_MODEL,
        "device": DEVICE,
        "cuda_available": cuda,
        "max_upload_mb": MAX_UPLOAD_MB,
        "hf_det": "https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_det",
        "hf_rec": "https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec",
    }


async def _read_upload(file: UploadFile) -> Tuple[bytes, str]:
    # Stream with size cap (do not trust Content-Length alone).
    chunks: List[bytes] = []
    total = 0
    while True:
        chunk = await file.read(1024 * 1024)
        if not chunk:
            break
        total += len(chunk)
        if total > MAX_UPLOAD_BYTES:
            raise HTTPException(
                status_code=413,
                detail=f"File too large. Max: {MAX_UPLOAD_MB}MB",
            )
        chunks.append(chunk)
    data = b"".join(chunks)
    if not data:
        raise HTTPException(status_code=400, detail="Empty upload")
    name = (file.filename or "upload.bin").lower()
    return data, name


@app.post("/ocr")
@app.post("/ocr/pdf")
async def ocr_endpoint(file: UploadFile = File(...)):
    global _INFER_BUSY
    if OCR_ENGINE is None:
        raise HTTPException(status_code=503, detail="OCR engine not loaded")

    # Reject concurrent jobs early (T400 cannot run multi-OCR safely).
    with _INFER_LOCK:
        if _INFER_BUSY:
            raise HTTPException(
                status_code=503,
                detail="OCR busy — another job is running. Retry later.",
                headers={"Retry-After": "10"},
            )
        _INFER_BUSY = True

    start = time.time()
    try:
        data, name = await _read_upload(file)
        result = await asyncio.to_thread(_run_ocr_job, data, name)
    except HTTPException:
        raise
    except Exception as exc:
        logger.error("OCR failed for %s: %s", getattr(file, "filename", "?"), exc, exc_info=True)
        raise HTTPException(status_code=500, detail=f"OCR failed: {exc}") from exc
    finally:
        with _INFER_LOCK:
            _INFER_BUSY = False

    elapsed = time.time() - start
    logger.info(
        "OCR done file=%s kind=%s pages=%s lines=%s chars=%s success=%s time=%.2fs",
        name,
        result.get("kind"),
        result.get("pages"),
        result.get("lines"),
        result.get("chars"),
        result.get("success"),
        elapsed,
    )
    return JSONResponse(
        {
            **result,
            "filename": file.filename,
            "processing_time_seconds": round(elapsed, 3),
            "det_model": DET_MODEL,
            "rec_model": REC_MODEL,
            "device": DEVICE,
        }
    )


def main():
    parser = argparse.ArgumentParser(description="Robotuth PP-OCRv6 medium GPU server")
    # Default bind all interfaces for Docker bridge access; firewall recommended.
    parser.add_argument("--host", default=os.getenv("OCR_BIND_HOST", "0.0.0.0"))
    parser.add_argument("--port", type=int, default=int(os.getenv("OCR_PORT", "8003")))
    parser.add_argument("--device", default=DEFAULT_DEVICE, help="gpu:0 or cpu")
    args = parser.parse_args()

    app.state.device = args.device
    logger.info("Starting OCR GPU server on %s:%s device=%s", args.host, args.port, args.device)
    uvicorn.run(app, host=args.host, port=args.port, log_level="info")


if __name__ == "__main__":
    main()
