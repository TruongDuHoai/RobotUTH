"""Embedding service using BAAI/bge-m3 with debug hash fallback."""

import hashlib
import logging
from functools import lru_cache
from typing import List

import numpy as np

from ..config import KNOWLEDGE_EMBEDDING_MODEL

logger = logging.getLogger(__name__)


class EmbeddingService:
    def __init__(self, model_name: str = KNOWLEDGE_EMBEDDING_MODEL):
        self.model_name = model_name
        self._tokenizer = None
        self._model = None
        self._device = None

    def _load(self):
        if self.model_name.lower() in ("hash", "debug-hash", "local-hash"):
            self._model = "hash"
            return
        if self._model is not None:
            return
        try:
            import torch
            from transformers import AutoModel, AutoTokenizer
        except ImportError as exc:
            logger.warning(
                "transformers/torch unavailable (%s); using hash embeddings for CPU/Docker",
                exc,
            )
            self._model = "hash"
            return

        self._device = "cuda" if torch.cuda.is_available() else "cpu"
        logger.info("Loading embedding model %s on %s", self.model_name, self._device)
        self._tokenizer = AutoTokenizer.from_pretrained(self.model_name)
        self._model = AutoModel.from_pretrained(self.model_name).to(self._device)
        self._model.eval()

    def embed_texts(self, texts: List[str], batch_size: int = 8) -> List[List[float]]:
        self._load()
        if self._model == "hash":
            return [self._hash_embed(text) for text in texts]

        import torch

        vectors: List[List[float]] = []
        with torch.no_grad():
            for start in range(0, len(texts), batch_size):
                batch = texts[start:start + batch_size]
                encoded = self._tokenizer(
                    batch,
                    padding=True,
                    truncation=True,
                    max_length=8192,
                    return_tensors="pt",
                ).to(self._device)
                output = self._model(**encoded)
                attention_mask = encoded["attention_mask"].unsqueeze(-1)
                embeddings = (output.last_hidden_state * attention_mask).sum(dim=1) / attention_mask.sum(dim=1).clamp(min=1)
                embeddings = torch.nn.functional.normalize(embeddings, p=2, dim=1)
                vectors.extend(embeddings.cpu().numpy().astype(np.float32).tolist())
        return vectors

    @lru_cache(maxsize=128)
    def embed_query_cached(self, query: str) -> tuple:
        vector = self.embed_texts([query])[0]
        return tuple(vector)

    def embed_query(self, query: str) -> List[float]:
        return list(self.embed_query_cached(query.strip()))

    def _hash_embed(self, text: str) -> List[float]:
        vector = np.zeros(1024, dtype=np.float32)
        for token in text.lower().split():
            digest = hashlib.sha256(token.encode("utf-8")).digest()
            index = int.from_bytes(digest[:4], "little") % 1024
            sign = 1.0 if digest[4] % 2 else -1.0
            vector[index] += sign
        norm = np.linalg.norm(vector)
        if norm > 0:
            vector = vector / norm
        return vector.tolist()


_embedding_service = None


def get_embedding_service() -> EmbeddingService:
    global _embedding_service
    if _embedding_service is None:
        _embedding_service = EmbeddingService()
    return _embedding_service
