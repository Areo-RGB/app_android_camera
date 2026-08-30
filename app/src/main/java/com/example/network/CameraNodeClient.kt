package com.example.network

import android.content.Context
import android.util.Log
import com.example.model.CameraNodeInfo
import com.example.model.NetworkPacket
import com.example.model.NodeRecordingStatus
import com.example.model.RecordingSettings
import com.example.util.DeviceUtils
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

class CameraNodeClient(
    private val context: Context
) {
    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val packetAdapter = moshi.adapter(NetworkPacket::class.java)

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null
    private var isConnected = false

    val nodeId: String = UUID.randomUUID().toString().take(8).uppercase()
    val deviceName: String = DeviceUtils.getDeviceName()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _directorName = MutableStateFlow<String?>("Not Connected")
    val directorName: StateFlow<String?> = _directorName.asStateFlow()

    private val _assignedCameraAngle = MutableStateFlow("CAM")
    val assignedCameraAngle: StateFlow<String> = _assignedCameraAngle.asStateFlow()

    private val _nodeStatus = MutableStateFlow(NodeRecordingStatus.IDLE)
    val nodeStatus: StateFlow<NodeRecordingStatus> = _nodeStatus.asStateFlow()

    // Callbacks to Camera Engine in ViewModel
    var onSyncSettingsReceived: ((RecordingSettings) -> Unit)? = null
    var onStartRecordingTriggered: ((countdownMs: Long, settings: RecordingSettings, takeTag: String) -> Unit)? = null
    var onStopRecordingTriggered: (() -> Unit)? = null
    var onClapperFlashTriggered: (() -> Unit)? = null

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Searching : ConnectionState()
        data class Connecting(val host: String) : ConnectionState()
        data class Connected(val host: String, val directorName: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val nsd = NetworkServiceDiscovery(context)

    fun startAutoDiscovery(onFound: (List<NetworkServiceDiscovery.DiscoveredDirector>) -> Unit) {
        _connectionState.value = ConnectionState.Searching
        val foundList = mutableListOf<NetworkServiceDiscovery.DiscoveredDirector>()
        nsd.startDiscovery { director ->
            if (foundList.none { it.host == director.host && it.port == director.port }) {
                foundList.add(director)
                onFound(foundList.toList())
            }
        }
    }

    fun stopDiscovery() {
        nsd.stopDiscovery()
    }

    fun connectToDirector(host: String, port: Int = 8989) {
        if (isConnected) disconnect()
        _connectionState.value = ConnectionState.Connecting(host)

        scope.launch {
            try {
                val newSocket = Socket()
                newSocket.connect(InetSocketAddress(host, port), 5000)
                socket = newSocket
                reader = BufferedReader(InputStreamReader(newSocket.getInputStream()))
                writer = PrintWriter(newSocket.getOutputStream(), true)
                isConnected = true

                // Send Handshake
                val (batt, isCharging) = DeviceUtils.getBatteryInfo(context)
                val storage = DeviceUtils.getAvailableStorageGb()
                val initialInfo = CameraNodeInfo(
                    nodeId = nodeId,
                    deviceName = deviceName,
                    ipAddress = DeviceUtils.getLocalIpAddress(context),
                    batteryPercent = batt,
                    isCharging = isCharging,
                    freeStorageGb = storage,
                    status = _nodeStatus.value.name
                )
                val handshakePacket = NetworkPacket(
                    type = NetworkPacket.TYPE_HANDSHAKE_REQUEST,
                    senderId = nodeId,
                    senderName = deviceName,
                    nodeInfo = initialInfo
                )
                sendPacket(handshakePacket)

                // Start heartbeat loop
                startHeartbeatSender()

                // Read incoming loop
                while (isActive && isConnected) {
                    val line = reader?.readLine() ?: break
                    val packet = packetAdapter.fromJson(line) ?: continue
                    handleIncomingPacket(packet)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed to $host:$port", e)
                withContext(Dispatchers.Main) {
                    _connectionState.value = ConnectionState.Error("Failed: ${e.localizedMessage ?: "Unknown error"}")
                }
            } finally {
                cleanupSocket()
            }
        }
    }

    private suspend fun handleIncomingPacket(packet: NetworkPacket) {
        when (packet.type) {
            NetworkPacket.TYPE_HANDSHAKE_ACK -> {
                val dirName = packet.senderName
                val assignedAngle = packet.nodeInfo?.cameraAngleLabel ?: "CAM"
                _directorName.value = dirName
                _assignedCameraAngle.value = assignedAngle
                withContext(Dispatchers.Main) {
                    _connectionState.value = ConnectionState.Connected(
                        host = socket?.inetAddress?.hostAddress ?: "",
                        directorName = dirName
                    )
                }
            }
            NetworkPacket.TYPE_SYNC_SETTINGS -> {
                packet.settings?.let { settings ->
                    withContext(Dispatchers.Main) {
                        onSyncSettingsReceived?.invoke(settings)
                    }
                }
            }
            NetworkPacket.TYPE_START_RECORDING -> {
                val settings = packet.settings ?: RecordingSettings()
                val targetMs = packet.countdownTargetTimeMs
                val takeTag = packet.takeTag
                _nodeStatus.value = NodeRecordingStatus.RECORDING
                withContext(Dispatchers.Main) {
                    onStartRecordingTriggered?.invoke(targetMs, settings, takeTag)
                }
            }
            NetworkPacket.TYPE_STOP_RECORDING -> {
                _nodeStatus.value = NodeRecordingStatus.IDLE
                withContext(Dispatchers.Main) {
                    onStopRecordingTriggered?.invoke()
                }
            }
            NetworkPacket.TYPE_CLAPPER_FLASH -> {
                withContext(Dispatchers.Main) {
                    onClapperFlashTriggered?.invoke()
                }
            }
            NetworkPacket.TYPE_HEARTBEAT -> {
                // Director Ping, echo back in regular heartbeat
            }
        }
    }

    private fun startHeartbeatSender() {
        scope.launch {
            while (isActive && isConnected) {
                delay(2000)
                val (batt, isCharging) = DeviceUtils.getBatteryInfo(context)
                val storage = DeviceUtils.getAvailableStorageGb()
                val info = CameraNodeInfo(
                    nodeId = nodeId,
                    deviceName = deviceName,
                    ipAddress = DeviceUtils.getLocalIpAddress(context),
                    batteryPercent = batt,
                    isCharging = isCharging,
                    freeStorageGb = storage,
                    status = _nodeStatus.value.name,
                    isRecording = _nodeStatus.value == NodeRecordingStatus.RECORDING,
                    cameraAngleLabel = _assignedCameraAngle.value
                )
                val packet = NetworkPacket(
                    type = NetworkPacket.TYPE_HEARTBEAT,
                    senderId = nodeId,
                    senderName = deviceName,
                    nodeInfo = info
                )
                sendPacket(packet)
            }
        }
    }

    fun notifyRecordingFinished(takeTag: String, fileName: String, durationSec: Long) {
        val packet = NetworkPacket(
            type = NetworkPacket.TYPE_RECORDING_FINISHED,
            senderId = nodeId,
            senderName = deviceName,
            takeTag = takeTag
        )
        sendPacket(packet)
    }

    fun updateNodeStatus(status: NodeRecordingStatus) {
        _nodeStatus.value = status
    }

    private fun sendPacket(packet: NetworkPacket) {
        scope.launch {
            try {
                val json = packetAdapter.toJson(packet)
                writer?.println(json)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending packet from node", e)
            }
        }
    }

    private fun cleanupSocket() {
        isConnected = false
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        reader = null
        writer = null
        _connectionState.value = ConnectionState.Disconnected
    }

    fun disconnect() {
        isConnected = false
        nsd.cleanup()
        cleanupSocket()
    }

    companion object {
        private const val TAG = "CameraNodeClient"
    }
}
