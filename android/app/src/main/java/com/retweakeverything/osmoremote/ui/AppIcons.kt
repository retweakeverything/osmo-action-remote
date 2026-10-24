package com.retweakeverything.osmoremote.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Nightlight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import com.retweakeverything.osmoremote.core.workflow.Step
import com.retweakeverything.osmoremote.protocol.CameraMode

/** Single place for icon choices so modes and blocks look the same everywhere. */
object AppIcons {
    val connect: ImageVector = Icons.Rounded.Bluetooth
    val remote: ImageVector = Icons.Rounded.SettingsRemote
    val workflows: ImageVector = Icons.AutoMirrored.Rounded.ViewList
    val camera: ImageVector = Icons.Rounded.CameraAlt

    fun mode(mode: CameraMode?): ImageVector = when (mode) {
        CameraMode.VIDEO -> Icons.Rounded.Videocam
        CameraMode.PHOTO -> Icons.Rounded.CameraAlt
        CameraMode.SLOW_MOTION -> Icons.Rounded.SlowMotionVideo
        CameraMode.TIMELAPSE -> Icons.Rounded.Timelapse
        CameraMode.HYPERLAPSE -> Icons.Rounded.Speed
        CameraMode.SUPER_NIGHT -> Icons.Rounded.Nightlight
        else -> Icons.Rounded.Tune
    }

    fun step(step: Step): ImageVector = when (step) {
        is Step.SetMode -> Icons.Rounded.Tune
        is Step.TakePhoto -> Icons.Rounded.CameraAlt
        is Step.RecordFor -> Icons.Rounded.Timer
        Step.StartRecording -> Icons.Rounded.FiberManualRecord
        Step.StopRecording -> Icons.Rounded.Stop
        Step.Highlight -> Icons.Rounded.Star
        is Step.Wait -> Icons.Rounded.HourglassEmpty
        is Step.Repeat -> Icons.Rounded.Repeat
        Step.Sleep -> Icons.Rounded.Bedtime
    }

    val run: ImageVector = Icons.Rounded.PlayArrow
}
