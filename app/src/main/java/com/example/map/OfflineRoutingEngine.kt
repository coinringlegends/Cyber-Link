package com.example.map

import java.io.File
import java.util.PriorityQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Real Offline A* Routing Engine operating strictly on installed local .clroute road graphs.
 * If no routing graph is installed or disconnected, honestly returns MissingData("ROUTINGDATEN FEHLEN").
 * NEVER draws fake straight-line "routes".
 */
class OfflineRoutingEngine(
    private val storageManager: MapStorageManager,
) {
    enum class RouteProfile(val labelDe: String, val defaultSpeedMps: Double) {
        AUTO("Auto", 13.9),         // ~50 km/h
        FAHRRAD("Fahrrad", 4.5),    // ~16 km/h
        FUSSGAENGER("Fußgänger", 1.35),
        WANDERN("Wandern", 1.15),
    }

    enum class RouteOptimization(val labelDe: String) {
        FASTEST("Schnellste Strecke"),
        SHORTEST("Kürzeste Strecke"),
    }

    data class RouteOptions(
        val profile: RouteProfile = RouteProfile.AUTO,
        val optimization: RouteOptimization = RouteOptimization.FASTEST,
        val avoidHighways: Boolean = false,
        val avoidUnpaved: Boolean = false,
    )

    @Serializable
    data class GraphNode(
        val id: String,
        val lat: Double,
        val lon: Double,
    )

    @Serializable
    data class GraphEdge(
        val fromId: String,
        val toId: String,
        val distanceMeters: Double,
        val speedMps: Double = 13.9,
        val isHighway: Boolean = false,
        val isUnpaved: Boolean = false,
        val allowedProfiles: List<String> = listOf("AUTO", "FAHRRAD", "FUSSGAENGER", "WANDERN"),
    )

    @Serializable
    data class RoutingGraph(
        val version: String,
        val regionId: String,
        val nodes: List<GraphNode>,
        val edges: List<GraphEdge>,
    )

    @Serializable
    data class RoutePoint(val lat: Double, val lon: Double)

    data class CalculatedRoute(
        val points: List<RoutePoint>,
        val distanceMeters: Double,
        val durationSeconds: Long,
        val routingDataVersion: String,
        val polylineJson: String,
    )

    sealed class RoutingResult {
        data class Success(val route: CalculatedRoute) : RoutingResult()
        data class MissingData(val message: String = "ROUTINGDATEN FEHLEN") : RoutingResult()
        data class NoPathFound(val message: String) : RoutingResult()
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun calculateRoute(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        waypoints: List<RoutePoint> = emptyList(),
        options: RouteOptions = RouteOptions(),
    ): RoutingResult = withContext(Dispatchers.Default) {
        val graphFiles = storageManager.routingDir.listFiles { f ->
            f.extension.equals("clroute", ignoreCase = true)
        }?.toList().orEmpty()

        if (graphFiles.isEmpty()) {
            return@withContext RoutingResult.MissingData("ROUTINGDATEN FEHLEN: Kein lokaler Routinggraph (.clroute) installiert.")
        }

        // Load first valid JSON routing graph covering start/end
        var selectedGraph: RoutingGraph? = null
        for (file in graphFiles) {
            val parsed = runCatching {
                json.decodeFromString<RoutingGraph>(file.readText(Charsets.UTF_8))
            }.getOrNull() ?: continue
            if (parsed.nodes.isNotEmpty() && parsed.edges.isNotEmpty()) {
                selectedGraph = parsed
                break
            }
        }

        val graph = selectedGraph
            ?: return@withContext RoutingResult.MissingData("ROUTINGDATEN FEHLEN: Keine gültigen Graphendaten gefunden.")

        val stops = listOf(RoutePoint(startLat, startLon)) + waypoints + listOf(RoutePoint(endLat, endLon))
        val combinedPoints = mutableListOf<RoutePoint>()
        var totalDistance = 0.0
        var totalDuration = 0.0

        for (i in 0 until stops.size - 1) {
            val legStart = stops[i]
            val legEnd = stops[i + 1]
            val startNode = nearestNode(graph.nodes, legStart.lat, legStart.lon)
                ?: return@withContext RoutingResult.MissingData("ROUTINGDATEN FEHLEN")
            val endNode = nearestNode(graph.nodes, legEnd.lat, legEnd.lon)
                ?: return@withContext RoutingResult.MissingData("ROUTINGDATEN FEHLEN")

            // If nearest graph node is > 25 km away, graph does not cover this region
            if (ContactMapStatusResolver.distanceMeters(legStart.lat, legStart.lon, startNode.lat, startNode.lon) > 25_000.0 ||
                ContactMapStatusResolver.distanceMeters(legEnd.lat, legEnd.lon, endNode.lat, endNode.lon) > 25_000.0
            ) {
                return@withContext RoutingResult.MissingData("ROUTINGDATEN FEHLEN: Region außerhalb des installierten Routinggraphen.")
            }

            val leg = runAStar(graph, startNode, endNode, options)
                ?: return@withContext RoutingResult.NoPathFound("Keine gültige Offline-Route für Profil ${options.profile.labelDe} gefunden.")

            if (combinedPoints.isEmpty()) {
                combinedPoints.addAll(leg.points)
            } else {
                combinedPoints.addAll(leg.points.drop(1))
            }
            totalDistance += leg.distanceMeters
            totalDuration += leg.durationSeconds
        }

        RoutingResult.Success(
            CalculatedRoute(
                points = combinedPoints,
                distanceMeters = totalDistance,
                durationSeconds = totalDuration.toLong(),
                routingDataVersion = graph.version,
                polylineJson = json.encodeToString(combinedPoints),
            )
        )
    }

    private fun runAStar(
        graph: RoutingGraph,
        start: GraphNode,
        goal: GraphNode,
        options: RouteOptions,
    ): CalculatedRoute? {
        val nodeById = graph.nodes.associateBy { it.id }
        val adj = HashMap<String, MutableList<GraphEdge>>()
        for (edge in graph.edges) {
            if (options.avoidHighways && edge.isHighway) continue
            if (options.avoidUnpaved && edge.isUnpaved) continue
            if (options.profile.name !in edge.allowedProfiles) continue
            adj.getOrPut(edge.fromId) { mutableListOf() }.add(edge)
        }

        data class OpenEntry(val nodeId: String, val fScore: Double) : Comparable<OpenEntry> {
            override fun compareTo(other: OpenEntry): Int = fScore.compareTo(other.fScore)
        }

        val gScore = HashMap<String, Double>().apply { put(start.id, 0.0) }
        val distAccum = HashMap<String, Double>().apply { put(start.id, 0.0) }
        val timeAccum = HashMap<String, Double>().apply { put(start.id, 0.0) }
        val cameFrom = HashMap<String, String>()
        val openSet = PriorityQueue<OpenEntry>().apply {
            add(OpenEntry(start.id, heuristic(start, goal, options)))
        }
        val closed = HashSet<String>()

        while (openSet.isNotEmpty()) {
            val current = openSet.poll() ?: break
            if (current.nodeId == goal.id) {
                val pathIds = mutableListOf<String>()
                var cursor: String? = goal.id
                while (cursor != null) {
                    pathIds.add(cursor)
                    cursor = cameFrom[cursor]
                }
                pathIds.reverse()
                val pts = pathIds.mapNotNull { nodeById[it] }.map { RoutePoint(it.lat, it.lon) }
                return CalculatedRoute(
                    points = pts,
                    distanceMeters = distAccum[goal.id] ?: 0.0,
                    durationSeconds = (timeAccum[goal.id] ?: 0.0).toLong(),
                    routingDataVersion = graph.version,
                    polylineJson = "",
                )
            }
            if (!closed.add(current.nodeId)) continue

            val curDist = distAccum[current.nodeId] ?: 0.0
            val curTime = timeAccum[current.nodeId] ?: 0.0
            val curG = gScore[current.nodeId] ?: Double.POSITIVE_INFINITY

            for (edge in adj[current.nodeId].orEmpty()) {
                val neighbor = nodeById[edge.toId] ?: continue
                if (neighbor.id in closed) continue
                val effectiveSpeed = minOf(edge.speedMps, options.profile.defaultSpeedMps).coerceAtLeast(0.5)
                val edgeTime = edge.distanceMeters / effectiveSpeed
                val stepCost = when (options.optimization) {
                    RouteOptimization.SHORTEST -> edge.distanceMeters
                    RouteOptimization.FASTEST -> edgeTime
                }
                val tentativeG = curG + stepCost
                if (tentativeG < (gScore[neighbor.id] ?: Double.POSITIVE_INFINITY)) {
                    cameFrom[neighbor.id] = current.nodeId
                    gScore[neighbor.id] = tentativeG
                    distAccum[neighbor.id] = curDist + edge.distanceMeters
                    timeAccum[neighbor.id] = curTime + edgeTime
                    openSet.add(OpenEntry(neighbor.id, tentativeG + heuristic(neighbor, goal, options)))
                }
            }
        }
        return null
    }

    private fun heuristic(a: GraphNode, b: GraphNode, options: RouteOptions): Double {
        val dist = ContactMapStatusResolver.distanceMeters(a.lat, a.lon, b.lat, b.lon)
        return when (options.optimization) {
            RouteOptimization.SHORTEST -> dist
            RouteOptimization.FASTEST -> dist / options.profile.defaultSpeedMps
        }
    }

    private fun nearestNode(nodes: List<GraphNode>, lat: Double, lon: Double): GraphNode? {
        var best: GraphNode? = null
        var bestDist = Double.POSITIVE_INFINITY
        for (node in nodes) {
            val d = ContactMapStatusResolver.distanceMeters(lat, lon, node.lat, node.lon)
            if (d < bestDist) {
                bestDist = d
                best = node
            }
        }
        return best
    }
}
