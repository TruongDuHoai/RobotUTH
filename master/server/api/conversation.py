"""
Conversation API - Voice-to-Voice Endpoints
"""

import logging

from fastapi import APIRouter, UploadFile, WebSocket, WebSocketDisconnect

from ..services import get_pipeline

router = APIRouter(prefix="/api/conversation", tags=["conversation"])
logger = logging.getLogger(__name__)


@router.post("/voice")
async def voice_conversation(audio: UploadFile):
    try:
        pipeline = get_pipeline()
        audio_bytes = await audio.read()

        logger.info(f"Received audio: {len(audio_bytes)} bytes")

        result = await pipeline.process(audio_bytes)

        if result["success"]:
            from fastapi.responses import Response
            return Response(
                content=result["audio"],
                media_type="audio/wav",
                headers={
                    "X-Transcript": result["transcript"],
                    "X-Response": result["response"],
                }
            )
        else:
            from fastapi import HTTPException
            raise HTTPException(500, result.get("error", "Pipeline error"))

    except Exception as e:
        logger.exception("voice conversation error")
        from fastapi import HTTPException
        raise HTTPException(500, str(e))


@router.post("/text")
async def text_conversation(text: str):
    try:
        pipeline = get_pipeline()

        logger.info(f"Received text: {text}")

        result = await pipeline.process_text(text)

        if result["success"]:
            from fastapi.responses import Response
            return Response(
                content=result["audio"],
                media_type="audio/wav",
                headers={
                    "X-Response": result["response"],
                }
            )
        else:
            from fastapi import HTTPException
            raise HTTPException(500, result.get("error", "Pipeline error"))

    except Exception as e:
        logger.exception("text conversation error")
        from fastapi import HTTPException
        raise HTTPException(500, str(e))


@router.post("/reset")
async def reset_conversation():
    try:
        pipeline = get_pipeline()
        pipeline.reset()
        return {"success": True, "message": "Conversation reset"}
    except Exception as e:
        logger.exception("reset error")
        from fastapi import HTTPException
        raise HTTPException(500, str(e))


@router.websocket("/ws/voice")
async def voice_websocket(websocket: WebSocket):
    await websocket.accept()
    pipeline = get_pipeline()

    try:
        while True:
            audio_data = await websocket.receive_bytes()

            logger.info(f"WebSocket received: {len(audio_data)} bytes")

            result = await pipeline.process(audio_data)

            if result["success"]:
                await websocket.send_bytes(result["audio"])

                await websocket.send_json({
                    "transcript": result["transcript"],
                    "response": result["response"],
                    "gpu_memory": pipeline.get_gpu_memory()
                })
            else:
                await websocket.send_json({
                    "error": result.get("error", "Pipeline error"),
                    "success": False
                })

    except WebSocketDisconnect:
        logger.info("WebSocket disconnected")
    except Exception:
        logger.exception("WebSocket error")
        await websocket.close()
