package com.pluxy.tv.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.View
import java.text.Collator

/** Le choix du lecteur est partagé par les fiches des films et des séries. */
object TrailerPlayer {
    private const val ASK = "ask"
    private const val PROBE = "https://www.youtube.com/watch?v=nLJR2twdK00"
    private fun prefs(context: Context) = context.getSharedPreferences("trailer_player", Context.MODE_PRIVATE)
    private fun selected(context: Context) = prefs(context).getString("package", null)
    private fun alwaysAsk(context: Context) = prefs(context).getBoolean("always_ask", false)

    private data class Player(val packageName: String, val label: String, val intent: Intent)

    private fun players(context: Context, links: TrailerLinks): List<Player> {
        val manager = context.packageManager
        val results = linkedMapOf<String, Player>()
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(links.web)).addCategory(Intent.CATEGORY_BROWSABLE)
        val flags = PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_ALL
        fun available(info: android.content.pm.ActivityInfo) = info.exported && info.enabled &&
            info.applicationInfo.enabled && info.packageName != context.packageName &&
            (info.permission == null || context.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED)
        // Les liens vnd.youtube découvrent aussi les lecteurs sans lien web approuvé par Android.
        for (url in listOf(links.web, links.video)) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            val matches = manager.queryIntentActivities(intent, flags)
            for (match in matches) {
                val info = match.activityInfo ?: continue
                if (!available(info)) continue
                if (info.packageName in results) continue
                // Une requête limitée à l'application évite le filtrage des domaines non approuvés.
                val webActivity = manager.queryIntentActivities(Intent(webIntent).setPackage(info.packageName), flags)
                    .firstOrNull { it.activityInfo?.let(::available) == true }?.activityInfo
                val target = webActivity ?: info
                results[info.packageName] = Player(
                    info.packageName,
                    info.applicationInfo.loadLabel(manager).toString(),
                    Intent(if (webActivity != null) webIntent else intent)
                        .setComponent(ComponentName(info.packageName, target.name)),
                )
            }
        }
        val collator = Collator.getInstance()
        return results.values.sortedWith { left, right -> collator.compare(left.label, right.label) }
    }

    fun bind(activity: Activity, button: View, url: String?) {
        val links = TrailerLinks.from(url)
        button.visibility = if (links == null) View.GONE else View.VISIBLE
        button.setOnClickListener(if (links == null) null else View.OnClickListener {
            open(activity, button, links)
        })
        button.setOnLongClickListener(if (links == null) null else View.OnLongClickListener {
            chooseForPlayback(activity, button, links)
            true
        })
    }

    fun preferenceLabel(context: Context): String {
        if (alwaysAsk(context)) return "Toujours demander"
        return players(context, TrailerLinks.from(PROBE)!!).firstOrNull { it.packageName == selected(context) }
            ?.label ?: "Choisir une application"
    }

    fun choosePreference(activity: Activity, anchor: View, onChanged: () -> Unit) {
        val installed = players(activity, TrailerLinks.from(PROBE)!!)
        CompactChoiceMenu.show(activity, anchor, "Lecteur des bandes-annonces",
            choices(installed) + CompactChoiceMenu.Choice("Toujours demander", ASK),
            if (alwaysAsk(activity)) ASK else selected(activity).orEmpty(),
            onSelect = { value ->
                prefs(activity).edit().apply {
                    putBoolean("always_ask", value == ASK)
                    if (value == ASK) remove("package") else putString("package", value)
                }.apply()
                onChanged()
            })
    }

    private fun open(activity: Activity, anchor: View, links: TrailerLinks) {
        val installed = players(activity, links)
        val saved = selected(activity)
        val player = installed.firstOrNull { it.packageName == saved }
        if (!alwaysAsk(activity) && player != null) {
            launch(activity, anchor, links, player)
        } else {
            if (saved != null && player == null) prefs(activity).edit().remove("package").apply()
            chooseForPlayback(activity, anchor, links, installed)
        }
    }

    private fun choices(installed: List<Player>) = installed.map { player ->
        val duplicateLabel = installed.count { it.label == player.label } > 1
        CompactChoiceMenu.Choice(player.label, player.packageName, if (duplicateLabel) player.packageName else null)
    }

    private fun chooseForPlayback(
        activity: Activity, anchor: View, links: TrailerLinks,
        installed: List<Player> = players(activity, links),
    ) {
        if (installed.isEmpty()) {
            InAppNotice.makeText(activity, "Aucune application compatible installée pour les bandes-annonces.", InAppNotice.LENGTH_LONG).show()
            return
        }
        CompactChoiceMenu.show(activity, anchor, "Lire la bande-annonce avec", choices(installed),
            selected(activity).orEmpty(), onSelect = { value ->
                installed.firstOrNull { it.packageName == value }?.let { launch(activity, anchor, links, it) }
            })
    }

    private fun launch(activity: Activity, anchor: View, links: TrailerLinks, player: Player) {
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            activity.startActivity(player.intent)
            if (!alwaysAsk(activity)) prefs(activity).edit().putString("package", player.packageName).apply()
        } catch (_: ActivityNotFoundException) {
            unavailable(activity, anchor, links, player)
        } catch (_: SecurityException) {
            unavailable(activity, anchor, links, player)
        }
    }

    private fun unavailable(activity: Activity, anchor: View, links: TrailerLinks, player: Player) {
        if (selected(activity) == player.packageName) prefs(activity).edit().remove("package").apply()
        InAppNotice.makeText(activity, "Ce lecteur est indisponible. Choisissez une autre application.", InAppNotice.LENGTH_LONG).show()
        chooseForPlayback(activity, anchor, links, players(activity, links).filter { it.packageName != player.packageName })
    }
}
