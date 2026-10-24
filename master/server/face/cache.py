"""
Face Cache - Redis Cache for Recognition
"""

import hashlib
import json
import logging
from typing import Dict, Optional

import numpy as np
import redis

from ..config import RECOGNITION_CACHE_TTL_S, REDIS_ENABLED, REDIS_URL

logger = logging.getLogger(__name__)


class FaceCache:
    """Redis cache for hot face recognition results"""

    def __init__(self, url: str = None, enabled: Optional[bool] = None):
        self.enabled = enabled if enabled is not None else REDIS_ENABLED
        self._client = None

        if self.enabled:
            try:
                self._client = redis.Redis.from_url(url or REDIS_URL, decode_responses=True)
                logger.info(f"Redis cache enabled: {url or REDIS_URL}")
            except Exception as e:
                logger.warning(f"Redis unavailable: {e}")
                self.enabled = False

    def is_available(self) -> bool:
        if not self.enabled or self._client is None:
            return False
        try:
            return bool(self._client.ping())
        except Exception as e:
            logger.warning(f"Redis ping failed: {e}")
            return False

    def embedding_key(self, embedding: list) -> str:
        arr = np.array(embedding, dtype=np.float32)
        arr = arr / (np.linalg.norm(arr) + 1e-8)
        rounded = np.round(arr, 3).astype(np.float32).tobytes()
        digest = hashlib.sha1(rounded).hexdigest()
        return f"face:rec:{digest}"

    def get_recognition(self, embedding: list) -> Optional[Dict]:
        if not self.is_available():
            return None
        try:
            raw = self._client.get(self.embedding_key(embedding))
            return json.loads(raw) if raw else None
        except Exception as e:
            logger.warning(f"Redis get failed: {e}")
            return None

    def set_recognition(self, embedding: list, result: Dict, ttl_s: int = None) -> None:
        if not self.is_available():
            return
        try:
            ttl = ttl_s or RECOGNITION_CACHE_TTL_S
            self._client.setex(self.embedding_key(embedding), ttl, json.dumps(result))
        except Exception as e:
            logger.warning(f"Redis set failed: {e}")

    def cache_person(self, person_id: str, payload: Dict, ttl_s: int = 3600) -> None:
        if not self.is_available():
            return
        try:
            self._client.setex(f"face:person:{person_id}", ttl_s, json.dumps(payload))
        except Exception as e:
            logger.warning(f"Redis cache person failed: {e}")

    def delete_person(self, person_id: str) -> None:
        if not self.is_available():
            return
        try:
            self._client.delete(f"face:person:{person_id}")
        except Exception as e:
            logger.warning(f"Redis delete person failed: {e}")


_face_cache: Optional[FaceCache] = None


def get_face_cache() -> FaceCache:
    global _face_cache
    if _face_cache is None:
        _face_cache = FaceCache()
    return _face_cache
