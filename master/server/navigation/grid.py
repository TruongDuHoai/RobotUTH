# Pathfinding Algorithm Implementation using A* Search
# Optimized for grid-based navigation on UTH campus map

import heapq
import logging
from typing import Dict, List, Optional, Set, Tuple

logger = logging.getLogger(__name__)


class Pathfinder:
    """A* Pathfinding Algorithm Implementation

    This class implements the A* search algorithm for finding optimal paths
    between locations on a grid-based map. It is optimized for the robot
    navigation requirements at University of Transport Ho Chi Minh City.
    """

    def __init__(self, obstacle_grid: List[List[bool]],
                 width: int, height: int,
                 diagonal_movement: bool = False):
        """
        Initialize Pathfinder

        Args:
            obstacle_grid: 2D grid where True = obstacle, False = traversable
            width: Grid width in cells
            height: Grid height in cells
            diagonal_movement: Enable diagonal moves (cost increases)
        """
        self.obstacle_grid = obstacle_grid
        self.width = width
        self.height = height
        self.diagonal_movement = diagonal_movement

        # Movement directions based on configuration
        if diagonal_movement:
            self.directions = [
                (0, 1), (1, 0), (0, -1), (-1, 0),  # Cardinal
                (1, 1), (1, -1), (-1, 1), (-1, -1) # Diagonal
            ]
        else:
            self.directions = [(0, 1), (1, 0), (0, -1), (-1, 0)]

        # Cache for computed paths
        self._path_cache: Dict[Tuple[Tuple[int, int], Tuple[int, int]], List[Tuple[int, int]]] = {}

    def heuristic(self, a: Tuple[int, int], b: Tuple[int, int]) -> float:
        """
        Calculate heuristic distance between two points.
        Using Manhattan distance (L1 norm) which is admissible for grid movement.

        Args:
            a: First position (x, y)
            b: Second position (x, y)

        Returns:
            Estimated distance
        """
        return abs(a[0] - b[0]) + abs(a[1] - b[1])

    def heuristic_diagonal(self, a: Tuple[int, int], b: Tuple[int, int]) -> float:
        """
        Calculate diagonal-aware heuristic distance.
        Uses Chebyshev distance for diagonal movement.
        """
        dx = abs(a[0] - b[0])
        dy = abs(a[1] - b[1])
        return max(dx, dy)

    def get_neighbors(self, pos: Tuple[int, int]) -> List[Tuple[int, int]]:
        """
        Get valid neighboring positions from current location.

        Args:
            pos: Current position (x, y)

        Returns:
            List of valid neighboring positions
        """
        x, y = pos
        neighbors = []

        for dx, dy in self.directions:
            nx, ny = x + dx, y + dy

            # Check bounds
            if not (0 <= nx < self.width and 0 <= ny < self.height):
                continue

            # Check obstacles
            if not self.is_traversable(nx, ny):
                continue

            neighbors.append((nx, ny))

        return neighbors

    def is_traversable(self, x: int, y: int) -> bool:
        """Check if a position is within bounds and not an obstacle"""
        if 0 <= x < self.width and 0 <= y < self.height:
            return not self.obstacle_grid[y][x]
        return False

    def find_path(self, start: Tuple[int, int], goal: Tuple[int, int],
                  use_cache: bool = True) -> Optional[List[Tuple[int, int]]]:
        """
        Find path from start to goal using A* algorithm.

        Args:
            start: Starting position (x, y)
            goal: Goal position (x, y)
            use_cache: Whether to use path caching for performance

        Returns:
            List of positions forming the path, or None if no path exists
        """
        # Validate inputs
        if not self.is_valid_position(*start):
            logger.warning(f"Invalid start position: {start}")
            return None

        if not self.is_valid_position(*goal):
            logger.warning(f"Invalid goal position: {goal}")
            return None

        # Check cache first
        if use_cache:
            cache_key = (start, goal)
            if cache_key in self._path_cache:
                logger.debug("Using cached path")
                return self._path_cache[cache_key]

        try:
            # Initialize A* search structures
            open_set = []
            heapq.heappush(open_set, (0, start))

            came_from: Dict[Tuple[int, int], Tuple[int, int]] = {}
            g_score: Dict[Tuple[int, int], float] = {start: 0}
            f_score: Dict[Tuple[int, int], float] = {start: self.heuristic(start, goal)}

            open_set_hash = {start}
            closed_set: Set[Tuple[int, int]] = set()

            while open_set:
                # Get node with lowest f_score
                _, current = heapq.heappop(open_set)
                open_set_hash.discard(current)

                # Check if reached goal
                if current == goal:
                    return self._reconstruct_path(came_from, current)

                # Skip if already processed
                if current in closed_set:
                    continue

                closed_set.add(current)

                # Explore neighbors
                for neighbor in self.get_neighbors(current):
                    if neighbor in closed_set:
                        continue

                    # Calculate tentative g_score
                    tentative_g = g_score[current] + self._get_move_cost(current, neighbor)

                    if neighbor not in g_score or tentative_g < g_score[neighbor]:
                        # This path is better than previous ones
                        came_from[neighbor] = current
                        g_score[neighbor] = tentative_g
                        f_score[neighbor] = tentative_g + self.heuristic(neighbor, goal)

                        if neighbor not in open_set_hash:
                            heapq.heappush(open_set, (f_score[neighbor], neighbor))
                            open_set_hash.add(neighbor)

            # No path found
            logger.info(f"No path found from {start} to {goal}")
            return None

        except Exception as e:
            logger.error(f"Pathfinding error: {e}")
            return None

    def _get_move_cost(self, from_pos: Tuple[int, int],
                      to_pos: Tuple[int, int]) -> float:
        """Calculate cost of moving between two adjacent positions"""
        # Basic cost = 1 for cardinal movements
        # Diagonal movements have higher cost (~1.41 = sqrt(2))
        dx = abs(to_pos[0] - from_pos[0])
        dy = abs(to_pos[1] - from_pos[1])

        if dx == 1 and dy == 1:
            return 1.414  # Diagonal
        return 1.0  # Cardinal

    def _reconstruct_path(self, came_from: Dict[Tuple[int, int], Tuple[int, int]],
                         current: Tuple[int, int]) -> List[Tuple[int, int]]:
        """Reconstruct path from start to goal using came_from dictionary"""
        total_path = [current]
        while current in came_from:
            current = came_from[current]
            total_path.append(current)

        # Reverse to get path from start to goal
        total_path.reverse()
        return total_path

    def is_valid_position(self, x: int, y: int) -> bool:
        """Check if position is within grid bounds"""
        return 0 <= x < self.width and 0 <= y < self.height

    def get_path_distance(self, path: List[Tuple[int, int]]) -> float:
        """Calculate total distance of a path"""
        if len(path) < 2:
            return 0.0

        total_distance = 0.0
        for i in range(len(path) - 1):
            dx = abs(path[i+1][0] - path[i][0])
            dy = abs(path[i+1][1] - path[i][1])

            if dx == 1 and dy == 1:
                total_distance += 1.414
            else:
                total_distance += 1.0

        return total_distance

    def simplify_path(self, path: List[Tuple[int, int]],
                     tolerance: int = 1) -> List[Tuple[int, int]]:
        """
        Simplify path by removing unnecessary waypoints.

        Args:
            path: Original path with many waypoints
            tolerance: Minimum steps needed to justify a waypoint

        Returns:
            Simplified path with fewer waypoints
        """
        if len(path) <= tolerance:
            return path

        simplified = [path[0]]
        last_simplified_idx = 0

        for i in range(2, len(path)):
            # Check if intermediate points can be skipped
            can_skip = True
            for j in range(last_simplified_idx + 1, i):
                if not self._is_straight_line(path[last_simplified_idx],
                                              path[j],
                                              path[i]):
                    can_skip = False
                    break

            if not can_skip:
                # Need to include previous point
                simplified.append(path[i-1])
                last_simplified_idx = i - 1

        # Always include the goal
        simplified.append(path[-1])

        return simplified

    def _is_straight_line(self, start: Tuple[int, int],
                         mid: Tuple[int, int],
                         end: Tuple[int, int]) -> bool:
        """Check if mid point lies approximately on straight line between start and end"""
        # Vector from start to end
        dx_total = end[0] - start[0]
        dy_total = end[1] - start[1]

        # If almost straight line (dx or dy is 0)
        if abs(dx_total) < 2 and abs(dy_total) < 2:
            return True

        # Check perpendicular distance from mid to line
        if abs(dx_total) > abs(dy_total):
            # Mostly horizontal
            expected_y = start[1] + (mid[0] - start[0]) * dy_total / dx_total
            return abs(mid[1] - expected_y) < 1
        else:
            # Mostly vertical
            expected_x = start[0] + (mid[1] - start[1]) * dx_total / dy_total
            return abs(mid[0] - expected_x) < 1

    def visualize_path_on_map(self, path: List[Tuple[int, int]]) -> str:
        """Generate ASCII visualization of path on map"""
        lines = []

        for y in range(min(self.height, 30)):
            row_chars = []
            for x in range(min(self.width, 50)):
                if (x, y) in path:
                    row_chars.append('*')
                elif self.obstacle_grid[y][x]:
                    row_chars.append('X')
                else:
                    row_chars.append('.')
            lines.append(''.join(row_chars))

        return '\n'.join(lines)

    def clear_cache(self):
        """Clear path computation cache"""
        self._path_cache.clear()
