"""
Robotuth v3 Main Server
FastAPI server with GPU-optimized services
"""

import logging
from pathlib import Path

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

# Import API routers
from server.api import asr, asr_stream, attendance, chat, conversation, issues, knowledge, robot_link, settings, tts

# Import config
from server.config import (
    DEBUG,
    FACE_ENABLED,
    HOST,
    KNOWLEDGE_ENABLED,
    PORT,
    ROBOT_API_TOKENS,
)
from server.middleware.robot_api_auth import RobotApiAuthMiddleware
from server.services.tts import get_tts_service

# Setup logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# Create FastAPI app
app = FastAPI(
    title="Robotuth v3 API",
    description="GPU-optimized AI services for robot",
    version="3.0.0"
)

# Access control for the admin/static surface lives in the nginx gateway
# (deploy/robot-gateway.conf): /static, /api/knowledge and /api/settings are
# restricted to the LAN CIDR. The app itself only enforces robot tokens.
app.add_middleware(RobotApiAuthMiddleware, tokens=ROBOT_API_TOKENS)

# CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Include routers
app.include_router(asr.router)
app.include_router(attendance.router)
app.include_router(conversation.router)
app.include_router(chat.router)
app.include_router(issues.router)
app.include_router(knowledge.router)
app.include_router(settings.router)
app.include_router(tts.router)
app.include_router(robot_link.router)
app.include_router(asr_stream.router)


# Health check endpoint
@app.get("/")
async def root():
    return {
        "service": "Robotuth v3",
        "status": "running",
        "version": "3.0.0",
        "gpu_optimized": True,
        "chat_mode": "knowledge_system_prompt",
    }


@app.get("/health")
async def health():
    """Health check endpoint — probes ASR/TTS live when possible."""
    asr_info = await asr.probe_asr_backend()
    asr_status = "online" if asr_info.get("available") else "offline"
    if asr_info.get("mode") == "proxy" and asr_info.get("available"):
        asr_status = "proxy_online"
    elif asr_info.get("mode") == "proxy" and not asr_info.get("available"):
        asr_status = "proxy_offline"

    return {
        "status": "healthy",
        "services": {
            "asr": asr_status,
            "asr_detail": {
                "mode": asr_info.get("mode"),
                "available": asr_info.get("available"),
                "model_loaded": asr_info.get("model_loaded"),
                "model": asr_info.get("model"),
                "device": asr_info.get("device"),
                "vram_mb": asr_info.get("vram_mb"),
                "backend_url": asr_info.get("backend_url"),
            },
            "attendance": "enabled" if FACE_ENABLED else "disabled",
            "conversation": "enabled",
            "chat": "enabled",
            "knowledge": "enabled" if KNOWLEDGE_ENABLED else "disabled",
            "tts": "online" if get_tts_service().health_check() else "offline",
        }
    }


# Mount static files for UI
static_dir = Path(__file__).parent.parent / "static"
if static_dir.exists():
    app.mount("/static", StaticFiles(directory=str(static_dir)), name="static")
    logger.info(f"Static files mounted from {static_dir}")
else:
    logger.warning(f"Static directory not found: {static_dir}")


if __name__ == "__main__":
    import uvicorn

    logger.info(f"Starting Robotuth v3 server on {HOST}:{PORT}")
    logger.info(f"Debug mode: {DEBUG}")

    uvicorn.run(
        "server.main:app",
        host=HOST,
        port=PORT,
        reload=DEBUG,
        log_level="info"
    )
