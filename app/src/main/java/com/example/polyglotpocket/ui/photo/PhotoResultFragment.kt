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

// Fragment for the translation and card-creation phase of the photo flow.
// Retrieves the AI bounding boxes from the ViewModel and binds them to the Custom View (BoxOverlayView) for rendering.
// It also handles the batch translation requests when multiple objects are selected
// (single http call with all the objs, instead of multiple ones)
class PhotoResultFragment : Fragment() {

    private var _binding: FragmentPhotoResultBinding? = null
    private val binding get() = _binding!!

    // Shared ViewModel instance to access data from PhotoCaptureFragment
    private val viewModel: PhotoViewModel by activityViewModels()

    // This Fragment is instantiated by the AndroidX Navigation component when
    // PhotoCaptureFragment executes `findNavController().navigate(R.id.action_photo_capture_to_result)` 
    // upon receiving the `DetectState.Ready` state from the ViewModel.
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

        // Retrieve from the viewModel the captured image
        val bitmap = viewModel.capturedBitmap
        binding.resultImage.setImageBitmap(bitmap)

        // Read the AI bounding boxes from the current ViewModel state and pass them to the Custom View
        val state = viewModel.detect.value
        if (bitmap != null && state is PhotoViewModel.DetectState.Ready) {
            binding.overlay.setObjects(state.objects, bitmap.width, bitmap.height)
        }

        // when user clicks on 'ok' button, call the viewModel method to get the translations via network call
        binding.okButton.setOnClickListener { onOk() }

        // when user clicks on button to do another scan, tell the nav controller to pop back stack:
        // destroy the current fragment and go back to the previous one (the photo capture)
        binding.scanAnotherButton.setOnClickListener { findNavController().popBackStack() }

        // when user clicks on button to go back, tell the nav controller to
        // destroy the current fragment and go back to home fragment
        binding.goBackButton.setOnClickListener {
            findNavController().popBackStack(R.id.homeFragment, false)
        }

        // Attach observer for the secondary network call (Translation)
        observeTranslation()
    }

    private fun onOk() {
        // Retrieve the list of English strings corresponding to the tapped bounding boxes
        val names = binding.overlay.selectedNames()
        if (names.isEmpty()) {
            Toast.makeText(requireContext(), R.string.photo_select_at_least_one, Toast.LENGTH_SHORT).show()
            return
        }
        // Expose the translation layout container and trigger the network call
        // Keep the boxes tappable: the user can change the selection and
        binding.resultsSection.visibility = View.VISIBLE
        viewModel.translateWords(names)
    }

    // Handles the UI state during the translation network request
    private fun observeTranslation() {
        viewModel.translate.observe(viewLifecycleOwner) { state ->
            when (state) {
                is PhotoViewModel.TranslateState.Loading -> {
                    binding.translateProgress.visibility = View.VISIBLE
                    binding.resultsList.removeAllViews()
                }
                is PhotoViewModel.TranslateState.Ready -> {
                    binding.translateProgress.visibility = View.GONE
                    showResults(state.words) // when the translations are ready, show them
                }
                is PhotoViewModel.TranslateState.Error -> {
                    binding.translateProgress.visibility = View.GONE
                    Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                }
                PhotoViewModel.TranslateState.Idle -> Unit
            }
        }
    }

    // Dynamically inflates the layout for each translated word and handles the 'exists' flag
    private fun showResults(words: List<TranslatedWord>) {
        // the user can change box selection and ask for another translation on the same photo,
        // when this happens the previous translations must be deleted before showing the new ones
        binding.resultsList.removeAllViews()
        for (word in words) {
            val row = layoutInflater.inflate(R.layout.item_photo_word, binding.resultsList, false)
            row.findViewById<TextView>(R.id.wordSource).text = word.wordSource
            row.findViewById<TextView>(R.id.wordTarget).text = word.wordTarget
            
            val addButton = row.findViewById<MaterialButton>(R.id.addButton)
            val statusText = row.findViewById<TextView>(R.id.statusText)

            // The backend already checked the DB. If 'exists' is true, hide the add button.
            if (word.exists) {
                addButton.visibility = View.GONE
                statusText.text = getString(R.string.photo_already_saved)
                statusText.visibility = View.VISIBLE
            } else {
                // if the card does not exist yet, put a listener on 'addbutton' (button to add a card)
                // and execute chooseThemeAndAdd() method when button is clicked
                addButton.setOnClickListener { chooseThemeAndAdd(word, addButton, statusText) }
            }
            binding.resultsList.addView(row)
        }
    }

    /** Theme picker (default 'objects'); OK saves the card, Cancel returns to the list. */
    private fun chooseThemeAndAdd(word: TranslatedWord, addButton: MaterialButton, statusText: TextView) {
        val view = layoutInflater.inflate(R.layout.dialog_theme, null)
        val container = view.findViewById<LinearLayout>(R.id.themeList)
        var chosen = themes.first().first // Default theme identifier
        
        // Map to quickly toggle radio button states
        val radios = HashMap<String, ImageView>()

        // Dynamically inflate radio button rows for the available themes
        for ((code, name) in themes) {
            val row = layoutInflater.inflate(R.layout.item_theme_row, container, false)
            row.findViewById<TextView>(R.id.themeName).text = name
            row.findViewById<ImageView>(R.id.themeIcon).setImageResource(themeIcon(code))
            
            val radio = row.findViewById<ImageView>(R.id.themeRadio)
            radio.setImageResource(if (code == chosen) R.drawable.ic_radio_on else R.drawable.ic_radio_off)
            radios[code] = radio
            
            // Single-choice logic
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
            .setPositiveButton(R.string.photo_ok) { _, _ -> addCard(word, chosen, addButton, statusText) } // add card
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // Executes the final network call to save the new card to the backend
    private fun addCard(word: TranslatedWord, theme: String, addButton: MaterialButton, statusText: TextView) {
        addButton.isEnabled = false
        
        // Scope the coroutine to the Fragment's lifecycle to prevent memory leaks if dismissed
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewModel.addCard(word, theme)
                
                // Hide button and show success text upon server confirmation
                addButton.visibility = View.GONE
                statusText.text = getString(R.string.photo_added)
                statusText.visibility = View.VISIBLE
            } catch (e: Exception) {
                addButton.isEnabled = true
                Toast.makeText(requireContext(), e.localizedMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    // Maps theme string codes to local drawable resources
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
