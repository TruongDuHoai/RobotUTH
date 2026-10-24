"""
Robotuth v3 Configuration
GPU-optimized for WSL + RTX 5060 8GB
"""

import os
from pathlib import Path

from dotenv import load_dotenv

# Base paths
BASE_DIR = Path(__file__).parent.parent
MODELS_DIR = BASE_DIR / "models"

# Load .env from the repo root so the app can be started directly (e.g. by PM2)
# without a shell wrapper. Real environment variables take precedence: this is
# loaded before the os.getenv() reads below but never overrides what is already set.
load_dotenv(BASE_DIR / ".env")

# Server Configuration
HOST = os.getenv("HOST", "0.0.0.0")
PORT = int(os.getenv("PORT", "8000"))
DEBUG = os.getenv("DEBUG", "false").lower() == "true"

# A comma-separated set makes it possible to rotate a single robot at a time.
ROBOT_API_TOKENS = tuple(
    token.strip() for token in os.getenv("ROBOT_API_TOKENS", "").split(",") if token.strip()
)

# LLM Configuration (OpenAI-compatible API)
OPENAI_API_BASE = os.getenv("OPENAI_API_BASE", "http://localhost:8080/v1")
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")
OPENAI_MODEL = os.getenv("OPENAI_MODEL", "robot")
REASONING_EFFORT = os.getenv("REASONING_EFFORT", "medium")

# Legacy llama.cpp Configuration (kept for backward compatibility)
LLAMA_SERVER_URL = os.getenv("LLAMA_SERVER_URL", "http://localhost:8080")
LLAMA_MODEL_PATH = os.getenv("LLAMA_MODEL_PATH", str(MODELS_DIR / "gemma-4-e2b-it" / "gemma-4-e2b-it.Q8_0.gguf"))
LLAMA_CTX_SIZE = int(os.getenv("LLAMA_CTX_SIZE", "2048"))
LLAMA_N_GPU_LAYERS = int(os.getenv("LLAMA_N_GPU_LAYERS", "999"))
LLAMA_GPU_LAYERS = int(os.getenv("LLAMA_GPU_LAYERS", "999"))

# ASR provider configuration
ASR_PROVIDER = os.getenv("ASR_PROVIDER", "parakeet").strip().lower()
if ASR_PROVIDER not in ("parakeet", "deepgram", "nemotron"):
    raise ValueError("ASR_PROVIDER must be 'parakeet', 'deepgram' or 'nemotron'")

# Nemotron ASR served by `nemo-speech serve` (OpenAI-compatible subset) on the
# host port that ASR_BACKEND_URL points at. CPU-only by design: the T400 is
# reserved for other projects. Chosen over Whisper for its silence behaviour —
# it returns "" where Whisper fabricates sentences (see outputs/BAO_CAO_ASR_*).
NEMOTRON_API_URL = os.getenv("NEMOTRON_API_URL", "/v1/audio/transcriptions")
NEMOTRON_MODEL = os.getenv("NEMOTRON_MODEL", "nemotron-3.5-asr-streaming-0.6b")
# BCP-47 tag the model documents; the serve build ignores `model` but honours
# the language hint.
NEMOTRON_LANGUAGE = os.getenv("NEMOTRON_LANGUAGE", "vi-VN")
NEMOTRON_TIMEOUT_SECONDS = float(os.getenv("NEMOTRON_TIMEOUT_SECONDS", "30"))
NEMOTRON_API_KEY = os.getenv("NEMOTRON_API_KEY", "")

# Deepgram hosted ASR. Keep the key server-side; never ship it in Android.
DEEPGRAM_API_KEY = os.getenv("DEEPGRAM_API_KEY", "")
DEEPGRAM_API_URL = os.getenv("DEEPGRAM_API_URL", "https://api.deepgram.com/v1/listen")
DEEPGRAM_MODEL = os.getenv("DEEPGRAM_MODEL", "nova-3")
DEEPGRAM_LANGUAGE = os.getenv("DEEPGRAM_LANGUAGE", "vi")
DEEPGRAM_SMART_FORMAT = os.getenv("DEEPGRAM_SMART_FORMAT", "true").lower() in (
    "1", "true", "yes", "on"
)
DEEPGRAM_TIMEOUT_SECONDS = float(os.getenv("DEEPGRAM_TIMEOUT_SECONDS", "90"))
DEEPGRAM_KEYTERMS = tuple(
    term.strip() for term in os.getenv("DEEPGRAM_KEYTERMS", "").split(",") if term.strip()
)

