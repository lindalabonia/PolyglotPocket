package com.example.polyglotpocket.ui.photo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.polyglotpocket.R
import com.example.polyglotpocket.data.TranslatedWord
import com.example.polyglotpocket.databinding.FragmentPhotoResultBinding
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * Second step of the photo flow: show the photo with tappable boxes, let the user
 * pick objects, then translate them in one batch when OK is pressed.
 */
class PhotoResultFragment : Fragment() {

    private var _binding: FragmentPhotoResultBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PhotoViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPhotoResultBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val bitmap = viewModel.capturedBitmap
        binding.resultImage.setImageBitmap(bitmap)

        val state = viewModel.detect.value
        if (bitmap != null && state is PhotoViewModel.DetectState.Ready) {
            binding.overlay.setObjects(state.objects, bitmap.width, bitmap.height)
        }

        binding.okButton.setOnClickListener { onOk() }
        binding.scanAnotherButton.setOnClickListener { findNavController().popBackStack() }
        binding.goBackButton.setOnClickListener {
            findNavController().popBackStack(R.id.homeFragment, false)
        }

        observeTranslation()
    }

    private fun onOk() {
        val names = binding.overlay.selectedNames()
        if (names.isEmpty()) {
            Toast.makeText(requireContext(), R.string.photo_select_at_least_one, Toast.LENGTH_SHORT).show()
            return
        }
        // Keep the boxes tappable: the user can change the selection and press OK
        // again to re-translate.
        binding.resultsSection.visibility = View.VISIBLE
        viewModel.translateWords(names)
    }

    private fun observeTranslation() {
        viewModel.translate.observe(viewLifecycleOwner) { state ->
            when (state) {
                is PhotoViewModel.TranslateState.Loading -> {
                    binding.translateProgress.visibility = View.VISIBLE
                    binding.resultsList.removeAllViews()
                }
                is PhotoViewModel.TranslateState.Ready -> {
                    binding.translateProgress.visibility = View.GONE
                    showResults(state.words)
                }
                is PhotoViewModel.TranslateState.Error -> {
                    binding.translateProgress.visibility = View.GONE
                    Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                }
                PhotoViewModel.TranslateState.Idle -> Unit
            }
        }
    }

    private fun showResults(words: List<TranslatedWord>) {
        binding.resultsList.removeAllViews()
        for (word in words) {
            val row = layoutInflater.inflate(R.layout.item_photo_word, binding.resultsList, false)
            row.findViewById<TextView>(R.id.wordSource).text = word.wordSource
            row.findViewById<TextView>(R.id.wordTarget).text = word.wordTarget
            val addButton = row.findViewById<MaterialButton>(R.id.addButton)
            val statusText = row.findViewById<TextView>(R.id.statusText)

            if (word.exists) {
                addButton.visibility = View.GONE
                statusText.text = getString(R.string.photo_already_saved)
                statusText.visibility = View.VISIBLE
            } else {
                addButton.setOnClickListener { chooseThemeAndAdd(word, addButton, statusText) }
            }
            binding.resultsList.addView(row)
        }
    }

    /** Theme picker (default 'objects'); OK saves the card, Cancel returns to the list. */
    private fun chooseThemeAndAdd(word: TranslatedWord, addButton: MaterialButton, statusText: TextView) {
        val view = layoutInflater.inflate(R.layout.dialog_theme, null)
        val container = view.findViewById<LinearLayout>(R.id.themeList)
        var chosen = themes.first().first // 'objects' is the default.
        val radios = HashMap<String, ImageView>()

        for ((code, name) in themes) {
            val row = layoutInflater.inflate(R.layout.item_theme_row, container, false)
            row.findViewById<TextView>(R.id.themeName).text = name
            row.findViewById<ImageView>(R.id.themeIcon).setImageResource(themeIcon(code))
            val radio = row.findViewById<ImageView>(R.id.themeRadio)
            radio.setImageResource(if (code == chosen) R.drawable.ic_radio_on else R.drawable.ic_radio_off)
            radios[code] = radio
            row.setOnClickListener {
                chosen = code
                for ((c, r) in radios) {
                    r.setImageResource(if (c == chosen) R.drawable.ic_radio_on else R.drawable.ic_radio_off)
                }
            }
            container.addView(row)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .setPositiveButton(R.string.photo_ok) { _, _ -> addCard(word, chosen, addButton, statusText) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addCard(word: TranslatedWord, theme: String, addButton: MaterialButton, statusText: TextView) {
        addButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewModel.addCard(word, theme)
                addButton.visibility = View.GONE
                statusText.text = getString(R.string.photo_added)
                statusText.visibility = View.VISIBLE
            } catch (e: Exception) {
                addButton.isEnabled = true
                Toast.makeText(requireContext(), e.localizedMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun themeIcon(code: String): Int = when (code) {
        "objects" -> R.drawable.ic_theme_objects
        "food" -> R.drawable.ic_theme_food
        "animals" -> R.drawable.ic_theme_animals
        "plants" -> R.drawable.ic_theme_plants
        "body" -> R.drawable.ic_theme_body
        "places" -> R.drawable.ic_theme_places
        "people" -> R.drawable.ic_theme_people
        "materials" -> R.drawable.ic_theme_materials
        "time" -> R.drawable.ic_theme_time
        "money" -> R.drawable.ic_theme_money
        "emotions" -> R.drawable.ic_theme_emotions
        else -> R.drawable.ic_theme_objects
    }

    // Themes known to the backend; 'objects' first so it is the default choice.
    private val themes = listOf(
        "objects" to "Objects",
        "food" to "Food",
        "animals" to "Animals",
        "plants" to "Plants",
        "body" to "Body",
        "places" to "Places",
        "people" to "People",
        "materials" to "Materials",
        "time" to "Time",
        "money" to "Money",
        "emotions" to "Emotions",
    )

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
