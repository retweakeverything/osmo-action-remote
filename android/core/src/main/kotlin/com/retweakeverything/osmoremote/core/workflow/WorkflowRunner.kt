package com.retweakeverything.osmoremote.core.workflow

import com.retweakeverything.osmoremote.core.CameraActions
import com.retweakeverything.osmoremote.core.CameraUnavailableException
import com.retweakeverything.osmoremote.core.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

sealed interface RunState {
    data object Idle : RunState

    data class Active(
        val workflow: Workflow,
        val current: String,
        /** Iteration labels from outermost to innermost loop, e.g. ["12/60"]. */
        val loops: List<String>,
        val stepsDone: Int,
        val paused: Boolean,
        val waitingForCamera: Boolean,
        /** Seconds left in the current timed step, if any. */
        val countdown: Int?,
    ) : RunState

    data class Finished(val workflow: Workflow, val stepsDone: Int) : RunState
    data class Stopped(val workflow: Workflow, val stepsDone: Int) : RunState
    data class Failed(val workflow: Workflow, val reason: String) : RunState
}

/**
 * Executes a [Workflow] against [CameraActions].
 *
 * - Pause freezes the timeline between steps and inside waits. The camera
 *   keeps doing whatever it was doing (e.g. a recording keeps running).
 * - Stop cancels the run and, if this run started a recording that is still
 *   going, stops it.
 * - If the camera drops, the current step waits up to [reconnectGraceMs] for
 *   it to come back, then retries; otherwise the run fails safely.
 */
class WorkflowRunner(
    private val camera: CameraActions,
    private val scope: CoroutineScope,
    private val reconnectGraceMs: Long = 120_000,
    private val tickMs: Long = 250,
    private val logger: Logger = Logger.NONE,
) {
    private val _state = MutableStateFlow<RunState>(RunState.Idle)
    val state: StateFlow<RunState> = _state

    private val paused = MutableStateFlow(false)
    private var job: Job? = null

    private var workflow: Workflow? = null
    private var stepsDone = 0
    private var currentLabel = ""
    private val loopLabels = ArrayDeque<String>()
    private var recordingStartedByUs = false

    @Volatile private var active = false

    val isRunning: Boolean get() = active

    fun start(wf: Workflow) {
        check(!isRunning) { "a workflow is already running" }
        workflow = wf
        stepsDone = 0
        loopLabels.clear()
        recordingStartedByUs = false
        paused.value = false
        active = true
        job = scope.launch {
            try {
                runSteps(wf.steps)
                _state.value = RunState.Finished(wf, stepsDone)
            } catch (e: CancellationException) {
                _state.value = RunState.Stopped(wf, stepsDone)
                throw e
            } catch (e: Exception) {
                logger.log("workflow failed: ${e.message}")
                _state.value = RunState.Failed(wf, e.message ?: e::class.simpleName.orEmpty())
            } finally {
                withContext(NonCancellable) { safeTeardown() }
                active = false
            }
        }
    }

    fun pause() { paused.value = true; publish() }
    fun resume() { paused.value = false; publish() }

    fun stop() { job?.cancel() }

    /** Clears a terminal state so the UI returns to the list. */
    fun dismiss() { if (!isRunning) _state.value = RunState.Idle }

    private suspend fun runSteps(steps: List<Step>) {
        for (step in steps) runStep(step)
    }

    private suspend fun runStep(step: Step) {
        if (step is Step.Repeat) return runRepeat(step)
        awaitNotPaused()
        currentLabel = step.describe()
        logger.log("wf: begin '${currentLabel}' loops=${loopLabels.toList()}")
        publish()
        when (step) {
            is Step.SetMode -> withCamera { camera.setMode(step.mode) }
            is Step.TakePhoto -> repeat(step.count) { i ->
                if (i > 0) timedWait(step.intervalSeconds)
                currentLabel = if (step.count > 1) "Take photo ${i + 1}/${step.count}" else "Take photo"
                publish()
                withCamera { camera.takePhoto() }
            }
            is Step.RecordFor -> {
                withCamera { camera.startRecording() }
                recordingStartedByUs = true
                timedWait(step.seconds)
                withCamera { camera.stopRecording() }
                recordingStartedByUs = false
            }
            Step.StartRecording -> { withCamera { camera.startRecording() }; recordingStartedByUs = true }
            Step.StopRecording -> { withCamera { camera.stopRecording() }; recordingStartedByUs = false }
            Step.Highlight -> withCamera { camera.highlight() }
            is Step.Wait -> timedWait(step.seconds)
            Step.Sleep -> withCamera { camera.sleep() }
            is Step.Repeat -> error("handled above")
        }
        stepsDone++
        logger.log("wf: end '${step.describe()}'")
        publish(countdown = null)
    }

    private suspend fun runRepeat(step: Step.Repeat) {
        if (step.steps.isEmpty()) {
            // Nothing to repeat. Looping here would spin forever without suspending.
            logger.log("wf: skipping empty '${step.describe()}'")
            stepsDone++
            return
        }
        var i = 0
        while (step.times == null || i < step.times) {
            // Guarantees each pass is a suspension point, so Stop/Pause always get through
            // even when the body is made only of instant blocks.
            yield()
            loopLabels.addLast(if (step.times == null) "${i + 1}" else "${i + 1}/${step.times}")
            try {
                runSteps(step.steps)
            } finally {
                loopLabels.removeLast()
            }
            i++
        }
    }

    /** Runs a camera call; on a dropped link waits for reconnect and retries once. */
    private suspend fun withCamera(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CameraUnavailableException) {
            logger.log("wf: camera unavailable (${e.message}), waiting for reconnect")
            publish(waitingForCamera = true)
            withTimeoutOrNull(reconnectGraceMs) { camera.ready.first { it } }
                ?: throw CameraUnavailableException("camera did not reconnect within ${reconnectGraceMs / 1000}s")
            publish(waitingForCamera = false)
            block()
        }
    }

    private suspend fun timedWait(seconds: Int) {
        var remainingMs = seconds * 1000L
        while (remainingMs > 0) {
            awaitNotPaused()
            publish(countdown = ((remainingMs + 999) / 1000).toInt())
            val slice = minOf(tickMs, remainingMs)
            delay(slice)
            remainingMs -= slice
        }
    }

    private suspend fun awaitNotPaused() {
        if (paused.value) paused.first { !it }
    }

    private suspend fun safeTeardown() {
        if (!recordingStartedByUs) return
        recordingStartedByUs = false
        runCatching { withTimeoutOrNull(5_000) { camera.stopRecording() } }
            .onFailure { logger.log("teardown stop failed: ${it.message}") }
    }

    private fun publish(countdown: Int? = currentCountdown(), waitingForCamera: Boolean = currentWaiting()) {
        val wf = workflow ?: return
        if (!active) return
        _state.value = RunState.Active(
            workflow = wf, current = currentLabel, loops = loopLabels.toList(), stepsDone = stepsDone,
            paused = paused.value, waitingForCamera = waitingForCamera, countdown = countdown,
        )
    }

    private fun currentCountdown(): Int? = (_state.value as? RunState.Active)?.countdown
    private fun currentWaiting(): Boolean = (_state.value as? RunState.Active)?.waitingForCamera ?: false
}
