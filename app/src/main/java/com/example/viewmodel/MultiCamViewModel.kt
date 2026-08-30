package com.example.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.camera.CameraXManager
import com.example.model.AppMode
import com.example.model.CameraLens
import com.example.model.CameraNodeInfo
import com.example.model.NetworkPacket
import com.example.model.NodeRecordingStatus
import com.example.model.RecordingSettings
import com.example.model.VideoFps
import com.example.model.VideoResolution
import com.example.model.VideoTakeItem
import com.example.network.CameraNodeClient
import com.example.network.DirectorServer
import com.example.network.NearbyConnectionsManager
import com.example.network.NearbyDiscoveredDirector
import com.example.network.NetworkServiceDiscovery
import com.example.util.DeviceUtils
import com.example.util.SyncBeepGenerator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class MultiCamViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()

    val cameraManager = CameraXManager(context)
    val nearbyManager = NearbyConnectionsManager(context)

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

    val discoveredNearbyDirectors: StateFlow<List<NearbyDiscoveredDirector>> = nearbyManager.discoveredDirectors

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
    private var nearbyHeartbeatJob: Job? = null

    val isTorchOn: StateFlow<Boolean> = cameraManager.isTorchOn

    // Active connection tracking for Camera Node (Nearby vs Socket)
    private var activeNearbyDirectorEndpointId: String? = null

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
        _assignedCameraAngle.value = "CAM 1 (Director)"

        // 1. Start Wi-Fi TCP Server & NSD
        val server = DirectorServer(context)
        directorServer = server

        server.startServer { ip, _ ->
            _directorIp.value = ip
        }

        viewModelScope.launch {
            server.connectedNodes.collect { wifiNodes ->
                // Merge Wi-Fi nodes with Google Nearby nodes
                updateConnectedNodes { current ->
                    val nearbyOnly = current.filterValues { it.connectionType == "Google Nearby" }
                    nearbyOnly + wifiNodes
                }
            }
        }
        viewModelScope.launch {
            server.eventLogs.collect { logs ->
                _directorLogs.value = (_directorLogs.value + logs).distinct().takeLast(60)
            }
        }

        // 2. Start Google Nearby Connections Advertising
        val directorName = "Director (${DeviceUtils.getDeviceName()})"
        nearbyManager.onLogMessage = { logMsg ->
            logDirectorEvent(logMsg)
        }

        nearbyManager.onPacketReceived = { endpointId, packet ->
            handleNearbyPacketAsDirector(endpointId, packet)
        }

        nearbyManager.onEndpointDisconnected = { endpointId ->
            updateConnectedNodes { current ->
                val removed = current.entries.find { it.key == endpointId || it.value.nodeId == endpointId }
                if (removed != null) {
                    logDirectorEvent("${removed.value.deviceName} (${removed.value.cameraAngleLabel}) disconnected [Nearby]")
                    current - removed.key
                } else current
            }
        }

        nearbyManager.startAdvertising(
            directorName = directorName,
            onSuccess = {
                logDirectorEvent("Google Nearby Advertising started (P2P Direct)")
            },
            onFailure = { e ->
                logDirectorEvent("Nearby Advertising error: ${e.message}")
            }
        )
    }

    private fun handleNearbyPacketAsDirector(endpointId: String, packet: NetworkPacket) {
        when (packet.type) {
            NetworkPacket.TYPE_HANDSHAKE_REQUEST -> {
                val nodeInfo = packet.nodeInfo ?: return
                val angleIndex = _connectedNodes.value.size
                val assignedBadge = "CAM ${('A' + angleIndex)}"

                val enriched = nodeInfo.copy(
                    nodeId = endpointId,
                    cameraAngleLabel = assignedBadge,
                    connectionType = "Google Nearby",
                    ipAddress = "Nearby (P2P)"
                )

                updateConnectedNodes { current ->
                    current + (endpointId to enriched)
                }

                // Send Handshake ACK back to node
                val ackPacket = NetworkPacket(
                    type = NetworkPacket.TYPE_HANDSHAKE_ACK,
                    senderId = "DIRECTOR_NEARBY",
                    senderName = "Director (${DeviceUtils.getDeviceName()})",
                    nodeInfo = enriched
                )
                nearbyManager.sendPacket(endpointId, ackPacket)
                logDirectorEvent("${enriched.deviceName} ($assignedBadge) connected via Google Nearby")
            }

            NetworkPacket.TYPE_HEARTBEAT -> {
                packet.nodeInfo?.let { updatedInfo ->
                    val now = System.currentTimeMillis()
                    val ping = if (packet.timestamp > 0) (now - packet.timestamp).coerceAtLeast(1) else 8
                    updateConnectedNodes { current ->
                        val existing = current[endpointId]
                        val badge = existing?.cameraAngleLabel ?: "CAM"
                        current + (endpointId to updatedInfo.copy(
                            nodeId = endpointId,
                            cameraAngleLabel = badge,
                            connectionType = "Google Nearby",
                            ipAddress = "Nearby (P2P)",
                            pingMs = ping
                        ))
                    }
                }
            }

            NetworkPacket.TYPE_RECORDING_FINISHED -> {
                logDirectorEvent("Take recorded on ${packet.senderName}: ${packet.takeTag} [Nearby]")
                updateConnectedNodes { current ->
                    current[endpointId]?.let { existing ->
                        current + (endpointId to existing.copy(
                            isRecording = false,
                            status = NodeRecordingStatus.SAVING.name,
                            lastTakeRecorded = packet.takeTag
                        ))
                    } ?: current
                }
            }
        }
    }

    private fun updateConnectedNodes(transform: (Map<String, CameraNodeInfo>) -> Map<String, CameraNodeInfo>) {
        _connectedNodes.value = transform(_connectedNodes.value)
    }

    private fun logDirectorEvent(msg: String) {
        _directorLogs.value = (_directorLogs.value + msg).takeLast(60)
    }

    fun toggleDirectorLocalCamera() {
        _isDirectorLocalCameraEnabled.value = !_isDirectorLocalCameraEnabled.value
    }

    fun updateSettings(newSettings: RecordingSettings) {
        _settings.value = newSettings
        cameraManager.updateSettings(newSettings)
        if (_appMode.value == AppMode.DIRECTOR) {
            directorServer?.broadcastSettings(newSettings)
            nearbyManager.broadcastPacket(
                NetworkPacket(
                    type = NetworkPacket.TYPE_SYNC_SETTINGS,
                    senderId = "DIRECTOR",
                    senderName = "Director",
                    settings = newSettings
                )
            )
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
        val targetStartTimestamp = System.currentTimeMillis() + (countdownSec * 1000L)

        // Broadcast over Wi-Fi Server
        directorServer?.broadcastStartRecording(
            settings = current,
            countdownSeconds = countdownSec,
            takeTag = takeTag
        )

        // Broadcast over Google Nearby Connections
        nearbyManager.broadcastPacket(
            NetworkPacket(
                type = NetworkPacket.TYPE_START_RECORDING,
                senderId = "DIRECTOR",
                senderName = "Director",
                settings = current,
                countdownTargetTimeMs = targetStartTimestamp,
                takeTag = takeTag
            )
        )

        logDirectorEvent("START RECORDING broadcasted (Countdown: ${countdownSec}s, Take: $takeTag)")

        // Run local synced countdown & record
        runSyncedCountdown(countdownSec, takeTag, isDirector = true)
    }

    fun stopMasterRecording() {
        directorServer?.broadcastStopRecording()
        nearbyManager.broadcastPacket(
            NetworkPacket(
                type = NetworkPacket.TYPE_STOP_RECORDING,
                senderId = "DIRECTOR",
                senderName = "Director"
            )
        )
        logDirectorEvent("STOP RECORDING broadcasted to all cameras")
        stopLocalRecording()
    }

    // --- Camera Node Mode Logic ---

    private fun startCameraNodeMode() {
        cleanupAll()
        _nodeConnectionState.value = CameraNodeClient.ConnectionState.Searching

        // 1. Local Wi-Fi Client & NSD
        val client = CameraNodeClient(context)
        nodeClient = client

        viewModelScope.launch {
            client.connectionState.collect { state ->
                // Only update if not connected via Nearby
                if (activeNearbyDirectorEndpointId == null) {
                    _nodeConnectionState.value = state
                }
            }
        }
        viewModelScope.launch {
            client.assignedCameraAngle.collect { angle ->
                if (activeNearbyDirectorEndpointId == null) {
                    _assignedCameraAngle.value = angle
                }
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

        // Start discovery for Wi-Fi directors
        client.startAutoDiscovery { directors ->
            _discoveredDirectors.value = directors
        }

        // 2. Start Google Nearby Connections Discovery
        nearbyManager.startDiscovery(
            onSuccess = {},
            onFailure = {}
        )
    }

    fun connectNodeToDirector(host: String, port: Int = 8989) {
        activeNearbyDirectorEndpointId = null
        nodeClient?.connectToDirector(host, port)
    }

    fun connectNodeToNearbyDirector(director: NearbyDiscoveredDirector) {
        _nodeConnectionState.value = CameraNodeClient.ConnectionState.Connecting(director.endpointName)
        val myNodeId = UUID.randomUUID().toString().take(8).uppercase()
        val myDeviceName = DeviceUtils.getDeviceName()

        nearbyManager.onPacketReceived = { endpointId, packet ->
            handleNearbyPacketAsNode(packet)
        }

        nearbyManager.onEndpointDisconnected = { endpointId ->
            if (endpointId == activeNearbyDirectorEndpointId) {
                activeNearbyDirectorEndpointId = null
                nearbyHeartbeatJob?.cancel()
                _nodeConnectionState.value = CameraNodeClient.ConnectionState.Disconnected
            }
        }

        nearbyManager.requestConnectionToDirector(
            nodeDeviceName = myDeviceName,
            directorEndpointId = director.endpointId,
            onConnected = { endpointId, dirName ->
                activeNearbyDirectorEndpointId = endpointId
                _nodeConnectionState.value = CameraNodeClient.ConnectionState.Connected("Google Nearby", dirName)

                // Send Handshake
                val (batt, isCharging) = DeviceUtils.getBatteryInfo(context)
                val storage = DeviceUtils.getAvailableStorageGb()
                val handshakePacket = NetworkPacket(
                    type = NetworkPacket.TYPE_HANDSHAKE_REQUEST,
                    senderId = myNodeId,
                    senderName = myDeviceName,
                    nodeInfo = CameraNodeInfo(
                        nodeId = myNodeId,
                        deviceName = myDeviceName,
                        ipAddress = "Nearby P2P",
                        batteryPercent = batt,
                        isCharging = isCharging,
                        freeStorageGb = storage,
                        connectionType = "Google Nearby",
                        status = NodeRecordingStatus.IDLE.name
                    )
                )
                nearbyManager.sendPacket(endpointId, handshakePacket)

                // Start Nearby Heartbeat sender
                startNearbyHeartbeatLoop(endpointId, myNodeId, myDeviceName)
            },
            onError = { errMsg ->
                _nodeConnectionState.value = CameraNodeClient.ConnectionState.Error(errMsg)
            }
        )
    }

    private fun handleNearbyPacketAsNode(packet: NetworkPacket) {
        when (packet.type) {
            NetworkPacket.TYPE_HANDSHAKE_ACK -> {
                packet.nodeInfo?.cameraAngleLabel?.let { badge ->
                    _assignedCameraAngle.value = badge
                }
            }

            NetworkPacket.TYPE_SYNC_SETTINGS -> {
                packet.settings?.let { newSettings ->
                    _settings.value = newSettings
                    cameraManager.updateSettings(newSettings)
                }
            }

            NetworkPacket.TYPE_START_RECORDING -> {
                val synced = packet.settings ?: RecordingSettings()
                _settings.value = synced
                cameraManager.updateSettings(synced)
                val targetMs = packet.countdownTargetTimeMs
                val takeTag = packet.takeTag
                val waitMs = (targetMs - System.currentTimeMillis()).coerceAtLeast(0)
                runTargetTimeCountdown(waitMs, takeTag)
            }

            NetworkPacket.TYPE_STOP_RECORDING -> {
                stopLocalRecording()
            }

            NetworkPacket.TYPE_CLAPPER_FLASH -> {
                triggerClapperFlashAndBeep()
            }
        }
    }

    private fun startNearbyHeartbeatLoop(directorEndpointId: String, nodeId: String, deviceName: String) {
        nearbyHeartbeatJob?.cancel()
        nearbyHeartbeatJob = viewModelScope.launch {
            while (isActive && activeNearbyDirectorEndpointId != null) {
                delay(2000)
                val (batt, isCharging) = DeviceUtils.getBatteryInfo(context)
                val storage = DeviceUtils.getAvailableStorageGb()
                val isRec = _isRecording.value
                val heartbeat = NetworkPacket(
                    type = NetworkPacket.TYPE_HEARTBEAT,
                    senderId = nodeId,
                    senderName = deviceName,
                    timestamp = System.currentTimeMillis(),
                    nodeInfo = CameraNodeInfo(
                        nodeId = nodeId,
                        deviceName = deviceName,
                        ipAddress = "Nearby P2P",
                        batteryPercent = batt,
                        isCharging = isCharging,
                        freeStorageGb = storage,
                        connectionType = "Google Nearby",
                        status = if (isRec) NodeRecordingStatus.RECORDING.name else NodeRecordingStatus.IDLE.name,
                        isRecording = isRec,
                        cameraAngleLabel = _assignedCameraAngle.value
                    )
                )
                nearbyManager.sendPacket(directorEndpointId, heartbeat)
            }
        }
    }

    fun refreshDirectorDiscovery() {
        nodeClient?.startAutoDiscovery { directors ->
            _discoveredDirectors.value = directors
        }
        nearbyManager.stopDiscovery()
        nearbyManager.startDiscovery()
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
                nearbyManager.broadcastPacket(
                    NetworkPacket(
                        type = NetworkPacket.TYPE_CLAPPER_FLASH,
                        senderId = "DIRECTOR",
                        senderName = "Director"
                    )
                )
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

                    // Also notify via Nearby if connected via Nearby
                    activeNearbyDirectorEndpointId?.let { endpointId ->
                        val finishedPacket = NetworkPacket(
                            type = NetworkPacket.TYPE_RECORDING_FINISHED,
                            senderId = endpointId,
                            senderName = DeviceUtils.getDeviceName(),
                            takeTag = takeTag
                        )
                        nearbyManager.sendPacket(endpointId, finishedPacket)
                    }
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
        nearbyHeartbeatJob?.cancel()
        cameraManager.stopRecording()
        directorServer?.stopServer()
        directorServer = null
        nodeClient?.disconnect()
        nodeClient = null
        nearbyManager.stopAllEndpoints()
        activeNearbyDirectorEndpointId = null
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
