"""
Pipeline nhận dạng tiếng Việt end-to-end với các lớp QA.

Luồng:
  audio ──▶ [tiền xử lý: resample 16k, mono, lọc DC, lọc nhiễu, trim]
        ──▶ [ASR backend: http (Parakeet server) | local (NeMo) | stub (test)]
        ──▶ [sửa chính tả từng từ + quy tắc ngôn ngữ]
        ──▶ [từ điển phrase-map domain (asr_postprocess)]

Tái sử dụng:
  - asr_postprocess.postprocess_transcript  (từ điển phrase-map theo domain UTH)
  - asr_wer_eval.score_pair / normalize_text (tính WER/CER)

Backend mặc định là HTTP tới server Parakeet đang chạy (asr_gpu_server.py),
nhưng cũng hỗ trợ chạy local NeMo để kiểm thử off-line.
"""
from __future__ import annotations

import logging
import sys
import tempfile
from pathlib import Path
from typing import Dict, List, Optional

logger = logging.getLogger("asr_pipeline.pipeline")

# Cho phép import các script cùng cấp (asr_postprocess, asr_wer_eval)
_SCRIPTS_DIR = Path(__file__).resolve().parent.parent
if str(_SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS_DIR))

from asr_pipeline import audio_preprocess as ap  # noqa: E402
from asr_pipeline import spelling as sp  # noqa: E402

# Import tối thiểu; nếu file không tồn tại thì pipeline vẫn import được (chỉ lỗi
# khi gọi backend cần tới). Dùng import lazily bên trong hàm để tránh phụ thuộc cứng.
try:  # pragma: no cover - depends on deploy env
    import asr_postprocess  # type: ignore
except Exception:  # noqa: BLE001
    asr_postprocess = None  # type: ignore
    logger.warning("asr_postprocess không import được (sẽ bỏ qua phrase-map dict)")


class ASRQualityPipeline:
    def __init__(
        self,
        backend: str = "http",
        backend_url: str = "http://127.0.0.1:8002/asr/ctc/chunk",
        model_name: str = "nvidia/parakeet-ctc-0.6b-vi",
        device: str = "cuda",
        target_sr: int = 16000,
        denoise: bool = True,
        spellcheck: bool = True,
        phrase_dict: bool = True,
        wordlist_path: Optional[Path] = None,
    ) -> None:
        self.backend = backend
        self.backend_url = backend_url
        self.model_name = model_name
        self.device = device
        self.target_sr = target_sr
        self.denoise = denoise
        self.spellcheck = spellcheck
        self.phrase_dict = phrase_dict
        self._wordlist = sp.load_wordlist(wordlist_path)
        self._local_model = None

    # ----------------------------- ASR backends ----------------------------- #
    def _transcribe_http(self, wav_bytes: bytes, session_id: str) -> Dict:
        import urllib.request  # lazy

        boundary = "----RobotuthPipelineBoundary"
        body = (
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"session_id\"\r\n\r\n{session_id}\r\n"
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"clip.wav\"\r\nContent-Type: audio/wav\r\n\r\n"
        ).encode() + wav_bytes + f"\r\n--{boundary}--\r\n".encode()
        req = urllib.request.Request(
            self.backend_url,
            data=body,
            headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=120) as resp:
            return json_loads(resp.read().decode("utf-8"))

    def _transcribe_local(self, wav_path: Path) -> Dict:
        import nemo.collections.asr as nemo_asr  # lazy

        if self._local_model is None:
            logger.info("Loading NeMo model %s ...", self.model_name)
            self._local_model = nemo_asr.models.ASRModel.from_pretrained(
                self.model_name, map_location=self.device
            )
        text = self._local_model.transcribe([str(wav_path)])[0]
        return {"transcript": text, "model": self.model_name, "device": self.device, "success": True}

    def _transcribe_stub(self, wav_path: Path) -> Dict:
        # Dùng cho test đơn vị: trả về chuỗi rỗng (evaluate sẽ override nếu cần).
        return {"transcript": "", "model": "stub", "device": "stub", "success": True}

    def _asr(self, wav_path: Path, session_id: str) -> Dict:
        if self.backend == "http":
            wav_bytes = Path(wav_path).read_bytes()
            return self._transcribe_http(wav_bytes, session_id)
        if self.backend == "local":
            return self._transcribe_local(wav_path)
        return self._transcribe_stub(wav_path)

    # ------------------------------- pipeline ------------------------------- #
    def transcribe_file(self, audio_path, session_id: str = "pipeline") -> Dict:
        """Chạy toàn bộ pipeline cho 1 file audio. Trả về dict kết quả chi tiết."""
        samples, sr, audio_meta = ap.preprocess(
            audio_path, target_sr=self.target_sr, denoise=self.denoise
        )

        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tf:
            tmp = Path(tf.name)
        ap.write_wav(tmp, samples, sr)

        try:
            resp = self._asr(tmp, session_id)
        finally:
            tmp.unlink(missing_ok=True)

        raw = (resp.get("transcript") or resp.get("transcript_raw") or "").strip()
        duration = float(resp.get("duration_seconds") or audio_meta.duration_seconds or 0.0)

        current = raw
        spell_meta: List[Dict] = []
        if self.spellcheck:
            current, spell_meta = sp.correct_spelling(current, self._wordlist)
        dict_meta: Dict = {"dict_applied": []}
        if self.phrase_dict and asr_postprocess is not None:
            current, _, dict_meta = asr_postprocess.postprocess_transcript(current)
        # Luôn áp dụng quy tắc ngôn ngữ ở đầu ra cuối
        final = sp.apply_language_rules(current)

        return {
            "raw": raw,
            "spelling_corrected": current if self.spellcheck else raw,
            "final": final,
            "spelling_applied": spell_meta,
            "dict_applied": dict_meta.get("dict_applied", []),
            "audio_meta": audio_meta.__dict__,
            "duration_seconds": duration,
            "model": resp.get("model"),
            "device": resp.get("device"),
            "success": bool(resp.get("success", True)),
        }


def json_loads(s: str) -> Dict:
    import json

    return json.loads(s)
