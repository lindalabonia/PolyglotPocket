package com.example.polyglotpocket.ui.login

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.polyglotpocket.BuildConfig
import com.example.polyglotpocket.R
import com.example.polyglotpocket.databinding.FragmentLoginBinding
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.launch

class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LoginViewModel by viewModels()
    private val credentialManager by lazy { CredentialManager.create(requireContext()) }

    // Show the saved-password picker only once per screen.
    private var credentialPickerShown = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.loginButton.setOnClickListener {
            viewModel.login(username(), password())
        }
        binding.registerButton.setOnClickListener {
            viewModel.register(username(), password())
        }

        // Standard pattern: offer a saved password when the user taps a field.
        val focusListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) requestSavedPassword()
        }
        binding.usernameInput.onFocusChangeListener = focusListener
        binding.passwordInput.onFocusChangeListener = focusListener

        binding.googleSignInButton.setOnClickListener { signInWithGoogle() }
        binding.retryButton.setOnClickListener { viewModel.tryAutoLogin() }

        viewModel.state.observe(viewLifecycleOwner) { state ->
            // Show the form ONLY when we actually need it (idle/error). While checking a
            // token or right before navigating home (success), keep the spinner so the
            // login form never flashes.
            val showForm = state is LoginViewModel.State.Idle || state is LoginViewModel.State.Error
            val showOffline = state is LoginViewModel.State.Offline
            binding.loginContent.visibility = if (showForm) View.VISIBLE else View.GONE
            binding.offlinePanel.visibility = if (showOffline) View.VISIBLE else View.GONE
            binding.loginProgress.visibility = if (!showForm && !showOffline) View.VISIBLE else View.GONE

            when (state) {
                is LoginViewModel.State.Success -> {
                    // Keep the spinner (don't reset to Idle) so the form doesn't flash
                    // before we navigate to Home.
                    saveCredentialAndGoHome(username(), password(), state.username)
                }
                is LoginViewModel.State.Error -> {
                    Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                    viewModel.consumeState()
                }
                else -> Unit
            }
        }

        // If a valid session token is already stored, skip login.
        if (savedInstanceState == null) viewModel.tryAutoLogin()
    }

    private fun username() = binding.usernameInput.text?.toString().orEmpty()
    private fun password() = binding.passwordInput.text?.toString().orEmpty()

    /** After a successful auth, offer to save the password, then go to the menu. */
    private fun saveCredentialAndGoHome(user: String, pass: String, displayName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (user.isNotEmpty() && pass.isNotEmpty()) {
                try {
                    credentialManager.createCredential(
                        requireActivity(),
                        CreatePasswordRequest(user, pass)
                    )
                } catch (e: CreateCredentialException) {
                    // User dismissed the save dialog or no provider: not fatal.
                    Log.w("CredentialManager", "Password save failed: ${e.message}")
                }
            }
            findNavController().navigate(
                R.id.action_login_to_home,
                bundleOf("username" to displayName)
            )
        }
    }

    private fun requestSavedPassword() {
        if (credentialPickerShown) return
        credentialPickerShown = true
        loginWithSavedPassword()
    }

    /** Ask Credential Manager for a saved password and log in with it. */
    private fun loginWithSavedPassword() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = credentialManager.getCredential(
                    requireActivity(),
                    GetCredentialRequest(listOf(GetPasswordOption()))
                )
                val cred = response.credential
                if (cred is PasswordCredential) {
                    binding.usernameInput.setText(cred.id)
                    binding.passwordInput.setText(cred.password)
                    viewModel.login(cred.id, cred.password)
                }
            } catch (e: GetCredentialException) {
                // No saved credential or user dismissed: stay silent.
                Log.w("CredentialManager", "No saved credential: ${e.message}")
            }
        }
    }

    /** Sign in with Google via Credential Manager (button flow), with a nonce. */
    private fun signInWithGoogle() {
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) {
            Toast.makeText(requireContext(), "Google Sign-In is not configured", Toast.LENGTH_SHORT).show()
            return
        }

        val rawNonce = UUID.randomUUID().toString()
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setNonce(sha256(rawNonce))
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = credentialManager.getCredential(requireActivity(), request)
                val cred = response.credential
                if (cred is CustomCredential &&
                    cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleCred = GoogleIdTokenCredential.createFrom(cred.data)
                    viewModel.loginWithGoogle(googleCred.idToken, rawNonce)
                } else {
                    Toast.makeText(requireContext(), "Unexpected credential type", Toast.LENGTH_SHORT).show()
                }
            } catch (e: GetCredentialException) {
                Log.w("GoogleSignIn", "Failed: ${e.message}")
                Toast.makeText(requireContext(), "Google sign-in failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** SHA-256 hex of the raw nonce; the backend checks it against the token's nonce. */
    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
