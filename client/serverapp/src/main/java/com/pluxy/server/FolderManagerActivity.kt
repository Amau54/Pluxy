package com.pluxy.server

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

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
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }
        root.addView(TextView(this).apply {
            text = theme.label
            textSize = 22f
            setTextColor(getColor(R.color.pluxy_text))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        root.addView(TextView(this).apply {
            text = "Maintenez la poignée pour changer l’ordre, ou touchez-la pour déplacer un dossier. Masquer conserve les fichiers."
            textSize = 14f
            setTextColor(getColor(R.color.pluxy_muted))
            setPadding(0, dp(6), 0, dp(12))
        })
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@FolderManagerActivity)
            clipToPadding = false
        }
        val dragHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0,
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean = moveFolder(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        })
        adapter = FolderAdapter(
            folderName = ::displayName,
            onVisibility = { index, visible -> folders[index] = folders[index].copy(visible = visible); save() },
            onRename = ::rename,
            onDelete = ::delete,
            onStartDrag = dragHelper::startDrag,
            onMove = ::moveFolder,
        )
        adapter.submit(folders)
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        root.addView(Button(this).apply {
            text = "+ Ajouter un dossier"
            isAllCaps = false
            textSize = 14f
            minHeight = dp(48)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setTextColor(getColor(R.color.pluxy_text))
            setBackgroundResource(R.drawable.bg_button_secondary)
            setOnClickListener { picker.launch(null) }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })
        setContentView(root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(dp(20) + bars.left, dp(20) + bars.top, dp(20) + bars.right, dp(16) + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        dragHelper.attachToRecyclerView(list)
    }

    private fun moveFolder(from: Int, to: Int): Boolean {
        if (from !in folders.indices || to !in folders.indices || from == to) return false
        folders.add(to, folders.removeAt(from))
        adapter.move(from, to)
        save(refresh = false)
        return true
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

    private fun save(refresh: Boolean = true) {
        preferences.saveFolders(theme, folders)
        if (refresh) adapter.submit(folders)
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
        private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
        private val onMove: (Int, Int) -> Boolean,
    ) : RecyclerView.Adapter<FolderAdapter.Holder>() {
        private var values: List<MediaFolder> = emptyList()

        fun submit(newValues: List<MediaFolder>) {
            values = newValues.toList()
            notifyDataSetChanged()
        }

        fun move(from: Int, to: Int) {
            values = values.toMutableList().apply { add(to, removeAt(from)) }
            notifyItemMoved(from, to)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val context = parent.context
            val largeText = context.resources.configuration.fontScale > 1.2f &&
                context.resources.configuration.screenWidthDp < 400
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_glass_card)
                setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(context, 6) }
            }
            val heading = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val handle = TextView(context).apply {
                text = "☰"
                textSize = 17f
                setTextColor(context.getColor(R.color.pluxy_accent_bright))
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                setBackgroundResource(R.drawable.bg_button_quiet)
            }
            heading.addView(handle, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)))
            val title = TextView(context).apply {
                textSize = 15f
                setTextColor(context.getColor(R.color.pluxy_text))
                includeFontPadding = false
            }
            heading.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(context, 8)
            })
            row.addView(heading, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            val visible = SwitchCompat(context).apply {
                text = if (largeText) "Visible dans la bibliothèque" else ""
                textSize = 14f
                minHeight = dp(context, 48)
                minimumWidth = dp(context, 48)
                setPadding(dp(context, 4), 0, dp(context, 4), 0)
                setBackgroundResource(R.drawable.bg_button_quiet)
                setTextColor(context.getColor(R.color.pluxy_muted))
            }
            val actions = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (largeText) row.addView(visible, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )) else actions.addView(visible, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(context, 8) })
            val rename = smallButton(context, "Renommer")
            actions.addView(rename, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val delete = smallButton(context, "Retirer")
            actions.addView(delete, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(context, 8)
            })
            row.addView(actions, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            return Holder(row, title, visible, rename, delete, handle).also { holder ->
                handle.setOnLongClickListener {
                    onStartDrag(holder)
                    true
                }
            }
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val folder = values[position]
            val name = folderName(folder)
            holder.title.text = name
            holder.handle.contentDescription = "Déplacer $name"
            holder.visible.contentDescription = "Afficher $name dans la bibliothèque"
            holder.rename.contentDescription = "Renommer $name"
            holder.delete.contentDescription = "Retirer $name de la bibliothèque"
            holder.visible.setOnCheckedChangeListener(null)
            holder.visible.isChecked = folder.visible
            holder.visible.setOnCheckedChangeListener { _, checked ->
                holder.bindingAdapterPosition.takeIf { it in values.indices }?.let { onVisibility(it, checked) }
            }
            holder.rename.setOnClickListener {
                holder.bindingAdapterPosition.takeIf { it in values.indices }?.let(onRename)
            }
            holder.delete.setOnClickListener {
                holder.bindingAdapterPosition.takeIf { it in values.indices }?.let(onDelete)
            }
            holder.handle.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index !in values.indices) return@setOnClickListener
                PopupMenu(holder.handle.context, holder.handle).apply {
                    menu.add(0, MOVE_UP, 0, "Déplacer vers le haut").isEnabled = index > 0
                    menu.add(0, MOVE_DOWN, 1, "Déplacer vers le bas").isEnabled = index < values.lastIndex
                    setOnMenuItemClickListener { item ->
                        val current = holder.bindingAdapterPosition
                        val target = current + if (item.itemId == MOVE_UP) -1 else 1
                        onMove(current, target)
                    }
                    show()
                }
            }
        }

        override fun getItemCount(): Int = values.size

        class Holder(
            view: LinearLayout,
            val title: TextView,
            val visible: SwitchCompat,
            val rename: Button,
            val delete: Button,
            val handle: TextView,
        ) : RecyclerView.ViewHolder(view)

        companion object {
            private const val MOVE_UP = 1
            private const val MOVE_DOWN = 2
            private fun smallButton(context: android.content.Context, label: String) = Button(context).apply {
                text = label
                isAllCaps = false
                textSize = 14f
                minHeight = dp(context, 48)
                setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4))
                minWidth = 0
                minimumWidth = 0
                setTextColor(context.getColor(R.color.pluxy_text))
                setBackgroundResource(R.drawable.bg_button_quiet)
            }

            private fun dp(context: android.content.Context, value: Int): Int =
                (value * context.resources.displayMetrics.density).toInt()
        }
    }

    companion object {
        const val EXTRA_THEME = "theme"
    }
}