# Parakeet ASR Configuration
# HF: https://huggingface.co/nvidia/parakeet-ctc-0.6b-Vietnamese
# NeMo ID: nvidia/parakeet-ctc-0.6b-vi
ASR_MODEL_PATH = os.getenv("ASR_MODEL_PATH", str(MODELS_DIR / "parakeet-ctc-0.6b-vi.nemo"))
ASR_USE_GPU = os.getenv("ASR_USE_GPU", "true").lower() == "true"
ASR_DEVICE = "cuda" if ASR_USE_GPU else "cpu"
# Docker proxies /asr/ctc/chunk to native GPU service on host (e.g. http://172.17.0.1:8002)
ASR_BACKEND_URL = os.getenv("ASR_BACKEND_URL", "")
# Pre-tune optim (local path mirrors host asr_gpu_server)
ASR_TRIM_SILENCE = os.getenv("ASR_TRIM_SILENCE", "true").lower() in ("1", "true", "yes", "on")
ASR_TRIM_PAD_MS = float(os.getenv("ASR_TRIM_PAD_MS", "150"))
ASR_TRIM_RMS = float(os.getenv("ASR_TRIM_RMS", "0.01"))
ASR_DICT_ENABLED = os.getenv("ASR_DICT_ENABLED", "true").lower() in ("1", "true", "yes", "on")

# Streaming relay (WS /ws/asr/{session}, `server/api/asr_stream.py`). The client
# streams 100 ms PCM16 frames; the server runs the same energy endpointer the
# Android client used (AudioPipeline.kt constants) and decodes each closed
# segment through the enabled BATCH provider — streaming capture, batch
# accuracy, still exactly one model running.
ASR_WS_SEGMENT_SILENCE_MS = float(os.getenv("ASR_WS_SEGMENT_SILENCE_MS", "900"))
ASR_WS_MIN_SPEECH_MS = float(os.getenv("ASR_WS_MIN_SPEECH_MS", "500"))
ASR_WS_PRE_ROLL_MS = float(os.getenv("ASR_WS_PRE_ROLL_MS", "500"))
# "manual": the client's stop control ends a turn (push-to-talk today).
# "auto": additionally end the turn after ASR_WS_TURN_SILENCE_MS of silence
# following the last closed segment (hands-free; default for scripts).
ASR_WS_TURN_END = os.getenv("ASR_WS_TURN_END", "manual").strip().lower()
ASR_WS_TURN_SILENCE_MS = float(os.getenv("ASR_WS_TURN_SILENCE_MS", "1500"))
ASR_WS_MAX_SESSION_S = float(os.getenv("ASR_WS_MAX_SESSION_S", "120"))
# Keep the relay bounded so a lobby never starves the shared box (decoder RTF
# 0.2 on Parakeet-CPU, near-saturating with the Nemotron streaming engine).
ASR_WS_MAX_SESSIONS = int(os.getenv("ASR_WS_MAX_SESSIONS", "2"))

# Optional LLM post-correct (Docker API only; default off)
ASR_LLM_CORRECT = os.getenv("ASR_LLM_CORRECT", "false").lower() in ("1", "true", "yes", "on")
ASR_LLM_CORRECT_MAX_TOKENS = int(os.getenv("ASR_LLM_CORRECT_MAX_TOKENS", "128"))
ASR_LLM_CORRECT_TIMEOUT_S = float(os.getenv("ASR_LLM_CORRECT_TIMEOUT_S", "8"))

# Face Recognition Configuration
# Master switch: disabled 2026-10-05 (feature not needed). Code kept intact —
# set FACE_ENABLED=true to re-enable; all /api/attendance/face/* endpoints
# answer 503 while off and insightface is never loaded.
FACE_ENABLED = os.getenv("FACE_ENABLED", "false").lower() == "true"
FACE_MODEL = os.getenv("FACE_MODEL", "buffalo_l")
FACE_USE_GPU = os.getenv("FACE_USE_GPU", "false").lower() == "true"
FACE_MATCH_THRESHOLD = float(os.getenv("FACE_MATCH_THRESHOLD", "0.50"))
FACE_TOP_K = int(os.getenv("FACE_TOP_K", "5"))
FACE_EMBEDDING_DIM = int(os.getenv("FACE_EMBEDDING_DIM", "512"))

# PostgreSQL Configuration
POSTGRES_HOST = os.getenv("POSTGRES_HOST", "127.0.0.1")
POSTGRES_PORT = int(os.getenv("POSTGRES_PORT", "5432"))
POSTGRES_USER = os.getenv("POSTGRES_USER", "robotuth")
POSTGRES_PASSWORD = os.getenv("POSTGRES_PASSWORD", "robotuth")
POSTGRES_DB_FACE = os.getenv("POSTGRES_DB_FACE", "robotuth_face")
POSTGRES_DB_KNOWLEDGE = os.getenv("POSTGRES_DB_KNOWLEDGE", "robotuth_knowledge")

