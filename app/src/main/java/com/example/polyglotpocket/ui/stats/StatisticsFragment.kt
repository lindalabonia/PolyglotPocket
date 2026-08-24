package com.example.polyglotpocket.ui.stats

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.polyglotpocket.R
import com.example.polyglotpocket.data.BackendApi
import com.example.polyglotpocket.data.SessionSummary
import com.example.polyglotpocket.data.Stats
import com.example.polyglotpocket.data.TokenStore
import com.example.polyglotpocket.databinding.FragmentStatisticsBinding
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch

/**
 * Per-language statistics (REQ. 3). The user picks a language among the ones
 * they have sessions in; the charts below are drawn on a Canvas from that
 * language's own sessions.
 */
class StatisticsFragment : Fragment() {

    private var _binding: FragmentStatisticsBinding? = null
    private val binding get() = _binding!!

    private var allSessions: List<SessionSummary> = emptyList()

    private val languageFlags = mapOf(
        "spa" to "🇪🇸 Spanish",
        "fra" to "🇫🇷 French",
        "por" to "🇵🇹 Portuguese",
        "nld" to "🇳🇱 Dutch",
        "arb" to "🇸🇦 Arabic",
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentStatisticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        load()
    }

    private fun load() {
        binding.progressBar.visibility = View.VISIBLE
        binding.statsContent.visibility = View.GONE
        binding.emptyText.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val token = TokenStore.get(requireContext())
            if (token == null) {
                showMessage(R.string.stats_error)
                return@launch
            }
            allSessions = try {
                BackendApi.getSessions(token)
            } catch (e: Exception) {
                showMessage(R.string.stats_error)
                return@launch
            }

            binding.progressBar.visibility = View.GONE
            val languages = Stats.languagesWithSessions(allSessions)
            if (languages.isEmpty()) {
                showMessage(R.string.stats_empty)
                return@launch
            }
            binding.statsContent.visibility = View.VISIBLE
            buildChips(languages)
        }
    }

    private fun showMessage(resId: Int) {
        binding.progressBar.visibility = View.GONE
        binding.statsContent.visibility = View.GONE
        binding.emptyText.setText(resId)
        binding.emptyText.visibility = View.VISIBLE
    }

    private fun buildChips(languages: List<String>) {
        binding.languageChips.removeAllViews()

        val prefs = requireContext().getSharedPreferences("polyglot_prefs", Context.MODE_PRIVATE)
        val prefLang = prefs.getString("target_lang", null)
        val initial = if (prefLang != null && prefLang in languages) prefLang else languages.first()

        for (code in languages) {
            val chip = Chip(requireContext()).apply {
                text = languageFlags[code] ?: code
                isCheckable = true
                isCheckedIconVisible = false
                id = View.generateViewId()
                tag = code
                isChecked = code == initial
            }
            binding.languageChips.addView(chip)
        }

        binding.languageChips.setOnCheckedStateChangeListener { group, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            (group.findViewById<Chip>(id)?.tag as? String)?.let { render(it) }
        }

        render(initial)
    }

    private fun render(code: String) {
        val name = plainName(code)
        val s = Stats.forLanguage(allSessions, code)
        val answers = s.correct + s.wrong

        binding.avgAccuracyText.text = getString(R.string.stats_percent, s.avgAccuracy)
        binding.avgAccuracySub.text = getString(R.string.stats_avg_sub, name, answers)
        binding.lineChart.setData(s.accuracySeries)
        binding.barChart.setData(s.cardsThisWeek)
        binding.donutChart.setData(s.correct, s.wrong)
        binding.correctCount.text = s.correct.toString()
        binding.wrongCount.text = s.wrong.toString()
        binding.cvwSub.text = getString(R.string.stats_cvw_sub, answers)

        binding.practiceButton.text = getString(R.string.stats_practice, name)
        binding.practiceButton.setOnClickListener {
            findNavController().navigate(
                R.id.action_stats_to_training,
                bundleOf("targetLang" to code, "mode" to "errors"),
            )
        }
    }

    private fun plainName(code: String): String = when (code) {
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
}
