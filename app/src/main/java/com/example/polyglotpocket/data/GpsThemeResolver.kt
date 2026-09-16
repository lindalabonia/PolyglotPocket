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
 * REQ. 5 (Location & GPS) & REQ. 1 (External Cloud / REST Services):
 * Result of GPS position detection and OpenStreetMap semantic reverse geocoding.
 *
 * Encapsulates the resolved human-readable place name, the mapped flashcard theme,
 * user-friendly UI display label, and physical WGS84 geographic coordinates.
 */
data class GpsContextResult(
    val placeName: String,
    val theme: String,
    val themeDisplayName: String,
    val latitude: Double,
    val longitude: Double,
)

/**
 * REQ. 5 (Location & GPS) & REQ. 1 (External Cloud / REST Services):
 * Geographic Position Engine and Context-Aware Theme Resolver.
 *
 * Architectural Workflow:
 *  1. **High-Accuracy Positioning (Google Play Services)**:
 *     Uses [com.google.android.gms.location.FusedLocationProviderClient] with [Priority.PRIORITY_HIGH_ACCURACY].
 *     Unlike the legacy Android framework [LocationManager] (which requires manual switching between
 *     `GPS_PROVIDER` and `NETWORK_PROVIDER` and suffers from slow indoor Time-To-First-Fix),
 *     the Fused Location Provider fuses GPS satellites, Wi-Fi, cell towers, and Bluetooth sensors.
 *     It also responds instantly to developer "Set Location" changes in the Android Studio Emulator.
 *  2. **Defensive Fallback Architecture**:
 *     If Google Play Services is unavailable or disabled, the engine falls back transparently
 *     to the system [LocationManager] cached provider reading.
 *  3. **External REST API Integration (OpenStreetMap Nominatim)**:
 *     Dispatches an HTTP GET request to `https://nominatim.openstreetmap.org/reverse` on a
 *     [Dispatchers.IO] coroutine thread. Parses JSON metadata (POI categories, OSM tags, amenity types).
 *  4. **Context-Aware Semantic Mapping**:
 *     Translates physical surrounding points of interest (e.g., restaurant, subway station, park)
 *     into targeted vocabulary study themes (`food`, `places`, `plants`), enabling location-based flashcard training.
 */
object GpsThemeResolver {

    // Human-readable labels and emoji badges displayed on the confirmation dialog
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
     * REQ. 5: Actively requests the exact high-accuracy location from Google Play Services.
     *
     * Why [FusedLocationProviderClient] over [LocationManager]:
     *  - Intelligent Sensor Fusion: Combines GPS, Wi-Fi, and cellular trilateration.
     *  - Immediate Emulator Sync: Accurately reflects location changes made via Android Studio's emulator controls.
     *  - Power Efficiency: Offloads satellite acquisition power to OS-level shared caches.
     *
     * Runs in a non-blocking [Dispatchers.IO] coroutine thread via Kotlin Task extensions (`.await()`).
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(context: Context): Location? = withContext(Dispatchers.IO) {
        try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            val cts = CancellationTokenSource()
            // Request an active, high-accuracy single location update
            val location = fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await()
            location ?: getLastKnownLocation(context)
        } catch (_: Exception) {
            getLastKnownLocation(context)
        }
    }

    /**
     * Secondary fallback using native Android [LocationManager].
     * Iterates through active providers (GPS, Network, Passive) and selects the reading
     * with the lowest accuracy error margin.
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
     * REQ. 1 & REQ. 5: Performs reverse geocoding by querying the OpenStreetMap Nominatim REST API.
     *
     * Communication Pipeline:
     *  1. Constructs an HTTP GET request to `https://nominatim.openstreetmap.org/reverse`.
     *  2. Passes WGS84 latitude, longitude, `zoom=18` (building-level precision), and `addressdetails=1`.
     *  3. Sets a custom `User-Agent` header as required by OpenStreetMap foundation acceptable use policies.
     *  4. Parses the JSON response to extract the place name, primary category, OSM type, and address tags.
     *  5. If the network call fails or Nominatim returns an empty name, falls back to Android's local [Geocoder].
     *  6. Evaluates [mapCategoryToTheme] to bind the geographic context to an app study category.
     */
    suspend fun resolvePlaceAndTheme(context: Context, latitude: Double, longitude: Double): GpsContextResult =
        withContext(Dispatchers.IO) {
            var placeName = "Current location"
            var detectedCategory = ""

            // 1. Query OpenStreetMap Nominatim (External Public REST API)
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

                    // Extract specific POI name or fall back to the first segment of the display address
                    val name = json.optString("name").ifEmpty {
                        json.optString("display_name").split(",").firstOrNull().orEmpty()
                    }
                    if (name.isNotBlank()) placeName = name

                    // Aggregate category, amenity/shop type, and address keys for semantic keyword matching
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
                // Network timeout or offline state: fallback to offline system Geocoder
            }

            // 2. Fallback to native Android Geocoder if Nominatim did not return a specific POI name
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

            // 3. Map detected category tags to an app flashcard theme
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
     * Maps OpenStreetMap tags, amenity types, and place name tokens into app flashcard categories.
     *
     * Semantic Mapping Examples:
     *  - "cafe, restaurant, pizza, bakery, bar" -> "food"
     *  - "park, forest, garden, nature_reserve" -> "plants"
     *  - "zoo, veterinary, aquarium, dog"       -> "animals"
     *  - "supermarket, mall, store, shop"       -> "objects"
     *  - "station, airport, museum, cathedral"  -> "places"
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
            else -> "places" // Default for general street or outdoor surroundings
        }
    }

    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { this.contains(it) }
    }
}
