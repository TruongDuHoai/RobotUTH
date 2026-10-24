# Map Manager for UTH Campus Navigation
# Handles map loading, storage, and location management

import json
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

import numpy as np


@dataclass
class MapLocation:
    """Represents a physical location in the UTH campus"""
    name: str                      # Short identifier (e.g., 'main_gate')
    display_name_vietnamese: str   # Vietnamese display name (e.g., 'Cổng chính')
    display_name_english: str      # English display name
    x: int                         # X coordinate on map grid
    y: int                         # Y coordinate on map grid
    description: str               # Location description
    category: str = 'unknown'      # Building, room, facility, etc.

    def to_dict(self) -> Dict[str, Any]:
        return {
            'name': self.name,
            'display_name_vietnamese': self.display_name_vietnamese,
            'display_name_english': self.display_name_english,
            'x': self.x,
            'y': self.y,
            'description': self.description,
            'category': self.category
        }


@dataclass
class RobotMap:
    """Represents the robot's navigable map of the UTH campus"""
    width: int                          # Map width in grid cells
    height: int                         # Map height in grid cells
    obstacle_grid: List[List[bool]]     # True = obstacle, False = traversable
    locations: Dict[str, MapLocation]   # Named locations
    metadata: Dict[str, Any] = field(default_factory=dict)  # Scale, units, etc.

    @classmethod
    def create_default_uth_map(cls) -> 'RobotMap':
        """Create default map structure for UTH campus"""

        # Initialize empty traversable grid
        obstacle_grid = [[False for _ in range(50)] for _ in range(50)]

        # Add example obstacles (walls, buildings)
        for y in range(15, 26):
            for x in range(20, 26):
                if 20 < x < 25 or 15 < y < 25:
                    obstacle_grid[y][x] = True

        # Example: Library area obstacle
        for y in range(35, 45):
            for x in range(30, 40):
                obstacle_grid[y][x] = True

        # Define major UTH campus locations
        locations = {
            'main_gate': MapLocation(
                name='main_gate',
                display_name_vietnamese='Cổng chính',
                display_name_english='Main Gate',
                x=25, y=48,
                description='Cổng chính của trường ĐH GTVT TP.HCM',
                category='entrance'
            ),
            'building_a': MapLocation(
                name='building_a',
                display_name_vietnamese='Tòa nhà A',
                display_name_english='Building A',
                x=23, y=20,
                description='Tòa nhà giảng đường chính',
                category='building'
            ),
            'library': MapLocation(
                name='library',
                display_name_vietnamese='Thư viện',
                display_name_english='Library',
                x=35, y=40,
                description='Thư viện trường ĐH GTVT',
                category='facility'
            ),
            'cafeteria': MapLocation(
                name='cafeteria',
                display_name_vietnamese='Quán ăn sinh viên',
                display_name_english='Student Cafeteria',
                x=15, y=35,
                description='Khu vực ăn uống sinh viên',
                category='facility'
            )
        }

        return cls(
            width=50,
            height=50,
            obstacle_grid=obstacle_grid,
            locations=locations,
            metadata={
                'cell_size_meters': 0.05,
                'origin': 'bottom_left',
                'coordinate_system': 'grid',
                'version': '1.0',
                'created_for': 'University of Transport Ho Chi Minh City'
            }
        )

    @classmethod
    def from_json_file(cls, filepath: str) -> 'RobotMap':
        """Load map configuration from JSON file"""
        with open(filepath, 'r', encoding='utf-8') as f:
            data = json.load(f)

        width = data['width']
        height = data['height']
        obstacle_grid = []
        obs_list = data.get('obstacles', [])

        for y in range(height):
            row = [False for _ in range(width)]
            for obs in obs_list:
                if obs.get('row') == y and obs.get('col') < width:
                    row[obs.get('col')] = True
            obstacle_grid.append(row)

        locations = {}
        for loc_data in data.get('locations', []):
            locations[loc_data['name']] = MapLocation(
                name=loc_data['name'],
                display_name_vietnamese=loc_data.get('vi', loc_data['name']),
                display_name_english=loc_data.get('en', loc_data['name']),
                x=loc_data['x'],
                y=loc_data['y'],
                description=loc_data.get('description', ''),
                category=loc_data.get('category', 'unknown')
            )

        return cls(
            width=width,
            height=height,
            obstacle_grid=obstacle_grid,
            locations=locations,
            metadata=data.get('metadata', {})
        )

    @classmethod
    def from_image(cls, image_path: str, cell_size: int = 5) -> 'RobotMap':
        """Load map from image file (white = traversable, black = obstacle)"""
        try:
            from PIL import Image

            img = Image.open(image_path).convert('L')
            img_array = np.array(img)

            width = (img.width + cell_size - 1) // cell_size
            height = (img.height + cell_size - 1) // cell_size

            obstacle_grid = []
            for y in range(height):
                row = []
                for x in range(width):
                    px_x = min(x * cell_size, img.width - 1)
                    px_y = min(y * cell_size, img.height - 1)
                    pixel_value = img_array[px_y, px_x]
                    row.append(pixel_value < 128)
                obstacle_grid.append(row)

            return cls(
                width=width,
                height=height,
                obstacle_grid=obstacle_grid,
                locations={},
                metadata={'source_image': image_path, 'cell_size': cell_size}
            )

        except ImportError:
            raise ImportError("Pillow library required. Install with: pip install Pillow")
        except Exception as e:
            raise RuntimeError(f"Failed to load map from image: {e}")

    def save_to_json(self, filepath: str):
        """Save map configuration to JSON file"""
        data = {
            'width': self.width,
            'height': self.height,
            'obstacles': [],
            'locations': [],
            'metadata': self.metadata
        }

        for y, row in enumerate(self.obstacle_grid):
            for x, is_obstacle in enumerate(row):
                if is_obstacle:
                    data['obstacles'].append({'row': y, 'col': x})

        for loc in self.locations.values():
            data['locations'].append(loc.to_dict())

        with open(filepath, 'w', encoding='utf-8') as f:
            json.dump(data, f, indent=2, ensure_ascii=False)

    def is_traversable(self, x: int, y: int) -> bool:
        """Check if a grid cell is traversable"""
        if 0 <= x < self.width and 0 <= y < self.height:
            return not self.obstacle_grid[y][x]
        return False

    def is_valid_position(self, x: int, y: int) -> bool:
        """Check if position is within map bounds"""
        return 0 <= x < self.width and 0 <= y < self.height

    def get_location_by_name(self, name: str) -> Optional[MapLocation]:
        """Get location by its name key"""
        return self.locations.get(name)

    def get_closest_location(self, x: int, y: int, max_locations: int = 3) -> List[Tuple[MapLocation, float]]:
        """Find closest named locations to given position"""
        distances = []
        for loc_name, loc in self.locations.items():
            dist = abs(loc.x - x) + abs(loc.y - y)
            distances.append((loc, dist))

        distances.sort(key=lambda x: x[1])
        return distances[:max_locations]

    def visualize_grid(self, path: Optional[List[Tuple[int, int]]] = None) -> str:
        """Generate ASCII visualization of map"""
        lines = []

        for y in range(min(self.height, 50)):
            line = ''
            for x in range(min(self.width, 60)):
                if path and (x, y) in path:
                    line += '#'
                elif self.obstacle_grid[y][x]:
                    line += 'X'
                else:
                    line += '.'
            lines.append(line)

        return '\n'.join(lines)


class MapManager:
    """Manages map loading, storage, and configuration"""

    def __init__(self):
        self.maps: Dict[str, Any] = {}
        self.default_map_id: Optional[str] = None
        self.load_default_uth_map()

    def load_default_uth_map(self) -> RobotMap:
        """Load default UTH campus map structure"""
        robot_map = RobotMap.create_default_uth_map()
        self.maps['default_uth'] = robot_map
        self.default_map_id = 'default_uth'
        return robot_map

    def get_map(self, map_id: str) -> Optional[RobotMap]:
        """Get map by ID"""
        return self.maps.get(map_id)
