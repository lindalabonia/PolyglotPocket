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
 * Schermata di allenamento (Flashcard UI).
 * Osserva il TrainingViewModel e aggiorna l'interfaccia.
 */
class TrainingFragment : Fragment(), SensorEventListener {
    private var _binding: FragmentTrainingBinding? = null
    private val binding get() = _binding!!

    // Inizializza il ViewModel con il lifecycle del Fragment
    private val viewModel: TrainingViewModel by viewModels()

    // Gestione del Giroscopio
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

        // Inizializziamo il SensorManager e il Giroscopio
        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        gyroscopeSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // Osserviamo i cambiamenti di stato dal ViewModel
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

        // Leggiamo lingua e modalità passate dalla Home
        val targetLang = arguments?.getString("targetLang") ?: "spa"
        val mode = arguments?.getString("mode") ?: "random"

        if (savedInstanceState == null) {
            viewModel.startTraining(mode = mode, targetLang = targetLang, numCards = 10)
        }
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
            // Stato 1: L'utente deve ancora rispondere
            canUseGyroscopeToNext = false // <-- Giroscopio disattivato finché non rispondi
            binding.inputAnswer.isEnabled = true
            binding.inputAnswer.text?.clear()
            binding.feedbackText.visibility = View.INVISIBLE
            binding.actionButton.text = getString(R.string.training_btn_check)
            binding.actionButton.setOnClickListener {
                val answer = binding.inputAnswer.text?.toString().orEmpty()
                viewModel.checkAnswer(answer)
            }
        } else {
            // Stato 2: L'utente ha risposto, mostriamo il feedback
            canUseGyroscopeToNext =
                true // <-- Giroscopio ATTIVATO: puoi ruotare il telefono per avanzare!
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

    // Registriamo il sensore quando la schermata è visibile
    override fun onResume() {
        super.onResume()
        gyroscopeSensor?.let { sensor ->
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    // Disattiviamo il sensore quando l'utente esce o mette in pausa (risparmio batteria)
    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    // Questo metodo viene chiamato da Android ogni volta che il giroscopio rileva movimento
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_GYROSCOPE) return

        // event.values[1] misura la rotazione sull'asse Y (rotazione/rollio del polso a destra o sinistra)
        val rotationSpeedY = event.values[1]
        val currentTime = System.currentTimeMillis()

        // Se l'utente ha già risposto (feedback visibile) ed esegue una rotazione decisa (> 2.5 rad/s)
        if (canUseGyroscopeToNext && abs(rotationSpeedY) > 2.5f) {
            // Controllo per evitare scatti multipli entro 1 secondo
            if (currentTime - lastRotationTriggerTime > 1000) {
                lastRotationTriggerTime = currentTime
                canUseGyroscopeToNext = false
                viewModel.nextCard() // Passa alla prossima carta! 🚀
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Non necessario per il nostro scopo
    }
}