package com.example.network

import android.content.Context
import android.util.Log
import com.example.model.NetworkPacket
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NearbyDiscoveredDirector(
    val endpointId: String,
    val endpointName: String
)

class NearbyConnectionsManager(private val context: Context) {

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val packetAdapter = moshi.adapter(NetworkPacket::class.java)

    private val strategy = Strategy.P2P_STAR

    // State tracking
    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _discoveredDirectors = MutableStateFlow<List<NearbyDiscoveredDirector>>(emptyList())
    val discoveredDirectors: StateFlow<List<NearbyDiscoveredDirector>> = _discoveredDirectors.asStateFlow()

    private val _connectedEndpoints = MutableStateFlow<Map<String, String>>(emptyMap()) // endpointId -> name
    val connectedEndpoints: StateFlow<Map<String, String>> = _connectedEndpoints.asStateFlow()

    // Callbacks
    var onPacketReceived: ((endpointId: String, packet: NetworkPacket) -> Unit)? = null
    var onEndpointConnected: ((endpointId: String, endpointName: String) -> Unit)? = null
    var onEndpointDisconnected: ((endpointId: String) -> Unit)? = null
    var onLogMessage: ((String) -> Unit)? = null

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes() ?: return
                try {
                    val json = String(bytes, Charsets.UTF_8)
                    val packet = packetAdapter.fromJson(json) ?: return
                    scope.launch(Dispatchers.Main) {
                        onPacketReceived?.invoke(endpointId, packet)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing packet payload from $endpointId", e)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Can be used for progress if sending large files/video clips in future
        }
    }

    // --- DIRECTOR / ADVERTISING ROLE ---

