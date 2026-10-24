"""
Tools for Robotuth v3 - Open-Meteo Weather & Time Tools + Navigation Support
"""

import logging
import os
from dataclasses import dataclass
from datetime import datetime
from typing import List, Optional

import requests

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

@dataclass
class SearchResult:
    title: str
    url: str
    snippet: str

class OpenMeteoWeatherTool:
    """Open-Meteo Weather API - Free, no API key required"""

    def __init__(self):
        self.base_url = "https://api.open-meteo.com/v1/forecast"

    def _get_weather_description(self, code: int) -> str:
        codes = {
            0: "Troi quang",
            1: "Troi quang co may",
            2: "Nhieu may",
            3: "May day dac",
            45: "Mua rao rao",
            48: "Mua rao rao dong lanh",
            51: "Mua nhe",
            53: "Mua vua",
            55: "Mua lon",
            61: "Mua rat lon",
            63: "Mua rao",
            65: "Mua rao dong lanh",
            71: "Mua tuyet nhe",
            73: "Mua tuyet vua",
            75: "Mua tuyet lon",
            77: "Mua tuyet rat lon",
            80: "Mua dong",
            85: "Mua dong nhe",
            95: "Mua dong vua",
            96: "Mua dong lon",
            99: "Mua dong rat lon"
        }
        return codes.get(code, "Khong ro")

    def get_weather(self, location: str = "Hanoi") -> str:
        try:
            lat, lon = 21.0285, 105.8542

            params = {
                "latitude": lat,
                "longitude": lon,
                "current": "temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m"
            }

            response = requests.get(self.base_url, params=params, timeout=10)
            response.raise_for_status()

            data = response.json()
            current = data.get("current", {})

            temp = current.get("temperature_2m", 0)
            humidity = current.get("relative_humidity_2m", 0)
            wind = current.get("wind_speed_10m", 0)
            code = current.get("weather_code", 0)

            description = self._get_weather_description(code)

            result = (
                f"Thời tiết hiện tại: {temp}°C, độ ẩm {humidity}%, "
                f"gió {wind} km/h, {description}."
            )

            logger.info(f"Weather: {result}")
            return result

        except Exception as e:
            logger.error(f"Weather API error: {e}")
            return "Khong the lay thong tin thoi tiet."

class TimeTool:
    """Get current time (local tool, no API needed)"""

    @staticmethod
    def get_current_time() -> str:
        now = datetime.now()
        return f"Bây giờ là {now.strftime('%H:%M')} ngày {now.strftime('%d/%m/%Y')}."

    @staticmethod
    def get_date() -> str:
        now = datetime.now()
        return now.strftime("%d/%m/%Y")

class BraveSearchTool:
    """Brave Search API integration for web search (optional, requires API key)"""

    def __init__(self, api_key: Optional[str] = None):
        self.api_key = api_key or os.getenv("BRAVE_SEARCH_API_KEY")
        self.base_url = "https://api.search.brave.com/res/v1/web/search"

        if not self.api_key:
            logger.warning("BRAVE_SEARCH_API_KEY not set. Tool will be disabled.")

    def search(self, query: str, count: int = 5) -> List[SearchResult]:
        if not self.api_key:
            logger.error("Brave Search API key not configured")
            return []

        try:
            params = {
                "q": query,
                "count": count,
                "text_decorations": False,
                "search_lang": "vi-VN"
            }

            headers = {
                "Accept": "application/json",
                "X-Subscription-Token": self.api_key
            }

            response = requests.get(
                self.base_url,
                params=params,
                headers=headers,
                timeout=10
            )
            response.raise_for_status()

            data = response.json()
            results = []

            if "web" in data and "results" in data["web"]:
                for item in data["web"]["results"]:
                    results.append(SearchResult(
                        title=item.get("title", ""),
                        url=item.get("url", ""),
                        snippet=item.get("description", "")
                    ))

            return results[:count]

        except Exception as e:
            logger.error(f"Brave Search error: {e}")
            return []

    def get_snippet_summary(self, query: str, max_results: int = 3) -> str:
        results = self.search(query, count=max_results)
        if not results:
            return "Tim thay khong ket qua."

        snippets = [r.snippet for r in results if r.snippet]
        if not snippets:
            return f"Tim thay {len(results)} ket qua nhung khong co mo ta."

        summary = ". ".join(snippets[:2])
        if len(summary) > 200:
            summary = summary[:200] + "..."

        return summary

class KnowledgeSearchTool:
    """Fast local knowledge-base query tool."""

    def __init__(self, base_url: Optional[str] = None):
        self.base_url = base_url or os.getenv("KNOWLEDGE_API_URL", "http://localhost:8000")

    def query(self, query: str, top_k: int = 5) -> str:
        try:
            response = requests.post(
                f"{self.base_url.rstrip('/')}/api/knowledge/query",
                json={"query": query, "top_k": top_k, "include_sources": True},
                timeout=30,
            )
            response.raise_for_status()
            data = response.json()
            answer = data.get("answer") or "Tôi chưa có thông tin này trong tài liệu đã nạp."
            sources = data.get("sources") or []
            if sources:
                names = []
                for source in sources:
                    filename = source.get("filename")
                    if filename and filename not in names:
                        names.append(filename)
                if names:
                    answer += "\nNguồn: " + ", ".join(names[:3])
            return answer
        except Exception as e:
            logger.error(f"Knowledge search error: {e}")
            return "Không thể truy vấn hệ tri thức lúc này."


# Import Navigation Tool from navigation module
try:
    from ..navigation.tool import NavigationTool
except ImportError:
    logger.warning("Navigation tool not available - pathfinding not enabled")
    NavigationTool = None


