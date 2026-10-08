package com.pluxy.tv.player

import com.pluxy.tv.api.MediaItem
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Test

class PlaybackSessionStateTest {
    @Test fun stateRoundTripPreservesCurrentEpisodeAndUserChoices() {
        val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            .adapter(PlaybackSessionState::class.java)
        val state = PlaybackSessionState(
            MediaItem("next", "Episode suivant", "", "mp4", 1, 300.0),
            "http://192.168.1.20:8420", 145000, false, 1.5f, true,
            PlaybackTrackChoice("en", "Commentaire", "audio/aac", 2, "changed-track"),
            PlaybackTrackChoice("fr", "Français", "text/vtt"), 1, true, true, 2, "truehd",
        )
        assertEquals(state, adapter.fromJson(adapter.toJson(state)))
    }

    @Test fun preferredLanguageOutweighsReusedTrackIdAfterSourceChange() {
        val chosen = PlaybackTrackChoice("en", mimeType = "audio/aac", channels = 2, trackId = "1")
        val wrong = PlaybackTrackChoice("fr", mimeType = "audio/aac", channels = 2, trackId = "1")
        val right = PlaybackTrackChoice("en", mimeType = "audio/aac", channels = 2, trackId = "new-id")
        assertTrue(chosen.matchScore(right) > chosen.matchScore(wrong))
    }

    @Test fun acknowledgingAnOldSendKeepsTheNewerSeekPending() {
        val pending = ProgressPendingState()
        val old = PendingProgress("server", "film", "session1", 1, 120000, 300000, 100)
        val seek = old.copy(sequence = 2, positionMs = 60000, createdAtMs = 200)
        pending.add(old)
        pending.add(seek)
        pending.acknowledge(old)
        assertEquals(listOf(seek), pending.snapshot())
        pending.add(old)
        assertEquals(listOf(seek), pending.snapshot())
    }

    @Test fun lateWorkFromAPreviousSessionCannotReplaceTheLatestIntent() {
        val pending = ProgressPendingState()
        val first = PendingProgress("server", "film", "old-session", 20, 120000, 300000, 100, 1)
        val replay = first.copy(sessionId = "new-session", sequence = 1, positionMs = 0, intentOrder = 2)
        pending.add(replay)
        pending.add(first)
        assertEquals(listOf(replay), pending.snapshot())
    }

    @Test fun lateOldCoroutineIsRejectedEvenAfterNewIntentWasAcknowledged() {
        val pending = ProgressPendingState()
        val old = PendingProgress("server", "film", "old-session", 20, 120000, 300000, 100, 1)
        val fresh = old.copy(sessionId = "new-session", sequence = 1, positionMs = 0, intentOrder = 2)
        pending.add(fresh)
        pending.acknowledge(fresh)
        pending.add(old)
        assertTrue(pending.snapshot().isEmpty())
        pending.add(fresh.copy(sequence = 2, positionMs = 10000, intentOrder = 3))
        assertEquals(10000L, pending.snapshot().single().positionMs)
    }
}
