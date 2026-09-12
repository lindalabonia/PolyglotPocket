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

// This screen handles a two-step password reset: (1) type email to get a code, (2) type the code and the new password.
class ForgotPasswordFragment : Fragment() {

    // Safely connects the XML layout (fragment_forgot_password.xml) to this code.
    private var _binding: FragmentForgotPasswordBinding? = null
    private val binding get() = _binding!!

    // Temporarily stores the email the user typed in step 1, so we can send it again in step 2.
    private var email = ""

    // Loads the XML layout onto the screen.
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentForgotPasswordBinding.inflate(inflater, container, false)
        return binding.root
    }

    // Sets up the buttons as soon as the screen is ready.
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Step 1: User clicks this to ask for a reset code via email.
        binding.sendCodeButton.setOnClickListener { sendCode() }
        
        // Step 2: User clicks this to submit the code and save their new password.
        binding.resetButton.setOnClickListener { doReset() }
        
        // If the user's code expired or they made a typo in their email, let them go back to step 1.
        binding.requestNewCodeButton.setOnClickListener { backToEmailStep() }
        
        // Goes back to the main Login screen.
        binding.backToLogin.setOnClickListener { findNavController().popBackStack() }
    }

    // Resets the screen back to step 1 (asking for the email address) and clears out any errors or old text.
    // e.g. after the old one expired or was invalidated by too many wrong attempts
    private fun backToEmailStep() {
        binding.codeInput.text?.clear()
        binding.newPasswordInput.text?.clear()
        binding.codeLayout.error = null
        binding.newPasswordLayout.error = null
        
        // Show step 1, hide step 2.
        binding.step2.visibility = View.GONE
        binding.step1.visibility = View.VISIBLE
        binding.sendCodeButton.isEnabled = true
    }

    // Handles the first step: validating the email and asking the server to send the reset code.
    private fun sendCode() {
        val typed = binding.emailInput.text?.toString()?.trim().orEmpty()
        
        // Make sure it actually looks like an email address before bothering the server.
        if (!Patterns.EMAIL_ADDRESS.matcher(typed).matches()) {
            binding.emailLayout.error = getString(R.string.register_err_email)
            return
        }
        
        binding.emailLayout.error = null
        email = typed
        
        // Disable the button so the user can't spam the server by clicking it 10 times.
        binding.sendCodeButton.isEnabled = false

        // Launch a background task for the network call.
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                BackendApi.forgotPassword(email)
                
                // Show a success message masking the email (e.g. m***@gmail.com) for privacy.
                // NOTE: We show this success message even if the email doesn't exist in our database.
                // This prevents hackers from guessing which emails are registered.
                binding.sentMessage.text = getString(R.string.reset_code_sent, maskEmail(email))
                
                // Move the UI to step 2.
                binding.step1.visibility = View.GONE
                binding.step2.visibility = View.VISIBLE
            } catch (e: Exception) {
                // Network failed. Turn the button back on so they can try again.
                Toast.makeText(requireContext(), R.string.reset_no_connection, Toast.LENGTH_SHORT).show()
                binding.sendCodeButton.isEnabled = true
            }
        }
    }

    // Handles the second step: validating the code and the new password, then sending them to the server.
    private fun doReset() {
        val code = binding.codeInput.text?.toString()?.trim().orEmpty()
        val newPassword = binding.newPasswordInput.text?.toString().orEmpty()

        binding.codeLayout.error = null
        binding.newPasswordLayout.error = null
        var valid = true
        
        // Check if the code box is empty.
        if (code.isBlank()) {
            binding.codeLayout.error = getString(R.string.reset_err_code); valid = false
        }
        // Check if the new password is secure enough (at least 8 characters).
        if (newPassword.length < MIN_PASSWORD) {
            binding.newPasswordLayout.error = getString(R.string.register_err_password, MIN_PASSWORD); valid = false
        }
        if (!valid) return

        // Prevent spam clicking while we wait for the server.
        binding.resetButton.isEnabled = false
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                BackendApi.resetPassword(email, code, newPassword)
                
                // It worked! Show a success message and kick the user back to the Login screen.
                Toast.makeText(requireContext(), R.string.reset_success, Toast.LENGTH_LONG).show()
                findNavController().popBackStack()
            } catch (e: ApiException) {
                // The server rejected the request (wrong code, expired code, or too many guesses).
                // We show the error directly under the code input box.
                binding.codeLayout.error = e.message
                binding.resetButton.isEnabled = true
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.reset_no_connection, Toast.LENGTH_SHORT).show()
                binding.resetButton.isEnabled = true
            }
        }
    }

    // Privacy helper: turns "abcdefg@gmail.com" into "a******@gmail.com".
    private fun maskEmail(address: String): String {
        val at = address.indexOf('@')
        if (at <= 0) return address
        return address.first() + "***" + address.substring(at)
    }

    // Cleans up memory when the user leaves this screen.
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val MIN_PASSWORD = 8
    }
}
