"""
Agent Core - Robotuth v3 AI Brain
Uses OpenAI-compatible API for LLM inference
"""

import logging
from dataclasses import dataclass, field
from typing import Dict, List, Optional

from ..config import KNOWLEDGE_ENABLED, KNOWLEDGE_USE_SYSTEM_PROMPT
from ..runtime_settings import get_llm_settings
from ..services.llm import LLMService
from .tools import ToolRouter

logger = logging.getLogger(__name__)


@dataclass
class AgentConfig:
    """Configuration for Agent Core"""
    api_base: str = ""
    api_key: str = ""
    model: str = ""
    reasoning_effort: str = ""
    context_size: int = 2048
    max_tokens: int = 256
    system_prompt: str = field(default="")

    def __post_init__(self):
        runtime = get_llm_settings()
        if not self.api_base:
            self.api_base = runtime["api_base"]
        if not self.api_key:
            self.api_key = runtime.get("api_key", "")
        if not self.model:
            self.model = runtime["model"]
        if not self.reasoning_effort:
            self.reasoning_effort = runtime.get("reasoning_effort", "")
        if not self.system_prompt:
            self.system_prompt = self._default_vi_prompt()

    @staticmethod
    def _default_vi_prompt() -> str:
        return (
            "Bạn là robotuth, trợ lý AI tiếng Việt. Trả lời ngắn gọn, rõ ràng, có dấu câu đầy đủ.\n\n"
            "QUAN TRONG:\n"
            "- KHI CÓ THÔNG TIN THỰC TẾ (thời tiết, thời gian): PHẢI trả lời DỰA TRÊN thông tin đó\n"
            "- KHÔNG trả lời chung chung khi có dữ liệu cụ thể\n"
            "- LUÔN dùng dấu chấm, phẩy, hỏi, chấm than\n\n"
            "Ví dụ:\n"
            "- Thông tin: '28°C, trời nắng' → Trả lời: 'Thời tiết hiện tại là 28°C, trời nắng đẹp.'\n"
            "- Thông tin: '14:30' → Trả lời: 'Bây giờ là 14:30.'\n"
            "- Không có thông tin: 'Chào bạn! Mình là robotuth, rất vui được giúp bạn.'"
        )


