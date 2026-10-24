package com.retweakeverything.osmoremote.core

import com.retweakeverything.osmoremote.protocol.CameraMode
import com.retweakeverything.osmoremote.protocol.Command
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraConnectionTest {
    private val device = CameraDevice("AA:BB:CC:DD:EE:FF", "OsmoAction5Pro0000")

    @Test fun pairedHandshakeAcksCameraAndSubscribes() = runTest {
        val fake = FakeCameraTransport(backgroundScope)
        fake.connect(device)
        val session = CameraSession(fake, backgroundScope)
        session.start()
        val id = session.handshake(firstPairing = false, verifyCode = 1234)
        assertEquals(0xFF44L, id)
        assertTrue("camera request must be acked", fake.acksReceived >= 1)
        assertTrue(fake.received.any { it.command == Command.STATUS_SUBSCRIBE })
        val status = withTimeout(2_000) { session.status.first { it != null } }!!
        assertEquals(63, status.batteryPercent)
    }

    @Test fun ackStopsCameraRetransmitting() = runTest {
        val fake = FakeCameraTransport(backgroundScope)
        fake.connect(device)
        val session = CameraSession(fake, backgroundScope).also { it.start() }
        session.handshake(firstPairing = false, verifyCode = 1)
        val sentAfterHandshake = fake.cameraRequestsSent
        advanceTimeBy(2_000)
        assertTrue("camera kept retransmitting after ack", fake.cameraRequestsSent <= sentAfterHandshake + 1)
    }

    @Test fun firstPairingReportsAwaitingConfirmation() = runTest {
        val fake = FakeCameraTransport(backgroundScope, confirmDelayMs = 5_000)
        fake.connect(device)
        val session = CameraSession(fake, backgroundScope).also { it.start() }
        var awaited = false
        session.handshake(firstPairing = true, verifyCode = 4821) { awaited = true }
        assertTrue(awaited)
        val req = fake.received.first { it.command == Command.CONNECTION && !it.isResponse }
        assertEquals("verify_mode=1 on first pairing", 1, req.payload[26].toInt())
    }

    @Test fun rejectionIsNotRetryable() = runTest {
        val fake = FakeCameraTransport(backgroundScope, allowConnection = false)
        fake.connect(device)
        val session = CameraSession(fake, backgroundScope).also { it.start() }
        try {
            session.handshake(firstPairing = true, verifyCode = 1)
            fail("expected rejection")
        } catch (e: HandshakeException) {
            assertEquals(false, e.retryable)
        }
    }

    @Test fun managerConnectsRecordsAndSwitchesMode() = runTest {
        val fake = FakeCameraTransport(backgroundScope)
        val store = InMemoryPairingStore()
        val mgr = CameraConnectionManager({ fake }, store, backgroundScope)
        mgr.connect(device)
        withTimeout(5_000) { mgr.ready.first { it } }
        assertTrue(store.isPaired(device.address))

        mgr.startRecording()
        assertTrue(mgr.status.value!!.isCapturing)
        mgr.stopRecording()
        assertTrue(!mgr.status.value!!.isCapturing)

        mgr.setMode(CameraMode.PHOTO)
        assertEquals(CameraMode.PHOTO, mgr.status.value!!.mode)
        mgr.disconnect()
        assertEquals(ConnectionState.Idle, mgr.state.value)
    }

    @Test fun managerReconnectsAfterLinkDrop() = runTest {
        val fake = FakeCameraTransport(backgroundScope)
        val mgr = CameraConnectionManager({ fake }, InMemoryPairingStore(), backgroundScope)
        mgr.connect(device)
        withTimeout(5_000) { mgr.ready.first { it } }

        fake.dropLink()
        withTimeout(1_000) { mgr.state.first { it is ConnectionState.Reconnecting } }
        withTimeout(10_000) { mgr.state.first { it is ConnectionState.Ready } }
        mgr.disconnect()
    }

    @Test fun commandsFailFastWhenNotConnected() = runTest {
        val mgr = CameraConnectionManager({ FakeCameraTransport(backgroundScope) }, InMemoryPairingStore(), backgroundScope)
        try {
            mgr.startRecording()
            fail("expected CameraUnavailableException")
        } catch (_: CameraUnavailableException) {
        }
    }
}
