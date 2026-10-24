"""
Face Recognition Service - InsightFace Wrapper
"""

import base64
import io
import logging
from typing import Dict, List, Optional

import numpy as np
from PIL import Image

from ..config import FACE_MODEL, FACE_USE_GPU

logger = logging.getLogger(__name__)


class FaceInsightService:
    """InsightFace wrapper with GPU option"""

    def __init__(self, model_name: str = None, use_gpu: Optional[bool] = None):
        self._model_name = model_name or FACE_MODEL
        self._use_gpu = use_gpu if use_gpu is not None else FACE_USE_GPU
        self._app = None

    def is_loaded(self) -> bool:
        return self._app is not None

    def _load(self):
        from insightface.app import FaceAnalysis

        providers = (
            ["CUDAExecutionProvider", "CPUExecutionProvider"]
            if self._use_gpu
            else ["CPUExecutionProvider"]
        )

        self._app = FaceAnalysis(name=self._model_name, providers=providers)
        self._app.prepare(ctx_id=0 if self._use_gpu else -1, det_size=(320, 320))

        if self._use_gpu:
            try:
                import torch
                logger.info(f"GPU Memory after face load: {torch.cuda.memory_allocated() / 1024**3:.2f}GB")
            except ImportError:
                pass

        logger.info(f"InsightFace loaded: {self._model_name} on {'GPU' if self._use_gpu else 'CPU'}")

    def detect(self, image_b64: str) -> List[Dict]:
        """Detect faces in base64 image"""
        if not self.is_loaded():
            self._load()

        img_bytes = base64.b64decode(image_b64)
        img = Image.open(io.BytesIO(img_bytes)).convert("RGB")
        faces = self._app.get(np.array(img))

        return [self._face_to_dict(face) for face in faces]

    @staticmethod
    def _face_to_dict(face) -> Dict:
        bbox = face.bbox.astype(int)
        return {
            "x1": int(bbox[0]),
            "y1": int(bbox[1]),
            "x2": int(bbox[2]),
            "y2": int(bbox[3]),
            "confidence": float(face.det_score),
            "embedding": (
                face.embedding.tolist()
                if hasattr(face, "embedding") and face.embedding is not None
                else None
            ),
        }


_face_svc: Optional[FaceInsightService] = None


def get_face_service() -> FaceInsightService:
    global _face_svc
    if _face_svc is None:
        _face_svc = FaceInsightService()
    return _face_svc
