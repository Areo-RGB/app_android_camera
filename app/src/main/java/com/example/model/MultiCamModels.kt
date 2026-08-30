package com.example.model

import com.squareup.moshi.JsonClass

enum class VideoResolution(val label: String, val width: Int, val height: Int) {
    UHD_4K("4K UHD (2160p)", 3840, 2160),
    FHD_1080P("Full HD (1080p)", 1920, 1080),
    HD_720P("HD (720p)", 1280, 720),
    SD_480P("SD (480p)", 854, 480)
}

enum class VideoFps(val label: String, val fpsValue: Int) {
    FPS_24("24 FPS (Cinematic)", 24),
    FPS_30("30 FPS (Standard)", 30),
    FPS_60("60 FPS (High Speed)", 60)
}

enum class CameraLens(val label: String) {
    BACK("Back Camera"),
    FRONT("Front Camera")
}

enum class AppMode {
    HUB,
    DIRECTOR,
    CAMERA_NODE
}

enum class NodeRecordingStatus {
    IDLE,
    PREPARING,
    COUNTDOWN,
    RECORDING,
    SAVING,
    ERROR
}

@JsonClass(generateAdapter = true)
data class RecordingSettings(
    val resolution: String = VideoResolution.FHD_1080P.name,
    val fps: String = VideoFps.FPS_30.name,
    val lens: String = CameraLens.BACK.name,
    val audioEnabled: Boolean = true,
    val torchEnabled: Boolean = false,
    val countdownSeconds: Int = 3,
    val clapperSyncBeep: Boolean = true,
    val sceneName: String = "Scene 1",
    val takeNumber: Int = 1
)

@JsonClass(generateAdapter = true)
data class CameraNodeInfo(
    val nodeId: String,
    val deviceName: String,
    val ipAddress: String,
    val port: Int = 8989,
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val freeStorageGb: Double = 0.0,
    val status: String = NodeRecordingStatus.IDLE.name,
    val isRecording: Boolean = false,
    val recordingDurationSec: Long = 0L,
    val activeResolution: String = VideoResolution.FHD_1080P.name,
    val activeFps: String = VideoFps.FPS_30.name,
    val pingMs: Long = 0L,
    val lastTakeRecorded: String = "",
    val cameraAngleLabel: String = "CAM"
)

@JsonClass(generateAdapter = true)
data class NetworkPacket(
    val type: String,
    val senderId: String,
    val senderName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val settings: RecordingSettings? = null,
    val nodeInfo: CameraNodeInfo? = null,
    val countdownTargetTimeMs: Long = 0L,
    val takeTag: String = "",
    val errorMessage: String? = null
) {
    companion object {
        const val TYPE_HANDSHAKE_REQUEST = "HANDSHAKE_REQUEST"
        const val TYPE_HANDSHAKE_ACK = "HANDSHAKE_ACK"
        const val TYPE_HEARTBEAT = "HEARTBEAT"
        const val TYPE_SYNC_SETTINGS = "SYNC_SETTINGS"
        const val TYPE_PREPARE_TAKE = "PREPARE_TAKE"
        const val TYPE_START_RECORDING = "START_RECORDING"
        const val TYPE_STOP_RECORDING = "STOP_RECORDING"
        const val TYPE_CLAPPER_FLASH = "CLAPPER_FLASH"
        const val TYPE_RECORDING_FINISHED = "RECORDING_FINISHED"
        const val TYPE_DISCONNECT = "DISCONNECT"
    }
}

data class VideoTakeItem(
    val id: String,
    val uriString: String,
    val filePath: String,
    val fileName: String,
    val durationSeconds: Long,
    val fileSizeBytes: Long,
    val timestamp: Long,
    val resolutionLabel: String,
    val takeTag: String,
    val nodeLabel: String = "Local"
)
