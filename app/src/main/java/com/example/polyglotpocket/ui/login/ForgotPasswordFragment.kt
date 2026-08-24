package com.example.polyglotpocket.ui.login

import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.polyglotpocket.R
import com.example.polyglotpocket.data.ApiException
import com.example.polyglotpocket.data.BackendApi
import com.example.polyglotpocket.databinding.FragmentForgotPasswordBinding
import kotlinx.coroutines.launch

/**
 * Password reset in two steps: (1) enter the email to receive a code, (2) enter
 * the code plus a new password. The server response for step 1 is generic (no
 * account enumeration), so the on-screen message is deliberately non-committal.
 */
class ForgotPasswordFragment : Fragment() {

    private var _binding: FragmentForgotPasswordBinding? = null
    private val binding get() = _binding!!

    // Email typed in step 1, reused to submit the reset in step 2.
    private var email = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentForgotPasswordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.sendCodeButton.setOnClickListener { sendCode() }
        binding.resetButton.setOnClickListener { doReset() }
        binding.requestNewCodeButton.setOnClickListener { backToEmailStep() }
        binding.backToLogin.setOnClickListener { findNavController().popBackStack() }
    }

    /** Return to step 1 so the user can ask for a fresh code (e.g. after the
     *  old one expired or was invalidated by too many wrong attempts). */
    private fun backToEmailStep() {
        binding.codeInput.text?.clear()
        binding.newPasswordInput.text?.clear()
        binding.codeLayout.error = null
        binding.newPasswordLayout.error = null
        binding.step2.visibility = View.GONE
        binding.step1.visibility = View.VISIBLE
        binding.sendCodeButton.isEnabled = true
    }

    private fun sendCode() {
        val typed = binding.emailInput.text?.toString()?.trim().orEmpty()
        if (!Patterns.EMAIL_ADDRESS.matcher(typed).matches()) {
            binding.emailLayout.error = getString(R.string.register_err_email)
            return
        }
        binding.emailLayout.error = null
        email = typed
        binding.sendCodeButton.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                BackendApi.forgotPassword(email)
                // Same message regardless of whether the account exists.
                binding.sentMessage.text = getString(R.string.reset_code_sent, maskEmail(email))
                binding.step1.visibility = View.GONE
                binding.step2.visibility = View.VISIBLE
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.reset_no_connection, Toast.LENGTH_SHORT).show()
                binding.sendCodeButton.isEnabled = true
            }
        }
    }

    private fun doReset() {
        val code = binding.codeInput.text?.toString()?.trim().orEmpty()
        val newPassword = binding.newPasswordInput.text?.toString().orEmpty()

        binding.codeLayout.error = null
        binding.newPasswordLayout.error = null
        var valid = true
        if (code.isBlank()) {
            binding.codeLayout.error = getString(R.string.reset_err_code); valid = false
        }
        if (newPassword.length < MIN_PASSWORD) {
            binding.newPasswordLayout.error = getString(R.string.register_err_password, MIN_PASSWORD); valid = false
        }
        if (!valid) return

        binding.resetButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                BackendApi.resetPassword(email, code, newPassword)
                Toast.makeText(requireContext(), R.string.reset_success, Toast.LENGTH_LONG).show()
                findNavController().popBackStack()
            } catch (e: ApiException) {
                // Server rejected the code (wrong / expired / too many attempts):
                // show it right under the code field so it isn't missed.
                binding.codeLayout.error = e.message
                binding.resetButton.isEnabled = true
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.reset_no_connection, Toast.LENGTH_SHORT).show()
                binding.resetButton.isEnabled = true
            }
        }
    }

    /** Mask the local part so only the first letter shows: l***@gmail.com */
    private fun maskEmail(address: String): String {
        val at = address.indexOf('@')
        if (at <= 0) return address
        return address.first() + "***" + address.substring(at)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val MIN_PASSWORD = 8
    }
}