class ToolRouter:
    """Router to determine which tool to call"""

    TOOL_KEYWORDS = {
        "weather": [
            "thoi tiet", "thoi tiet hom nay", "troi", "nong", "lanh",
            "mua", "nang", "gio", "am", "du bao", "nhiet do",
            "ha noi", "ho chi minh", "da nang", "can tho",
            "thời tiết", "trời", "nóng", "lạnh", "gió", "ẩm",
            "mưa", "nắng", "dự báo", "nhiệt độ",
        ],
        "brave_search": [
            "tin tuc", "bao chi", "su kien", "hom nay co gi",
            "ai la", "tong thong", "thu tuong", "gia", "mua ban",
            "cong thuc", "cach lam", "huong dan", "wiki", "google",
            "thanh pho", "dia diem", "du lich", "khach san",
            "tin tức", "báo chí", "sự kiện", "hôm nay có gì",
            "tổng thống", "thủ tướng", "giá", "thành phố",
            "công thức", "hướng dẫn", "du lịch", "khách sạn",
            "thủ tướng", "ai là ai", "ai", "người",
        ],
        "knowledge": [
            "tri thức", "tài liệu", "kiến thức", "thông tin về", "hỏi về",
            "quy định", "quy chế", "hướng dẫn", "robotuth biết",
        ],
        "time": [
            "may gio", "thoi gian", "bay gio la may", "ngay hom nay",
            "ngay mai", "hom qua", "thu may", "tuan nay",
            "mấy giờ", "bây giờ", "giờ", "ngày hôm nay",
            "ngày mai", "hôm qua", "thứ mấy", "tuần",
        ],
        "navigation": [
            "di chuyển", "đi đến", "đến đây", "đi tới", "chạy đến",
            "tới phòng", "tới khu vực", "tới chỗ", "đi đến bàn",
            "tới tòa nhà", "tòa nhà", "phòng học", "thư viện", "cổng chính",
            "navigate", "move", "go to", "come here", "here please",
            "đường đi", "lộ trình", "phương hướng",
            "dừng lại", "dừng ngay", "dừng khẩn cấp", "emergency stop",
            "khoảng cách", "bao xa", "cách bao nhiêu", "distance",
            "toạ đâu", "ở đâu", "vị trí", "current position",
            "return to start", "tránh vật cản", "obstacle",
        ]
    }

    def __init__(self, brave_api_key: Optional[str] = None):
        self.weather_tool = OpenMeteoWeatherTool()
        self.brave_search = BraveSearchTool(brave_api_key)
        self.time_tool = TimeTool()
        self.knowledge_tool = KnowledgeSearchTool()
        self.navigation_tool = None

        if NavigationTool is not None:
            try:
                self.navigation_tool = NavigationTool()
                logger.info("Navigation tool initialized successfully")
            except Exception as e:
                logger.error(f"Failed to initialize navigation tool: {e}")

    def detect_tool(self, query: str) -> Optional[str]:
        query_lower = query.lower()

        for tool, keywords in self.TOOL_KEYWORDS.items():
            if any(kw in query_lower for kw in keywords):
                return tool

        return None

    def execute_tool(self, tool_name: str, query: str) -> str:
        try:
            if tool_name == "weather":
                return self.weather_tool.get_weather()

            elif tool_name == "brave_search":
                results = self.brave_search.search(query, count=3)
                if not results:
                    return "Khong tim thay ket qua tim kiem."

                formatted = f"Tim thay {len(results)} ket qua:\n"
                for i, r in enumerate(results, 1):
                    formatted += f"{i}. {r.title}\n   {r.snippet[:150]}...\n"

                return formatted

            elif tool_name == "time":
                return self.time_tool.get_current_time()

            elif tool_name == "knowledge":
                return self.knowledge_tool.query(query)

            elif tool_name == "navigation":
                if self.navigation_tool is None:
                    return "Tinh nang dieu huong chua duoc kich hoat."

                query_lower = query.lower()

                if "đến" in query_lower or "tới" in query_lower or "tòa nhà" in query_lower:
                    target_loc = None
                    for loc_key in ["building_a", "library", "cafeteria", "main_gate"]:
                        if loc_key in query_lower:
                            target_loc = loc_key
                            break

                    if not target_loc:
                        mappings = {
                            "tòa nhà a": "building_a",
                            "thư viện": "library",
                            "quán ăn": "cafeteria",
                            "cổng chính": "main_gate",
                            "cổng": "main_gate"
                        }
                        for vi_name, eng_key in mappings.items():
                            if vi_name in query_lower:
                                target_loc = eng_key
                                break

                    if target_loc:
                        return self.navigation_tool.navigate_to_location(target_loc)
                    else:
                        return "Xin vui long chi dinh dia diem cu the (vi du: 'den thu vien', 'toa nha A')."

                elif "dừng" in query_lower or "stop" in query_lower:
                    return self.navigation_tool.emergency_stop()

                elif "khoảng cách" in query_lower or "bao xa" in query_lower:
                    return self.navigation_tool.calculate_distance("building_a", "library")

                elif "vị trí" in query_lower or "at đâu" in query_lower or "where am i" in query_lower:
                    return self.navigation_tool.get_current_position()

                elif "available locations" in query_lower or "các địa điểm" in query_lower:
                    return self.navigation_tool.get_available_locations()

                else:
                    return self.navigation_tool.navigate_to_location("library")

            else:
                return f"Tool khong duoc ho tro: {tool_name}"

        except Exception as e:
            logger.error(f"Tool execution error: {e}")
            return f"Loi khi thuc hien tool: {str(e)}"
