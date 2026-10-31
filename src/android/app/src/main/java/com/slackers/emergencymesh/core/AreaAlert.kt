package com.slackers.emergencymesh.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_000.0
private const val MAX_RADIUS_METERS = 200_000f
private val DIRECTIONS = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")

/**
 * A responder's message for one place. It stays readable by everyone, because a warning must reach
 * people who hold no keys, and its role signature proves who sent it. Every phone relays it, but
 * only phones inside the area show it.
 */
data class AreaAlert(
    val text: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
) {
    fun covers(atLatitude: Double, atLongitude: Double): Boolean =
        distanceMeters(latitude, longitude, atLatitude, atLongitude) <= radiusMeters

    fun toBody() = MessageBody(text, latitude, longitude, accuracyMeters = radiusMeters)

    companion object {
        /** A malformed area can't be checked, so it's refused rather than shown to everyone. */
        fun fromBody(body: MessageBody): AreaAlert? {
            val latitude = body.latitude?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
            val longitude = body.longitude?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
            val radius = body.accuracyMeters?.takeIf { it.isFinite() && it > 0f && it <= MAX_RADIUS_METERS } ?: return null
            if (body.text.isBlank()) return null
            return AreaAlert(body.text, latitude, longitude, radius)
        }
    }
}

/** The great-circle distance, which needs no maps or internet. */
fun distanceMeters(fromLatitude: Double, fromLongitude: Double, toLatitude: Double, toLongitude: Double): Double {
    val latitudeChange = Math.toRadians(toLatitude - fromLatitude)
    val longitudeChange = Math.toRadians(toLongitude - fromLongitude)
    val halfChord = sin(latitudeChange / 2).pow(2) +
        cos(Math.toRadians(fromLatitude)) * cos(Math.toRadians(toLatitude)) * sin(longitudeChange / 2).pow(2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(halfChord.coerceIn(0.0, 1.0)))
}

/** Which way to walk, in words, because a rescuer may have no map loaded. */
fun compassDirection(fromLatitude: Double, fromLongitude: Double, toLatitude: Double, toLongitude: Double): String {
    val from = Math.toRadians(fromLatitude)
    val to = Math.toRadians(toLatitude)
    val change = Math.toRadians(toLongitude - fromLongitude)
    val bearing = Math.toDegrees(atan2(sin(change) * cos(to), cos(from) * sin(to) - sin(from) * cos(to) * cos(change)))
    val sector = floor(((bearing + 360 + 22.5) % 360) / 45).toInt()
    return DIRECTIONS[sector % DIRECTIONS.size]
}
