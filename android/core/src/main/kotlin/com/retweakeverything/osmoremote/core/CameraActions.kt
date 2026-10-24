package com.retweakeverything.osmoremote.core

import com.retweakeverything.osmoremote.protocol.CameraMode
import com.retweakeverything.osmoremote.protocol.CameraStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * Camera operations used by the UI and the workflow engine. Each call
 * suspends until the camera acknowledges it (where the camera acks at all).
 */
interface CameraActions {
    /** True when commands can be sent right now. */
    val ready: StateFlow<Boolean>
    val status: StateFlow<CameraStatus?>

    suspend fun startRecording()
    suspend fun stopRecording()

    /** Shutter. Takes a photo when the camera is in a photo mode. */
    suspend fun takePhoto()
    suspend fun setMode(mode: CameraMode)

    /** Highlight tag. The camera does not acknowledge it. */
    suspend fun highlight()

    /**
     * Puts the camera to sleep. A phone cannot wake it again: the camera only
     * accepts the wake broadcast from a fixed (public) Bluetooth address, which
     * Android reserves for system apps. The user must press the camera's button.
     */
    suspend fun sleep()
}

/** The camera answered, but refused or failed the command. */
class CommandException(message: String) : Exception(message)

/** No usable link to the camera. Retrying after reconnect may succeed. */
class CameraUnavailableException(message: String = "camera not connected") : Exception(message)
