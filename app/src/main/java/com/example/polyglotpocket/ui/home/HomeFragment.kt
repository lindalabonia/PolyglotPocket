package com.example.polyglotpocket.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.polyglotpocket.R
import com.example.polyglotpocket.data.GpsThemeResolver
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
        "spa" to "🇪🇸 Spanish (spa)",
        "fra" to "🇫🇷 French (fra)",
        "por" to "🇵🇹 Portuguese (por)",
        "nld" to "🇳🇱 Dutch (nld)",
        "arb" to "🇸🇦 Arabic (arb)",
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
        binding.studyHereButton.setOnClickListener { checkLocationAndStartGps() }
        binding.addPhotoButton.setOnClickListener { comingSoon() }
        binding.statsButton.setOnClickListener { comingSoon() }
        binding.testBackendButton.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_apitest)
        }
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

    private fun startGpsFlow() {
        val lang = selectedLanguageCode ?: return
        Toast.makeText(requireContext(), R.string.gps_detecting, Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch {
            val location = GpsThemeResolver.getCurrentLocation(requireContext())
            // If location is not immediately available, use default coordinates
            val lat = location?.latitude ?: 41.9028 // Rome by default
            val lon = location?.longitude ?: 12.4964

            val contextResult = GpsThemeResolver.resolvePlaceAndTheme(requireContext(), lat, lon)

            AlertDialog.Builder(requireContext())
                .setTitle(R.string.gps_context_dialog_title)
                .setMessage(
                    getString(
                        R.string.gps_context_dialog_message,
                        contextResult.placeName,
                        contextResult.themeDisplayName
                    )
                )
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

    private fun updateLanguageDisplay() {
        val code = selectedLanguageCode
        binding.selectedLanguageText.text =
            if (code == null) getString(R.string.home_language_placeholder)
            else languageNames[code] ?: code
    }

    private fun showLanguageSelectionDialog() {
        val codes = languageNames.keys.toList()
        val displayOptions = codes.map { languageNames[it] ?: it }.toTypedArray()
        val currentIndex = selectedLanguageCode?.let { codes.indexOf(it) } ?: -1

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.home_select_language_title)
            .setSingleChoiceItems(displayOptions, currentIndex) { dialog, which ->
                selectedLanguageCode = codes[which]

                // Save selected language to SharedPreferences
                val prefs = requireContext().getSharedPreferences("polyglot_prefs", Context.MODE_PRIVATE)
                prefs.edit { putString("target_lang", selectedLanguageCode) }

                updateLanguageDisplay()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun comingSoon() {
        Toast.makeText(requireContext(), R.string.coming_soon, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}