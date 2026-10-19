package com.cookalong.app

import com.cookalong.app.net.Connectivity
import com.cookalong.app.net.NetReading
import com.cookalong.app.ui.syncToast
import com.cookalong.app.ui.screens.offlineNotice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityTest {

    @Test fun `only validated or unknown counts as online`() {
        assertTrue(Connectivity.isOnline(NetReading.VALIDATED))
        assertTrue(Connectivity.isOnline(NetReading.UNKNOWN)) // never block the app on a state we can't read
        assertFalse(Connectivity.isOnline(NetReading.NONE))
        assertFalse(Connectivity.isOnline(NetReading.NOT_VALIDATED)) // connected to a router with no uplink
    }

    @Test fun `cold start offline is reported at once`() = runTest {
        val out = Connectivity.onlineFlow(flow { emit(NetReading.NONE) }, graceMs = 1_500).toList()
        assertEquals(listOf(false), out)
        assertEquals(0L, currentTime)
    }

    @Test fun `going offline waits for the grace period, coming back is immediate`() = runTest {
        val readings = flow {
            emit(NetReading.VALIDATED)
            delay(1_000); emit(NetReading.NONE)
            delay(5_000); emit(NetReading.VALIDATED)
        }
        val times = mutableListOf<Pair<Long, Boolean>>()
        Connectivity.onlineFlow(readings, graceMs = 1_500).collect { times += currentTime to it }
        assertEquals(listOf(0L to true, 2_500L to false, 6_000L to true), times)
    }

    @Test fun `a blip shorter than the grace period is never reported`() = runTest {
        val readings = flow {
            emit(NetReading.VALIDATED)
            delay(1_000); emit(NetReading.NOT_VALIDATED)
            delay(500); emit(NetReading.VALIDATED)
            delay(3_000)
        }
        assertEquals(listOf(true), Connectivity.onlineFlow(readings, graceMs = 1_500).toList())
    }

    @Test fun `repeated readings don't repeat the flag`() = runTest {
        val readings = flow {
            emit(NetReading.VALIDATED); emit(NetReading.UNKNOWN); emit(NetReading.VALIDATED)
        }
        assertEquals(listOf(true), Connectivity.onlineFlow(readings).toList())
    }

    @Test fun `offline notice depends on whether online recipes are saved`() {
        assertEquals("You're offline. Showing saved recipes.", offlineNotice(120))
        assertEquals("You're offline. More recipes download when you're online.", offlineNotice(0))
    }

    @Test fun `first download is not announced as new recipes`() {
        assertEquals("429 more recipes downloaded", syncToast(429, firstDownload = true))
        assertEquals("1 more recipe downloaded", syncToast(1, firstDownload = true))
        assertEquals("3 new recipes added", syncToast(3, firstDownload = false))
        assertEquals("1 new recipe added", syncToast(1, firstDownload = false))
    }
}
