package com.retweakeverything.osmoremote.core.workflow

import com.retweakeverything.osmoremote.core.CameraActions
import com.retweakeverything.osmoremote.core.CameraUnavailableException
import com.retweakeverything.osmoremote.protocol.CameraMode
import com.retweakeverything.osmoremote.protocol.CameraStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
private class RecordingCamera(private val scope: TestScope) : CameraActions {
    val calls = mutableListOf<String>()
    val readyFlow = MutableStateFlow(true)
    var failNextWith: Exception? = null
    override val ready: StateFlow<Boolean> = readyFlow
    override val status: StateFlow<CameraStatus?> = MutableStateFlow(null)

    private fun call(name: String) {
        failNextWith?.let { failNextWith = null; throw it }
        calls += "$name@${scope.currentTime}"
    }

    override suspend fun startRecording() = call("start")
    override suspend fun stopRecording() = call("stop")
    override suspend fun takePhoto() = call("photo")
    override suspend fun setMode(mode: CameraMode) = call("mode:${mode.name}")
    override suspend fun highlight() = call("highlight")
    override suspend fun sleep() = call("sleep")
}

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowRunnerTest {
    private fun wf(vararg steps: Step) = Workflow("t", "test", steps.toList())

    @Test fun photoThenClipRunsBlocksInOrder() = runTest {
        val cam = RecordingCamera(this)
        val runner = WorkflowRunner(cam, this)
        runner.start(Templates.all.first { it.id == "tpl-photo-then-clip" })
        advanceUntilIdle()
        assertEquals(listOf("mode:PHOTO@0", "photo@0", "mode:VIDEO@0", "start@0", "stop@2000"), cam.calls)
        assertTrue(runner.state.value is RunState.Finished)
    }

    @Test fun repeatRunsBodyNTimes() = runTest {
        val cam = RecordingCamera(this)
        val runner = WorkflowRunner(cam, this)
        runner.start(wf(Step.Repeat(3, listOf(Step.TakePhoto(), Step.Wait(10)))))
        advanceUntilIdle()
        assertEquals(listOf("photo@0", "photo@10000", "photo@20000"), cam.calls)
    }

    @Test fun pauseFreezesTimeline() = runTest {
        val cam = RecordingCamera(this)
        val runner = WorkflowRunner(cam, this)
        runner.start(wf(Step.Wait(10), Step.TakePhoto()))
        advanceTimeBy(4_000); runCurrent()
        runner.pause()
        advanceTimeBy(30_000); runCurrent()
        assertTrue("nothing should run while paused", cam.calls.isEmpty())
        assertTrue((runner.state.value as RunState.Active).paused)
        runner.resume()
        advanceUntilIdle()
        assertEquals(1, cam.calls.size)
        // 4s before pause + 30s paused + ~6s remaining
        val at = cam.calls[0].substringAfter('@').toLong()
        assertTrue("photo at $at", at in 39_500..40_500)
    }

    @Test fun stopDuringRecordingStopsTheCamera() = runTest {
        val cam = RecordingCamera(this)
        val runner = WorkflowRunner(cam, this)
        runner.start(wf(Step.RecordFor(60)))
        advanceTimeBy(5_000); runCurrent()
        runner.stop()
        advanceUntilIdle()
        assertEquals(listOf("start@0", "stop@5000"), cam.calls)
        assertTrue(runner.state.value is RunState.Stopped)
    }

    @Test fun stopWhenWeDidNotStartRecordingLeavesCameraAlone() = runTest {
        val cam = RecordingCamera(this)
        val runner = WorkflowRunner(cam, this)
        runner.start(wf(Step.Wait(60)))
        advanceTimeBy(5_000); runCurrent()
        runner.stop()
        advanceUntilIdle()
        assertTrue(cam.calls.isEmpty())
    }

    @Test fun waitsForReconnectThenRetries() = runTest {
        val cam = RecordingCamera(this)
        val runner = WorkflowRunner(cam, this)
        cam.readyFlow.value = false
        cam.failNextWith = CameraUnavailableException()
        runner.start(wf(Step.TakePhoto()))
        runCurrent()
        assertTrue((runner.state.value as RunState.Active).waitingForCamera)
        advanceTimeBy(8_000)
        cam.readyFlow.value = true
        advanceUntilIdle()
        assertEquals(listOf("photo@8000"), cam.calls)
        assertTrue(runner.state.value is RunState.Finished)
    }

    @Test fun failsWhenCameraNeverReturns() = runTest {
        val cam = RecordingCamera(this)
        val runner = WorkflowRunner(cam, this, reconnectGraceMs = 10_000)
        cam.readyFlow.value = false
        cam.failNextWith = CameraUnavailableException()
        runner.start(wf(Step.TakePhoto()))
        advanceUntilIdle()
        assertTrue(runner.state.value is RunState.Failed)
    }

    @Test fun reportsLoopProgress() = runTest {
        val cam = RecordingCamera(this)
        val runner = WorkflowRunner(cam, this)
        runner.start(wf(Step.Repeat(5, listOf(Step.Wait(10)))))
        advanceTimeBy(25_000); runCurrent()
        val s = runner.state.first { it is RunState.Active } as RunState.Active
        assertEquals(listOf("3/5"), s.loops)
        runner.stop()
    }

    /** Regression: an empty "until stopped" Repeat spun the main thread and caused an ANR. */
    @Test fun emptyForeverRepeatIsSkipped() = runTest {
        val cam = RecordingCamera(this)
        val runner = WorkflowRunner(cam, this)
        runner.start(wf(Step.TakePhoto(), Step.Repeat(null, emptyList()), Step.Highlight))
        advanceUntilIdle()
        assertEquals(listOf("photo@0", "highlight@0"), cam.calls)
        assertTrue(runner.state.value is RunState.Finished)
    }

    @Test fun workflowJsonRoundTrips() {
        Templates.all.forEach { assertEquals(it, WorkflowJson.decode(WorkflowJson.encode(it))) }
        assertEquals(Templates.all, WorkflowJson.decodeAll(WorkflowJson.encodeAll(Templates.all)))
    }

    @Test fun estimatesDuration() {
        val interval = Templates.all.first { it.id == "tpl-interval" }
        assertEquals(3_600L, interval.steps.estimatedSeconds())
        assertEquals(null, listOf(Step.Repeat(null, listOf(Step.Wait(1)))).estimatedSeconds())
    }
}
