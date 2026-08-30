package com.example.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.camera.CameraXManager
import com.example.model.AppMode
import com.example.model.CameraLens
import com.example.model.CameraNodeInfo
import com.example.model.NodeRecordingStatus
import com.example.model.RecordingSettings
import com.example.model.VideoFps
import com.example.model.VideoResolution
import com.example.model.VideoTakeItem
import com.example.network.CameraNodeClient
import com.example.network.DirectorServer
import com.example.network.NetworkServiceDiscovery
import com.example.util.DeviceUtils
import com.example.util.SyncBeepGenerator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MultiCamViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()

    val cameraManager = CameraXManager(context)
    private var directorServer: DirectorServer? = null
    private var nodeClient: CameraNodeClient? = null

    private val _appMode = MutableStateFlow(AppMode.HUB)
    val appMode: StateFlow<AppMode> = _appMode.asStateFlow()

    private val _settings = MutableStateFlow(RecordingSettings())
    val settings: StateFlow<RecordingSettings> = _settings.asStateFlow()

    // Director states
    private val _directorIp = MutableStateFlow("127.0.0.1")
    val directorIp: StateFlow<String> = _directorIp.asStateFlow()

    private val _isDirectorLocalCameraEnabled = MutableStateFlow(true)
    val isDirectorLocalCameraEnabled: StateFlow<Boolean> = _isDirectorLocalCameraEnabled.asStateFlow()

    private val _connectedNodes = MutableStateFlow<Map<String, CameraNodeInfo>>(emptyMap())
    val connectedNodes: StateFlow<Map<String, CameraNodeInfo>> = _connectedNodes.asStateFlow()

    private val _directorLogs = MutableStateFlow<List<String>>(emptyList())
    val directorLogs: StateFlow<List<String>> = _directorLogs.asStateFlow()

    // Node Client states
    private val _nodeConnectionState = MutableStateFlow<CameraNodeClient.ConnectionState>(CameraNodeClient.ConnectionState.Disconnected)
    val nodeConnectionState: StateFlow<CameraNodeClient.ConnectionState> = _nodeConnectionState.asStateFlow()

    private val _discoveredDirectors = MutableStateFlow<List<NetworkServiceDiscovery.DiscoveredDirector>>(emptyList())
    val discoveredDirectors: StateFlow<List<NetworkServiceDiscovery.DiscoveredDirector>> = _discoveredDirectors.asStateFlow()

    private val _assignedCameraAngle = MutableStateFlow("CAM A")
    val assignedCameraAngle: StateFlow<String> = _assignedCameraAngle.asStateFlow()

    // Recording & Sync Animation States
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationSec = MutableStateFlow(0L)
    val recordingDurationSec: StateFlow<Long> = _recordingDurationSec.asStateFlow()

    private val _countdownValue = MutableStateFlow<Int?>(null)
    val countdownValue: StateFlow<Int?> = _countdownValue.asStateFlow()

    private val _isClapperFlashing = MutableStateFlow(false)
    val isClapperFlashing: StateFlow<Boolean> = _isClapperFlashing.asStateFlow()

    // Recorded Takes Library
    private val _recordedTakes = MutableStateFlow<List<VideoTakeItem>>(emptyList())
    val recordedTakes: StateFlow<List<VideoTakeItem>> = _recordedTakes.asStateFlow()

    private var countdownJob: Job? = null
    private var durationTimerJob: Job? = null

    val isTorchOn: StateFlow<Boolean> = cameraManager.isTorchOn

    // --- Mode Setup ---

    fun selectMode(mode: AppMode) {
        _appMode.value = mode
        when (mode) {
            AppMode.DIRECTOR -> startDirectorMode()
            AppMode.CAMERA_NODE -> startCameraNodeMode()
            AppMode.HUB -> cleanupAll()
        }
    }

    // --- Director Mode Logic ---

    private fun startDirectorMode() {
        cleanupAll()
        val server = DirectorServer(context)
        directorServer = server
        _assignedCameraAngle.value = "CAM 1 (Director)"

        server.startServer { ip, _ ->
            _directorIp.value = ip
        }

        viewModelScope.launch {
            server.connectedNodes.collect { nodes ->
                _connectedNodes.value = nodes
            }
        }
        viewModelScope.launch {
            server.eventLogs.collect { logs ->
                _directorLogs.value = logs
            }
        }
    }

    fun toggleDirectorLocalCamera() {
        _isDirectorLocalCameraEnabled.value = !_isDirectorLocalCameraEnabled.value
    }

    fun updateSettings(newSettings: RecordingSettings) {
        _settings.value = newSettings
        cameraManager.updateSettings(newSettings)
        if (_appMode.value == AppMode.DIRECTOR) {
            directorServer?.broadcastSettings(newSettings)
        }
    }

    fun setResolution(resolution: VideoResolution) {
        updateSettings(_settings.value.copy(resolution = resolution.name))
    }

    fun setFps(fps: VideoFps) {
        updateSettings(_settings.value.copy(fps = fps.name))
    }

    fun setLens(lens: CameraLens) {
        updateSettings(_settings.value.copy(lens = lens.name))
    }

    fun toggleAudio() {
        updateSettings(_settings.value.copy(audioEnabled = !_settings.value.audioEnabled))
    }

    fun toggleTorch() {
        val next = !_settings.value.torchEnabled
        updateSettings(_settings.value.copy(torchEnabled = next))
        cameraManager.setTorch(next)
    }

    fun setSceneName(scene: String) {
        updateSettings(_settings.value.copy(sceneName = scene))
    }

    fun nextTake() {
        updateSettings(_settings.value.copy(takeNumber = _settings.value.takeNumber + 1))
    }

    // --- Synchronized Start / Stop Recording ---

    fun startMasterRecording() {
        if (_isRecording.value) return
        val current = _settings.value
        val takeTag = "${current.sceneName}_Take_${current.takeNumber}"
        val countdownSec = current.countdownSeconds

        // Broadcast to all connected camera nodes
        directorServer?.broadcastStartRecording(
            settings = current,
            countdownSeconds = countdownSec,
            takeTag = takeTag
        )

        // Run local synced countdown & record
        runSyncedCountdown(countdownSec, takeTag, isDirector = true)
    }

    fun stopMasterRecording() {
        directorServer?.broadcastStopRecording()
        stopLocalRecording()
    }

    // --- Camera Node Mode Logic ---

    private fun startCameraNodeMode() {
        cleanupAll()
        val client = CameraNodeClient(context)
        nodeClient = client

        viewModelScope.launch {
            client.connectionState.collect { state ->
                _nodeConnectionState.value = state
            }
        }
        viewModelScope.launch {
            client.assignedCameraAngle.collect { angle ->
                _assignedCameraAngle.value = angle
            }
        }

        client.onSyncSettingsReceived = { syncedSettings ->
            _settings.value = syncedSettings
            cameraManager.updateSettings(syncedSettings)
        }

        client.onStartRecordingTriggered = { targetTimeMs, syncedSettings, takeTag ->
            _settings.value = syncedSettings
            cameraManager.updateSettings(syncedSettings)
            val waitMs = (targetTimeMs - System.currentTimeMillis()).coerceAtLeast(0)
            runTargetTimeCountdown(waitMs, takeTag)
        }

        client.onStopRecordingTriggered = {
            stopLocalRecording()
        }

        client.onClapperFlashTriggered = {
            triggerClapperFlashAndBeep()
        }

        // Start discovery for directors
        client.startAutoDiscovery { directors ->
            _discoveredDirectors.value = directors
        }
    }

    fun connectNodeToDirector(host: String, port: Int = 8989) {
        nodeClient?.connectToDirector(host, port)
    }

    fun refreshDirectorDiscovery() {
        nodeClient?.startAutoDiscovery { directors ->
            _discoveredDirectors.value = directors
        }
    }

    // --- Countdown & Clapper Engine ---

    private fun runSyncedCountdown(seconds: Int, takeTag: String, isDirector: Boolean) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (i in seconds downTo 1) {
                _countdownValue.value = i
                delay(1000)
            }
            _countdownValue.value = 0 // Action / 0

            // Trigger Clapper Beep & Flash
            triggerClapperFlashAndBeep()

            if (isDirector) {
                directorServer?.broadcastClapperFlash()
            }

            delay(200)
            _countdownValue.value = null

            // Start local recording
            if (!isDirector || _isDirectorLocalCameraEnabled.value) {
                startLocalRecording(takeTag)
            } else {
                _isRecording.value = true
                startDurationTimer()
            }
        }
    }

    private fun runTargetTimeCountdown(waitMs: Long, takeTag: String) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            val seconds = (waitMs / 1000L).toInt()
            if (seconds > 0) {
                for (i in seconds downTo 1) {
                    _countdownValue.value = i
                    delay(1000)
                }
            } else if (waitMs > 0) {
                delay(waitMs)
            }
            _countdownValue.value = 0

            triggerClapperFlashAndBeep()
            delay(200)
            _countdownValue.value = null

            startLocalRecording(takeTag)
        }
    }

    private fun triggerClapperFlashAndBeep() {
        viewModelScope.launch {
            _isClapperFlashing.value = true
            if (_settings.value.clapperSyncBeep) {
                SyncBeepGenerator.playSyncBeep(1000.0, 120)
            }
            delay(150)
            _isClapperFlashing.value = false
        }
    }

    private fun startLocalRecording(takeTag: String) {
        val angle = _assignedCameraAngle.value
        cameraManager.startRecording(
            takeTag = takeTag,
            cameraAngle = angle,
            onStarted = {
                _isRecording.value = true
                nodeClient?.updateNodeStatus(NodeRecordingStatus.RECORDING)
                startDurationTimer()
            },
            onFinalized = { item ->
                _isRecording.value = false
                durationTimerJob?.cancel()
                nodeClient?.updateNodeStatus(NodeRecordingStatus.IDLE)
                if (item != null) {
                    _recordedTakes.value = listOf(item) + _recordedTakes.value
                    nodeClient?.notifyRecordingFinished(takeTag, item.fileName, item.durationSeconds)
                }
            }
        )
    }

    private fun stopLocalRecording() {
        cameraManager.stopRecording()
        _isRecording.value = false
        durationTimerJob?.cancel()
        nodeClient?.updateNodeStatus(NodeRecordingStatus.IDLE)
    }

    private fun startDurationTimer() {
        durationTimerJob?.cancel()
        durationTimerJob = viewModelScope.launch {
            _recordingDurationSec.value = 0L
            while (_isRecording.value) {
                delay(1000)
                _recordingDurationSec.value += 1L
            }
        }
    }

    fun deleteTake(item: VideoTakeItem) {
        _recordedTakes.value = _recordedTakes.value.filter { it.id != item.id }
    }

    fun cleanupAll() {
        countdownJob?.cancel()
        durationTimerJob?.cancel()
        cameraManager.stopRecording()
        directorServer?.stopServer()
        directorServer = null
        nodeClient?.disconnect()
        nodeClient = null
        _isRecording.value = false
        _countdownValue.value = null
        _isClapperFlashing.value = false
    }

    override fun onCleared() {
        super.onCleared()
        cleanupAll()
        cameraManager.release()
    }
}
