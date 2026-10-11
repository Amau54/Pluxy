package com.pluxy.tv.player

/**
 * Pure, Android-free remote-control state machine.
 *
 * Android TV and HDMI-CEC remotes do not all emit the same sequence: some repeat
 * ACTION_DOWN aggressively, some lose ACTION_UP, and some expose the center key as
 * ENTER or BUTTON_A. PlayerActivity normalizes those platform events into this small
 * model, then executes the returned commands.
 */
internal class TvPlaybackKeyRouter(
    private val staleKeyTimeoutMs: Long = DEFAULT_STALE_KEY_TIMEOUT_MS,
    private val missingUpNewPressMs: Long = DEFAULT_MISSING_UP_NEW_PRESS_MS,
) {
    enum class Key {
        CENTER,
        LEFT,
        RIGHT,
        UP,
        DOWN,
        BACK,
        MEDIA_PLAY_PAUSE,
        MEDIA_PLAY,
        MEDIA_PAUSE,
        MEDIA_REWIND,
        MEDIA_FAST_FORWARD,
        OTHER,
    }

    enum class Phase {
        DOWN,
        UP,
    }

    enum class Focus {
        PLAYBACK,
        TIMELINE,
        PLAY_PAUSE,
        OTHER_CONTROL,
    }

    data class State(
        val controlsVisible: Boolean,
        val seekActive: Boolean,
        val focus: Focus,
    )

    sealed interface Command {
        data object ShowControls : Command
        data object ToggleOrReplay : Command
        data object PlayOrReplay : Command
        data object Pause : Command
        data class StartSeek(val direction: Int) : Command
        data object KeepSeekAlive : Command
        data object CommitSeek : Command
        data object CancelSeek : Command
        data object HideControls : Command
        data object Exit : Command
    }

    data class Decision(
        val handled: Boolean,
        val commands: List<Command> = emptyList(),
    )

    private val heldKeys = mutableMapOf<Key, Long>()

    @Suppress("UNUSED_PARAMETER")
    fun route(
        key: Key,
        phase: Phase,
        repeatCount: Int,
        eventTimeMs: Long,
        state: State,
    ): Decision {
        if (key == Key.OTHER) return Decision(handled = false)

        // A visible secondary control owns center/enter itself so Android can preserve
        // its normal pressed state and click semantics.
        if (key == Key.CENTER &&
            state.controlsVisible &&
            state.focus == Focus.OTHER_CONTROL
        ) {
            heldKeys.remove(key)
            return Decision(handled = false)
        }

        // Every visible button participates in horizontal navigation. Seeking is
        // reserved for the timeline and hidden controls, so side actions stay reachable.
        if ((key == Key.LEFT || key == Key.RIGHT) &&
            key !in heldKeys &&
            state.controlsVisible &&
            (state.focus == Focus.OTHER_CONTROL || state.focus == Focus.PLAY_PAUSE)
        ) {
            heldKeys.remove(key)
            return Decision(handled = false)
        }

        return when (phase) {
            Phase.DOWN -> onDown(key, repeatCount, eventTimeMs, state)
            Phase.UP -> onUp(key, state)
        }
    }

    fun reset() {
        heldKeys.clear()
    }

    private fun onDown(
        key: Key,
        repeatCount: Int,
        eventTimeMs: Long,
        state: State,
    ): Decision {
        val previousEventAt = heldKeys[key]
        val elapsed = previousEventAt?.let { eventTimeMs - it } ?: Long.MAX_VALUE
        val isStale = previousEventAt != null &&
            elapsed > staleKeyTimeoutMs
        // Certaines passerelles HDMI-CEC perdent UP. Un nouveau DOWN sans repeat,
        // suffisamment éloigné du précédent, est alors une nouvelle pression réelle.
        val recoveredPress = previousEventAt != null &&
            repeatCount == 0 &&
            elapsed >= missingUpNewPressMs
        val isFirstDown = previousEventAt == null || isStale || recoveredPress
        heldKeys[key] = eventTimeMs

        if (!isFirstDown) {
            return when (key) {
                Key.LEFT,
                Key.RIGHT,
                Key.MEDIA_REWIND,
                Key.MEDIA_FAST_FORWARD,
                -> Decision(handled = true, commands = listOf(Command.KeepSeekAlive))

                else -> Decision(handled = true)
            }
        }

        return when (key) {
            Key.CENTER -> Decision(
                handled = true,
                commands = buildList {
                    if (!state.controlsVisible || state.focus == Focus.PLAYBACK) add(Command.ShowControls)
                    if (state.seekActive) add(Command.CommitSeek)
                    add(Command.ToggleOrReplay)
                },
            )

            Key.LEFT,
            Key.MEDIA_REWIND,
            -> seekStart(direction = -1, state)

            Key.RIGHT,
            Key.MEDIA_FAST_FORWARD,
            -> seekStart(direction = 1, state)

            Key.UP,
            Key.DOWN,
            -> if (state.controlsVisible && state.focus != Focus.PLAYBACK) {
                heldKeys.remove(key)
                Decision(handled = false)
            } else {
                Decision(handled = true, commands = listOf(Command.ShowControls))
            }

            Key.BACK -> Decision(
                handled = true,
                commands = listOf(
                    when {
                        state.seekActive -> Command.CancelSeek
                        state.controlsVisible -> Command.HideControls
                        else -> Command.Exit
                    },
                ),
            )

            Key.MEDIA_PLAY_PAUSE -> Decision(
                handled = true,
                commands = listOf(Command.ShowControls, Command.ToggleOrReplay),
            )

            Key.MEDIA_PLAY -> Decision(
                handled = true,
                commands = listOf(Command.ShowControls, Command.PlayOrReplay),
            )

            Key.MEDIA_PAUSE -> Decision(
                handled = true,
                commands = listOf(Command.ShowControls, Command.Pause),
            )

            Key.OTHER -> Decision(handled = false)
        }
    }

    private fun onUp(key: Key, state: State): Decision {
        val wasHeld = heldKeys.remove(key) != null
        return when (key) {
            Key.LEFT,
            Key.RIGHT,
            Key.MEDIA_REWIND,
            Key.MEDIA_FAST_FORWARD,
            -> Decision(
                handled = true,
                commands = if (state.seekActive && wasHeld) {
                    listOf(Command.CommitSeek)
                } else {
                    emptyList()
                },
            )

            // The action already ran on the first DOWN. Consuming UP prevents a second
            // click when focus moved to the play button while the key was held.
            Key.CENTER,
            Key.BACK,
            Key.MEDIA_PLAY_PAUSE,
            Key.MEDIA_PLAY,
            Key.MEDIA_PAUSE,
            -> Decision(handled = true)

            Key.UP,
            Key.DOWN,
            -> Decision(handled = wasHeld)

            Key.OTHER -> Decision(handled = false)
        }
    }

    private fun seekStart(direction: Int, state: State): Decision = Decision(
        handled = true,
        commands = buildList {
            if (!state.controlsVisible) add(Command.ShowControls)
            add(Command.StartSeek(direction))
        },
    )

    private companion object {
        const val DEFAULT_STALE_KEY_TIMEOUT_MS = 1_500L
        const val DEFAULT_MISSING_UP_NEW_PRESS_MS = 250L
    }
}