class AgentCore:
    """Core AI agent for Robotuth v3"""

    def __init__(self, config: Optional[AgentConfig] = None, brave_api_key: Optional[str] = None):
        self.config = config or AgentConfig()
        self.conversation_history: List[Dict[str, str]] = []
        self.max_history = 10
        self.tool_router = ToolRouter(brave_api_key)

    def _build_messages(self, user_message: str) -> List[Dict[str, str]]:
        messages = [{"role": "system", "content": self.config.system_prompt}]

        for turn in self.conversation_history[-self.max_history:]:
            messages.append({"role": "user", "content": turn['user']})
            messages.append({"role": "assistant", "content": turn['assistant']})

        messages.append({"role": "user", "content": user_message})
        return messages

    def _build_messages_with_context(self, user_message: str, context: str) -> List[Dict[str, str]]:
        system_msg = f"{self.config.system_prompt}\n\nTHÔNG TIN CÓ SẴN: {context}\n\nQUAN TRỌNG: Bạn PHẢI trả lời dựa trên thông tin trên."
        return [
            {"role": "system", "content": system_msg},
            {"role": "user", "content": user_message},
        ]

    def _call_llm(self, messages: List[Dict[str, str]]) -> str:
        """Single LLM contract: shared service with retry, raises LLMUnavailable.

        Callers (process → conversation pipeline) already fail soft with
        success:false, so no apology string is ever returned as an answer.
        """
        svc = LLMService(
            api_base=self.config.api_base,
            api_key=self.config.api_key,
            model=self.config.model,
            reasoning_effort=self.config.reasoning_effort,
        )
        return svc.chat(messages, max_tokens=self.config.max_tokens)

    def _query_knowledge(self, query: str) -> Optional[str]:
        """Answer via built-in knowledge system prompt (instruction + uploaded docs)."""
        if not KNOWLEDGE_ENABLED:
            return None
        try:
            from ..knowledge.search import get_search_service

            if KNOWLEDGE_USE_SYSTEM_PROMPT:
                result = get_search_service().query_with_system_prompt(query, include_sources=False)
            else:
                result = get_search_service().query(query, top_k=5, include_sources=False)
            if result.get("success"):
                answer = (result.get("answer") or "").strip()
                if answer:
                    return answer
        except Exception as exc:
            logger.warning("Knowledge query failed: %s", exc)
        return None

    def process(self, user_message: str) -> str:
        logger.info(f"User: {user_message}")

        if self._is_greeting(user_message):
            response = "Chào bạn! Mình là robotuth, rất vui được giúp bạn."
            logger.info(f"Bot: {response}")
            self.conversation_history.append({
                "user": user_message,
                "assistant": response,
                "tool_used": None,
                "tool_result": None
            })
            return response

        tool_name = self.tool_router.detect_tool(user_message)
        tool_result = None

        if tool_name:
            logger.info(f"Tool detected: {tool_name}")
            tool_result = self.tool_router.execute_tool(tool_name, user_message)
            logger.info(f"Tool result: {tool_result[:100]}...")

        if tool_name in ["weather", "time"] and tool_result:
            response = self._format_tool_response(tool_name, tool_result, user_message)
        else:
            response = self._query_knowledge(user_message)
            if response:
                logger.info("Knowledge answered query: %s", user_message[:80])
            else:
                if tool_result:
                    messages = self._build_messages_with_context(user_message, tool_result)
                else:
                    messages = self._build_messages(user_message)

                response = self._call_llm(messages)
                response = self._clean_response(response)

        self.conversation_history.append({
            "user": user_message,
            "assistant": response,
            "tool_used": tool_name,
            "tool_result": tool_result
        })

        if len(self.conversation_history) > self.max_history * 2:
            self.conversation_history = self.conversation_history[-self.max_history:]

        logger.info(f"Bot: {response}")
        return response

    def _is_greeting(self, message: str) -> bool:
        greeting_keywords = ["chào", "xin chào", "hello", "hi", "hey"]
        message_lower = message.lower().strip()
        return any(kw in message_lower for kw in greeting_keywords) and len(message_lower.split()) <= 3

    def _format_tool_response(self, tool_name: str, tool_result: str, user_message: str) -> str:
        if tool_name == "weather":
            result = tool_result.replace("Thoi tiet", "Thời tiết").replace("hien tai", "hiện tại")
            result = result.replace("do am", "độ ẩm").replace("gio", "gió")
            return f"{result} Bạn có cần thông tin thêm không?"
        elif tool_name == "time":
            return f"{tool_result}"
        else:
            return tool_result

    def _clean_response(self, text: str) -> str:
        for open_tag, close_tag in [("<思考>", "</思考>"), ("<thinking>", "</thinking>")]:
            if open_tag in text:
                text = text.split(close_tag)[-1] if close_tag in text else text.split(open_tag)[0]

        if "Thinking Process:" in text:
            text = text.split("Thinking Process:")[0].strip()

        text = text.replace("Assistant:", "").strip()
        text = text.replace("User:", "").strip()
        text = text.replace("System:", "").strip()

        lines = [line.strip() for line in text.split('\n') if line.strip()]
        text = ' '.join(lines)

        if not text:
            fallback_responses = [
                "Chào bạn! Mình là robotuth, rất vui được giúp bạn.",
                "Xin chào! Mình có thể giúp gì cho bạn?",
                "Dạ, mình đang lắng nghe. Bạn cần giúp gì không?"
            ]
            import random
            return random.choice(fallback_responses)

        return text

    def reset_history(self):
        self.conversation_history.clear()
        logger.info("Conversation history reset")

    def get_status(self) -> Dict:
        return {
            "history_turns": len(self.conversation_history),
            "max_history": self.max_history,
            "api_base": self.config.api_base,
            "model": self.config.model,
            "reasoning_effort": self.config.reasoning_effort,
        }


class SimpleToolRouter:
    """Simple rule-based tool router for common tasks"""

    TOOLS = {
        "weather": ["thời tiết", "mưa", "nắng", "nhiệt độ", "dự báo"],
        "time": ["mấy giờ", "thời gian", "ngày hôm nay", "hôm nay là"],
        "calc": ["tính", "bằng bao nhiêu", "+", "-", "x", "chia"],
        "greeting": ["chào", "xin chào", "hey", "hello", "hi"],
        "name": ["tên gì", "là ai", "giới thiệu"],
        "capability": ["làm gì", "khả năng", "giúp gì"]
    }

    @classmethod
    def detect_intent(cls, message: str) -> Optional[str]:
        msg_lower = message.lower()
        for intent, keywords in cls.TOOLS.items():
            if any(kw in msg_lower for kw in keywords):
                return intent
        return None

    @classmethod
    def get_quick_response(cls, intent: str) -> Optional[str]:
        responses = {
            "greeting": "Chào bạn! Mình là robotuth, rất vui được gặp bạn. Bạn cần mình giúp gì không?",
            "name": "Mình là robotuth, một trợ lý AI thông minh. Rất vui được làm quen với bạn!",
            "capability": "Mình có thể trả lời câu hỏi, tính toán, trò chuyện, và hỗ trợ bạn nhiều việc khác. Bạn thử hỏi xem!",
            "time": None,
            "weather": None,
            "calc": None,
        }
        return responses.get(intent)


class RobotuthAgent:
    """High-level agent combining LLM + tool routing"""

    def __init__(self, config: Optional[AgentConfig] = None, brave_api_key: Optional[str] = None):
        self.core = AgentCore(config, brave_api_key)
        self.simple_tool_router = SimpleToolRouter()

    def chat(self, user_message: str) -> str:
        return self.core.process(user_message)

    def reset(self):
        self.core.reset_history()
