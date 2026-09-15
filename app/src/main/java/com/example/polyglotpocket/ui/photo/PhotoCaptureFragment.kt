package com.example.polyglotpocket.ui.photo

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.example.polyglotpocket.R
import com.example.polyglotpocket.databinding.FragmentPhotoCaptureBinding

// First step of the photo flow: lets the user take a live picture using CameraX or pick one from the gallery.
class PhotoCaptureFragment : Fragment() {

    // View binding to interact with UI elements (like buttons and image preview) safely.
    private var _binding: FragmentPhotoCaptureBinding? = null
    private val binding get() = _binding!!

    // Shared ViewModel: it uses `activityViewModels()` so both the PhotoCaptureFragment and PhotoResultFragment screens share the exact same data and photo.
    private val viewModel: PhotoViewModel by activityViewModels()

    // CameraX object used to actually capture the high-quality picture when the user taps the shutter button.
    private var imageCapture: ImageCapture? = null

    // System prompt that asks the user for Camera permissions.
    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) enterPreviewMode()
            else Toast.makeText(requireContext(), R.string.photo_permission_needed, Toast.LENGTH_SHORT).show()
        }

    // Android's modern Photo Picker tool. It safely lets the user choose a photo from their gallery without needing storage permissions.
    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            // If the user picked a photo (didn't just close the picker), process it.
            if (uri != null) handlePickedImage(uri)
        }

    // Inflates the layout XML for this screen.
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPhotoCaptureBinding.inflate(inflater, container, false)
        return binding.root
    }

    // Sets up all buttons and prepares the screen based on the user's choices.
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Grab the target language chosen in the previous screen and tell the ViewModel.
        viewModel.targetLang = arguments?.getString("targetLang").orEmpty()
        viewModel.resetForNewCapture()

        // When the user taps "Take Photo", check permissions and start the live camera.
        binding.takePictureButton.setOnClickListener { ensureCameraThenPreview() }
        
        // When the user taps "Choose from Library", open the system's gallery picker.
        binding.chooseLibraryButton.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        
        // When the user taps the big round shutter button, actually snap the photo.
        binding.captureButton.setOnClickListener { takePhoto() }

        // Start listening to the ViewModel to know when the AI finishes analyzing the photo.
        observeDetection()
    }

    // Listens to the ViewModel's state to update the UI (like showing a spinner or navigating to the results).
    private fun observeDetection() {
        viewModel.detect.observe(viewLifecycleOwner) { state ->
            when (state) {
                is PhotoViewModel.DetectState.Loading ->
                    // Show a loading spinner while the photo uploads to the AI server.
                    binding.progressOverlay.visibility = View.VISIBLE
                is PhotoViewModel.DetectState.Ready -> {
                    // AI finished successfully! Hide the spinner and move to the Result screen.
                    binding.progressOverlay.visibility = View.GONE
                    findNavController().navigate(R.id.action_photo_capture_to_result)
                }
                is PhotoViewModel.DetectState.Empty -> {
                    // The AI didn't find any recognizable objects in the photo. Tell the user and reset the screen.
                    binding.progressOverlay.visibility = View.GONE
                    Toast.makeText(requireContext(), R.string.photo_no_objects, Toast.LENGTH_SHORT).show()
                    resetToChoice()
                }
                is PhotoViewModel.DetectState.Error -> {
                    // Network or server error. Show a popup message and reset the screen.
                    binding.progressOverlay.visibility = View.GONE
                    Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                    resetToChoice()
                }
                PhotoViewModel.DetectState.Idle -> Unit
            }
        }
    }

    // Checks if the app already has camera permissions. If yes, starts the camera; if no, asks the user.
    private fun ensureCameraThenPreview() {
        val granted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) enterPreviewMode() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    // Hides the initial "Take Photo/Gallery" buttons and reveals the live camera viewfinder.
    private fun enterPreviewMode() {
        binding.choiceButtons.visibility = View.GONE
        binding.previewView.visibility = View.VISIBLE
        binding.captureButton.visibility = View.VISIBLE
        startCamera()
    }

    // Resets the screen to its initial state, hiding the camera viewfinder.
    private fun resetToChoice() {
        binding.previewView.visibility = View.GONE
        binding.captureButton.visibility = View.GONE
        binding.choiceButtons.visibility = View.VISIBLE
    }

    // Connects the device's actual camera hardware to the preview box on the screen using CameraX.
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            // with CameraX the provider is a 'manager' between the code and the camera physical hardware
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                // Link the camera feed to our UI view.
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()
            try {
                // Before taking any photo, disconnect the camera from any other app that was using the camera in background to avoid conflicts
                provider.unbindAll()
                // tell the provider to manage the lifecycle of the camera (eg. if the user turns off the phone
                // or changes the app, disconnect the camera)
                provider.bindToLifecycle(
                    viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.localizedMessage, Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // Triggers the CameraX shutter to take a still picture.
    private fun takePhoto() {
        // if the imageCapture object created in startCamera() is not ready yet, so camera is not ready,
        // ignore the click (return)
        val capture = imageCapture ?: return
        
        // Temporarily disable the button so the user doesn't take 5 photos by double-tapping.
        binding.captureButton.isEnabled = false
        
        capture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                // Photo taken successfully!
                override fun onCaptureSuccess(image: ImageProxy) {
                    // CameraX sometimes captures photos sideways. We rotate it back to upright immediately.
                    val bitmap = rotate(image.toBitmap(), image.imageInfo.rotationDegrees)
                    image.close() // Free up memory by deleting the just captured image (saved as bitmap)
                    binding.captureButton.isEnabled = true // enable the button so user can take another image
                    
                    // Hand the final, upright picture to the ViewModel for AI processing.
                    viewModel.onImageCaptured(bitmap)
                }

                // Something went wrong while taking the photo.
                override fun onError(exception: ImageCaptureException) {
                    binding.captureButton.isEnabled = true
                    Toast.makeText(requireContext(), exception.localizedMessage, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    // Converts the file chosen from the gallery into a usable image (Bitmap) and sends it to the ViewModel.
    private fun handlePickedImage(uri: Uri) {
        val bitmap = try {
            decodeBitmap(uri)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), e.localizedMessage, Toast.LENGTH_LONG).show()
            return
        }
        viewModel.onImageCaptured(bitmap) // pass the photo to the viewModel
    }

    // Safely reads an image file from the phone's gallery, handling newer and older Android versions.
    private fun decodeBitmap(uri: Uri): Bitmap {
        val resolver = requireContext().contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Modern way (Android 9+): Automatically fixes tilted photos using EXIF data.
            val source = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                // Force it to be a software bitmap so we can compress it to Base64 later.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            // Old way (Before Android 9)
            resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
        }
    }

    // Helper function that spins an image by a specific number of degrees to ensure it's upright.
    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // Cleans up the view binding to prevent memory leaks when navigating away.
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
