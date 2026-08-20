package com.example.polyglotpocket.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.polyglotpocket.R
import com.example.polyglotpocket.databinding.FragmentHomeBinding

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

        binding.trainRandomButton.setOnClickListener { comingSoon() }
        binding.trainErrorsButton.setOnClickListener { comingSoon() }
        binding.studyHereButton.setOnClickListener { comingSoon() }
        binding.addPhotoButton.setOnClickListener { comingSoon() }
        binding.statsButton.setOnClickListener { comingSoon() }
        binding.testBackendButton.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_apitest)
        }
    }

    private fun comingSoon() {
        Toast.makeText(requireContext(), R.string.coming_soon, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
