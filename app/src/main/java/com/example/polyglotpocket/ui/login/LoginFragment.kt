package com.example.polyglotpocket.ui.login

import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
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

// This screen handles everything related to logging in: standard username/password, Google Sign-In, and saved passwords.
class LoginFragment : Fragment() {

    // Safely connects the XML layout (fragment_login.xml) to this code so we don't have to use findViewById.
    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    // Connects this UI screen to its "brain" (LoginViewModel) where the actual network login happens.
    private val viewModel: LoginViewModel by viewModels()
    
    // The official Android tool that talks to the phone's password manager (like Google Password Manager).
    private val credentialManager by lazy { CredentialManager.create(requireContext()) }

    // Makes sure we only show the "saved passwords" popup once per visit, so we don't annoy the user.
    private var credentialPickerShown = false

    // Loads the XML layout onto the screen.
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    // Sets up all the buttons and actions as soon as the screen is ready.
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // When the user taps the login button, tell the ViewModel to try logging in.
        binding.loginButton.setOnClickListener {
            viewModel.login(username(), password())
        }
        
        // Opens the popup window where new users can register.
        binding.registerButton.setOnClickListener { showRegisterDialog() }
        
        // Moves the user to the "I forgot my password" screen.
        binding.forgotPasswordLink.setOnClickListener {
            findNavController().navigate(R.id.action_login_to_forgot)
        }

