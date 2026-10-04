package com.pluxy.server.http

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class HttpWorkSchedulerTest {
    @Test fun `busy transfers cannot starve control and have a bounded queue`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        HttpWorkScheduler(controlThreads = 1, transferThreads = 1, pending = 1).use { workers ->
            workers.transfer { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            workers.transfer { }
            assertThrows(RejectedExecutionException::class.java) { workers.transfer { } }
            val control = CountDownLatch(1)
            workers.control { control.countDown() }
            assertTrue(control.await(1, TimeUnit.SECONDS))
            release.countDown()
        }
    }

    @Test fun `successful writes reset inactivity deadline`() {
        var now = 0L
        val activity = ConnectionActivity { now }
        now = 29
        assertFalse(activity.expired(30))
        activity.progress()
        now = 58
        assertFalse(activity.expired(30))
        now = 59
        assertTrue(activity.expired(30))
    }
}
