package com.example.polyglotpocket.ui.home

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.polyglotpocket.R
import com.example.polyglotpocket.data.BackendApi
import com.example.polyglotpocket.databinding.FragmentHomeBinding
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // Mappa con bandiere e nomi leggibili per le lingue del backend
    private val languageNames = mapOf(
        "spa" to "🇪🇸 Spagnolo (spa)",
        "fra" to "🇫🇷 Francese (fra)",
        "por" to "🇵🇹 Portoghese (por)",
        "nld" to "🇳🇱 Olandese (nld)",
        "jpn" to "🇯🇵 Giapponese (jpn)",
    )

    private var selectedLanguageCode = "spa"

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

        // 1. Carica la lingua salvata in precedenza su disco (SharedPreferences)
        val prefs = requireContext().getSharedPreferences("polyglot_prefs", Context.MODE_PRIVATE)
        selectedLanguageCode = prefs.getString("target_lang", "spa") ?: "spa"
        updateLanguageDisplay()

        // 2. Click sul box della lingua per aprirne la selezione
        binding.languageCard.setOnClickListener {
            showLanguageSelectionDialog()
        }

        // 3. Click su Allenamento Casuale (modalità "random")
        binding.trainRandomButton.setOnClickListener {
            val bundle = bundleOf(
                "targetLang" to selectedLanguageCode,
                "mode" to "random"
            )
            findNavController().navigate(R.id.action_home_to_training, bundle)
        }

        // 4. Click su Impara dai tuoi errori (modalità "errors")
        binding.trainErrorsButton.setOnClickListener {
            val bundle = bundleOf(
                "targetLang" to selectedLanguageCode,
                "mode" to "errors"
            )
            findNavController().navigate(R.id.action_home_to_training, bundle)
        }
        binding.studyHereButton.setOnClickListener { comingSoon() }
        binding.addPhotoButton.setOnClickListener { comingSoon() }
        binding.statsButton.setOnClickListener { comingSoon() }
        binding.testBackendButton.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_apitest)
        }
    }

    private fun updateLanguageDisplay() {
        binding.selectedLanguageText.text = languageNames[selectedLanguageCode] ?: selectedLanguageCode
    }

    private fun showLanguageSelectionDialog() {
        val codes = languageNames.keys.toList()
        val displayOptions = codes.map { languageNames[it] ?: it }.toTypedArray()
        val currentIndex = codes.indexOf(selectedLanguageCode).coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.home_select_language_title)
            .setSingleChoiceItems(displayOptions, currentIndex) { dialog, which ->
                selectedLanguageCode = codes[which]

                // Salva la scelta su SharedPreferences
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