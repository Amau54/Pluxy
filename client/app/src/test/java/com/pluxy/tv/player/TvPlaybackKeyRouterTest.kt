package com.pluxy.tv.player

import com.pluxy.tv.player.TvPlaybackKeyRouter.Command
import com.pluxy.tv.player.TvPlaybackKeyRouter.Focus
import com.pluxy.tv.player.TvPlaybackKeyRouter.Key
import com.pluxy.tv.player.TvPlaybackKeyRouter.Phase
import com.pluxy.tv.player.TvPlaybackKeyRouter.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvPlaybackKeyRouterTest {
    @Test fun `direction restores button focus after touch without seeking or toggling`() {
        val surface = State(controlsVisible = true, seekActive = false, focus = Focus.PLAYBACK)
        for (key in listOf(Key.UP, Key.DOWN)) {
            val router = TvPlaybackKeyRouter()
            assertDecision(router.route(key, Phase.DOWN, 0, 100L, surface), Command.ShowControls)
            assertDecision(router.route(key, Phase.UP, 0, 150L, surface))
        }
        assertDecision(TvPlaybackKeyRouter().route(Key.CENTER, Phase.DOWN, 0, 100L, surface),
            Command.ShowControls, Command.ToggleOrReplay)
    }
    private val hidden = State(
        controlsVisible = false,
        seekActive = false,
        focus = Focus.PLAYBACK,
    )
    private val timeline = State(
        controlsVisible = true,
        seekActive = false,
        focus = Focus.TIMELINE,
    )
    private val primary = State(
        controlsVisible = true,
        seekActive = false,
        focus = Focus.PLAY_PAUSE,
    )
    private val secondary = State(
        controlsVisible = true,
        seekActive = false,
        focus = Focus.OTHER_CONTROL,
    )

    @Test
    fun `center toggles immediately and never fires twice for one press`() {
        val router = TvPlaybackKeyRouter()

        assertDecision(
            router.route(Key.CENTER, Phase.DOWN, 0, 100L, hidden),
            Command.ShowControls,
            Command.ToggleOrReplay,
        )
        assertDecision(router.route(Key.CENTER, Phase.DOWN, 1, 150L, primary))
        assertDecision(router.route(Key.CENTER, Phase.UP, 0, 180L, primary))

        assertDecision(
            router.route(Key.CENTER, Phase.DOWN, 0, 300L, primary),
            Command.ToggleOrReplay,
        )
    }

    @Test
    fun `center on timeline toggles instead of being swallowed`() {
        val router = TvPlaybackKeyRouter()

        assertDecision(
            router.route(Key.CENTER, Phase.DOWN, 0, 100L, timeline),
            Command.ToggleOrReplay,
        )
    }

    @Test
    fun `secondary controls keep native center click semantics`() {
        val decision = TvPlaybackKeyRouter().route(
            Key.CENTER,
            Phase.DOWN,
            0,
            100L,
            secondary,
        )

        assertFalse(decision.handled)
        assertTrue(decision.commands.isEmpty())
    }

    @Test
    fun `left and right start preview then commit exactly once on release`() {
        val router = TvPlaybackKeyRouter()

        assertDecision(
            router.route(Key.RIGHT, Phase.DOWN, 0, 100L, hidden),
            Command.ShowControls,
            Command.StartSeek(1),
        )
        assertDecision(
            router.route(
                Key.RIGHT,
                Phase.DOWN,
                1,
                200L,
                primary.copy(seekActive = true),
            ),
            Command.KeepSeekAlive,
        )
        assertDecision(
            router.route(
                Key.RIGHT,
                Phase.UP,
                0,
                250L,
                primary.copy(seekActive = true),
            ),
            Command.CommitSeek,
        )
        assertDecision(router.route(Key.RIGHT, Phase.UP, 0, 260L, timeline))
    }

    @Test
    fun `left and right navigate the entire visible button row`() {
        val primaryRouter = TvPlaybackKeyRouter()

        assertFalse(primaryRouter.route(Key.LEFT, Phase.DOWN, 0, 90L, primary).handled)
        assertFalse(primaryRouter.route(Key.RIGHT, Phase.DOWN, 0, 95L, primary).handled)
        val secondaryRouter = TvPlaybackKeyRouter()
        assertFalse(secondaryRouter.route(Key.LEFT, Phase.DOWN, 0, 100L, secondary).handled)
        assertFalse(secondaryRouter.route(Key.RIGHT, Phase.DOWN, 0, 120L, secondary).handled)
    }

    @Test
    fun `back follows cancel hide exit hierarchy`() {
        val seeking = timeline.copy(seekActive = true)

        assertDecision(
            TvPlaybackKeyRouter().route(Key.BACK, Phase.DOWN, 0, 100L, seeking),
            Command.CancelSeek,
        )
        assertDecision(
            TvPlaybackKeyRouter().route(Key.BACK, Phase.DOWN, 0, 100L, timeline),
            Command.HideControls,
        )
        assertDecision(
            TvPlaybackKeyRouter().route(Key.BACK, Phase.DOWN, 0, 100L, hidden),
            Command.Exit,
        )
    }

    @Test
    fun `all media transport keys have explicit commands`() {
        val cases = listOf(
            Key.MEDIA_PLAY_PAUSE to listOf(Command.ShowControls, Command.ToggleOrReplay),
            Key.MEDIA_PLAY to listOf(Command.ShowControls, Command.PlayOrReplay),
            Key.MEDIA_PAUSE to listOf(Command.ShowControls, Command.Pause),
            Key.MEDIA_REWIND to listOf(Command.StartSeek(-1)),
            Key.MEDIA_FAST_FORWARD to listOf(Command.StartSeek(1)),
        )

        cases.forEachIndexed { index, (key, expected) ->
            val decision = TvPlaybackKeyRouter().route(
                key,
                Phase.DOWN,
                0,
                100L + index,
                timeline,
            )
            assertTrue("$key should be handled", decision.handled)
            assertEquals("$key commands", expected, decision.commands)
        }
    }

    @Test
    fun `up or down reveals hidden controls but navigates visible controls`() {
        val hiddenRouter = TvPlaybackKeyRouter()
        assertDecision(
            hiddenRouter.route(Key.UP, Phase.DOWN, 0, 100L, hidden),
            Command.ShowControls,
        )

        val visibleDecision = TvPlaybackKeyRouter().route(
            Key.DOWN,
            Phase.DOWN,
            0,
            100L,
            primary,
        )
        assertFalse(visibleDecision.handled)
    }

    @Test
    fun `stale held key cannot permanently block a remote after lost key up`() {
        val router = TvPlaybackKeyRouter(staleKeyTimeoutMs = 500L)

        assertDecision(
            router.route(Key.CENTER, Phase.DOWN, 0, 100L, primary),
            Command.ToggleOrReplay,
        )
        assertDecision(
            router.route(Key.CENTER, Phase.DOWN, 0, 800L, primary),
            Command.ToggleOrReplay,
        )
    }

    @Test
    fun `second deliberate center press works when hdmi cec loses key up`() {
        val router = TvPlaybackKeyRouter()

        assertDecision(
            router.route(Key.CENTER, Phase.DOWN, 0, 100L, primary),
            Command.ToggleOrReplay,
        )
        assertDecision(
            router.route(Key.CENTER, Phase.DOWN, 0, 400L, primary),
            Command.ToggleOrReplay,
        )
    }

    @Test
    fun `first observed down still acts when a remote starts with a repeat count`() {
        val decision = TvPlaybackKeyRouter().route(
            Key.MEDIA_PLAY,
            Phase.DOWN,
            3,
            100L,
            hidden,
        )

        assertDecision(decision, Command.ShowControls, Command.PlayOrReplay)
    }

    private fun assertDecision(
        actual: TvPlaybackKeyRouter.Decision,
        vararg expectedCommands: Command,
    ) {
        assertTrue(actual.handled)
        assertEquals(expectedCommands.toList(), actual.commands)
    }
}
