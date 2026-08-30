package com.example.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.model.CameraLens
import com.example.model.RecordingSettings
import com.example.model.VideoResolution
import com.example.model.VideoTakeItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class CameraXManager(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationSec = MutableStateFlow(0L)
    val recordingDurationSec: StateFlow<Long> = _recordingDurationSec.asStateFlow()

    private val _audioAmplitude = MutableStateFlow(0f)
    val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()

    private val _isTorchOn = MutableStateFlow(false)
    val isTorchOn: StateFlow<Boolean> = _isTorchOn.asStateFlow()

    private val _lastSavedVideo = MutableStateFlow<VideoTakeItem?>(null)
    val lastSavedVideo: StateFlow<VideoTakeItem?> = _lastSavedVideo.asStateFlow()

    private var currentLifecycleOwner: LifecycleOwner? = null
    private var currentPreviewView: PreviewView? = null
    private var currentSettings: RecordingSettings = RecordingSettings()
    private var currentTakeTag: String = "Take_1"
    private var currentCameraAngle: String = "CAM_A"

    companion object {
        private const val TAG = "CameraXManager"
    }

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        settings: RecordingSettings,
        cameraAngle: String = "CAM_A",
        onBound: () -> Unit = {}
    ) {
        this.currentLifecycleOwner = lifecycleOwner
        this.currentPreviewView = previewView
        this.currentSettings = settings
        this.currentCameraAngle = cameraAngle

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                setupAndBind(settings)
                onBound()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind camera lifecycle", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun setupAndBind(settings: RecordingSettings) {
        val provider = cameraProvider ?: return
        val lifecycleOwner = currentLifecycleOwner ?: return
        val previewView = currentPreviewView ?: return

        try {
            provider.unbindAll()

            val lensFacing = when (settings.lens) {
                CameraLens.FRONT.name -> CameraSelector.LENS_FACING_FRONT
                else -> CameraSelector.LENS_FACING_BACK
            }
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val quality = when (settings.resolution) {
                VideoResolution.UHD_4K.name -> Quality.UHD
                VideoResolution.HD_720P.name -> Quality.HD
                VideoResolution.SD_480P.name -> Quality.SD
                else -> Quality.FHD
            }

            val qualitySelector = QualitySelector.from(
                quality,
                FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
            )

            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .setExecutor(ContextCompat.getMainExecutor(context))
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                videoCapture
            )

            // Apply torch state if back camera
            if (settings.lens == CameraLens.BACK.name && settings.torchEnabled) {
                setTorch(true)
            } else {
                setTorch(false)
            }

            Log.d(TAG, "Camera bound successfully: ${settings.resolution} - ${settings.lens}")
        } catch (e: Exception) {
            Log.e(TAG, "Error binding camera with settings", e)
        }
    }

    fun updateSettings(settings: RecordingSettings) {
        this.currentSettings = settings
        if (!_isRecording.value) {
            setupAndBind(settings)
        }
    }

    fun startRecording(
        takeTag: String,
        cameraAngle: String,
        onStarted: () -> Unit = {},
        onFinalized: (VideoTakeItem?) -> Unit = {}
    ) {
        val videoCap = videoCapture ?: return
        if (_isRecording.value) return

        this.currentTakeTag = takeTag
        this.currentCameraAngle = cameraAngle

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val cleanTakeTag = takeTag.replace(" ", "_").replace(":", "-")
        val fileName = "MultiCam_${cleanTakeTag}_${cameraAngle}_$timeStamp.mp4"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MultiCam")
            }
        }

        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        val pendingRecording = videoCap.output.prepareRecording(context, mediaStoreOutput)

        val hasAudioPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (currentSettings.audioEnabled && hasAudioPerm) {
            try {
                pendingRecording.withAudioEnabled()
            } catch (e: SecurityException) {
                Log.e(TAG, "Audio permission missing when starting recording", e)
            }
        }

        activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    _isRecording.value = true
                    _recordingDurationSec.value = 0L
                    onStarted()
                    Log.d(TAG, "Recording started: $fileName")
                }
                is VideoRecordEvent.Status -> {
                    val durationNanos = event.recordingStats.recordedDurationNanos
                    _recordingDurationSec.value = durationNanos / 1_000_000_000L
                    _audioAmplitude.value = (event.recordingStats.audioStats.audioAmplitude).coerceIn(0.0, 1.0).toFloat()
                }
                is VideoRecordEvent.Finalize -> {
                    _isRecording.value = false
                    val duration = _recordingDurationSec.value
                    if (!event.hasError()) {
                        val outputUri = event.outputResults.outputUri
                        val item = VideoTakeItem(
                            id = UUID.randomUUID().toString(),
                            uriString = outputUri.toString(),
                            filePath = outputUri.path ?: fileName,
                            fileName = fileName,
                            durationSeconds = duration,
                            fileSizeBytes = event.recordingStats.numBytesRecorded,
                            timestamp = System.currentTimeMillis(),
                            resolutionLabel = currentSettings.resolution,
                            takeTag = takeTag,
                            nodeLabel = cameraAngle
                        )
                        _lastSavedVideo.value = item
                        onFinalized(item)
                        Log.d(TAG, "Recording completed successfully: $outputUri ($duration s)")
                    } else {
                        Log.e(TAG, "Recording failed: ${event.error}")
                        onFinalized(null)
                    }
                    activeRecording = null
                }
            }
        }
    }

    fun stopRecording() {
        if (_isRecording.value) {
            activeRecording?.stop()
        }
    }

    fun setTorch(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
        _isTorchOn.value = enabled
    }

    fun toggleTorch() {
        setTorch(!_isTorchOn.value)
    }

    fun setZoom(linearZoom: Float) {
        camera?.cameraControl?.setLinearZoom(linearZoom.coerceIn(0f, 1f))
    }

    fun release() {
        stopRecording()
        cameraProvider?.unbindAll()
        camera = null
        videoCapture = null
    }
}
