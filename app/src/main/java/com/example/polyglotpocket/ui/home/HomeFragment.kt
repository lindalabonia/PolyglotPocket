package com.example.polyglotpocket.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.polyglotpocket.R
import com.example.polyglotpocket.data.BackendApi
import com.example.polyglotpocket.data.GpsThemeResolver
import com.example.polyglotpocket.data.Stats
import com.example.polyglotpocket.data.TokenStore
import com.example.polyglotpocket.databinding.FragmentHomeBinding
import kotlinx.coroutines.launch

/**
 * Main menu. Each button maps to a project feature:
 *  - Random training      -> mode 1 (REQ. 7, 9, 10)
 *  - Review your mistakes -> mode 2
 *  - Study here (GPS)     -> REQ. 5 + REQ. 1 (OpenStreetMap)
 *  - Add word from photo  -> REQ. 6 + REQ. 8 (Cloud Vision) + REQ. 1 (translation)
 *  - Statistics           -> REQ. 3 (2D graphics)
 *
 * The entries show a placeholder for now; we wire them one at a time.
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // Runtime location permissions (GPS)
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            startGpsFlow()
        } else {
            Toast.makeText(requireContext(), R.string.gps_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    // Flags and readable names for the languages available in the backend DB.
    private val languageNames = mapOf(
        "spa" to "🇪🇸 Spanish",
        "fra" to "🇫🇷 French",
        "por" to "🇵🇹 Portuguese",
        "nld" to "🇳🇱 Dutch",
        "arb" to "🇸🇦 Arabic",
    )

    // null until the user picks a language for the first time.
    private var selectedLanguageCode: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val username = arguments?.getString("username").orEmpty()
        binding.welcomeText.text = getString(R.string.home_welcome, username)

        // Load the last chosen language from local storage (null the first time).
        val prefs = requireContext().getSharedPreferences("polyglot_prefs", Context.MODE_PRIVATE)
        selectedLanguageCode = prefs.getString("target_lang", null)
        // Ignore a stale language no longer available (e.g. old 'jpn').
        if (selectedLanguageCode !in languageNames.keys) selectedLanguageCode = null
        updateLanguageDisplay()

        binding.languageCard.setOnClickListener { showLanguageSelectionDialog() }

        // Training buttons require a language to be selected first.
        binding.trainRandomButton.setOnClickListener { startTraining("random") }
        binding.trainErrorsButton.setOnClickListener { startTraining("errors") }
        binding.addPhotoButton.setOnClickListener { startPhoto() }
        binding.studyHereButton.setOnClickListener { checkLocationAndStartGps() }

        // Expand/collapse the practice calendar (kept collapsed to save space).
        binding.calendarHeader.setOnClickListener {
            val expanded = binding.calendarBody.visibility == View.VISIBLE
            binding.calendarBody.visibility = if (expanded) View.GONE else View.VISIBLE
            binding.calendarHint.visibility = if (expanded) View.VISIBLE else View.GONE
            binding.calendarChevron.rotation = if (expanded) 0f else 90f
        }

        loadProgress()
    }

    /** Load the user's sessions (all languages) and fill the progress section.
     *  Non-critical: on failure the placeholders simply stay. */
    private fun loadProgress() {
        viewLifecycleOwner.lifecycleScope.launch {
            val token = TokenStore.get(requireContext()) ?: return@launch
            val sessions = try {
                BackendApi.getSessions(token)
            } catch (e: Exception) {
                return@launch
            }

            val totals = Stats.totals(sessions)
            binding.kpiSessions.text = totals.sessions.toString()
            binding.kpiCards.text = totals.cards.toString()
            binding.kpiTime.text = formatDuration(totals.durationMs)

            val streak = Stats.currentStreak(sessions)
            if (streak > 0) {
                binding.streakEmoji.text = getString(R.string.home_streak_emoji_active)
                binding.streakText.text = getString(R.string.home_streak_days, streak)
                binding.streakSubText.text = getString(R.string.home_streak_sub, streak)
            } else {
                binding.streakEmoji.text = getString(R.string.home_streak_emoji_none)
                binding.streakText.text = getString(R.string.home_streak_none_title)
                binding.streakSubText.text = getString(R.string.home_streak_none_sub)
            }

            val calendar = Stats.calendar(sessions)
            binding.calendarView.setData(calendar.levels, calendar.startMondayMillis)
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    private fun checkLocationAndStartGps() {
        if (selectedLanguageCode == null) {
            Toast.makeText(requireContext(), R.string.home_select_language_first, Toast.LENGTH_SHORT).show()
            return
        }

        val fineCheck = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseCheck = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)

        if (fineCheck == PackageManager.PERMISSION_GRANTED || coarseCheck == PackageManager.PERMISSION_GRANTED) {
            startGpsFlow()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    /**
     * REQ. 5 (Location & GPS) & REQ. 1 (External Cloud & Map Services):
     * Orchestrates the location-aware study flow:
     *  1. Fetches coordinates via [GpsThemeResolver.getCurrentLocation] (Google Play Services FusedLocation).
     *  2. Resolves POI name and semantic theme via OpenStreetMap Nominatim REST API.
     *  3. Renders an interactive Leaflet.js map with a pinpoint marker in a sandboxed [WebView].
     *  4. Transmits the resolved theme to [TrainingFragment] inside a navigation [Bundle].
     */
    private fun startGpsFlow() {
        val lang = selectedLanguageCode ?: return
        Toast.makeText(requireContext(), R.string.gps_detecting, Toast.LENGTH_SHORT).show()
        binding.studyHereButton.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 1. Acquire current device coordinates (high-accuracy fused GPS/network)
                val location = GpsThemeResolver.getCurrentLocation(requireContext())
                // Safe default fallback coordinates (Rome, Italy) if GPS fix is unavailable
                val lat = location?.latitude ?: 41.9028
                val lon = location?.longitude ?: 12.4964

                // 2. Query OpenStreetMap Nominatim for POI details and categorize theme
                val contextResult = GpsThemeResolver.resolvePlaceAndTheme(requireContext(), lat, lon)

                // 3. Inflate preview dialog with embedded interactive map
                val dialogBinding = com.example.polyglotpocket.databinding.DialogGpsPreviewBinding.inflate(layoutInflater)
                dialogBinding.detectedPlaceText.text = contextResult.placeName
                dialogBinding.detectedThemeText.text = contextResult.themeDisplayName

                // Configure WebView for Leaflet.js cartographic tile rendering
                val webView = dialogBinding.mapWebView
                webView.webViewClient = android.webkit.WebViewClient()
                webView.webChromeClient = android.webkit.WebChromeClient()
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = "PolyglotPocket-Android/1.0"
                    loadWithOverviewMode = true
                    useWideViewPort = true
                }

                // Render vector tiles centered on the user's coordinates with a map pin marker
                val html = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
                        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
                        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
                        <style>
                            body, html, #map { margin: 0; padding: 0; width: 100%; height: 100%; background: #e0e0e0; }
                        </style>
                    </head>
                    <body>
                        <div id="map"></div>
                        <script>
                            var map = L.map('map', { zoomControl: false, attributionControl: false }).setView([${contextResult.latitude}, ${contextResult.longitude}], 16);
                            L.tileLayer('https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png', {
                                subdomains: 'abcd',
                                maxZoom: 19
                            }).addTo(map);
                            L.marker([${contextResult.latitude}, ${contextResult.longitude}]).addTo(map);
                        </script>
                    </body>
                    </html>
                """.trimIndent()
                webView.loadDataWithBaseURL("https://openstreetmap.org", html, "text/html", "UTF-8", null)

                // 4. Confirmation dialog: upon confirmation, navigate to TrainingFragment with theme bundle
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setView(dialogBinding.root)
                    .setPositiveButton(R.string.gps_btn_start) { _, _ ->
                        findNavController().navigate(
                            R.id.action_home_to_training,
                            bundleOf(
                                "targetLang" to lang,
                                "mode" to "gps",
                                "theme" to contextResult.theme
                            )
                        )
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "GPS detection error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.studyHereButton.isEnabled = true
            }
        }
    }

    /** Navigate to training only if a language is selected, otherwise warn. */
    private fun startTraining(mode: String) {
        val lang = selectedLanguageCode
        if (lang == null) {
            Toast.makeText(requireContext(), R.string.home_select_language_first, Toast.LENGTH_SHORT).show()
            return
        }
        findNavController().navigate(
            R.id.action_home_to_training,
            bundleOf("targetLang" to lang, "mode" to mode)
        )
    }

    /** Open the photo flow only if a language is selected (needed to translate). */
    private fun startPhoto() {
        val lang = selectedLanguageCode
        if (lang == null) {
            Toast.makeText(requireContext(), R.string.home_select_language_first, Toast.LENGTH_SHORT).show()
            return
        }
        findNavController().navigate(
            R.id.action_home_to_photo,
            bundleOf("targetLang" to lang)
        )
    }

    private fun updateLanguageDisplay() {
        val code = selectedLanguageCode
        binding.selectedLanguageText.text =
            if (code == null) getString(R.string.home_language_placeholder)
            else languageNames[code] ?: code
    }

    private fun showLanguageSelectionDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_language, null)
        val container = view.findViewById<LinearLayout>(R.id.languageList)
        val prefs = requireContext().getSharedPreferences("polyglot_prefs", Context.MODE_PRIVATE)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        for ((code, label) in languageNames) {
            val row = layoutInflater.inflate(R.layout.item_language_row, container, false)
            row.findViewById<TextView>(R.id.langName).text = label
            row.findViewById<ImageView>(R.id.langRadio).setImageResource(
                if (code == selectedLanguageCode) R.drawable.ic_radio_on else R.drawable.ic_radio_off
            )
            row.setOnClickListener {
                selectedLanguageCode = code
                prefs.edit { putString("target_lang", code) }
                updateLanguageDisplay()
                dialog.dismiss()
            }
            container.addView(row)
        }
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}