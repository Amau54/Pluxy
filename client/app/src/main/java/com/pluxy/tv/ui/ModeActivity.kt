package com.pluxy.tv.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.pluxy.server.ApplicationMode
import com.pluxy.server.ServerPreferences
import com.pluxy.server.ServerService
import com.pluxy.tv.R

/** Point d'entrée unique : le choix reste enregistré jusqu'au changement dans les options. */
class ModeActivity : ThemedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = ApplicationMode.read(this)
        if (mode != null && !intent.getBooleanExtra(ApplicationMode.EXTRA_CHOOSE_MODE, false)) {
            open(mode)
            return
        }
        val television = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            val padding = resources.getDimensionPixelSize(R.dimen.screen_pad)
            setPadding(padding, padding, padding, padding)
        }
        val content = FrameLayout(this).apply {
            addView(column, FrameLayout.LayoutParams(if (television) dp(608) else -1, -2, Gravity.CENTER))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            fitsSystemWindows = true
            setBackgroundResource(ambientBackgroundResource())
            addView(content)
        }
        setContentView(scroll)
        column.addView(TextView(this).apply {
            text = "Pluxy"
            textSize = if (television) 30f else 26f
            setTextColor(pluxyColor(R.color.text_primary))
        })
        column.addView(TextView(this).apply {
            text = "Comment veux-tu utiliser cet appareil ?"
            textSize = 16f
            setTextColor(pluxyColor(R.color.text_secondary))
            setPadding(0, dp(8), 0, dp(20))
        })
        fun choice(value: ApplicationMode, label: String, explanation: String): Button {
            return Button(this).apply {
                text = SpannableString("$label\n$explanation").apply {
                    setSpan(StyleSpan(Typeface.BOLD), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(RelativeSizeSpan(0.88f), label.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                contentDescription = "$label. $explanation"
                isAllCaps = false
                textSize = if (television) 17f else 15f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(pluxyColor(R.color.text_primary))
                setBackgroundResource(R.drawable.focus_card)
                stateListAnimator = null
                setPadding(dp(16), dp(12), dp(16), dp(12))
                minHeight = dp(80)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
                setOnClickListener {
                    ApplicationMode.save(this@ModeActivity, value)
                    if (value == ApplicationMode.READER) {
                        ServerPreferences(this@ModeActivity).runningDesired = false
                        stopService(Intent(this@ModeActivity, ServerService::class.java))
                    }
                    open(value, resetTask = true)
                }
                column.addView(this)
            }
        }
        val reader = choice(ApplicationMode.READER, "Lecteur", "Lire les médias d’un serveur Pluxy.")
        val server = choice(ApplicationMode.SERVER, "Serveur", "Partager les médias de cet appareil.")
        column.addView(TextView(this).apply {
            text = "Tu pourras changer de mode dans les options."
            textSize = 13f
            setTextColor(pluxyColor(R.color.text_secondary))
            setPadding(0, dp(6), 0, 0)
        })
        if (television) (if (mode == ApplicationMode.SERVER) server else reader).requestFocus()
    }

    private fun open(mode: ApplicationMode, resetTask: Boolean = false) {
        val target = if (mode == ApplicationMode.SERVER) com.pluxy.server.MainActivity::class.java else MainActivity::class.java
        startActivity(Intent(this, target).apply {
            if (resetTask) flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
