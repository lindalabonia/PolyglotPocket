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

/**
 * First step of the photo flow: let the user take a picture (CameraX preview +
 * shutter) or pick one from the gallery, then hand the bitmap to the ViewModel
 * for object detection and move on to the result screen.
 */
class PhotoCaptureFragment : Fragment() {

    private var _binding: FragmentPhotoCaptureBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PhotoViewModel by activityViewModels()

    private var imageCapture: ImageCapture? = null

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) enterPreviewMode()
            else Toast.makeText(requireContext(), R.string.photo_permission_needed, Toast.LENGTH_SHORT).show()
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) handlePickedImage(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPhotoCaptureBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.targetLang = arguments?.getString("targetLang").orEmpty()
        viewModel.resetForNewCapture()

        binding.takePictureButton.setOnClickListener { ensureCameraThenPreview() }
        binding.chooseLibraryButton.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.captureButton.setOnClickListener { takePhoto() }

        observeDetection()
    }

    private fun observeDetection() {
        viewModel.detect.observe(viewLifecycleOwner) { state ->
            when (state) {
                is PhotoViewModel.DetectState.Loading ->
                    binding.progressOverlay.visibility = View.VISIBLE
                is PhotoViewModel.DetectState.Ready -> {
                    binding.progressOverlay.visibility = View.GONE
                    findNavController().navigate(R.id.action_photo_capture_to_result)
                }
                is PhotoViewModel.DetectState.Empty -> {
                    binding.progressOverlay.visibility = View.GONE
                    Toast.makeText(requireContext(), R.string.photo_no_objects, Toast.LENGTH_SHORT).show()
                    resetToChoice()
                }
                is PhotoViewModel.DetectState.Error -> {
                    binding.progressOverlay.visibility = View.GONE
                    Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                    resetToChoice()
                }
                PhotoViewModel.DetectState.Idle -> Unit
            }
        }
    }

    private fun ensureCameraThenPreview() {
        val granted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) enterPreviewMode() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun enterPreviewMode() {
        binding.choiceButtons.visibility = View.GONE
        binding.previewView.visibility = View.VISIBLE
        binding.captureButton.visibility = View.VISIBLE
        startCamera()
    }

    private fun resetToChoice() {
        binding.previewView.visibility = View.GONE
        binding.captureButton.visibility = View.GONE
        binding.choiceButtons.visibility = View.VISIBLE
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.localizedMessage, Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        binding.captureButton.isEnabled = false
        capture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = rotate(image.toBitmap(), image.imageInfo.rotationDegrees)
                    image.close()
                    binding.captureButton.isEnabled = true
                    viewModel.onImageCaptured(bitmap)
                }

                override fun onError(exception: ImageCaptureException) {
                    binding.captureButton.isEnabled = true
                    Toast.makeText(requireContext(), exception.localizedMessage, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun handlePickedImage(uri: Uri) {
        val bitmap = try {
            decodeBitmap(uri)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), e.localizedMessage, Toast.LENGTH_LONG).show()
            return
        }
        viewModel.onImageCaptured(bitmap)
    }

    /** Decode a gallery image. ImageDecoder (API 28+) applies the EXIF rotation;
     *  a software bitmap is required so it can later be compressed to JPEG. */
    private fun decodeBitmap(uri: Uri): Bitmap {
        val resolver = requireContext().contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
        }
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
