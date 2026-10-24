"""
Ghi log kết quả nhận dạng và tổng hợp xu hướng chất lượng theo thời gian.

Mỗi lần chạy (run) sinh:
  - <output_dir>/<run_id>.jsonl         : chi tiết từng mẫu (1 dòng JSON)
  - <output_dir>/<run_id>.summary.json  : chỉ số tổng hợp của run
  - <output_dir>/results.jsonl          : file tích luỹ (append) để theo dõi trend

Hàm `trend()` đọc toàn bộ summary trong thư mục để vẽ xu hướng WER/CER qua các
lần chạy — phục vụ cải thiện chất lượng nhận dạng theo thời gian.
"""
from __future__ import annotations

import json
import logging
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List

logger = logging.getLogger("asr_pipeline.logger")


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def log_result(output_dir: Path, run_id: str, record: Dict) -> None:
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    detail = output_dir / f"{run_id}.jsonl"
    cumulative = output_dir / "results.jsonl"
    line = json.dumps(record, ensure_ascii=False)
    with detail.open("a", encoding="utf-8") as f:
        f.write(line + "\n")
    with cumulative.open("a", encoding="utf-8") as f:
        f.write(line + "\n")


def write_summary(output_dir: Path, run_id: str, summary: Dict) -> Path:
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    path = output_dir / f"{run_id}.summary.json"
    path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


def load_summaries(output_dir: Path) -> List[Dict]:
    output_dir = Path(output_dir)
    out: List[Dict] = []
    if not output_dir.is_dir():
        return out
    for p in sorted(output_dir.glob("*.summary.json")):
        try:
            out.append(json.loads(p.read_text(encoding="utf-8")))
        except Exception as exc:  # noqa: BLE001
            logger.warning("Skip bad summary %s: %s", p, exc)
    return out


def trend(output_dir: Path) -> List[Dict]:
    """Trả về danh sách các run đã ghi, mỗi phần tử có WER/CER và timestamp."""
    rows: List[Dict] = []
    for s in load_summaries(output_dir):
        rows.append(
            {
                "run_id": s.get("run_id"),
                "created_at": s.get("created_at"),
                "samples": s.get("scored_rows", 0),
                "wer": round(s.get("wer", 0.0), 4),
                "cer": round(s.get("cer", 0.0), 4),
                "task_success_rate": s.get("task_success_rate"),
                "empty_rate": s.get("empty_rate"),
                "model": ",".join(s.get("models", []) or []),
            }
        )
    return rows


def print_trend(output_dir: Path) -> None:
    rows = trend(output_dir)
    if not rows:
        print("(chưa có kết quả nào để theo dõi trend)")
        return
    print(f"{'run_id':28} {'time':20} {'n':>4} {'WER':>7} {'CER':>7} {'ok%':>5}  model")
    for r in rows:
        ts = (r["created_at"] or "")[:19].replace("T", " ")
        ok = "" if r["task_success_rate"] is None else f"{r['task_success_rate']*100:4.0f}"
        print(
            f"{str(r['run_id'])[:28]:28} {ts:20} {r['samples']:>4} "
            f"{r['wer']*100:6.1f}% {r['cer']*100:6.1f}% {ok:>5}  {r['model']}"
        )
