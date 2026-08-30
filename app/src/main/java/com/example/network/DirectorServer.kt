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
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class DirectorServer(
    private val context: Context,
    private val port: Int = 8989
) {
    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val packetAdapter = moshi.adapter(NetworkPacket::class.java)

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    private val clientSockets = ConcurrentHashMap<String, ClientSession>()
    private val _connectedNodes = MutableStateFlow<Map<String, CameraNodeInfo>>(emptyMap())
    val connectedNodes: StateFlow<Map<String, CameraNodeInfo>> = _connectedNodes.asStateFlow()

    private val _eventLogs = MutableStateFlow<List<String>>(emptyList())
    val eventLogs: StateFlow<List<String>> = _eventLogs.asStateFlow()

    private val nsd = NetworkServiceDiscovery(context)
    val directorId = "DIRECTOR_${System.currentTimeMillis() % 10000}"
    val directorName = "Director (${DeviceUtils.getDeviceName()})"

    data class ClientSession(
        val nodeId: String,
        val socket: Socket,
        val reader: BufferedReader,
        val writer: PrintWriter,
        var lastHeartbeatTime: Long = System.currentTimeMillis()
    )

    fun startServer(onStarted: (ip: String, port: Int) -> Unit) {
        if (isRunning) return
        isRunning = true

        scope.launch {
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                val localIp = DeviceUtils.getLocalIpAddress(context)
                logEvent("Director Server started on $localIp:$port")
                withContext(Dispatchers.Main) {
                    onStarted(localIp, port)
                }

                // Register with NSD for zero-config auto discovery
                val serviceName = "MultiCam_${directorName.replace(" ", "_")}"
                nsd.registerDirectorService(serviceName, port) { regName ->
                    logEvent("Registered broadcast as: $regName")
                }

                // Launch heartbeat monitor
                launchHeartbeatMonitor()

                // Accept client connections loop
                while (isActive && isRunning) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        launch {
                            handleNewClient(socket)
                        }
                    } catch (e: Exception) {
                        if (!isRunning) break
                        Log.e(TAG, "Error accepting client", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start director server", e)
                logEvent("Failed to start server: ${e.message}")
            }
        }
    }

    private fun handleNewClient(socket: Socket) {
        val clientIp = socket.inetAddress.hostAddress ?: "Unknown"
        var sessionNodeId: String? = null
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            // Read initial handshake packet
            val line = reader.readLine() ?: return
            val packet = packetAdapter.fromJson(line) ?: return

            if (packet.type == NetworkPacket.TYPE_HANDSHAKE_REQUEST) {
                val nodeInfo = packet.nodeInfo ?: return
                sessionNodeId = nodeInfo.nodeId

                // Assign camera angle badge (e.g. CAM A, CAM B, CAM C)
                val angleIndex = clientSockets.size
                val angleLabel = "CAM ${('A' + angleIndex)}"
                val enrichedNodeInfo = nodeInfo.copy(
                    ipAddress = clientIp,
                    cameraAngleLabel = angleLabel
                )

                val session = ClientSession(
                    nodeId = sessionNodeId,
                    socket = socket,
                    reader = reader,
                    writer = writer
                )
                clientSockets[sessionNodeId] = session

                updateNodeMap { current ->
                    current + (sessionNodeId to enrichedNodeInfo)
                }

                // Send Handshake ACK with director identity
                val ackPacket = NetworkPacket(
                    type = NetworkPacket.TYPE_HANDSHAKE_ACK,
                    senderId = directorId,
                    senderName = directorName,
                    nodeInfo = enrichedNodeInfo
                )
                sendPacketToClient(session, ackPacket)
                logEvent("${enrichedNodeInfo.deviceName} ($angleLabel) connected from $clientIp")

                // Keep reading messages from client
                while (scope.isActive && isRunning) {
                    val msg = reader.readLine() ?: break
                    val incoming = packetAdapter.fromJson(msg) ?: continue
                    processIncomingPacket(sessionNodeId, incoming)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client session closed: ${e.message}")
        } finally {
            sessionNodeId?.let { id ->
                val removedNode = _connectedNodes.value[id]
                clientSockets.remove(id)
                updateNodeMap { current -> current - id }
                logEvent("${removedNode?.deviceName ?: "Camera Node"} disconnected")
            }
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun processIncomingPacket(nodeId: String, packet: NetworkPacket) {
        when (packet.type) {
            NetworkPacket.TYPE_HEARTBEAT -> {
                packet.nodeInfo?.let { updatedInfo ->
                    val now = System.currentTimeMillis()
                    val ping = if (packet.timestamp > 0) (now - packet.timestamp).coerceAtLeast(1) else 10
                    clientSockets[nodeId]?.lastHeartbeatTime = now
                    updateNodeMap { current ->
                        val existing = current[nodeId]
                        val badge = existing?.cameraAngleLabel ?: "CAM"
                        current + (nodeId to updatedInfo.copy(
                            cameraAngleLabel = badge,
                            pingMs = ping
                        ))
                    }
                }
            }
            NetworkPacket.TYPE_RECORDING_FINISHED -> {
                logEvent("Take recorded on ${packet.senderName}: ${packet.takeTag}")
                updateNodeMap { current ->
                    current[nodeId]?.let { existing ->
                        current + (nodeId to existing.copy(
                            isRecording = false,
                            status = NodeRecordingStatus.SAVING.name,
                            lastTakeRecorded = packet.takeTag
                        ))
                    } ?: current
                }
            }
            NetworkPacket.TYPE_DISCONNECT -> {
                clientSockets[nodeId]?.socket?.close()
            }
        }
    }

    private fun launchHeartbeatMonitor() {
        scope.launch {
            while (isActive && isRunning) {
                delay(3000)
                val now = System.currentTimeMillis()
                val deadNodes = mutableListOf<String>()

                clientSockets.forEach { (id, session) ->
                    if (now - session.lastHeartbeatTime > 10000) {
                        deadNodes.add(id)
                    } else {
                        // Send ping packet
                        val pingPacket = NetworkPacket(
                            type = NetworkPacket.TYPE_HEARTBEAT,
                            senderId = directorId,
                            senderName = directorName,
                            timestamp = now
                        )
                        sendPacketToClient(session, pingPacket)
                    }
                }

                deadNodes.forEach { id ->
                    val session = clientSockets.remove(id)
                    try {
                        session?.socket?.close()
                    } catch (_: Exception) {}
                    updateNodeMap { current -> current - id }
                    logEvent("Node $id timed out")
                }
            }
        }
    }

    fun broadcastSettings(settings: RecordingSettings) {
        val packet = NetworkPacket(
            type = NetworkPacket.TYPE_SYNC_SETTINGS,
            senderId = directorId,
            senderName = directorName,
            settings = settings
        )
        broadcast(packet)
        logEvent("Settings synchronized: ${settings.resolution}, ${settings.fps}")
    }

    fun broadcastStartRecording(
        settings: RecordingSettings,
        countdownSeconds: Int = 3,
        takeTag: String
    ) {
        val targetStartTimestamp = System.currentTimeMillis() + (countdownSeconds * 1000L)
        val packet = NetworkPacket(
            type = NetworkPacket.TYPE_START_RECORDING,
            senderId = directorId,
            senderName = directorName,
            settings = settings,
            countdownTargetTimeMs = targetStartTimestamp,
            takeTag = takeTag
        )
        broadcast(packet)
        logEvent("START RECORDING triggered for $takeTag (Countdown: ${countdownSeconds}s)")
    }

    fun broadcastStopRecording() {
        val packet = NetworkPacket(
            type = NetworkPacket.TYPE_STOP_RECORDING,
            senderId = directorId,
            senderName = directorName
        )
        broadcast(packet)
        logEvent("STOP RECORDING broadcasted to all cameras")
    }

    fun broadcastClapperFlash() {
        val packet = NetworkPacket(
            type = NetworkPacket.TYPE_CLAPPER_FLASH,
            senderId = directorId,
            senderName = directorName
        )
        broadcast(packet)
    }

    private fun broadcast(packet: NetworkPacket) {
        val json = packetAdapter.toJson(packet)
        clientSockets.values.forEach { session ->
            scope.launch {
                try {
                    session.writer.println(json)
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending packet to ${session.nodeId}", e)
                }
            }
        }
    }

    private fun sendPacketToClient(session: ClientSession, packet: NetworkPacket) {
        scope.launch {
            try {
                val json = packetAdapter.toJson(packet)
                session.writer.println(json)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending to client", e)
            }
        }
    }

    private fun updateNodeMap(transform: (Map<String, CameraNodeInfo>) -> Map<String, CameraNodeInfo>) {
        _connectedNodes.value = transform(_connectedNodes.value)
    }

    private fun logEvent(msg: String) {
        Log.d(TAG, msg)
        _eventLogs.value = (_eventLogs.value + msg).takeLast(50)
    }

    fun stopServer() {
        isRunning = false
        nsd.cleanup()
        clientSockets.values.forEach {
            try {
                it.socket.close()
            } catch (_: Exception) {}
        }
        clientSockets.clear()
        _connectedNodes.value = emptyMap()
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "DirectorServer"
    }
}
