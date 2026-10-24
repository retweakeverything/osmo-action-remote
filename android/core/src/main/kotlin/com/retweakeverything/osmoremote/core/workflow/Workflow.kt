package com.retweakeverything.osmoremote.core.workflow

import com.retweakeverything.osmoremote.protocol.CameraMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Building blocks. The engine runs them exactly as ordered and does not try
 * to judge whether a sequence makes sense; that is the user's call.
 */
@Serializable
sealed interface Step {
    @Serializable @SerialName("set_mode")
    data class SetMode(val mode: CameraMode) : Step

    @Serializable @SerialName("photo")
    data class TakePhoto(val count: Int = 1, val intervalSeconds: Int = 2) : Step

    @Serializable @SerialName("record_for")
    data class RecordFor(val seconds: Int) : Step

    @Serializable @SerialName("start_recording")
    data object StartRecording : Step

    @Serializable @SerialName("stop_recording")
    data object StopRecording : Step

    @Serializable @SerialName("highlight")
    data object Highlight : Step

    @Serializable @SerialName("wait")
    data class Wait(val seconds: Int) : Step

    /** Repeats [steps]; [times] = null repeats until the user stops the run. */
    @Serializable @SerialName("repeat")
    data class Repeat(val times: Int?, val steps: List<Step>) : Step

    @Serializable @SerialName("sleep")
    data object Sleep : Step
}

@Serializable
data class Workflow(
    val id: String,
    val name: String,
    val steps: List<Step>,
    val template: Boolean = false,
)

/** Plain-English one-liner for a step, used in the builder and run screens. */
fun Step.describe(): String = when (this) {
    is Step.SetMode -> "Set mode: ${mode.label}"
    is Step.TakePhoto -> if (count == 1) "Take photo" else "Take $count photos, ${intervalSeconds}s apart"
    is Step.RecordFor -> "Record for ${formatSeconds(seconds)}"
    Step.StartRecording -> "Start recording"
    Step.StopRecording -> "Stop recording"
    Step.Highlight -> "Highlight tag"
    is Step.Wait -> "Wait ${formatSeconds(seconds)}"
    is Step.Repeat -> (times?.let { "Repeat $it times" } ?: "Repeat until stopped") + " (${steps.size} blocks)"
    Step.Sleep -> "Sleep camera"
}

fun formatSeconds(s: Int): String = when {
    s < 60 -> "${s}s"
    s % 3600 == 0 -> "${s / 3600}h"
    s % 60 == 0 -> "${s / 60} min"
    else -> "${s / 60}m ${s % 60}s"
}

/** Total runtime in seconds, or null when the workflow repeats forever. */
fun List<Step>.estimatedSeconds(): Long? {
    var total = 0L
    for (step in this) {
        total += when (step) {
            is Step.RecordFor -> step.seconds.toLong()
            is Step.Wait -> step.seconds.toLong()
            is Step.TakePhoto -> ((step.count - 1).coerceAtLeast(0) * step.intervalSeconds).toLong()
            is Step.Repeat -> {
                val inner = step.steps.estimatedSeconds() ?: return null
                val times = step.times ?: return null
                inner * times
            }
            else -> 0L
        }
    }
    return total
}

/** JSON used for local storage and for sharing workflows between phones. */
object WorkflowJson {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }

    fun encode(workflow: Workflow): String = json.encodeToString(Workflow.serializer(), workflow)
    fun decode(text: String): Workflow = json.decodeFromString(Workflow.serializer(), text)

    fun encodeAll(workflows: List<Workflow>): String =
        json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Workflow.serializer()), workflows)

    fun decodeAll(text: String): List<Workflow> =
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Workflow.serializer()), text)
}

object Templates {
    val all: List<Workflow> = listOf(
        Workflow(
            id = "tpl-interval", name = "Interval clips", template = true,
            steps = listOf(
                Step.SetMode(CameraMode.VIDEO),
                Step.Repeat(60, listOf(Step.RecordFor(3), Step.Wait(57))),
            ),
        ),
        Workflow(
            id = "tpl-timed-clip", name = "Timed clip", template = true,
            steps = listOf(Step.SetMode(CameraMode.VIDEO), Step.RecordFor(600)),
        ),
        Workflow(
            id = "tpl-photo-then-clip", name = "Photo + 2s clip", template = true,
            steps = listOf(
                Step.SetMode(CameraMode.PHOTO), Step.TakePhoto(),
                Step.SetMode(CameraMode.VIDEO), Step.RecordFor(2),
            ),
        ),
        Workflow(
            id = "tpl-photo-interval", name = "Photo every 5 min", template = true,
            steps = listOf(
                Step.SetMode(CameraMode.PHOTO),
                Step.Repeat(24, listOf(Step.TakePhoto(), Step.Wait(300))),
            ),
        ),
    )
}
