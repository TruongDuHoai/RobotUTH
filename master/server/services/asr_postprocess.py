"""
ASR text post-process for Docker API (dictionary).

Mirrors deploy/gpu/asr_postprocess.py so local-mode ASR and /correct can share
dictionary rules without importing the host GPU-service package.
"""

from __future__ import annotations

import json
import logging
import os
import re
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from ..config import BASE_DIR

logger = logging.getLogger(__name__)

_DEFAULT_CANDIDATES = (
    BASE_DIR / "data" / "asr_dictionary.json",
    BASE_DIR / "deploy" / "gpu" / "asr_dictionary.json",
)

_CACHE: Tuple[float, str, List[Tuple[str, str]]] = (-1.0, "", [])


def _env_bool(name: str, default: bool = True) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in ("1", "true", "yes", "on")


def dictionary_enabled() -> bool:
    return _env_bool("ASR_DICT_ENABLED", True)


def dictionary_path() -> Path:
    raw = os.getenv("ASR_DICT_PATH", "").strip()
    if raw:
        return Path(raw)
    for p in _DEFAULT_CANDIDATES:
        if p.is_file():
            return p
    return _DEFAULT_CANDIDATES[0]


def _normalize_spaces(text: str) -> str:
    return re.sub(r"\s+", " ", (text or "").strip())


def load_phrase_pairs(path: Optional[Path] = None) -> List[Tuple[str, str]]:
    global _CACHE
    p = path or dictionary_path()
    try:
        mtime = p.stat().st_mtime if p.is_file() else -1.0
    except OSError:
        mtime = -1.0
    key = str(p)
    if _CACHE[1] == key and _CACHE[0] == mtime:
        return _CACHE[2]

    pairs: List[Tuple[str, str]] = []
    if not p.is_file():
        if dictionary_enabled():
            logger.warning("ASR dictionary not found: %s", p)
        _CACHE = (mtime, key, pairs)
        return pairs

    try:
        data = json.loads(p.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        logger.error("Failed to load ASR dictionary %s: %s", p, exc)
        _CACHE = (mtime, key, pairs)
        return pairs

    raw_pairs: List[Tuple[str, str]] = []
    if isinstance(data, dict):
        if "replacements" in data and isinstance(data["replacements"], list):
            for item in data["replacements"]:
                if not isinstance(item, dict):
                    continue
                src = item.get("from") or item.get("src") or item.get("wrong")
                dst = item.get("to") or item.get("dst") or item.get("correct")
                if src and dst is not None:
                    raw_pairs.append((str(src), str(dst)))
        else:
            for k, v in data.items():
                if k.startswith("_") or k in ("version", "comment", "description"):
                    continue
                if isinstance(v, str):
                    raw_pairs.append((str(k), v))
    elif isinstance(data, list):
        for item in data:
            if isinstance(item, dict):
                src = item.get("from") or item.get("src")
                dst = item.get("to") or item.get("dst")
                if src and dst is not None:
                    raw_pairs.append((str(src), str(dst)))

    pairs = sorted(
        ((_normalize_spaces(a), _normalize_spaces(b)) for a, b in raw_pairs if a.strip()),
        key=lambda t: len(t[0]),
        reverse=True,
    )
    _CACHE = (mtime, key, pairs)
    logger.info("Loaded %d ASR dictionary pairs from %s", len(pairs), p)
    return pairs


def apply_dictionary(
    text: str,
    *,
    pairs: Optional[List[Tuple[str, str]]] = None,
    enabled: Optional[bool] = None,
) -> Tuple[str, Dict]:
    """Longest non-overlapping phrase map (mirrors deploy/gpu/asr_postprocess)."""
    if enabled is None:
        enabled = dictionary_enabled()
    raw = _normalize_spaces(text)
    meta: Dict = {"dict_enabled": enabled, "dict_applied": [], "dict_path": str(dictionary_path())}
    if not enabled or not raw:
        return raw, meta

    phrase_pairs = pairs if pairs is not None else load_phrase_pairs()
    if not phrase_pairs:
        return raw, meta

    candidates: List[Tuple[int, int, str, str]] = []
    for src, dst in phrase_pairs:
        # Exact no-op only. Casing-only maps are intentional.
        if not src or src == dst:
            continue
        pattern = re.compile(
            r"(?<![\wÀ-ỹ])" + re.escape(src) + r"(?![\wÀ-ỹ])",
            re.IGNORECASE,
        )
        for m in pattern.finditer(raw):
            # If target already sits here (case-insensitive), only casing-upgrade.
            m_dst = re.match(re.escape(dst), raw[m.start() :], re.IGNORECASE)
            if m_dst:
                end = m.start() + m_dst.end()
                if raw[m.start() : end] == dst:
                    continue
                candidates.append((m.start(), end, src, dst))
                continue
            matched = raw[m.start() : m.end()]
            if matched == dst:
                continue
            candidates.append((m.start(), m.end(), src, dst))

    candidates.sort(key=lambda t: (t[1] - t[0], -t[0]), reverse=True)
    selected: List[Tuple[int, int, str, str]] = []
    occupied: List[Tuple[int, int]] = []

    def _overlaps(a0: int, a1: int) -> bool:
        for b0, b1 in occupied:
            if not (a1 <= b0 or a0 >= b1):
                return True
        return False

    for start, end, src, dst in candidates:
        if _overlaps(start, end):
            continue
        occupied.append((start, end))
        selected.append((start, end, src, dst))

    selected.sort(key=lambda t: t[0], reverse=True)
    out = raw
    applied_counts: Dict[Tuple[str, str], int] = {}
    for start, end, src, dst in selected:
        out = out[:start] + dst + out[end:]
        key = (src, dst)
        applied_counts[key] = applied_counts.get(key, 0) + 1

    applied: List[Dict] = [
        {"from": src, "to": dst, "count": n} for (src, dst), n in applied_counts.items()
    ]
    meta["dict_applied"] = applied
    return _normalize_spaces(out), meta


def postprocess_transcript(text: str) -> Tuple[str, str, Dict]:
    raw = _normalize_spaces(text)
    final, meta = apply_dictionary(raw)
    return final, raw, meta
