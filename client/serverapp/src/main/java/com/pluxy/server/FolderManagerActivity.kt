package com.pluxy.server

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

/** Gestion locale des sources : ordre par glisser-déposer, titre et visibilité sans toucher aux fichiers. */
class FolderManagerActivity : AppCompatActivity() {

    private lateinit var preferences: ServerPreferences
    private lateinit var theme: MediaTheme
    private lateinit var adapter: FolderAdapter
    private val folders = mutableListOf<MediaFolder>()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (folders.any { it.uri == uri.toString() }) return@runCatching
            folders += MediaFolder(uri.toString(), defaultName(uri))
            save()
        }.onFailure { Toast.makeText(this, "Impossible d'ajouter ce dossier. Sélectionnez un dossier accessible.", Toast.LENGTH_LONG).show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme = intent.getStringExtra(EXTRA_THEME)
            ?.let { runCatching { MediaTheme.valueOf(it) }.getOrNull() } ?: MediaTheme.VIDEO
        preferences = ServerPreferences(this)
        folders += preferences.folders(theme)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.pluxy_bg))
            setPadding(dp(20), dp(24), dp(20), dp(20))
        }
        root.addView(TextView(this).apply {
            text = theme.label
            textSize = 26f
            setTextColor(getColor(R.color.pluxy_accent))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Maintenez puis glissez un dossier pour changer son ordre. Masquer ignore ce dossier manuel sans supprimer les fichiers."
            textSize = 14f
            setTextColor(getColor(R.color.pluxy_muted))
            setPadding(0, dp(6), 0, dp(12))
        })
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@FolderManagerActivity)
            clipToPadding = false
        }
        adapter = FolderAdapter(
            folderName = ::displayName,
            onVisibility = { index, visible -> folders[index] = folders[index].copy(visible = visible); save() },
            onRename = ::rename,
            onDelete = ::delete,
        )
        adapter.submit(folders)
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        root.addView(Button(this).apply {
            text = "+ Ajouter un dossier"
            isAllCaps = false
            setTextColor(getColor(R.color.pluxy_text))
            setBackgroundResource(R.drawable.bg_button_secondary)
            setOnClickListener { picker.launch(null) }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52),
        ).apply { topMargin = dp(14) })
        setContentView(root)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0,
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                Collections.swap(folders, viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                adapter.notifyItemMoved(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                save()
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        }).attachToRecyclerView(list)
    }

    private fun rename(index: Int) {
        val input = EditText(this).apply {
            setText(folders[index].title)
            hint = "Titre affiché"
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Nom du dossier")
            .setMessage("Laissez vide pour reprendre le nom Android du dossier.")
            .setView(input)
            .setPositiveButton("Enregistrer") { _, _ ->
                folders[index] = folders[index].copy(title = input.text.toString().trim().take(60))
                save()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun delete(index: Int) {
        val name = displayName(folders[index])
        AlertDialog.Builder(this)
            .setTitle("Retirer ce dossier ?")
            .setMessage("« $name » ne sera plus analysé. Aucun fichier ne sera supprimé.")
            .setPositiveButton("Retirer") { _, _ ->
                val removed = folders.removeAt(index)
                save()
                val remaining = MediaTheme.entries.flatMap { preferences.folders(it).map(MediaFolder::uri) }
                if (SafGrantPolicy.canRelease(removed.uri, remaining) && preferences.flushFolders()) {
                    runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(removed.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun save() {
        preferences.saveFolders(theme, folders)
        adapter.submit(folders)
        setResult(RESULT_OK)
    }

    private fun displayName(folder: MediaFolder): String =
        folder.title.ifBlank { defaultName(Uri.parse(folder.uri)) }

    private fun defaultName(uri: Uri): String = runCatching {
        DocumentFile.fromTreeUri(this, uri)?.name
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Dossier Android"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class FolderAdapter(
        private val folderName: (MediaFolder) -> String,
        private val onVisibility: (Int, Boolean) -> Unit,
        private val onRename: (Int) -> Unit,
        private val onDelete: (Int) -> Unit,
    ) : RecyclerView.Adapter<FolderAdapter.Holder>() {
        private var values: List<MediaFolder> = emptyList()

        fun submit(newValues: List<MediaFolder>) {
            values = newValues.toList()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val context = parent.context
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                setBackgroundResource(R.drawable.bg_option)
                setPadding(dp(context, 12), dp(context, 8), dp(context, 10), dp(context, 8))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(context, 10) }
            }
            val handle = TextView(context).apply {
                text = "☰"
                textSize = 22f
                setTextColor(context.getColor(R.color.pluxy_accent_bright))
                gravity = Gravity.CENTER
            }
            row.addView(handle, LinearLayout.LayoutParams(dp(context, 36), dp(context, 54)))
            val title = TextView(context).apply {
                textSize = 16f
                setTextColor(context.getColor(R.color.pluxy_text))
                maxLines = 2
            }
            row.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val visible = SwitchCompat(context).apply { text = ""; contentDescription = "Afficher le dossier" }
            row.addView(visible)
            val rename = smallButton(context, "Nom")
            row.addView(rename, LinearLayout.LayoutParams(dp(context, 76), dp(context, 58)).apply {
                marginStart = dp(context, 8)
            })
            val delete = smallButton(context, "Suppr.")
            row.addView(delete, LinearLayout.LayoutParams(dp(context, 96), dp(context, 58)).apply {
                marginStart = dp(context, 8)
            })
            return Holder(row, title, visible, rename, delete)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val folder = values[position]
            holder.title.text = folderName(folder)
            holder.visible.setOnCheckedChangeListener(null)
            holder.visible.isChecked = folder.visible
            holder.visible.setOnCheckedChangeListener { _, checked -> onVisibility(holder.bindingAdapterPosition, checked) }
            holder.rename.setOnClickListener { onRename(holder.bindingAdapterPosition) }
            holder.delete.setOnClickListener { onDelete(holder.bindingAdapterPosition) }
        }

        override fun getItemCount(): Int = values.size

        class Holder(
            view: LinearLayout,
            val title: TextView,
            val visible: SwitchCompat,
            val rename: Button,
            val delete: Button,
        ) : RecyclerView.ViewHolder(view)

        companion object {
            private fun smallButton(context: android.content.Context, label: String) = Button(context).apply {
                text = label
                isAllCaps = false
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setTextColor(context.getColor(R.color.pluxy_text))
                setBackgroundResource(R.drawable.bg_button_secondary)
            }

            private fun dp(context: android.content.Context, value: Int): Int =
                (value * context.resources.displayMetrics.density).toInt()
        }
    }

    companion object {
        const val EXTRA_THEME = "theme"
    }
}
