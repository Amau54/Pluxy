package com.pluxy.tv.ui

import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pluxy.tv.PluxyApplication
import com.pluxy.tv.R

/** Préférences locales, entièrement utilisables avec les flèches et OK. */
class HomePreferencesActivity : ThemedActivity() {
    private val shelfNames = mapOf(
        "continue" to "Reprendre",
        "up_next" to "À suivre",
        "recent" to "Ajouts récents",
        "movies" to "Films",
        "series" to "Séries",
    )
    private lateinit var adapter: ShelfAdapter
    private lateinit var list: RecyclerView
    private val focusRequests = RecyclerFocusRequester()
    private var order: List<String> = emptyList()
    private val hidden = linkedSetOf<String>()
    private var focusedTag: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        order = PluxyApplication.homeShelfOrder(this)
        hidden += PluxyApplication.hiddenHomeShelves(this)
        focusedTag = savedInstanceState?.getString(STATE_FOCUS)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_tv_ambient)
            val safe = resources.getDimensionPixelSize(R.dimen.screen_pad)
            setPadding(safe, dp(24), safe, dp(20))
        }
        root.addView(TextView(this).apply {
            text = "Personnaliser l’accueil"
            textSize = if (isTelevision(this@HomePreferencesActivity)) 26f else 24f
            setTextColor(pluxyColor(R.color.text_primary))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        root.addView(TextView(this).apply {
            text = "Choisissez les rubriques à afficher. Les flèches changent leur ordre."
            textSize = if (isTelevision(this@HomePreferencesActivity)) 16f else 14f
            setTextColor(pluxyColor(R.color.text_secondary))
            setPadding(0, dp(6), 0, dp(14))
        })
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@HomePreferencesActivity)
            clipChildren = false
            clipToPadding = false
        }
        adapter = ShelfAdapter(
            onVisible = { id, visible ->
                if (visible) hidden.remove(id) else hidden.add(id)
                save(controlTag(id, "visible"))
            },
            onMove = { id, direction ->
                val moved = movedShelfOrder(order, id, direction)
                if (moved != order) {
                    order = moved
                    save(controlTag(id, if (direction < 0) "up" else "down"))
                }
            },
            onFocused = { focusedTag = it },
        )
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        setContentView(root)
        render(focusedTag ?: order.firstOrNull()?.let { controlTag(it, "visible") })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_FOCUS, currentFocus?.tag as? String ?: focusedTag)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        focusRequests.cancel()
        super.onDestroy()
    }

    private fun save(targetTag: String) {
        PluxyApplication.saveHomeShelves(this, order, hidden)
        render(targetTag)
    }

    private fun render(targetTag: String?) {
        val rows = order.mapIndexed { index, id ->
            ShelfPreference(id, shelfNames[id] ?: id, index + 1, id !in hidden, index > 0, index < order.lastIndex)
        }
        adapter.submitList(rows) {
            if (isFinishing || isDestroyed || targetTag == null) return@submitList
            val id = rows.firstOrNull { targetTag.startsWith("shelf:${it.id}:") }?.id
                ?: return@submitList
            val row = adapter.currentList.firstOrNull { it.id == id } ?: return@submitList
            val usableTag = when {
                targetTag == controlTag(id, "up") && !row.canMoveUp -> controlTag(id, "visible")
                targetTag == controlTag(id, "down") && !row.canMoveDown -> controlTag(id, "visible")
                else -> targetTag
            }
            focusRequests.request(list, position = { adapter.currentList.indexOfFirst { it.id == id } }) {
                it.findViewWithTag<View>(usableTag)?.requestFocus() == true
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class ShelfPreference(
        val id: String,
        val label: String,
        val ordinal: Int,
        val visible: Boolean,
        val canMoveUp: Boolean,
        val canMoveDown: Boolean,
    )

    private class ShelfAdapter(
        private val onVisible: (String, Boolean) -> Unit,
        private val onMove: (String, Int) -> Unit,
        private val onFocused: (String) -> Unit,
    ) : ListAdapter<ShelfPreference, ShelfAdapter.Holder>(DIFF) {
        init { setHasStableIds(true) }

        override fun getItemId(position: Int): Long = stableLongId("preference:${getItem(position).id}")

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val context = parent.context
            val television = isTelevision(context)
            val wide = context.resources.configuration.screenWidthDp >= 600 ||
                context.resources.configuration.fontScale <= 1.2f
            val row = LinearLayout(context).apply {
                orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                val p = dp(context, 8)
                setPadding(p, dp(context, 3), p, dp(context, 3))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(context, 4) }
            }
            val label = TextView(context).apply {
                textSize = if (television) 17f else 15f
                setTextColor(context.pluxyColor(R.color.text_primary))
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
            }
            row.addView(label, if (wide) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            } else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val controls = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(controls, if (wide) LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val toggle = SwitchCompat(context).apply {
                isFocusable = true
                showText = false
                setBackgroundResource(R.drawable.bg_setting_row)
                setPadding(dp(context, 4), 0, dp(context, 4), 0)
                minHeight = dp(context, 48)
            }
            controls.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 48)).apply {
                if (!wide) weight = 1f
            })
            val moves = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            controls.addView(moves)
            fun moveButton(text: String) = Button(context).apply {
                this.text = text
                textSize = if (television) 18f else 16f
                isAllCaps = false
                setTextColor(context.getColorStateList(R.color.tab_text))
                setBackgroundResource(R.drawable.bg_setting_row)
                stateListAnimator = null
                minHeight = dp(context, 48)
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
            }
            val up = moveButton("↑")
            val down = moveButton("↓")
            moves.addView(up, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)))
            moves.addView(down, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)))
            return Holder(row, label, toggle, up, down)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            holder.shelfId = item.id
            holder.label.text = "${item.ordinal}. ${item.label}"
            holder.toggle.setOnCheckedChangeListener(null)
            holder.toggle.isChecked = item.visible
            holder.toggle.text = ""
            holder.toggle.contentDescription = "${item.label}, ${if (item.visible) "affichée" else "masquée"}. OK pour changer."
            holder.toggle.setOnCheckedChangeListener { _, checked ->
                if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION && holder.shelfId == item.id) {
                    onVisible(item.id, checked)
                }
            }
            holder.up.isEnabled = item.canMoveUp
            holder.down.isEnabled = item.canMoveDown
            holder.up.alpha = if (item.canMoveUp) 1f else .45f
            holder.down.alpha = if (item.canMoveDown) 1f else .45f
            holder.up.contentDescription = "Monter ${item.label}"
            holder.down.contentDescription = "Descendre ${item.label}"
            holder.up.setOnClickListener { onMove(item.id, -1) }
            holder.down.setOnClickListener { onMove(item.id, 1) }
            listOf(holder.toggle to "visible", holder.up to "up", holder.down to "down").forEach { (view, action) ->
                view.tag = controlTag(item.id, action)
                view.setOnFocusChangeListener { _, focused -> if (focused) onFocused(view.tag as String) }
            }
        }

        class Holder(
            view: LinearLayout,
            val label: TextView,
            val toggle: SwitchCompat,
            val up: Button,
            val down: Button,
        ) : RecyclerView.ViewHolder(view) { var shelfId: String? = null }

        companion object {
            private fun dp(context: android.content.Context, value: Int): Int =
                (value * context.resources.displayMetrics.density).toInt()
            private val DIFF = object : DiffUtil.ItemCallback<ShelfPreference>() {
                override fun areItemsTheSame(oldItem: ShelfPreference, newItem: ShelfPreference) = oldItem.id == newItem.id
                override fun areContentsTheSame(oldItem: ShelfPreference, newItem: ShelfPreference) = oldItem == newItem
            }
        }
    }

    companion object {
        private const val STATE_FOCUS = "home_preferences_focus"
        private fun controlTag(id: String, action: String) = "shelf:$id:$action"
        private fun isTelevision(context: android.content.Context): Boolean =
            context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
