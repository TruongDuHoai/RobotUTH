"""
Voice-to-Voice Conversation Pipeline
STT (Parakeet GPU) -> LLM (Gemma-4 GPU) -> TTS (VieNeU CPU)
"""

import asyncio
import logging
from typing import Dict, List, Optional

import numpy as np

from ..core.agent import RobotuthAgent
from .stt import get_asr_service
from .tts import get_tts_service

logger = logging.getLogger(__name__)


class VoiceToVoicePipeline:
    """Voice-to-voice conversation pipeline with GPU optimization"""

    def __init__(self):
        self.stt = get_asr_service()
        self.agent = RobotuthAgent()
        self.tts = get_tts_service()

        self.conversation_history: List[Dict[str, str]] = []
        self.max_history = 10
        self._lock = asyncio.Lock()

    async def process(self, audio_input: bytes) -> Dict:
        async with self._lock:
            try:
                logger.info("Step 1: STT transcription")
                audio_array = np.frombuffer(audio_input, dtype=np.float32)
                transcript = await asyncio.to_thread(self.stt.transcribe, audio_array)
                logger.info(f"Transcript: {transcript}")

                logger.info("Step 2: Agent generation")
                response = await asyncio.to_thread(self.agent.chat, transcript)
                logger.info(f"Response: {response}")

                self.conversation_history = [
                    {"user": turn["user"], "assistant": turn["assistant"]}
                    for turn in self.agent.core.conversation_history
                ]

                logger.info("Step 3: TTS synthesis")
                audio_output = await asyncio.to_thread(self.tts.synthesize, response)
                logger.info(f"TTS audio length: {len(audio_output)} bytes")

                return {
                    "transcript": transcript,
                    "response": response,
                    "audio": audio_output,
                    "success": True
                }

            except Exception as e:
                logger.error(f"Pipeline error: {e}")
                return {
                    "transcript": "",
                    "response": "Xin lỗi, có lỗi xảy ra trong quá trình xử lý.",
                    "audio": b"",
                    "success": False,
                    "error": str(e)
                }

    async def process_text(self, text_input: str) -> Dict:
        async with self._lock:
            try:
                logger.info("Agent text generation")
                response = await asyncio.to_thread(self.agent.chat, text_input)

                self.conversation_history = [
                    {"user": turn["user"], "assistant": turn["assistant"]}
                    for turn in self.agent.core.conversation_history
                ]

                audio_output = await asyncio.to_thread(self.tts.synthesize, response)

                return {
                    "response": response,
                    "audio": audio_output,
                    "success": True
                }

            except Exception as e:
                logger.error(f"Text pipeline error: {e}")
                return {
                    "response": "Xin lỗi, có lỗi xảy ra.",
                    "audio": b"",
                    "success": False,
                    "error": str(e)
                }

    def reset(self):
        self.conversation_history = []
        self.agent.reset()
        logger.info("Conversation history reset")

    def get_gpu_memory(self) -> Dict:
        try:
            import torch
            if torch.cuda.is_available():
                return {
                    "allocated_gb": torch.cuda.memory_allocated() / 1024**3,
                    "reserved_gb": torch.cuda.memory_reserved() / 1024**3,
                    "free_gb": (torch.cuda.get_device_properties(0).total_memory - torch.cuda.memory_reserved()) / 1024**3
                }
        except ImportError:
            pass
        return {"allocated_gb": 0, "reserved_gb": 0, "free_gb": 0}


_pipeline: Optional[VoiceToVoicePipeline] = None


def get_pipeline() -> VoiceToVoicePipeline:
    global _pipeline
    if _pipeline is None:
        _pipeline = VoiceToVoicePipeline()
    return _pipeline


def reset_pipeline():
    global _pipeline
    _pipeline = None
