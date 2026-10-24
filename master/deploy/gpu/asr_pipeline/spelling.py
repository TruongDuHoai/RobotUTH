"""
Sửa lỗi chính tả từng từ + áp dụng quy tắc ngôn ngữ cho tiếng Việt.

Hai lớp sửa lỗi (bổ sung cho từ điển phrase-map trong asr_postprocess.py):
  1. Spelling correction (từ điển): với mỗi từ KHÔNG có trong từ điển, sinh các
     candidate bằng cách áp dụng các cặp ký tự dễ nhầm (confusion pairs) đặc trưng
     tiếng Việt (ph↔f, s↔x, d↔gi↔v, ch↔tr↔c, n↔l, i↔y...). Chọn candidate nằm
     trong từ điển và gần từ gốc nhất (edit distance nhỏ nhất).
  2. Language rules: chuẩn hóa unicode NFC, gộp khoảng trắng, sửa dấu câu lặp,
     viết hoa chữ cái đầu câu, chuẩn hóa khoảng trắng quanh dấu câu.

Hoàn toàn dùng stdlib để dễ test và chạy ngay cả khi chưa cài torch/nemo.
"""
from __future__ import annotations

import logging
import re
import unicodedata
from pathlib import Path
from typing import Dict, List, Optional, Tuple

logger = logging.getLogger("asr_pipeline.spelling")

_DEFAULT_WORDLIST = Path(__file__).resolve().parent / "data" / "vi_wordlist.txt"

# Cặp ký tự / âm tiết dễ nhầm trong ASR tiếng Việt (Latin script).
# Thứ tự ưu tiên: digraph trước, rồi đơn lẻ.
CONFUSION_PAIRS: List[Tuple[str, str]] = [
    ("gi", "d"), ("gi", "v"), ("d", "v"), ("d", "gi"), ("v", "gi"), ("v", "d"),
    ("ph", "f"), ("f", "ph"),
    ("tr", "ch"), ("ch", "tr"), ("tr", "c"), ("c", "tr"), ("ch", "c"), ("c", "ch"),
    ("n", "l"), ("l", "n"),
    ("s", "x"), ("x", "s"),
    ("i", "y"), ("y", "i"),
    ("ươ", "ư"), ("ư", "ươ"),
    ("o", "ô"), ("ô", "o"),
    ("a", "ă"), ("ă", "a"),
    ("d", "đ"), ("đ", "d"),
    ("ngh", "ng"), ("ng", "ngh"),
    ("nh", "n"), ("n", "nh"),
    ("th", "t"), ("t", "th"),
    ("qu", "w"), ("w", "qu"),
]

_SPACE = re.compile(r"\s+")
_PUNCT_REPEAT = re.compile(r"([?.!,;:]){2,}")
_TRAILING_SPACE_BEFORE_PUNCT = re.compile(r"\s+([?.!,;:])")
_SENTENCE_START = re.compile(r"(^|[.?!]\s+)([a-zàáảãạăắằẳẵặâấầẩẫậđèéẻẽẹêếềểễệìíỉĩịòóỏõọôốồổỗộơớờởỡợùúủũụûứừửữựỳýỷỹỵ])")


def edit_distance(a: str, b: str) -> int:
    if a == b:
        return 0
    m, n = len(a), len(b)
    if m == 0:
        return n
    if n == 0:
        return m
    prev = list(range(n + 1))
    for i in range(1, m + 1):
        cur = [i] + [0] * n
        ca = a[i - 1]
        for j in range(1, n + 1):
            cost = 0 if ca == b[j - 1] else 1
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        prev = cur
    return prev[n]


def load_wordlist(path: Optional[Path] = None) -> set:
    p = Path(path) if path else _DEFAULT_WORDLIST
    words: set = set()
    if not p.is_file():
        logger.warning("Wordlist not found: %s", p)
        return words
    for line in p.read_text(encoding="utf-8").splitlines():
        w = line.strip().lower()
        if w:
            words.add(unicodedata.normalize("NFC", w))
    logger.info("Loaded %d words from %s", len(words), p)
    return words


def _candidates(word: str, wordlist: set) -> List[str]:
    """Sinh candidate sửa lỗi từ confusion pairs (single edit)."""
    cands: List[str] = []
    seen = set()
    for wrong, right in CONFUSION_PAIRS:
        if wrong in word:
            cand = word.replace(wrong, right)
            if cand != word and cand not in seen:
                seen.add(cand)
                cands.append(cand)
    # Cũng thử xoá ký tự lặp (ASR hay đọc lặp: "ngoài" -> "ngoaì"? ít; nhưng "làm" -> "llàm")
    # Chỉ áp dụng khi từ gốc dài >=4 để tránh nhiễu.
    if len(word) >= 4:
        for i in range(len(word) - 1):
            if word[i] == word[i + 1]:
                cand = word[:i] + word[i + 1 :]
                if cand not in seen:
                    seen.add(cand)
                    cands.append(cand)
    return cands


def correct_word(word: str, wordlist: set) -> Tuple[str, Optional[str]]:
    """
    Trả về (từ_đã_sửa, nguyên_nhân) với nguyên_nhân là candidate được chọn
    (None nếu không đổi). Chỉ đổi khi candidate nằm trong từ điển.
    """
    w = unicodedata.normalize("NFC", word).lower()
    if not w or w in wordlist:
        return word, None
    best: Optional[str] = None
    best_d = 99
    for cand in _candidates(w, wordlist):
        if cand in wordlist:
            d = edit_distance(w, cand)
            if d < best_d:
                best_d = d
                best = cand
    if best is not None:
        # Giữ nguyên vị trí viết hoa của từ gốc
        if word[:1].isupper():
            best = best[:1].upper() + best[1:]
        return best, best
    return word, None


def correct_spelling(text: str, wordlist: Optional[set] = None) -> Tuple[str, List[Dict]]:
    """
    Sửa chính tả toàn bộ câu. Trả về (văn_bản_đã_sửa, danh_sách_thay_đổi).
    Giữ lại dấu câu/khoảng trắng; chỉ sửa các token chữ cái.
    """
    if wordlist is None:
        wordlist = load_wordlist()
    applied: List[Dict] = []
    out_tokens: List[str] = []
    for tok in re.findall(r"\S+|\s+", text):
        if tok.strip() == "":
            out_tokens.append(tok)
            continue
        # tách phần chữ và dấu câu đuôi
        m = re.match(r"^([\wà-ỹÀ-Ỹ]+)(.*)$", tok, re.UNICODE)
        if not m:
            out_tokens.append(tok)
            continue
        core, trailing = m.group(1), m.group(2)
        fixed, cause = correct_word(core, wordlist)
        if cause is not None:
            applied.append({"from": core, "to": fixed})
        out_tokens.append(fixed + trailing)
    return "".join(out_tokens), applied


def apply_language_rules(text: str) -> str:
    """Quy tắc ngôn ngữ: NFC, gộp space, sửa dấu câu lặp, viết hoa đầu câu."""
    text = unicodedata.normalize("NFC", text or "").strip()
    text = _SPACE.sub(" ", text)
    # xoá khoảng trắng thừa trước dấu câu
    text = _TRAILING_SPACE_BEFORE_PUNCT.sub(r"\1", text)
    # gộp dấu câu lặp
    text = _PUNCT_REPEAT.sub(r"\1", text)
    # viết hoa chữ cái đầu câu
    text = _SENTENCE_START.sub(lambda m: m.group(1) + m.group(2).upper(), text)
    text = _SPACE.sub(" ", text).strip()
    return text