    fun startAdvertising(
        directorName: String,
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(strategy)
            .build()

        val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
                log("Nearby: Connection initiated from ${connectionInfo.endpointName} ($endpointId)")
                // Automatically accept connection for smooth multi-camera workflow
                connectionsClient.acceptConnection(endpointId, payloadCallback)
                    .addOnSuccessListener {
                        log("Nearby: Accepted connection request from ${connectionInfo.endpointName}")
                    }
                    .addOnFailureListener { e ->
                        log("Nearby: Failed to accept connection: ${e.message}")
                    }
            }

            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                when (result.status.statusCode) {
                    ConnectionsStatusCodes.STATUS_OK -> {
                        log("Nearby: Successfully connected to endpoint: $endpointId")
                        val current = _connectedEndpoints.value.toMutableMap()
                        current[endpointId] = "CameraNode_$endpointId"
                        _connectedEndpoints.value = current
                        scope.launch(Dispatchers.Main) {
                            onEndpointConnected?.invoke(endpointId, current[endpointId] ?: endpointId)
                        }
                    }
                    ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                        log("Nearby: Connection rejected by $endpointId")
                    }
                    ConnectionsStatusCodes.STATUS_ERROR -> {
                        log("Nearby: Connection error with $endpointId")
                    }
                }
            }

            override fun onDisconnected(endpointId: String) {
                log("Nearby: Disconnected from $endpointId")
                val current = _connectedEndpoints.value.toMutableMap()
                current.remove(endpointId)
                _connectedEndpoints.value = current
                scope.launch(Dispatchers.Main) {
                    onEndpointDisconnected?.invoke(endpointId)
                }
            }
        }

        connectionsClient.startAdvertising(
            directorName,
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            _isAdvertising.value = true
            log("Nearby: Started advertising as '$directorName'")
            onSuccess()
        }.addOnFailureListener { e ->
            _isAdvertising.value = false
            log("Nearby: Advertising failed: ${e.localizedMessage}")
            onFailure(e)
        }
    }

    fun stopAdvertising() {
        try {
            // Always call the platform stop API. The local state can be stale after an error,
            // and stopAdvertising() is safe even when advertising is no longer active.
            connectionsClient.stopAdvertising()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping advertising", e)
        } finally {
            _isAdvertising.value = false
        }
    }

    // --- CAMERA NODE / DISCOVERY ROLE ---

    fun startDiscovery(
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .build()

        _discoveredDirectors.value = emptyList()

        val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                log("Nearby: Found Director '${info.endpointName}' ($endpointId)")
                val current = _discoveredDirectors.value.toMutableList()
                if (current.none { it.endpointId == endpointId }) {
                    current.add(NearbyDiscoveredDirector(endpointId, info.endpointName))
                    _discoveredDirectors.value = current
                }
            }

            override fun onEndpointLost(endpointId: String) {
                log("Nearby: Director lost ($endpointId)")
                val current = _discoveredDirectors.value.toMutableList()
                current.removeAll { it.endpointId == endpointId }
                _discoveredDirectors.value = current
            }
        }

        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            _isDiscovering.value = true
            log("Nearby: Discovery active, searching for Director...")
            onSuccess()
        }.addOnFailureListener { e ->
            _isDiscovering.value = false
            log("Nearby: Discovery failed: ${e.localizedMessage}")
            onFailure(e)
        }
    }

    fun stopDiscovery() {
        try {
            // Always stop the platform operation so re-entering Camera Node mode starts cleanly.
            connectionsClient.stopDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping discovery", e)
        } finally {
            _isDiscovering.value = false
            _discoveredDirectors.value = emptyList()
        }
    }

    fun requestConnectionToDirector(
        nodeDeviceName: String,
        directorEndpointId: String,
        onConnected: (endpointId: String, directorName: String) -> Unit,
        onError: (String) -> Unit
    ) {
        val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
                log("Nearby: Connecting to Director ${connectionInfo.endpointName} ($endpointId)...")
                connectionsClient.acceptConnection(endpointId, payloadCallback)
            }

            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                when (result.status.statusCode) {
                    ConnectionsStatusCodes.STATUS_OK -> {
                        log("Nearby: Connected to Director ($endpointId)!")
                        val current = _connectedEndpoints.value.toMutableMap()
                        val dirName = _discoveredDirectors.value.find { it.endpointId == endpointId }?.endpointName ?: "Director"
                        current[endpointId] = dirName
                        _connectedEndpoints.value = current
                        scope.launch(Dispatchers.Main) {
                            onConnected(endpointId, dirName)
                            onEndpointConnected?.invoke(endpointId, dirName)
                        }
                    }
                    ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                        log("Nearby: Connection rejected by Director ($endpointId)")
                        scope.launch(Dispatchers.Main) {
                            onError("Connection rejected by Director")
                        }
                    }
                    else -> {
                        log("Nearby: Connection failed with code ${result.status.statusCode}")
                        scope.launch(Dispatchers.Main) {
                            onError("Connection error code ${result.status.statusCode}")
                        }
                    }
                }
            }

            override fun onDisconnected(endpointId: String) {
                log("Nearby: Disconnected from Director ($endpointId)")
                val current = _connectedEndpoints.value.toMutableMap()
                current.remove(endpointId)
                _connectedEndpoints.value = current
                scope.launch(Dispatchers.Main) {
                    onEndpointDisconnected?.invoke(endpointId)
                }
            }
        }

        connectionsClient.requestConnection(
            nodeDeviceName,
            directorEndpointId,
            connectionLifecycleCallback
        ).addOnSuccessListener {
            log("Nearby: Connection requested to $directorEndpointId")
        }.addOnFailureListener { e ->
            log("Nearby: Request connection error: ${e.localizedMessage}")
            onError(e.localizedMessage ?: "Unknown error")
        }
    }

    // --- PACKET SENDING ---

    fun sendPacket(endpointId: String, packet: NetworkPacket) {
        try {
            val json = packetAdapter.toJson(packet)
            val payload = Payload.fromBytes(json.toByteArray(Charsets.UTF_8))
            connectionsClient.sendPayload(endpointId, payload)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending packet to $endpointId", e)
        }
    }

    fun broadcastPacket(packet: NetworkPacket) {
        val endpoints = _connectedEndpoints.value.keys.toList()
        if (endpoints.isEmpty()) return
        try {
            val json = packetAdapter.toJson(packet)
            val payload = Payload.fromBytes(json.toByteArray(Charsets.UTF_8))
            connectionsClient.sendPayload(endpoints, payload)
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting packet to ${endpoints.size} endpoints", e)
        }
    }

    fun disconnectEndpoint(endpointId: String) {
        try {
            connectionsClient.disconnectFromEndpoint(endpointId)
            val current = _connectedEndpoints.value.toMutableMap()
            current.remove(endpointId)
            _connectedEndpoints.value = current
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting $endpointId", e)
        }
    }

    fun stopAllEndpoints() {
        // Nearby treats advertising, discovery, and endpoint connections as separate operations.
        // Tear down all three so Director -> Hub -> Director and Node -> Hub -> Node are restart-safe.
        stopAdvertising()
        stopDiscovery()
        try {
            connectionsClient.stopAllEndpoints()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping all endpoints", e)
        } finally {
            _connectedEndpoints.value = emptyMap()
            _isAdvertising.value = false
            _isDiscovering.value = false
            _discoveredDirectors.value = emptyList()
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        onLogMessage?.invoke(message)
    }

    companion object {
        const val SERVICE_ID = "com.example.multicamsync"
        private const val TAG = "NearbyConnManager"
    }
}
