package com.pluxy.tv.ui

import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pluxy.tv.api.MediaItem
import com.pluxy.tv.api.PluxyApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object MetadataMatchDialog {
    fun show(activity: AppCompatActivity, item: MediaItem, changed: () -> Unit) {
        val api = PluxyApi.create()
        var work: Job? = null
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val form = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, 0, padding, padding) }
        val search = EditText(activity).apply {
            isSingleLine = true; hint = "Titre du film ou de la série"
            filters = arrayOf(android.text.InputFilter.LengthFilter(160))
            setText(item.seriesName ?: item.metadataTitle ?: item.title)
        }
        val status = TextView(activity).apply { text = "Le choix sera conservé lors des prochains scans." }
        val results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { addView(results); visibility = android.view.View.GONE }
        fun action(label: String) = Button(activity).apply {
            text = label; isAllCaps = false
            setBackgroundResource(com.pluxy.tv.R.drawable.bg_btn_secondary)
            setTextColor(androidx.core.content.ContextCompat.getColor(activity, com.pluxy.tv.R.color.btn_secondary_text))
            minHeight = (48 * activity.resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
        }
        val find = action("Rechercher")
        form.addView(search); form.addView(find); form.addView(status)
        form.addView(scroll, LinearLayout.LayoutParams(-1, (240 * activity.resources.displayMetrics.density).toInt()))
        val dialog = AlertDialog.Builder(activity).setTitle("Corriger les informations").setView(form).setNegativeButton("Fermer", null).create()
        find.setOnClickListener {
            if (search.text.toString().trim().isBlank()) { search.error = "Saisissez un titre"; return@setOnClickListener }
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(search.windowToken, 0)
            find.requestFocus()
            work?.cancel()
            work = activity.lifecycleScope.launch {
                find.isEnabled = false; status.text = "Recherche…"; results.removeAllViews(); scroll.visibility = android.view.View.GONE
                try {
                    val matches = api.searchMatches(item.id, search.text.toString().trim())
                    status.text = if (matches.isEmpty()) "Aucun résultat. Essayez un autre titre." else "Choisissez la bonne correspondance."
                    scroll.visibility = if (matches.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
                    matches.forEach { match ->
                        results.addView(action(listOf(match.title, match.year).filter(String::isNotBlank).joinToString(" · ")).apply {
                            setOnClickListener {
                                work?.cancel()
                                work = activity.lifecycleScope.launch {
                                    status.text = "Enregistrement…"; find.isEnabled = false
                                    for (index in 0 until results.childCount) results.getChildAt(index).isEnabled = false
                                    try { api.chooseMatch(item.id, match.id); CatalogueRepository.invalidate(api.baseUrl); changed(); dialog.dismiss() }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { status.text = e.message ?: "Correspondance indisponible" }
                                    finally { find.isEnabled = true; for (index in 0 until results.childCount) results.getChildAt(index).isEnabled = true }
                                }
                            }
                        })
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { status.text = e.message ?: "Vérifiez la configuration TMDB du serveur." }
                finally { find.isEnabled = true }
            }
        }
        dialog.setOnDismissListener { work?.cancel(); api.close() }
        dialog.show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION)
            find.requestFocus() else search.requestFocus()
    }
}
