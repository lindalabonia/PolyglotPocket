package com.example.polyglotpocket.ui.training

import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
        val mode = arguments?.getString("mode") ?: "random"
        val theme = arguments?.getString("theme")

        // Ask how many cards, then start. Survives rotation via the ViewModel:
        // re-show the dialog only if a session hasn't started yet.
        if (!viewModel.hasStarted) {
            showCardCountDialog(mode, targetLang, theme)
        }
    }

    /** Let the user pick the number of cards before the session starts. */
    private fun showCardCountDialog(mode: String, targetLang: String, theme: String?) {
        val counts = intArrayOf(2, 5, 10, 20)
        val labels = counts.map { getString(R.string.training_card_count_option, it) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.training_card_count_title)
            .setItems(labels) { _, which ->
                viewModel.startTraining(mode = mode, targetLang = targetLang, numCards = counts[which], theme = theme)
            }
            .setCancelable(false)
            .setNegativeButton(R.string.training_back_to_home) { _, _ ->
                findNavController().popBackStack()
            }
            .show()
    }

    private fun renderQuestion(state: TrainingViewModel.State.Question) {
        binding.progressText.text = getString(
            R.string.training_progress,
            state.currentIndex + 1,
            state.totalCards
        )

        binding.themeChip.text = state.card.theme
        binding.wordSourceText.text = state.card.wordSource

        val feedback = state.feedback

        if (feedback == null) {
            // State 1: Waiting for user answer
            canUseGyroscopeToNext = false
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

            if (feedback.isCorrect) {
                binding.feedbackText.text = getString(R.string.training_feedback_correct)
                binding.feedbackText.setTextColor(Color.parseColor("#2E7D32"))
            } else {
                binding.feedbackText.text =
                    getString(R.string.training_feedback_wrong, feedback.correctAnswer)
                binding.feedbackText.setTextColor(Color.parseColor("#C62828"))
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
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.training_summary_title)
            .setMessage(getString(R.string.training_summary_result, state.correct, state.wrong))
            .setPositiveButton(R.string.training_back_to_home) { _, _ ->
                findNavController().popBackStack()
            }
            .setCancelable(false)
            .show()
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