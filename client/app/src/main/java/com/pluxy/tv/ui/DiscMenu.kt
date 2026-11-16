package com.pluxy.tv.ui

import android.app.Activity
import android.view.View
import com.pluxy.tv.api.DiscCatalogue
import com.pluxy.tv.api.DiscTitle
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Uses the same focus, contrast and sizing as the player's other TV menus. */
object DiscMenu {
    suspend fun choose(activity: Activity, anchor: View, disc: DiscCatalogue): DiscTitle? =
        suspendCancellableCoroutine { continuation ->
            var menu: android.widget.PopupWindow? = null
            menu = CompactChoiceMenu.show(activity, anchor,
                title = "Contenu du disque · qualité originale",
                choices = disc.titles.map { title ->
                    val minutes = (title.duration / 60).toInt()
                    CompactChoiceMenu.Choice(title.label, title.key,
                        "${minutes / 60} h ${minutes % 60} min · ${title.key}")
                },
                selectedValue = disc.defaultTitle,
                dismissOnSelect = false,
                onSelect = { key ->
                    if (continuation.isActive) continuation.resume(disc.titles.firstOrNull { it.key == key })
                    menu?.dismiss()
                },
                onDismiss = { if (continuation.isActive) continuation.resume(null) })
            if (menu == null && continuation.isActive) continuation.resume(null)
            continuation.invokeOnCancellation { anchor.post { menu?.dismiss() } }
        }
}