        // Prompt the user with their saved passwords as soon as they tap on the username or password fields.
        val focusListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) requestSavedPassword()
        }
        binding.usernameInput.onFocusChangeListener = focusListener
        binding.passwordInput.onFocusChangeListener = focusListener

        // Starts the Google Sign-In process when the user taps the Google button.
        binding.googleSignInButton.setOnClickListener { signInWithGoogle() }
        
        // Let the user try logging in again if their internet was down.
        binding.retryButton.setOnClickListener { viewModel.tryAutoLogin() }

        // Watch for updates from the ViewModel (e.g., "loading", "success", "error") and update the screen.
        viewModel.state.observe(viewLifecycleOwner) { state ->
            // Only show the login form if we are idle or if there was an error. 
            // If we are loading, we hide the form and show the spinning wheel.
            val showForm = state is LoginViewModel.State.Idle || state is LoginViewModel.State.Error
            val showOffline = state is LoginViewModel.State.Offline
            binding.loginContent.visibility = if (showForm) View.VISIBLE else View.GONE
            binding.offlinePanel.visibility = if (showOffline) View.VISIBLE else View.GONE
            binding.loginProgress.visibility = if (!showForm && !showOffline) View.VISIBLE else View.GONE

            when (state) {
                is LoginViewModel.State.Success -> {
                    // Login worked! Ask the user if they want to save their password, then go to the main app screen.
                    saveCredentialAndGoHome(username(), password(), state.username)
                }
                is LoginViewModel.State.Error -> {
                    // Login failed: show a small popup message (Toast) with the error, then reset the state.
                    Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                    viewModel.consumeState()
                }
                else -> Unit
            }
        }

        // When the app first opens, quietly check if the user is already logged in from a previous session.
        if (savedInstanceState == null) viewModel.tryAutoLogin()
    }

    // Grabs the text the user typed into the username box.
    private fun username() = binding.usernameInput.text?.toString().orEmpty()
    
    // Grabs the text the user typed into the password box.
    private fun password() = binding.passwordInput.text?.toString().orEmpty()

    // Creates and shows a popup window asking for username, email, and password to create a new account.
    private fun showRegisterDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_register, null)
        val userField = view.findViewById<EditText>(R.id.regUsername)
        val emailField = view.findViewById<EditText>(R.id.regEmail)
        val passField = view.findViewById<EditText>(R.id.regPassword)
        val userLayout = view.findViewById<TextInputLayout>(R.id.regUsernameLayout)
        val emailLayout = view.findViewById<TextInputLayout>(R.id.regEmailLayout)
        val passLayout = view.findViewById<TextInputLayout>(R.id.regPasswordLayout)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .setPositiveButton(R.string.register_create, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val user = userField.text?.toString()?.trim().orEmpty()
                val email = emailField.text?.toString()?.trim().orEmpty()
                val pass = passField.text?.toString().orEmpty()

                userLayout.error = null
                emailLayout.error = null
                passLayout.error = null

                var valid = true
                
                // Check if the username is long enough
                if (user.length < MIN_USERNAME) {
                    userLayout.error = getString(R.string.register_err_username); valid = false
                }
                // Check if the email looks like a real email address
                if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    emailLayout.error = getString(R.string.register_err_email); valid = false
                }
                // Check if the password is long enough
                if (pass.length < MIN_PASSWORD) {
                    passLayout.error = getString(R.string.register_err_password, MIN_PASSWORD); valid = false
                }
                // Stop here if there are any errors in the form
                if (!valid) return@setOnClickListener

                // Copy the details into the main login screen so the phone can save them later.
                binding.usernameInput.setText(user)
                binding.passwordInput.setText(pass)
                dialog.dismiss()
                
                // Tell the ViewModel to actually create the account on the server.
                viewModel.register(user, email, pass)
            }
        }
        dialog.show()
    }

    // Asks the phone to save the newly typed password, then moves the user to the Home screen.
    private fun saveCredentialAndGoHome(user: String, pass: String, displayName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (user.isNotEmpty() && pass.isNotEmpty()) {
                try {
                    // Opens the system popup asking "Do you want to save this password to Google?"
                    credentialManager.createCredential(
                        requireActivity(),
                        CreatePasswordRequest(user, pass)
                    )
                } catch (e: CreateCredentialException) {
                    // It's okay if the user says no or closes the popup, just log it.
                    Log.w("CredentialManager", "Password save failed: ${e.message}")
                }
            }
            // Move to the Home screen and bring the user's name along.
            findNavController().navigate(
                R.id.action_login_to_home,
                bundleOf("username" to displayName)
            )
        }
    }

    // A small helper to make sure we don't spam the user with the password prompt multiple times.
    private fun requestSavedPassword() {
        if (credentialPickerShown) return
        credentialPickerShown = true
        loginWithSavedPassword()
    }

    // Asks the phone's password manager if there are any saved passwords for this app.
    private fun loginWithSavedPassword() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Fetch saved passwords from the device
                val response = credentialManager.getCredential(
                    requireActivity(),
                    GetCredentialRequest(listOf(GetPasswordOption()))
                )
                val cred = response.credential
                if (cred is PasswordCredential) {
                    // If the user picked a saved account, fill in the text boxes and click "login" automatically.
                    binding.usernameInput.setText(cred.id)
                    binding.passwordInput.setText(cred.password)
                    viewModel.login(cred.id, cred.password)
                }
            } catch (e: GetCredentialException) {
                // If there are no saved passwords or the user closed the popup, silently do nothing.
                Log.w("CredentialManager", "No saved credential: ${e.message}")
            }
        }
    }

    // Opens the Google Sign-In popup using the Android Credential Manager.
    private fun signInWithGoogle() {
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) {
            Toast.makeText(requireContext(), "Google Sign-In is not configured", Toast.LENGTH_SHORT).show()
            return
        }

        // Generate a random string (nonce) to securely link this login attempt to our server.
        val rawNonce = UUID.randomUUID().toString()
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setNonce(sha256(rawNonce))
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Show the "Choose an account" Google popup to the user
                val response = credentialManager.getCredential(requireActivity(), request)
                val cred = response.credential
                
                // If the user picked a Google account, extract the official ID token.
                if (cred is CustomCredential &&
                    cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleCred = GoogleIdTokenCredential.createFrom(cred.data)
                    // Send the Google token and our random string to our server to finish logging in.
                    viewModel.loginWithGoogle(googleCred.idToken, rawNonce)
                } else {
                    Toast.makeText(requireContext(), "Unexpected credential type", Toast.LENGTH_SHORT).show()
                }
            } catch (e: GetCredentialException) {
                // The user probably closed the Google popup without picking an account.
                Log.w("GoogleSignIn", "Failed: ${e.message}")
                Toast.makeText(requireContext(), "Google sign-in failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Converts our random string (nonce) into a secure hash format that Google requires.
    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    // Cleans up memory when the user leaves this screen so the app doesn't crash.
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val MIN_USERNAME = 3
        private const val MIN_PASSWORD = 8
    }
}
