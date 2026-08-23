package com.example.polyglotpocket.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Result of GPS location detection and geographical context resolution.
 */
data class GpsContextResult(
    val placeName: String,
    val theme: String,
    val themeDisplayName: String,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Resolves GPS position and maps the surrounding place/context
 * (via OpenStreetMap / Geocoder) to one of the app flashcard themes.
 */
object GpsThemeResolver {

    private val THEME_DISPLAY_NAMES = mapOf(
        "food" to "🍕 Food & Dining",
        "plants" to "🌳 Parks & Nature",
        "animals" to "🐶 Animals & Pets",
        "objects" to "🛍️ Shopping & Objects",
        "body" to "🏋️ Health & Sports",
        "places" to "🏛️ Places & Landmarks",
        "people" to "📚 Study & People",
        "money" to "💳 Money & Finance",
        "time" to "⏱️ Time",
        "materials" to "🧱 Materials",
        "emotions" to "😊 Emotions",
    )

    /**
     * Actively requests the exact high-accuracy location from Google Play Services FusedLocationProvider.
     * Instantly picks up emulator "Set Location" updates and real device movements.
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(context: Context): Location? = withContext(Dispatchers.IO) {
        try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            val cts = CancellationTokenSource()
            val location = fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await()
            location ?: getLastKnownLocation(context)
        } catch (_: Exception) {
            getLastKnownLocation(context)
        }
    }

    /**
     * Fallback cached location using system LocationManager.
     */
    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(context: Context): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = locationManager.getProviders(true)
        var bestLocation: Location? = null

        for (provider in providers) {
            val l = locationManager.getLastKnownLocation(provider) ?: continue
            if (bestLocation == null || l.accuracy < bestLocation.accuracy) {
                bestLocation = l
            }
        }
        return bestLocation
    }

    /**
     * Resolves the nearby place context and maps it to an app flashcard theme
     * by querying OpenStreetMap Nominatim with system Geocoder fallback.
     */
    suspend fun resolvePlaceAndTheme(context: Context, latitude: Double, longitude: Double): GpsContextResult =
        withContext(Dispatchers.IO) {
            var placeName = "Current location"
            var detectedCategory = ""

            // 1. Query OpenStreetMap Nominatim (Detailed reverse geocoding)
            try {
                val osmUrl = "https://nominatim.openstreetmap.org/reverse?format=json&lat=$latitude&lon=$longitude&zoom=18&addressdetails=1"
                val conn = (URL(osmUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "PolyglotPocket-Android/1.0")
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                if (conn.responseCode in 200..299) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(text)
                    val name = json.optString("name").ifEmpty {
                        json.optString("display_name").split(",").firstOrNull().orEmpty()
                    }
                    if (name.isNotBlank()) placeName = name

                    val category = json.optString("category")
                    val type = json.optString("type")
                    val addressObj = json.optJSONObject("address")
                    val extraTags = buildString {
                        append("$category $type ")
                        addressObj?.let { addr ->
                            val keys = addr.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                append("$key ${addr.optString(key)} ")
                            }
                        }
                    }
                    detectedCategory = extraTags
                }
                conn.disconnect()
            } catch (_: Exception) {
                // Fallback on timeout or network absence
            }

            // 2. If Nominatim did not return a specific name, fallback to Android Geocoder
            if (placeName == "Current location") {
                try {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                    val address = addresses?.firstOrNull()
                    if (address != null) {
                        placeName = address.featureName ?: address.thoroughfare ?: address.locality ?: "Current location"
                    }
                } catch (_: Exception) {}
            }

            // 3. Map detected category to app theme
            val theme = mapCategoryToTheme(detectedCategory, placeName)
            val displayName = THEME_DISPLAY_NAMES[theme] ?: theme.replaceFirstChar { it.uppercase() }

            GpsContextResult(
                placeName = placeName,
                theme = theme,
                themeDisplayName = displayName,
                latitude = latitude,
                longitude = longitude,
            )
        }

    /**
     * Maps OSM tags or place name keywords to flashcard themes.
     */
    private fun mapCategoryToTheme(category: String, placeName: String): String {
        val text = "$category $placeName".lowercase()

        return when {
            text.containsAny("restaurant", "cafe", "bar", "food", "pizza", "bakery", "supermarket", "ice_cream", "pub", "bistro", "diner", "fast_food", "deli", "coffee", "eatery", "meal") -> "food"
            text.containsAny("park", "garden", "forest", "pitch", "nature", "wood", "green", "tree", "flora", "botanical", "meadow", "nature_reserve") -> "plants"
            text.containsAny("zoo", "pet", "animal", "veterinary", "dog", "aquarium", "wildlife", "safari", "fauna") -> "animals"
            text.containsAny("craft", "carpenter", "quarry", "mine", "builder", "masonry", "timber", "lumber", "construction", "hardware") -> "materials"
            text.containsAny("shop", "store", "mall", "clothes", "electronics", "market", "outlet", "boutique", "retail", "furniture") -> "objects"
            text.containsAny("hospital", "pharmacy", "gym", "fitness", "doctor", "dentist", "sport", "pool", "clinic", "health", "wellness", "spa") -> "body"
            text.containsAny("school", "university", "college", "library", "faculty", "office", "campus", "academy", "classroom", "workplace") -> "people"
            text.containsAny("bank", "atm", "finance", "exchange", "credit", "vault") -> "money"
            text.containsAny("theatre", "theater", "cinema", "art", "arts_centre", "theme_park", "amusement", "comedy", "yoga", "meditation", "spa", "viewpoint", "memorial", "monastery", "concert", "circus") -> "emotions"
            text.containsAny("clock", "sundial", "watchmaker", "planetarium", "observatory", "watch") -> "time"
            text.containsAny("station", "monument", "museum", "square", "church", "cathedral", "castle", "tower", "street", "avenue", "road", "plaza", "hall", "bridge", "airport", "harbor") -> "places"
            else -> "places" // Default for outdoor / general street context
        }
    }

    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { this.contains(it) }
    }
}
