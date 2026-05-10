package com.example.track.network

import android.util.Log
import com.example.track.Constants
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

private const val TAG = "DirectionsService"

sealed class DirectionsResult {
    data class Success(val routeDetails: RouteDetails) : DirectionsResult()
    data class Error(val message: String, val status: String? = null) : DirectionsResult()
}

object DirectionsService {

    suspend fun getRoutePolyline(
        origin: LatLng,
        destination: LatLng
    ): List<LatLng>? = withContext(Dispatchers.IO) {
        try {
            val url = buildDirectionsUrl(origin, destination)
            Log.d(TAG, "Fetching route from: $url")
            val response = URL(url).readText()
            Log.d(TAG, "Response received: ${response.take(500)}...")
            parseRouteFromResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching route", e)
            null
        }
    }

    private fun buildDirectionsUrl(origin: LatLng, destination: LatLng): String {
        return "https://maps.googleapis.com/maps/api/directions/json?" +
                "origin=${origin.latitude},${origin.longitude}" +
                "&destination=${destination.latitude},${destination.longitude}" +
                "&mode=driving" +
                "&key=${Constants.MAPS_API_KEY}"
    }

    private fun parseRouteFromResponse(response: String): List<LatLng>? {
        val json = JSONObject(response)
        val status = json.getString("status")
        Log.d(TAG, "Directions API status: $status")

        if (status != "OK") {
            val errorMessage = json.optString("error_message", "Unknown error")
            Log.e(TAG, "Directions API error: $status - $errorMessage")
            return null
        }

        val routes = json.getJSONArray("routes")
        if (routes.length() == 0) {
            Log.e(TAG, "No routes returned")
            return null
        }

        val route = routes.getJSONObject(0)
        val overviewPolyline = route.getJSONObject("overview_polyline")
        val encodedPolyline = overviewPolyline.getString("points")
        Log.d(TAG, "Encoded polyline length: ${encodedPolyline.length}")

        return decodePolyline(encodedPolyline)
    }

    /**
     * Decodes an encoded polyline string into a list of LatLng points.
     * This is the standard Google Polyline Algorithm.
     */
    fun decodePolyline(encoded: String): List<LatLng> {
        val poly = mutableListOf<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0

            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)

            val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dlat

            shift = 0
            result = 0

            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)

            val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dlng

            val latLng = LatLng(lat.toDouble() / 1E5, lng.toDouble() / 1E5)
            poly.add(latLng)
        }

        Log.d(TAG, "Decoded ${poly.size} points from polyline")
        return poly
    }

    /**
     * Gets route details including distance and duration with detailed error info
     */
    suspend fun getRouteDetailsWithStatus(
        origin: LatLng,
        destination: LatLng
    ): DirectionsResult = withContext(Dispatchers.IO) {
        try {
            val url = buildDirectionsUrl(origin, destination)
            Log.d(TAG, "Fetching directions: origin=${origin.latitude},${origin.longitude} dest=${destination.latitude},${destination.longitude}")

            val response = URL(url).readText()
            Log.d(TAG, "Raw response: ${response.take(1000)}")

            val json = JSONObject(response)
            val status = json.getString("status")

            when (status) {
                "OK" -> {
                    val routes = json.getJSONArray("routes")
                    if (routes.length() == 0) {
                        DirectionsResult.Error("No routes found", status)
                    } else {
                        val route = routes.getJSONObject(0)
                        val legs = route.getJSONArray("legs")
                        val leg = legs.getJSONObject(0)
                        val distance = leg.getJSONObject("distance")
                        val duration = leg.getJSONObject("duration")
                        val overviewPolyline = route.getJSONObject("overview_polyline")
                        val encodedPolyline = overviewPolyline.getString("points")

                        val polylinePoints = decodePolyline(encodedPolyline)
                        Log.d(TAG, "Route found: ${distance.getString("text")}, ${duration.getString("text")}, ${polylinePoints.size} points")

                        DirectionsResult.Success(
                            RouteDetails(
                                distanceText = distance.getString("text"),
                                distanceValue = distance.getInt("value"),
                                durationText = duration.getString("text"),
                                durationValue = duration.getInt("value"),
                                polylinePoints = polylinePoints
                            )
                        )
                    }
                }
                "REQUEST_DENIED" -> {
                    val errorMessage = json.optString("error_message", "API key issue or Directions API not enabled")
                    Log.e(TAG, "REQUEST_DENIED: $errorMessage")
                    DirectionsResult.Error("API Error: $errorMessage\n\nPlease enable Directions API in Google Cloud Console", status)
                }
                "OVER_QUERY_LIMIT" -> {
                    DirectionsResult.Error("API quota exceeded. Please check billing.", status)
                }
                "ZERO_RESULTS" -> {
                    DirectionsResult.Error("No route found between these locations", status)
                }
                else -> {
                    val errorMessage = json.optString("error_message", "Unknown error")
                    Log.e(TAG, "API Error: $status - $errorMessage")
                    DirectionsResult.Error("$status: $errorMessage", status)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error fetching directions", e)
            DirectionsResult.Error("Network error: ${e.message}")
        }
    }

    /**
     * Gets route details including distance and duration (legacy method)
     */
    suspend fun getRouteDetails(
        origin: LatLng,
        destination: LatLng
    ): RouteDetails? {
        return when (val result = getRouteDetailsWithStatus(origin, destination)) {
            is DirectionsResult.Success -> result.routeDetails
            is DirectionsResult.Error -> {
                Log.e(TAG, "getRouteDetails failed: ${result.message}")
                null
            }
        }
    }
}

data class RouteDetails(
    val distanceText: String,      // e.g., "12.5 km"
    val distanceValue: Int,        // in meters
    val durationText: String,      // e.g., "25 mins"
    val durationValue: Int,        // in seconds
    val polylinePoints: List<LatLng>
)
