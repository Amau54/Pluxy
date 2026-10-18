package com.pluxy.tv.ui

import android.content.Intent
import android.view.View
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import com.pluxy.tv.PluxyApplication
import com.pluxy.tv.player.PlayerActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URL

class AutoSkipPlaybackTest {
    @Test fun settingsSwitchIsAvailableAndRemembersItsValue() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val before=PluxyApplication.autoSkipSegments(context)
        val activity=instrumentation.startActivitySync(Intent(context,SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                activity.window.decorView.findViewWithTag<View>("category:playback").performClick()
                activity.window.decorView.findViewWithTag<View>("setting:Passer automatiquement les intros et génériques").performClick()
                assertEquals(!before,PluxyApplication.autoSkipSegments(context))
                activity.window.decorView.findViewWithTag<View>("setting:Passer automatiquement les intros et génériques").performClick()
                assertEquals(before,PluxyApplication.autoSkipSegments(context))
            }
        } finally {
            PluxyApplication.setAutoSkipSegments(context,before)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun realPlayerSkipsIntroAndCreditsButKeepsPostCreditsAndHonorsTheSwitch() {
        val args=InstrumentationRegistry.getArguments()
        val server=args.getString("auto_skip_test_url").orEmpty()
        val id=args.getString("auto_skip_test_item").orEmpty()
        assumeTrue("Requires the isolated 24-second chapter fixture",server.isNotBlank() && id.isNotBlank())
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val json=URL("$server/api/library/items/$id").openConnection().apply {
            connectTimeout=10_000; readTimeout=10_000
        }.getInputStream().bufferedReader().use { it.readText() }
        PluxyApplication.setServer(context,server,"Test passage automatique")
        PluxyApplication.setPlaybackStrategy(context,"direct")
        PluxyApplication.setAutoSkipSegments(context,false)
        val activity=instrumentation.startActivitySync(Intent(context,PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_ITEM,json).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PlayerActivity
        val playerField=PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible=true }
        val seek=PlayerActivity::class.java.getDeclaredMethod("goToAbsolute",Long::class.javaPrimitiveType).apply { isAccessible=true }
        val events=mutableListOf<Long>()
        fun player()=playerField.get(activity) as Player
        fun awaitCondition(message:String,predicate:()->Boolean) {
            val deadline=System.currentTimeMillis()+25_000
            var success=false
            while(System.currentTimeMillis()<deadline && !success) {
                instrumentation.runOnMainSync { success=predicate() }
                if(!success) Thread.sleep(100)
            }
            assertTrue(message,success)
        }
        try {
            awaitCondition("The chapter fixture must play") { (playerField.get(activity) as? Player)?.isPlaying==true }
            instrumentation.runOnMainSync {
                player().addListener(object:Player.Listener {
                    override fun onPositionDiscontinuity(oldPosition:Player.PositionInfo,newPosition:Player.PositionInfo,reason:Int) {
                        if(reason==Player.DISCONTINUITY_REASON_SEEK) events+=newPosition.positionMs
                    }
                })
                player().pause()
                seek.invoke(activity,3_500L)
            }
            awaitCondition("The paused seek must finish") { player().playbackState==Player.STATE_READY && player().currentPosition>=3_400 }
            PluxyApplication.setAutoSkipSegments(context,true)
            Thread.sleep(1_200)
            instrumentation.runOnMainSync {
                assertTrue("Pausing must preserve the intro",player().currentPosition<5_000)
                assertTrue(events.none { it in 5_900..6_200 })
                PluxyApplication.setAutoSkipSegments(context,false)
                player().play()
            }
            Thread.sleep(1_100)
            instrumentation.runOnMainSync {
                assertTrue("Disabled automatic skipping must keep the intro",events.none { it in 5_900..6_200 })
                player().pause()
                seek.invoke(activity,3_500L)
                PluxyApplication.setAutoSkipSegments(context,true)
                player().play()
            }
            awaitCondition("Enabling the switch must seek to the end of the intro") { events.any { it in 5_900..6_200 } }
            instrumentation.runOnMainSync {
                player().pause()
                seek.invoke(activity,18_300L)
                player().play()
            }
            awaitCondition("Credits must jump to 21 seconds, preserving the final scene") { events.any { it in 20_900..21_200 } }
            instrumentation.runOnMainSync {
                player().pause()
                assertTrue(player().currentPosition<24_000)
                assertNotEquals(Player.STATE_ENDED,player().playbackState)
            }
        } finally {
            PluxyApplication.setAutoSkipSegments(context,true)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
