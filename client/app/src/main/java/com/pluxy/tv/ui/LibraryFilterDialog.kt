package com.pluxy.tv.ui

import android.content.Context
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.pluxy.tv.R
import com.pluxy.tv.api.LibraryFacets

/** Choix groupés, applicables d'un seul geste ; annuler laisse la bibliothèque intacte. */
object LibraryFilterDialog {
    fun show(context: Context, original: TvLibraryContent.Query, facets: LibraryFacets, apply: (TvLibraryContent.Query) -> Unit) {
        var draft = original
        val density = context.resources.displayMetrics.density
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
        }
        fun choose(label: String, choices: List<Pair<String, String>>, selected: String, update: (String) -> Unit) {
            AlertDialog.Builder(context).setTitle(label)
                .setSingleChoiceItems(choices.map { it.first }.toTypedArray(), choices.indexOfFirst { it.second == selected }) { dialog, index ->
                    update(choices[index].second)
                    dialog.dismiss()
                }.setNegativeButton("Annuler", null).show()
        }
        fun row(label: String, value: () -> String, click: (Button) -> Unit): Button = Button(context).apply {
            isAllCaps = false
            setBackgroundResource(R.drawable.bg_setting_row)
            setTextColor(context.pluxyColor(R.color.text_primary))
            minHeight = (48 * density).toInt()
            text = "$label · ${value()}"
            setOnClickListener { click(this) }
            rows.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (4 * density).toInt() })
        }
        if (original.destination in setOf("movie", "series", "clip", "home", "all")) {
            val watched = listOf("Tous" to "all", "Non vus" to "unwatched", "Déjà vus" to "watched", "En cours" to "in_progress")
            row("Lecture", { watched.firstOrNull { it.second == draft.watchedFilter }?.first ?: "Tous" }) { button ->
                choose("Lecture", watched, draft.watchedFilter) {
                    draft = draft.copy(watchedFilter = it)
                    button.text = "Lecture · ${watched.first { pair -> pair.second == it }.first}"
                }
            }
            if (original.destination != "clip") row("Genres", { if (draft.genres.isEmpty()) "Tous" else draft.genres.joinToString(", ") }) { button ->
                val genres = (facets.genres + draft.genres).distinct().sorted()
                if (genres.isEmpty()) {
                    AlertDialog.Builder(context).setMessage("Les genres apparaîtront après l’identification des films et séries.").setPositiveButton("Fermer", null).show()
                } else {
                    val selected = draft.genres.toMutableSet()
                    AlertDialog.Builder(context).setTitle("Genres")
                        .setMultiChoiceItems(genres.toTypedArray(), genres.map { it in selected }.toBooleanArray()) { _, index, checked ->
                            if (checked) selected.add(genres[index]) else selected.remove(genres[index])
                        }.setPositiveButton("Choisir") { _, _ ->
                            draft = draft.copy(genres = selected.toSet())
                            button.text = "Genres · ${if (selected.isEmpty()) "Tous" else selected.joinToString(", ")}"
                        }.setNeutralButton("Tous") { _, _ ->
                            draft = draft.copy(genres = emptySet()); button.text = "Genres · Tous"
                        }.setNegativeButton("Annuler", null).show()
                }
            }
            if (original.destination != "clip") row("Année", { draft.year?.toString() ?: "Toutes" }) { button ->
                val years = listOf("Toutes" to "") + (facets.years + listOfNotNull(draft.year)).distinct().sortedDescending().map { it.toString() to it.toString() }
                choose("Année", years, draft.year?.toString().orEmpty()) {
                    draft = draft.copy(year = it.toIntOrNull()); button.text = "Année · ${it.ifBlank { "Toutes" }}"
                }
            }
            val durations = if (original.destination == "clip") listOf("Toutes" to "", "Moins de 1 min" to "1",
                "Moins de 5 min" to "5", "Moins de 15 min" to "15", "Moins de 30 min" to "30")
                else listOf("Toutes" to "", "Moins de 90 min" to "90", "Moins de 2 h" to "120", "Moins de 3 h" to "180")
            row("Durée", { durations.firstOrNull { it.second == draft.durationMaxMinutes?.toString().orEmpty() }?.first ?: "Toutes" }) { button ->
                choose("Durée", durations, draft.durationMaxMinutes?.toString().orEmpty()) {
                    draft = draft.copy(durationMaxMinutes = it.toIntOrNull()); button.text = "Durée · ${durations.first { pair -> pair.second == it }.first}"
                }
            }
            val resolutions = listOf("Toutes" to "all", "4K" to "4k", "1080p" to "1080p", "720p" to "720p", "SD" to "sd")
            row("Résolution", { resolutions.firstOrNull { it.second == draft.resolution }?.first ?: "Toutes" }) { button ->
                choose("Résolution", resolutions, draft.resolution) {
                    draft = draft.copy(resolution = it); button.text = "Résolution · ${resolutions.first { pair -> pair.second == it }.first}"
                }
            }
            rows.addView(SwitchCompat(context).apply {
                text = "HDR uniquement"
                minHeight = (48 * density).toInt()
                isChecked = draft.hdrOnly
                setTextColor(context.pluxyColor(R.color.text_primary))
                setOnCheckedChangeListener { _, checked -> draft = draft.copy(hdrOnly = checked) }
            })
        }
        AlertDialog.Builder(context).setTitle("Filtres")
            .setView(ScrollView(context).apply { addView(rows) })
            .setPositiveButton("Appliquer") { _, _ -> apply(draft) }
            .setNeutralButton("Réinitialiser") { _, _ -> apply(original.copy(watchedFilter = "all", genres = emptySet(), year = null, durationMaxMinutes = null, resolution = "all", hdrOnly = false, titlePrefix = "")) }
            .setNegativeButton("Annuler", null).show()
    }
}
