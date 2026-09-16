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
 * REQ. 4 (Hardware Sensors) & REQ. 7, 9, 10 (Concurrency, REST API, Database):
 * Interactive Flashcard Training Screen.
 *
 * Implements [SensorEventListener] to utilize the device's physical gyroscope sensor:
 *  - **Why Gyroscope (TYPE_GYROSCOPE) instead of Accelerometer (TYPE_ACCELEROMETER)**:
 *    An accelerometer detects linear acceleration and gravity (susceptible to walking shakes
 *    and false positives). A gyroscope specifically measures angular rotation velocity (rad/s)
 *    around the device's Y-axis (longitudinal axis). This isolates a deliberate, natural wrist
 *    twist gesture ("flick") from accidental ambient movements.
 *  - **Lifecycle-aware battery preservation**:
 *    The hardware sensor is registered only in [onResume] and immediately unregistered in [onPause]
 *    to avoid background battery drain.
 *  - **Hands-Free Study UX**:
 *    Once the user answers a flashcard and feedback is displayed, twisting the phone advances
 *    to the next card without needing to tap the screen.
 */
class TrainingFragment : Fragment(), SensorEventListener {
    private var _binding: FragmentTrainingBinding? = null
    private val binding get() = _binding!!

    // Initialize the ViewModel scoped to the Fragment lifecycle
    private val viewModel: TrainingViewModel by viewModels()

    // --- REQ. 4: Hardware Gyroscope Sensor Infrastructure ---
    // Android system sensor manager service
    private var sensorManager: SensorManager? = null

    // Reference to the physical gyroscope sensor (null if device/emulator lacks one)
    private var gyroscopeSensor: Sensor? = null

    // Timestamp of the last gesture trigger to debounce and prevent double-skips
    private var lastRotationTriggerTime = 0L

    // State gate: gyroscope advances cards ONLY when feedback (correct/wrong) is actively visible
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

        binding.themeIcon.setImageResource(themeIcon(state.card.theme))
        binding.themeLabel.text = state.card.theme.replaceFirstChar { it.uppercase() }

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

    // Icon for the card's theme (same set used by the photo theme picker).
    private fun themeIcon(code: String): Int = when (code) {
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

    /**
     * REQ. 4: Registers the physical gyroscope sensor listener when the Fragment enters the foreground.
     * Uses [SensorManager.SENSOR_DELAY_UI] (~60Hz rate), which provides optimal responsiveness
     * for human wrist movements without stressing the CPU or draining battery unnecessarily.
     */
    override fun onResume() {
        super.onResume()
        gyroscopeSensor?.let { sensor ->
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    /**
     * REQ. 4: Unregisters the sensor listener immediately when the Fragment is paused.
     * Crucial for battery efficiency: prevents the physical sensor from running when the app
     * is in the background, screen is locked, or another Fragment is displayed.
     */
    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    /**
     * REQ. 4: Hardware Gyroscope Callback.
     * Invoked by the Android OS whenever the device experiences rotational motion.
     *
     * Mathematical & Algorithmic Design:
     *  1. **Sensor Isolation**: Verifies that the event originates from [Sensor.TYPE_GYROSCOPE].
     *  2. **Coordinate Axis Mapping**: In Android, the Y-axis runs longitudinally up the center
     *     of the device screen. Therefore, [SensorEvent.values][1] represents angular rotation
     *     speed around the vertical Y-axis (a natural wrist twist to the left or right).
     *  3. **State Machine Gate**: Evaluates [canUseGyroscopeToNext]. The gesture is ignored
     *     while the user is reading or answering a flashcard, activating only when feedback is displayed.
     *  4. **Threshold Triggering**: Uses `abs(rotationSpeedY) > 2.5f` rad/s (~143 deg/s). This
     *     requires an intentional, snappy gesture, preventing accidental triggers from hand tremors.
     *  5. **Debounce Filter (1000ms)**: After a valid wrist flick, human recoil creates a secondary
     *     counter-rotation. The 1-second debounce window ensures exactly one card is advanced per flick.
     */
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_GYROSCOPE) return

        // event.values[1] measures angular rotation velocity around the Y-axis (rad/s)
        val rotationSpeedY = event.values[1]
        val currentTime = System.currentTimeMillis()

        // Trigger only when feedback is visible and angular velocity exceeds the deliberate gesture threshold
        if (canUseGyroscopeToNext && abs(rotationSpeedY) > 2.5f) {
            // 1000ms debounce prevents double-advancing from the wrist recoil motion
            if (currentTime - lastRotationTriggerTime > 1000) {
                lastRotationTriggerTime = currentTime
                canUseGyroscopeToNext = false // Lock until next card's feedback appears
                viewModel.nextCard()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Calibration changes do not impact discrete gesture threshold detection
    }
}