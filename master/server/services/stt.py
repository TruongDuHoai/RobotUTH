"""
STT Service - Parakeet CTC with GPU Optimization
"""

import logging
import time
from pathlib import Path
from typing import Optional

import numpy as np

from ..config import ASR_DEVICE, ASR_MODEL_PATH, ASR_USE_GPU

logger = logging.getLogger(__name__)


class ParakeetAsrServiceGPU:
    """Parakeet CTC NeMo model wrapper with GPU optimization"""

    def __init__(self, model_path: Optional[str] = None, use_gpu: Optional[bool] = None):
        self._model_path = Path(model_path or ASR_MODEL_PATH)
        self._use_gpu = use_gpu if use_gpu is not None else ASR_USE_GPU
        self._model = None
        self._device = ASR_DEVICE

        if self._use_gpu:
            try:
                import torch
                if not torch.cuda.is_available():
                    logger.warning("CUDA not available, falling back to CPU")
                    self._use_gpu = False
                    self._device = "cpu"
            except ImportError:
                logger.warning("PyTorch not available, falling back to CPU")
                self._use_gpu = False
                self._device = "cpu"

    def is_loaded(self) -> bool:
        return self._model is not None

    def device(self) -> str:
        return self._device

    def _load(self):
        import nemo.collections.asr as nemo_asr
        import torch

        logger.info(f"Loading Parakeet CTC: {self._model_path} on {self._device}")

        try:
            logger.info("Attempting to load from HuggingFace: nvidia/parakeet-ctc-0.6b-vi")
            self._model = nemo_asr.models.ASRModel.from_pretrained("nvidia/parakeet-ctc-0.6b-vi")
        except Exception as e:
            logger.warning(f"Failed to load from HuggingFace: {e}")
            if not self._model_path.exists():
                models_dir = Path(__file__).parent.parent.parent / "models"
                alts = list(models_dir.rglob("*.nemo"))
                if alts:
                    self._model_path = alts[0]
                    logger.warning(f"Model fallback: {self._model_path}")
                else:
                    raise FileNotFoundError(
                        f"Model not found: {self._model_path}. "
                        f"Please download parakeet-ctc-0.6b-vi.nemo to models/ directory or use HuggingFace"
                    )
            self._model = nemo_asr.models.EncDecCTCModelBPE.restore_from(str(self._model_path))

        if self._use_gpu:
            self._model = self._model.to(self._device)
            self._model = self._model.half()
            torch.cuda.empty_cache()
            torch.backends.cudnn.benchmark = True
            torch.backends.cudnn.deterministic = False

            memory_allocated = torch.cuda.memory_allocated() / 1024**3
            memory_reserved = torch.cuda.memory_reserved() / 1024**3
            logger.info(f"GPU Memory - Allocated: {memory_allocated:.2f}GB, Reserved: {memory_reserved:.2f}GB")

        self._model.eval()
        logger.info(f"Parakeet CTC loaded on {self._device}")

    def transcribe(self, audio: np.ndarray) -> str:
        if not self.is_loaded():
            self._load()

        import torch

        start_time = time.time()

        with torch.no_grad():
            if self._use_gpu:
                with torch.cuda.amp.autocast():
                    results = self._model.transcribe([audio], batch_size=1)
            else:
                results = self._model.transcribe([audio], batch_size=1)

        first = results[0]
        text = first.text if hasattr(first, "text") else str(first)

        processing_time = time.time() - start_time
        logger.info(f"Transcription completed in {processing_time:.2f}s: '{text[:80]}...'")

        return text.strip()

    def transcribe_stream(self, audio_bytes: bytes) -> str:
        import io
        import wave

        try:
            wf = wave.open(io.BytesIO(audio_bytes), "rb")
            if wf.getnchannels() != 1:
                raise ValueError("Only mono audio is supported")
            if wf.getframerate() != 16000:
                raise ValueError("Only 16kHz sample rate is supported")
            if wf.getsampwidth() != 2:
                raise ValueError("Only 16-bit PCM is supported")

            raw = wf.readframes(wf.getnframes())
            wf.close()

            audio_array = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
        except Exception as e:
            logger.error(f"Failed to parse WAV: {e}")
            if len(audio_bytes) % 2 != 0:
                audio_bytes = audio_bytes[:-1]
            audio_array = np.frombuffer(audio_bytes, dtype=np.int16).astype(np.float32) / 32768.0

        return self.transcribe(audio_array)

    def cleanup(self):
        if self._use_gpu and self._model is not None:
            import torch
            del self._model
            torch.cuda.empty_cache()
            self._model = None
            logger.info("Parakeet CTC model cleaned up")


_asr_service: Optional[ParakeetAsrServiceGPU] = None


def get_asr_service() -> ParakeetAsrServiceGPU:
    global _asr_service
    if _asr_service is None:
        _asr_service = ParakeetAsrServiceGPU()
    return _asr_service


def reset_asr_service():
    global _asr_service
    if _asr_service is not None:
        _asr_service.cleanup()
    _asr_service = None
