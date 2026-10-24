"""Build LLM system prompt from admin instruction + all uploaded knowledge texts.

Modeled after ChatUTH admin (port 9999):
  system_prompt = system_instruction + text extracted from every ready document.
"""

from __future__ import annotations

import logging
import threading
from typing import Dict, List, Optional, Tuple

from ..config import BASE_DIR, KNOWLEDGE_SYSTEM_PROMPT_MAX_CHARS
from .schema import get_connection, init_schema

logger = logging.getLogger(__name__)

INSTRUCTION_PATH = BASE_DIR / "data" / "system_instruction.md"
_LOCK = threading.Lock()

DEFAULT_SYSTEM_INSTRUCTION = """### VAI TRÒ ###
Bạn là RobotUTH, trợ lý AI tiếng Việt thân thiện của Trường Đại học Giao thông vận tải TP.HCM (UTH).
Mục tiêu: trả lời chính xác, ngắn gọn, dễ hiểu dựa trên tri thức đã nạp.

### NGUYÊN TẮC ###
1. Ưu tiên thông tin trong phần TÀI LIỆU TRI THỨC bên dưới. Không bịa số liệu, địa điểm, quy định.
2. Nếu tài liệu không có thông tin phù hợp, nói rõ: "Tôi chưa có thông tin này trong tài liệu đã nạp."
3. Chỉ chào hỏi khi người dùng chào; câu hỏi thông tin thì trả lời trực tiếp.
4. Không dùng LaTeX. Viết tiếng Việt có dấu, câu đầy đủ.
5. Cuối câu trả lời có thể nêu tên tài liệu nguồn khi trích thông tin cụ thể.

### HƯỚNG DẪN ###
- Trả lời ngắn gọn, lịch sự, xưng hô thân thiện.
- Tổng hợp nhiều đoạn tài liệu nếu cần để đủ ý.
"""

DOCUMENTS_HEADER = "\n\n### TÀI LIỆU TRI THỨC (toàn bộ file đã tải lên) ###\n"
DOCUMENTS_FOOTER = "\n### KẾT THÚC TÀI LIỆU ###\n"


def _ensure_parent() -> None:
    INSTRUCTION_PATH.parent.mkdir(parents=True, exist_ok=True)


def get_system_instruction() -> str:
    with _LOCK:
        if INSTRUCTION_PATH.exists():
            try:
                content = INSTRUCTION_PATH.read_text(encoding="utf-8").strip()
                if content:
                    return content
            except Exception as exc:
                logger.warning("Failed to read system instruction: %s", exc)
        return DEFAULT_SYSTEM_INSTRUCTION.strip()


def set_system_instruction(content: str) -> str:
    text = (content or "").strip()
    if not text:
        raise ValueError("System instruction không được để trống")
    with _LOCK:
        _ensure_parent()
        INSTRUCTION_PATH.write_text(text + "\n", encoding="utf-8")
    return text


def reset_system_instruction() -> str:
    return set_system_instruction(DEFAULT_SYSTEM_INSTRUCTION.strip())


def _load_document_blocks() -> List[Dict]:
    """Return ready documents with full text (from full_text or reassembled chunks)."""
    init_schema()
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT id, filename, full_text, total_chars, status
                FROM knowledge_documents
                WHERE status = 'ready'
                ORDER BY id ASC
                """
            )
            documents = cur.fetchall() or []
            blocks: List[Dict] = []
            for doc in documents:
                text = (doc.get("full_text") or "").strip()
                if not text:
                    cur.execute(
                        """
                        SELECT content FROM knowledge_chunks
                        WHERE document_id = %s
                        ORDER BY chunk_index ASC
                        """,
                        (doc["id"],),
                    )
                    chunks = cur.fetchall() or []
                    text = "\n\n".join(
                        (row.get("content") or "").strip() for row in chunks if row.get("content")
                    ).strip()
                if not text:
                    continue
                blocks.append(
                    {
                        "id": doc["id"],
                        "filename": doc.get("filename") or f"doc-{doc['id']}",
                        "text": text,
                        "chars": len(text),
                    }
                )
            return blocks


def format_documents_section(blocks: List[Dict], max_chars: Optional[int] = None) -> Tuple[str, List[Dict], bool]:
    """Format document blocks into prompt section. Returns (text, included_meta, truncated)."""
    limit = max_chars if max_chars is not None else KNOWLEDGE_SYSTEM_PROMPT_MAX_CHARS
    if limit <= 0:
        limit = 120_000

    parts: List[str] = []
    included: List[Dict] = []
    used = 0
    truncated = False

    for block in blocks:
        header = f"\n## [{block['id']}] {block['filename']}\n"
        body = block["text"]
        piece = header + body + "\n"
        if used + len(piece) > limit and included:
            remaining = limit - used - len(header) - 40
            if remaining > 200:
                parts.append(header + body[:remaining] + "\n…[cắt bớt do giới hạn độ dài]\n")
                included.append(
                    {
                        "id": block["id"],
                        "filename": block["filename"],
                        "chars": remaining,
                        "truncated": True,
                    }
                )
            truncated = True
            break
        if used + len(piece) > limit and not included:
            # Single huge doc: still include truncated head
            remaining = max(200, limit - len(header) - 40)
            parts.append(header + body[:remaining] + "\n…[cắt bớt do giới hạn độ dài]\n")
            included.append(
                {
                    "id": block["id"],
                    "filename": block["filename"],
                    "chars": remaining,
                    "truncated": True,
                }
            )
            truncated = True
            break
        parts.append(piece)
        included.append(
            {
                "id": block["id"],
                "filename": block["filename"],
                "chars": block["chars"],
                "truncated": False,
            }
        )
        used += len(piece)

    return "".join(parts), included, truncated


def build_system_prompt(max_chars: Optional[int] = None) -> Dict:
    """
    Full system prompt for the model:
      system_instruction + all uploaded document texts.
    """
    instruction = get_system_instruction()
    blocks = _load_document_blocks()
    docs_budget = None
    if max_chars is not None and max_chars > 0:
        # Reserve instruction + headers space
        overhead = len(instruction) + len(DOCUMENTS_HEADER) + len(DOCUMENTS_FOOTER) + 64
        docs_budget = max(1000, max_chars - overhead)
    elif KNOWLEDGE_SYSTEM_PROMPT_MAX_CHARS > 0:
        overhead = len(instruction) + len(DOCUMENTS_HEADER) + len(DOCUMENTS_FOOTER) + 64
        docs_budget = max(1000, KNOWLEDGE_SYSTEM_PROMPT_MAX_CHARS - overhead)

    docs_text, included, truncated = format_documents_section(blocks, max_chars=docs_budget)

    if docs_text.strip():
        prompt = instruction.rstrip() + DOCUMENTS_HEADER + docs_text + DOCUMENTS_FOOTER
    else:
        prompt = instruction.rstrip() + "\n\n(Chưa có tài liệu tri thức nào được nạp.)\n"

    return {
        "system_prompt": prompt,
        "instruction_chars": len(instruction),
        "documents_chars": len(docs_text),
        "total_chars": len(prompt),
        "document_count": len(included),
        "documents": included,
        "truncated": truncated,
        "instruction_path": str(INSTRUCTION_PATH),
    }


def get_system_prompt_text(max_chars: Optional[int] = None) -> str:
    return build_system_prompt(max_chars=max_chars)["system_prompt"]
