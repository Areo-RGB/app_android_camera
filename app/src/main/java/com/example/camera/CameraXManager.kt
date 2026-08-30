package com.example.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.view.View
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalSessionConfig
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
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
import com.example.model.VideoFps
import com.example.model.VideoResolution
import com.example.model.VideoTakeItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class CameraXManager(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var previewAttachStateListener: View.OnAttachStateChangeListener? = null

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
        detachPreviewLifecycleListener()

        this.currentLifecycleOwner = lifecycleOwner
        this.currentPreviewView = previewView
        this.currentSettings = settings
        this.currentCameraAngle = cameraAngle

        val attachStateListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                // AndroidView leaving composition means this preview is no longer visible.
                // Unbind immediately instead of keeping the Activity-lifecycle camera open.
                if (currentPreviewView === previewView) {
                    unbindCamera()
                }
            }
        }
        previewAttachStateListener = attachStateListener
        previewView.addOnAttachStateChangeListener(attachStateListener)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                // The view may have left composition while CameraX was initializing.
                if (currentPreviewView === previewView) {
                    setupAndBind(settings)
                    onBound()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind camera lifecycle", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @OptIn(ExperimentalSessionConfig::class)
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

            val capture = VideoCapture.Builder(recorder).build()
            videoCapture = capture

            // CameraX 1.5 can query frame-rate ranges that are guaranteed for this exact
            // Preview + VideoCapture use-case combination. Use one of those ranges for the
            // session so the FPS selector controls the real camera session rather than UI only.
            val requestedFps = requestedFps(settings)
            val baseSessionConfig = SessionConfig.Builder(preview, capture).build()
            val cameraInfo = provider.getCameraInfo(cameraSelector)
            val supportedFrameRateRanges = cameraInfo.getSupportedFrameRateRanges(baseSessionConfig)
            val selectedFrameRateRange = selectFrameRateRange(requestedFps, supportedFrameRateRanges)

            val sessionConfig = if (selectedFrameRateRange != null) {
                SessionConfig.Builder(preview, capture)
                    .setFrameRateRange(selectedFrameRateRange)
                    .build()
            } else {
                Log.w(
                    TAG,
                    "Requested ${requestedFps}fps is not supported for ${settings.resolution}/${settings.lens}. " +
                        "Available ranges: $supportedFrameRateRanges. Binding with device default FPS."
                )
                baseSessionConfig
            }

            camera = try {
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    sessionConfig
                )
            } catch (frameRateError: IllegalArgumentException) {
                // Device-specific combinations can still reject a range at bind time. Keep the
                // preview/recorder usable instead of crashing or leaving the camera unbound.
                Log.w(
                    TAG,
                    "Could not bind requested ${requestedFps}fps range $selectedFrameRateRange; " +
                        "falling back to device default FPS",
                    frameRateError
                )
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    baseSessionConfig
                )
            }

            // Apply torch state if back camera
            if (settings.lens == CameraLens.BACK.name && settings.torchEnabled) {
                setTorch(true)
            } else {
                setTorch(false)
            }

            Log.d(
                TAG,
                "Camera bound successfully: ${settings.resolution} - ${settings.lens} - " +
                    "requested ${requestedFps}fps, applied ${selectedFrameRateRange ?: "device default"}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error binding camera with settings", e)
        }
    }

    private fun requestedFps(settings: RecordingSettings): Int {
        return when (settings.fps) {
            VideoFps.FPS_24.name -> VideoFps.FPS_24.fpsValue
            VideoFps.FPS_60.name -> VideoFps.FPS_60.fpsValue
            else -> VideoFps.FPS_30.fpsValue
        }
    }

    private fun selectFrameRateRange(
        requestedFps: Int,
        supportedRanges: Set<Range<Int>>
    ): Range<Int>? {
        return supportedRanges
            .filter { it.contains(requestedFps) }
            .minWithOrNull(
                compareBy<Range<Int>>(
                    { if (it.lower == requestedFps && it.upper == requestedFps) 0 else 1 },
                    { it.upper - it.lower },
                    { kotlin.math.abs(it.lower - requestedFps) + kotlin.math.abs(it.upper - requestedFps) }
                )
            )
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
        // Stop even if VideoRecordEvent.Start has not arrived yet. This makes screen disposal and
        // rapid mode changes safe during the short asynchronous recording-start window.
        activeRecording?.let { recording ->
            try {
                recording.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Recording was already stopping/stopped", e)
            }
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

    fun unbindCamera() {
        stopRecording()
        detachPreviewLifecycleListener()
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding camera", e)
        }
        camera = null
        videoCapture = null
        currentPreviewView = null
        currentLifecycleOwner = null
        _isTorchOn.value = false
    }

    private fun detachPreviewLifecycleListener() {
        val listener = previewAttachStateListener ?: return
        currentPreviewView?.removeOnAttachStateChangeListener(listener)
        previewAttachStateListener = null
    }

    fun release() {
        unbindCamera()
        cameraProvider = null
    }
}
