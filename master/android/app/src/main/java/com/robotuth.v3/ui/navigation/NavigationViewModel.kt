package com.robotuth.v3.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robotuth.v3.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.PriorityQueue

data class UthLocation(
    val name: String,
    val displayNameVi: String,
    val displayNameEn: String,
    val x: Int,
    val y: Int,
    val description: String
)

data class NavigationUiState(
    val width: Int = 50,
    val height: Int = 50,
    val currentX: Int = 25,
    val currentY: Int = 45,
    val targetX: Int? = null,
    val targetY: Int? = null,
    val selectedLocationName: String? = null,
    val path: List<Pair<Int, Int>> = emptyList(),
    val locations: List<UthLocation> = emptyList(),
    val statusText: String = "Chọn một địa điểm để tìm đường",
    val distanceText: String = "",
    val timeText: String = ""
)

class NavigationViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(NavigationUiState())
    val uiState: StateFlow<NavigationUiState> = _uiState.asStateFlow()

    // 50x50 obstacle grid representing UTH campus obstacles
    private val obstacleGrid = Array(50) { BooleanArray(50) }

    init {
        setupUthCampus()
    }

    private fun setupUthCampus() {
        val uthLocations = listOf(
            UthLocation("main_gate", "Cổng chính UTH", "Main Gate UTH", 25, 48, "Cổng chính đường Võ Văn Ngân"),
            UthLocation("building_a", "Tòa nhà A", "Building A", 23, 20, "Tòa nhà giảng đường chính"),
            UthLocation("library", "Thư viện UTH", "Library UTH", 35, 40, "Thư viện & phòng tự học"),
            UthLocation("cafeteria", "Nhà ăn sinh viên", "Student Cafeteria", 15, 35, "Khu ẩm thực & giải khát")
        )

        // Set up some obstacles (buildings/walls) in the grid
        // Main Building A area obstacles
        for (y in 15..25) {
            for (x in 20..25) {
                if (x != 23 || y != 20) { // Don't block the location itself
                    obstacleGrid[y][x] = true
                }
            }
        }

        // Library obstacles
        for (y in 35..44) {
            for (x in 30..39) {
                if (x != 35 || y != 40) {
                    obstacleGrid[y][x] = true
                }
            }
        }

        _uiState.update {
            it.copy(
                locations = uthLocations
            )
        }
    }

    fun isObstacle(x: Int, y: Int): Boolean {
        if (x in 0..49 && y in 0..49) {
            return obstacleGrid[y][x]
        }
        return true
    }

    fun selectLocation(location: UthLocation) {
        _uiState.update {
            it.copy(
                targetX = location.x,
                targetY = location.y,
                selectedLocationName = location.displayNameVi
            )
        }
        findPathToTarget()
    }

    fun setCustomTarget(x: Int, y: Int) {
        if (isObstacle(x, y)) {
            _uiState.update { it.copy(statusText = "Vị trí chọn bị vướng vật cản!") }
            return
        }
        _uiState.update {
            it.copy(
                targetX = x,
                targetY = y,
                selectedLocationName = "Tọa độ ($x, $y)"
            )
        }
        findPathToTarget()
    }

    private fun findPathToTarget() {
        val state = _uiState.value
        val startX = state.currentX
        val startY = state.currentY
        val goalX = state.targetX ?: return
        val goalY = state.targetY ?: return

        val path = runAStar(startX, startY, goalX, goalY)
        if (path != null) {
            val distance = path.size
            val realDist = distance * 0.05 // ~0.05m per cell
            val estTime = distance * 2 // 2 seconds per cell simulation

            _uiState.update {
                it.copy(
                    path = path,
                    statusText = "Đã tìm thấy đường đi!",
                    distanceText = "Khoảng cách: ${String.format("%.1f", realDist)} mét",
                    timeText = "Thời gian đi dự kiến: $estTime giây"
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    path = emptyList(),
                    statusText = "Không thể tìm thấy đường đi (bị chặn)!",
                    distanceText = "",
                    timeText = ""
                )
            }
        }
    }

    // A* algorithm implementation in Kotlin
    private fun runAStar(startX: Int, startY: Int, goalX: Int, goalY: Int): List<Pair<Int, Int>>? {
        val start = Pair(startX, startY)
        val goal = Pair(goalX, goalY)

        val openSet = PriorityQueue<Node>(compareBy { it.f })
        openSet.add(Node(start, 0.0, heuristic(start, goal)))

        val cameFrom = mutableMapOf<Pair<Int, Int>, Pair<Int, Int>>()
        val gScore = mutableMapOf<Pair<Int, Int>, Double>().withDefault { Double.MAX_VALUE }
        gScore[start] = 0.0

        val inOpenSet = mutableSetOf(start)
        val closedSet = mutableSetOf<Pair<Int, Int>>()

        val directions = listOf(
            Pair(0, 1), Pair(1, 0), Pair(0, -1), Pair(-1, 0)
        )

        while (openSet.isNotEmpty()) {
            val current = openSet.poll().pos
            inOpenSet.remove(current)

            if (current == goal) {
                return reconstructPath(cameFrom, current)
            }

            closedSet.add(current)

            for (dir in directions) {
                val neighborX = current.first + dir.first
                val neighborY = current.second + dir.second
                val neighbor = Pair(neighborX, neighborY)

                if (neighborX !in 0..49 || neighborY !in 0..49) continue
                if (isObstacle(neighborX, neighborY)) continue
                if (neighbor in closedSet) continue

                val tentativeG = gScore.getValue(current) + 1.0

                if (tentativeG < gScore.getValue(neighbor)) {
                    cameFrom[neighbor] = current
                    gScore[neighbor] = tentativeG
                    val f = tentativeG + heuristic(neighbor, goal)
                    
                    if (neighbor !in inOpenSet) {
                        openSet.add(Node(neighbor, tentativeG, f))
                        inOpenSet.add(neighbor)
                    }
                }
            }
        }
        return null
    }

    private fun heuristic(a: Pair<Int, Int>, b: Pair<Int, Int>): Double {
        return (Math.abs(a.first - b.first) + Math.abs(a.second - b.second)).toDouble()
    }

    private fun reconstructPath(cameFrom: Map<Pair<Int, Int>, Pair<Int, Int>>, current: Pair<Int, Int>): List<Pair<Int, Int>> {
        val path = mutableListOf(current)
        var curr = current
        while (cameFrom.containsKey(curr)) {
            curr = cameFrom[curr]!!
            path.add(0, curr)
        }
        return path
    }

    private data class Node(val pos: Pair<Int, Int>, val g: Double, val f: Double)
}