POSTGRES_DSN_FACE = f"postgresql://{POSTGRES_USER}:{POSTGRES_PASSWORD}@{POSTGRES_HOST}:{POSTGRES_PORT}/{POSTGRES_DB_FACE}"
POSTGRES_DSN_KNOWLEDGE = f"postgresql://{POSTGRES_USER}:{POSTGRES_PASSWORD}@{POSTGRES_HOST}:{POSTGRES_PORT}/{POSTGRES_DB_KNOWLEDGE}"

# Redis Configuration
REDIS_URL = os.getenv("REDIS_URL", "redis://localhost:6379/0")
REDIS_ENABLED = os.getenv("REDIS_ENABLED", "true").lower() == "true"
RECOGNITION_CACHE_TTL_S = int(os.getenv("RECOGNITION_CACHE_TTL_S", "3"))

# Knowledge Base Configuration
KNOWLEDGE_ENABLED = os.getenv("KNOWLEDGE_ENABLED", "true").lower() == "true"
KNOWLEDGE_TOP_K = int(os.getenv("KNOWLEDGE_TOP_K", "5"))
KNOWLEDGE_CHUNK_SIZE = int(os.getenv("KNOWLEDGE_CHUNK_SIZE", "500"))
KNOWLEDGE_CHUNK_OVERLAP = int(os.getenv("KNOWLEDGE_CHUNK_OVERLAP", "50"))
KNOWLEDGE_MIN_SCORE = float(os.getenv("KNOWLEDGE_MIN_SCORE", "0.35"))
KNOWLEDGE_SEMANTIC_WEIGHT = float(os.getenv("KNOWLEDGE_SEMANTIC_WEIGHT", "0.7"))
KNOWLEDGE_KEYWORD_WEIGHT = float(os.getenv("KNOWLEDGE_KEYWORD_WEIGHT", "0.3"))
KNOWLEDGE_EMBEDDING_MODEL = os.getenv("KNOWLEDGE_EMBEDDING_MODEL", "BAAI/bge-m3")
KNOWLEDGE_UPLOAD_EXTENSIONS = (".pdf", ".docx", ".txt")
KNOWLEDGE_MAX_UPLOAD_MB = int(os.getenv("KNOWLEDGE_MAX_UPLOAD_MB", "20"))
# Max chars of assembled system prompt (instruction + all document texts)
KNOWLEDGE_SYSTEM_PROMPT_MAX_CHARS = int(os.getenv("KNOWLEDGE_SYSTEM_PROMPT_MAX_CHARS", "100000"))
# Chat answers using full knowledge system prompt (not hybrid RAG-only)
KNOWLEDGE_USE_SYSTEM_PROMPT = os.getenv("KNOWLEDGE_USE_SYSTEM_PROMPT", "true").lower() == "true"
# Reasoning models count hidden reasoning against the output budget. A 768-token
# cap made `low` reasoning finish without any user-facing answer.
KNOWLEDGE_ANSWER_MAX_TOKENS = int(os.getenv("KNOWLEDGE_ANSWER_MAX_TOKENS", "2048"))

# OCR fallback for scanned PDFs (host PP-OCRv6 medium on :8003)
# Docker: OCR_BACKEND_URL=http://172.17.0.1:8003
OCR_BACKEND_URL = os.getenv("OCR_BACKEND_URL", "").rstrip("/")
OCR_MIN_TEXT_CHARS = int(os.getenv("OCR_MIN_TEXT_CHARS", "80"))
OCR_TIMEOUT_SECONDS = float(os.getenv("OCR_TIMEOUT_SECONDS", "300"))


# TTS Configuration
TTS_PROVIDER = os.getenv("TTS_PROVIDER", "vieneu").strip().lower()
if TTS_PROVIDER not in ("vieneu", "openai"):
    raise ValueError("TTS_PROVIDER must be 'vieneu' or 'openai'")
TTS_MODEL = os.getenv("TTS_MODEL", "google-tts/vi")
TTS_BACKEND_URL = os.getenv("TTS_BACKEND_URL", "")
TTS_SERVICE_URL = os.getenv("TTS_SERVICE_URL", TTS_BACKEND_URL or "http://localhost:8004")
TTS_ENABLED = os.getenv("TTS_ENABLED", "true").lower() == "true"
TTS_VOICE = os.getenv("TTS_VOICE", "ngoc-huyen")
TTS_TIMEOUT_SECONDS = float(os.getenv("TTS_TIMEOUT_SECONDS", "45"))

# Logging Configuration
LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO")
