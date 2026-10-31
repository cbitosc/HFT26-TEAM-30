package com.slackers.emergencymesh.engine

/** A recently learned next hop toward a device. */
internal data class MeshRoute(
    val destinationDeviceId: String,
    val nextHopDeviceId: String,
    val hopCount: Int,
    val learnedAtMillis: Long,
)

/** In-memory shortest-hop routes learned from verified packets. */
internal class RouteTable(
    private val routeLifetimeMillis: Long = ROUTE_LIFETIME_MILLIS,
) {
    private val routes = mutableMapOf<String, MeshRoute>()

    @Synchronized
    fun learn(destinationDeviceId: String, nextHopDeviceId: String, hopCount: Int, nowMillis: Long) {
        if (destinationDeviceId.isBlank() || nextHopDeviceId.isBlank() || hopCount < 1) return
        val current = freshRoute(destinationDeviceId, nowMillis)
        if (current == null || hopCount <= current.hopCount) {
            routes[destinationDeviceId] = MeshRoute(
                destinationDeviceId = destinationDeviceId,
                nextHopDeviceId = nextHopDeviceId,
                hopCount = hopCount,
                learnedAtMillis = nowMillis,
            )
        }
    }

    @Synchronized
    fun routeTo(destinationDeviceId: String, nowMillis: Long): MeshRoute? =
        freshRoute(destinationDeviceId, nowMillis)

    @Synchronized
    fun forgetVia(nextHopDeviceId: String) {
        routes.entries.removeAll { it.value.nextHopDeviceId == nextHopDeviceId }
    }

    private fun freshRoute(destinationDeviceId: String, nowMillis: Long): MeshRoute? {
        val route = routes[destinationDeviceId] ?: return null
        if (nowMillis >= route.learnedAtMillis && nowMillis - route.learnedAtMillis >= routeLifetimeMillis) {
            routes.remove(destinationDeviceId)
            return null
        }
        return route
    }

    private companion object {
        const val ROUTE_LIFETIME_MILLIS = 5 * 60 * 1_000L
    }
}
