package com.example.polyglotpocket.ui.login

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.polyglotpocket.R
import com.example.polyglotpocket.databinding.FragmentLoginBinding

class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LoginViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.loginButton.setOnClickListener {
            viewModel.login(
                binding.usernameInput.text?.toString().orEmpty(),
                binding.passwordInput.text?.toString().orEmpty()
            )
        }

        viewModel.state.observe(viewLifecycleOwner) { state ->
            val loading = state is LoginViewModel.State.Loading
            binding.loginProgress.visibility = if (loading) View.VISIBLE else View.GONE
            binding.loginButton.isEnabled = !loading

            when (state) {
                is LoginViewModel.State.Success -> {
                    findNavController().navigate(
                        R.id.action_login_to_home,
                        bundleOf("username" to state.username)
                    )
                    viewModel.consumeState()
                }
                is LoginViewModel.State.Error -> {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.login_error_empty),
                        Toast.LENGTH_SHORT
                    ).show()
                    viewModel.consumeState()
                }
                else -> Unit
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
