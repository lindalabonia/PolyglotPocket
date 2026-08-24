package com.example.polyglotpocket.ui.training

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.polyglotpocket.R
import com.example.polyglotpocket.databinding.FragmentTrainingBinding
import kotlin.math.abs

/**
 * Flashcard training screen.
 * Observes the TrainingViewModel and updates the UI.
 */
class TrainingFragment : Fragment(), SensorEventListener {
    private var _binding: FragmentTrainingBinding? = null
    private val binding get() = _binding!!

    // Initialize the ViewModel scoped to the Fragment lifecycle
    private val viewModel: TrainingViewModel by viewModels()

    // Gyroscope management
    private var sensorManager: SensorManager? = null
    private var gyroscopeSensor: Sensor? = null
    private var lastRotationTriggerTime = 0L
    private var canUseGyroscopeToNext = false

    // Display name of the study language, for the "Translate to ..." hint.
    private var langDisplay = ""

    // True once the session is complete (so exiting no longer needs a warning).
    private var sessionOver = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTrainingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize SensorManager and Gyroscope sensor
        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        gyroscopeSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // Observe ViewModel state changes
        viewModel.state.observe(viewLifecycleOwner) { state ->
            when (state) {
                is TrainingViewModel.State.Loading -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.trainingContainer.visibility = View.GONE
                }

                is TrainingViewModel.State.Question -> {
                    binding.progressBar.visibility = View.GONE
                    binding.trainingContainer.visibility = View.VISIBLE
                    renderQuestion(state)
                }

                is TrainingViewModel.State.Finished -> {
                    sessionOver = true
                    showSummaryDialog(state)
                }

                is TrainingViewModel.State.Error -> {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                    findNavController().popBackStack()
                }
            }
        }

        // Language, mode and optional theme (from GPS) passed from the Home screen.
        val targetLang = arguments?.getString("targetLang") ?: "spa"
        langDisplay = languageName(targetLang)
        val mode = arguments?.getString("mode") ?: "random"
        val theme = arguments?.getString("theme")

        // Ask how many cards, then start. Survives rotation via the ViewModel:
        // re-show the dialog only if a session hasn't started yet.
        if (!viewModel.hasStarted) {
            showCardCountDialog(mode, targetLang, theme)
        }

        // Warn before leaving an unfinished session (it is only saved at the end).
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (viewModel.hasStarted && !sessionOver) {
                        confirmExit()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    /** Confirm before leaving mid-session, since progress would be lost. */
    private fun confirmExit() {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.training_exit_message)
            .setPositiveButton(R.string.training_exit_leave) { _, _ -> findNavController().popBackStack() }
            .setNegativeButton(R.string.training_exit_continue, null)
            .show()
    }

    /** Let the user pick the number of cards before the session starts. */
    private fun showCardCountDialog(mode: String, targetLang: String, theme: String?) {
        val view = layoutInflater.inflate(R.layout.dialog_card_count, null)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .setCancelable(false)
            .setNegativeButton(R.string.training_back_to_home) { _, _ ->
                findNavController().popBackStack()
            }
            .create()

        val options = mapOf(R.id.count2 to 2, R.id.count5 to 5, R.id.count10 to 10, R.id.count20 to 20)
        for ((id, count) in options) {
            view.findViewById<View>(id).setOnClickListener {
                dialog.dismiss()
                viewModel.startTraining(mode = mode, targetLang = targetLang, numCards = count, theme = theme)
            }
        }
        dialog.show()
    }

    private fun renderQuestion(state: TrainingViewModel.State.Question) {
        binding.progressText.text = getString(
            R.string.training_progress,
            state.currentIndex + 1,
            state.totalCards
        )

        binding.hintText.text = getString(R.string.training_hint_lang, langDisplay)
        binding.wordSourceText.text = state.card.wordSource

        val feedback = state.feedback

        if (feedback == null) {
            // State 1: Waiting for user answer
            canUseGyroscopeToNext = false
            binding.flashcardView.setCardBackgroundColor(Color.TRANSPARENT)
            binding.flashcardView.strokeWidth = 0
            binding.inputAnswer.isEnabled = true
            binding.inputAnswer.text?.clear()
            binding.feedbackText.visibility = View.INVISIBLE
            binding.actionButton.text = getString(R.string.training_btn_check)
            binding.actionButton.setOnClickListener {
                val answer = binding.inputAnswer.text?.toString().orEmpty()
                viewModel.checkAnswer(answer)
            }
        } else {
            // State 2: User answered, show feedback and enable gyroscope advance
            canUseGyroscopeToNext = true
            binding.inputAnswer.isEnabled = false
            binding.feedbackText.visibility = View.VISIBLE

            val strokePx = (2.5f * resources.displayMetrics.density).toInt()
            if (feedback.isCorrect) {
                binding.feedbackText.text = getString(R.string.training_feedback_correct)
                binding.feedbackText.setTextColor(Color.parseColor("#2E7D32"))
                binding.flashcardView.setCardBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.pp_feedback_green_bg)
                )
                binding.flashcardView.strokeWidth = strokePx
                binding.flashcardView.setStrokeColor(
                    ContextCompat.getColor(requireContext(), R.color.pp_feedback_green_fg)
                )
            } else {
                // "Wrong ❌" then the correct translation enlarged.
                val answer = feedback.correctAnswer
                val text = SpannableStringBuilder()
                val titleStart = text.length
                text.append(getString(R.string.training_feedback_wrong_title))
                text.setSpan(StyleSpan(Typeface.BOLD), titleStart, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.append("\n").append(getString(R.string.training_feedback_wrong_prefix)).append(" ")
                val start = text.length
                text.append(answer)
                text.setSpan(RelativeSizeSpan(1.6f), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                binding.feedbackText.text = text
                binding.feedbackText.setTextColor(Color.parseColor("#C62828"))
                binding.flashcardView.setCardBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.pp_feedback_red_bg)
                )
                binding.flashcardView.strokeWidth = strokePx
                binding.flashcardView.setStrokeColor(
                    ContextCompat.getColor(requireContext(), R.color.pp_feedback_red_fg)
                )
            }

            val isLastCard = state.currentIndex + 1 == state.totalCards
            binding.actionButton.text = if (isLastCard) {
                getString(R.string.training_btn_finish)
            } else {
                getString(R.string.training_btn_next)
            }

            binding.actionButton.setOnClickListener {
                viewModel.nextCard()
            }
        }
    }

    private fun showSummaryDialog(state: TrainingViewModel.State.Finished) {
        val view = layoutInflater.inflate(R.layout.dialog_summary, null)
        view.findViewById<TextView>(R.id.summaryCorrect).text = state.correct.toString()
        view.findViewById<TextView>(R.id.summaryWrong).text = state.wrong.toString()
        MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.training_back_to_home) { _, _ ->
                findNavController().popBackStack()
            }
            .show()
    }

    private fun languageName(code: String): String = when (code) {
        "spa" -> "Spanish"
        "fra" -> "French"
        "por" -> "Portuguese"
        "nld" -> "Dutch"
        "arb" -> "Arabic"
        else -> code
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // Register gyroscope sensor when screen becomes visible
    override fun onResume() {
        super.onResume()
        gyroscopeSensor?.let { sensor ->
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    // Unregister sensor when paused to preserve battery
    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    // Called on hardware gyroscope motion events
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_GYROSCOPE) return

        // event.values[1] measures rotation velocity around the Y-axis (wrist twist)
        val rotationSpeedY = event.values[1]
        val currentTime = System.currentTimeMillis()

        // When feedback is visible and user twists the wrist (> 2.5 rad/s)
        if (canUseGyroscopeToNext && abs(rotationSpeedY) > 2.5f) {
            // Debounce for 1 second to prevent double triggers
            if (currentTime - lastRotationTriggerTime > 1000) {
                lastRotationTriggerTime = currentTime
                canUseGyroscopeToNext = false
                viewModel.nextCard()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for this use case
    }
}