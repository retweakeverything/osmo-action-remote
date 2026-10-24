package com.retweakeverything.osmoremote.protocol

/** Shooting modes reported by and settable on the camera (camera_mode). */
enum class CameraMode(val code: Int, val label: String) {
    SLOW_MOTION(0x00, "Slow Motion"),
    VIDEO(0x01, "Video"),
    TIMELAPSE(0x02, "Timelapse"),
    PHOTO(0x05, "Photo"),
    HYPERLAPSE(0x0A, "Hyperlapse"),
    LIVE_STREAM(0x1A, "Live Stream"),
    UVC_LIVE(0x23, "UVC Live"),
    SUPER_NIGHT(0x28, "SuperNight"),
    SUBJECT_TRACKING(0x34, "Subject Tracking"),
    ;

    companion object {
        /** Modes offered in the mode picker. */
        val selectable = listOf(VIDEO, PHOTO, SLOW_MOTION, TIMELAPSE, HYPERLAPSE, SUPER_NIGHT)

        fun fromCode(code: Int): CameraMode? = entries.firstOrNull { it.code == code }
    }
}

/** camera_status values. */
enum class CaptureState(val code: Int) {
    SCREEN_OFF(0x00),
    IDLE(0x01),
    PLAYBACK(0x02),
    CAPTURING(0x03),
    PRE_RECORDING(0x05),
    UNKNOWN(-1),
    ;

    companion object {
        fun fromCode(code: Int): CaptureState = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * Camera Status Push (0x1D/0x02). Offsets verified live on the Action 5 Pro
 * (battery drift, record timer, SD drain, mode switch all observed).
 */
data class CameraStatus(
    val modeCode: Int,
    val captureState: CaptureState,
    val videoResolution: Int,
    val fpsIndex: Int,
    val recordSeconds: Int,
    val freeSpaceMb: Long,
    val remainingPhotos: Long,
    val remainingRecordSeconds: Long,
    val sleeping: Boolean,
    val batteryPercent: Int,
) {
    val mode: CameraMode? get() = CameraMode.fromCode(modeCode)
    val modeLabel: String get() = mode?.label ?: "Mode 0x%02x".format(modeCode)
    val isCapturing: Boolean get() = captureState == CaptureState.CAPTURING

    companion object {
        const val MIN_PAYLOAD = 38

        fun parse(payload: ByteArray): CameraStatus? {
            if (payload.size < MIN_PAYLOAD) return null
            return CameraStatus(
                modeCode = payload.getU8(0),
                captureState = CaptureState.fromCode(payload.getU8(1)),
                videoResolution = payload.getU8(2),
                fpsIndex = payload.getU8(3),
                recordSeconds = payload.getU16(5),
                freeSpaceMb = payload.getU32(15),
                remainingPhotos = payload.getU32(19),
                remainingRecordSeconds = payload.getU32(23),
                sleeping = payload.getU8(28) == 3,
                batteryPercent = payload.getU8(37),
            )
        }
    }
}
