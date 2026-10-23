package com.pluxy.tv.player

import org.junit.Assert.*
import org.junit.Test

class VerticalPlayerSwipeTest {
    @Test fun `right controls brightness and upward motion increases it`() {
        val swipe = VerticalPlayerSwipe(8f)
        swipe.start(900f, 400f, 1000f)
        assertTrue(swipe.right)
        assertEquals(.5f, swipe.move(902f, 140f, 800f)!!, .001f)
        assertFalse(swipe.isTap)
    }

    @Test fun `left controls volume and downward motion decreases it`() {
        val swipe = VerticalPlayerSwipe(8f)
        swipe.start(100f, 200f, 1000f)
        assertFalse(swipe.right)
        assertEquals(-.5f, swipe.move(99f, 460f, 800f)!!, .001f)
    }

    @Test fun `a tap does not modify the playback settings`() {
        val swipe = VerticalPlayerSwipe(8f)
        swipe.start(100f, 200f, 1000f)
        assertNull(swipe.move(102f, 204f, 800f))
        assertTrue(swipe.isTap)
    }

    @Test fun `horizontal gestures never change a setting or trigger a tap`() {
        val swipe = VerticalPlayerSwipe(8f)
        swipe.start(100f, 200f, 1000f)
        assertNull(swipe.move(180f, 210f, 800f))
        assertNull(swipe.move(180f, 10f, 800f))
        assertFalse(swipe.isTap)
    }

    @Test fun `crossing the middle preserves the initial control and cancellation stops adjustment`() {
        val swipe = VerticalPlayerSwipe(8f)
        swipe.start(490f, 500f, 1000f)
        assertNotNull(swipe.move(520f, 350f, 800f))
        assertFalse(swipe.right)
        swipe.cancel()
        assertNull(swipe.move(520f, 200f, 800f))
        assertFalse(swipe.isTap)
        swipe.start(700f, 100f, 1000f)
        assertTrue(swipe.right)
        assertTrue(swipe.isTap)
    }
}
