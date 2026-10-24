# Navigation Tool for Agent Core
# Integrates pathfinding capabilities into RobotUTHv3 agent system

import logging
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

from .grid import Pathfinder
from .locations import MapManager, RobotMap

logger = logging.getLogger(__name__)


@dataclass
class MoveCommand:
    """Movement command structure"""
    action: str  # "move_to", "stop", "get_position", etc.
    parameters: Dict[str, any]


class NavigationTool:
    """
    Navigation tool for UTH campus pathfinding and robot movement control.

    This tool integrates with the Agent Core to allow users to:
    - Navigate to specific locations on campus
    - Get distance information between locations
    - Control robot movement (move, stop, emergency stop)
    - Query current position
    """

    def __init__(self, map_manager: Optional[MapManager] = None):
        """
        Initialize Navigation Tool

        Args:
            map_manager: Map manager instance (optional, creates default if not provided)
        """
        self.map_manager = map_manager or MapManager()
        self.current_map: Optional[RobotMap] = None
        self.pathfinder: Optional[Pathfinder] = None
        self.current_position: Tuple[int, int] = (0, 0)

        # Load default UTH campus map
        self.load_default_uth_map()

    def load_default_uth_map(self) -> bool:
        """Load default UTH campus map structure"""
        try:
            from .locations import RobotMap

            self.current_map = RobotMap.create_default_uth_map()
            self.pathfinder = Pathfinder(
                obstacle_grid=self.current_map.obstacle_grid,
                width=self.current_map.width,
                height=self.current_map.height
            )

            logger.info("Successfully loaded default UTH campus map")
            return True

        except Exception as e:
            logger.error(f"Failed to load UTH map: {e}")
            return False

    def load_map_from_json(self, filepath: str) -> bool:
        """Load map configuration from JSON file"""
        try:
            self.current_map = RobotMap.from_json_file(filepath)
            self.pathfinder = Pathfinder(
                obstacle_grid=self.current_map.obstacle_grid,
                width=self.current_map.width,
                height=self.current_map.height
            )
            logger.info(f"Loaded map from {filepath}")
            return True
        except Exception as e:
            logger.error(f"Failed to load map from {filepath}: {e}")
            return False

    def navigate_to_location(self, location_name: str,
                            start_x: Optional[int] = None,
                            start_y: Optional[int] = None) -> str:
        """
        Navigate to a named location on the UTH campus.

        Args:
            location_name: Name of the target location (e.g., 'building_a', 'library')
            start_x: Starting X coordinate (default: current position)
            start_y: Starting Y coordinate (default: current position)

        Returns:
            Vietnamese response string with navigation result
        """
        if not self.current_map:
            return "❌ Hệ thống bản đồ chưa được khởi tạo."

        # Find location by name
        target_location = self.current_map.get_location_by_name(location_name.lower())

        if not target_location:
            available_locations = ', '.join([loc.display_name_vietnamese for loc in self.current_map.locations.values()])
            return f"❌ Không tìm thấy địa điểm: {location_name}. \nĐịa điểm có sẵn: {available_locations}"

        # Calculate path
        start_pos = (start_x or self.current_position[0],
                    start_y or self.current_position[1])
        goal_pos = (target_location.x, target_location.y)

        path = self.pathfinder.find_path(start_pos, goal_pos)

        if not path:
            return f"❌ Không thể tìm đường đến {target_location.display_name_vietnamese}. Có thể có chướng ngại vật cản đường."

        # Visualize path (for debugging/logging)
        path_visualization = self.pathfinder.visualize_path_on_map(path)
        logger.debug(f"Found path with {len(path)} steps:\n{path_visualization}")

        # Simulate movement execution (integration point with hardware)
        success = self._execute_movement(path)

        if success:
            distance = self.pathfinder.get_path_distance(path)
            estimated_time = distance * 2.0  # Assume 0.5 m/s speed

            return (f"✅ Đã đến {target_location.display_name_vietnamese}!\n"
                   f"Số bước di chuyển: {len(path)}\n"
                   f"Khoảng cách: {distance:.1f} đơn vị (~{distance * 0.05:.1f} mét)\n"
                   f"Thời gian dự kiến: {estimated_time:.0f} giây")
        else:
            return "⚠️ Di chuyển bị gián đoạn do chướng ngại vật. Vui lòng thử lại."

    def calculate_distance(self, loc1_name: str, loc2_name: str) -> str:
        """
        Calculate distance between two campus locations.

        Args:
            loc1_name: First location name
            loc2_name: Second location name

        Returns:
            Distance calculation result in Vietnamese
        """
        if not self.current_map:
            return "❌ Hệ thống bản đồ chưa được khởi tạo."

        loc1 = self.current_map.get_location_by_name(loc1_name.lower())
        loc2 = self.current_map.get_location_by_name(loc2_name.lower())

        if not loc1:
            return f"❌ Không tìm thấy địa điểm: {loc1_name}"
        if not loc2:
            return f"❌ Không tìm thấy địa điểm: {loc2_name}"

        # Calculate Manhattan distance on grid
        grid_distance = abs(loc1.x - loc2.x) + abs(loc1.y - loc2.y)
        real_distance = grid_distance * 0.05  # Convert to meters

        return f"📏 Khoảng cách từ {loc1.display_name_vietnamese} đến {loc2.display_name_vietnamese}:\n" \
               f"- Trên bản đồ: {grid_distance} ô lưới\n" \
               f"- Thực tế: ~{real_distance:.1f} mét"

    def get_current_position(self) -> str:
        """Get current robot position"""
        x, y = self.current_position

        # Find closest named location
        closest = self.current_map.get_closest_location(x, y, max_locations=1) if self.current_map else []

        if closest:
            loc, dist = closest[0]
            return (f"📍 Vị trí hiện tại của bạn:\n"
                   f"Tọa độ: ({x}, {y})\n"
                   f"Gần nhất: {loc.display_name_vietnamese} (cách {dist} ô)")
        else:
            return f"📍 Vị trí hiện tại: ({x}, {y})"

    def emergency_stop(self) -> str:
        """Emergency stop command"""
        logger.warning("EMERGENCY STOP triggered!")
        return "🛑 ĐÃ DỪNG KHẨN CẤP! Tất cả chuyển động đã dừng ngay lập tức."

    def get_available_locations(self) -> str:
        """List all available campus locations"""
        if not self.current_map:
            return "❌ Chưa có dữ liệu bản đồ."

        lines = ["🗺️ Các địa điểm trong khu vực trường ĐH GTVT TP.HCM:", ""]

        for loc in sorted(self.current_map.locations.values(),
                         key=lambda loc: loc.display_name_vietnamese):
            category_icon = {
                'entrance': '🚪',
                'building': '🏢',
                'facility': '🏛️',
                'parking': '🅿️',
                'unknown': '📍'
            }.get(loc.category, '📍')

            lines.append(f"{category_icon} {loc.display_name_vietnamese} ({loc.display_name_english})")
            lines.append(f"   📐 Tọa độ: ({loc.x}, {loc.y}) | {loc.description}")

        return '\n'.join(lines)

    def follow_path_manually(self, path: List[Tuple[int, int]],
                            step_delay: float = 0.5) -> bool:
        """
        Follow a path step-by-step with delay (useful for testing).

        Args:
            path: Path coordinates list
            step_delay: Delay between movements in seconds

        Returns:
            True if completed successfully, False if interrupted
        """
        if len(path) < 2:
            return True

        logger.info(f"Starting path following with {len(path) - 1} steps")

        for i in range(len(path) - 1):
            current = path[i]
            next_pos = path[i + 1]

            direction = self._calculate_direction(current, next_pos)
            logger.info(f"Moving {direction} from {current} to {next_pos}")

            # Update position tracking
            self.current_position = next_pos

            # Simulated delay (in real implementation, wait for hardware completion)
            import time
            time.sleep(step_delay)

        return True

    def _execute_movement(self, path: List[Tuple[int, int]]) -> bool:
        """
        Execute movement along path (placeholder for hardware integration).

        In production, this would:
        - Send commands to motor controller
        - Monitor sensor feedback
        - Handle errors and replanning
        """
        logger.info(f"Executing movement along path of {len(path)} points")

        # TODO: Integrate with actual motor driver/robot hardware
        # For now, just simulate successful execution

        # Validate path doesn't hit obstacles
        for x, y in path:
            if not self.is_traversable(x, y):
                logger.error(f"Obstacle detected at path position ({x}, {y})")
                return False

        return True

    def is_traversable(self, x: int, y: int) -> bool:
        """Check if position is traversable"""
        if self.current_map:
            return self.current_map.is_traversable(x, y)
        return False

    def _calculate_direction(self, current: Tuple[int, int],
                           next_pos: Tuple[int, int]) -> str:
        """Calculate movement direction from two positions"""
        dx = next_pos[0] - current[0]
        dy = next_pos[1] - current[1]

        if abs(dx) > abs(dy):
            return "tiến" if dx > 0 else "lùi"
        else:
            return "phải" if dy > 0 else "trái"
