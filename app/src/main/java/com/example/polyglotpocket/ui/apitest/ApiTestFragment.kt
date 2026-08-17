package com.example.polyglotpocket.ui.apitest

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.polyglotpocket.data.ApiConfig
import com.example.polyglotpocket.databinding.FragmentApiTestBinding

class ApiTestFragment : Fragment() {

    private var _binding: FragmentApiTestBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ApiTestViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentApiTestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.baseUrlText.text = "BASE_URL: ${ApiConfig.BASE_URL}"

        binding.loadLanguagesButton.setOnClickListener { viewModel.loadLanguages() }
        binding.loadCardButton.setOnClickListener {
            viewModel.loadCard(binding.targetLangInput.text?.toString().orEmpty())
        }

        viewModel.state.observe(viewLifecycleOwner) { state ->
            binding.apiTestProgress.visibility = if (state.loading) View.VISIBLE else View.GONE
            binding.outputText.text = state.output
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
