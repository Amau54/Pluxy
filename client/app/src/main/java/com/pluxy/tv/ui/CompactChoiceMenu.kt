package com.pluxy.tv.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.ColorUtils
import com.pluxy.tv.R
import kotlin.math.roundToInt

/** Menu de choix commun aux filtres et aux réglages. */
object CompactChoiceMenu {
    data class Choice(val label: String, val value: String, val detail: String? = null, val inlineDetail: Boolean = false)

    data class Action(val label: String, val onClick: () -> Unit)

    fun show(
        activity: Activity,
        anchor: View,
        title: String,
        choices: List<Choice>,
        selectedValue: String,
        actions: List<Action> = emptyList(),
        onSelect: (String) -> Unit,
        onDismiss: (() -> Unit)? = null,
    ) {
        if (activity.isFinishing || activity.isDestroyed || !anchor.isAttachedToWindow) return
        if (choices.isEmpty() && actions.isEmpty()) return

        val television = activity.resources.configuration.uiMode and
            Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
        val restoreAnchorFocus = television || !anchor.isInTouchMode
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val rowHeight = dp(if (television) 52 else 48)
        val gap = dp(8)
        val primary = activity.pluxyColor(R.color.text_primary)
        val secondary = activity.pluxyColor(R.color.text_secondary)
        val accent = activity.pluxyColor(R.color.accent)

        fun shape(top: Int, bottom: Int, border: Int = Color.TRANSPARENT) =
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
                cornerRadius = dp(11).toFloat()
                setStroke(dp(1), border)
            }

        fun rowBackground() = StateListDrawable().apply {
            val focused = shape(
                activity.pluxyColor(R.color.glass_focus_top),
                activity.pluxyColor(R.color.glass_focus_bottom),
                activity.pluxyColor(R.color.border_focus),
            )
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(
                intArrayOf(android.R.attr.state_pressed),
                shape(
                    activity.pluxyColor(R.color.glass_focus_top),
                    activity.pluxyColor(R.color.glass_focus_bottom),
                ),
            )
            addState(
                intArrayOf(android.R.attr.state_selected),
                shape(
                    activity.pluxyColor(R.color.surface_variant),
                    activity.pluxyColor(R.color.surface),
                ),
            )
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gap, gap, gap, gap)
            background = shape(
                ColorUtils.setAlphaComponent(activity.pluxyColor(R.color.glass_top), 245),
                ColorUtils.setAlphaComponent(activity.pluxyColor(R.color.glass_bottom), 248),
                activity.pluxyColor(R.color.border),
            ).apply { cornerRadius = dp(19).toFloat() }
            clipToOutline = true
        }
        ViewCompat.setAccessibilityPaneTitle(root, title)

        val heading = TextView(activity).apply {
            text = title
            textSize = if (television) 14f else 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(secondary)
            setPadding(dp(10), dp(5), dp(10), dp(9))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        ViewCompat.setAccessibilityHeading(heading, true)
        root.addView(heading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val popup = PopupWindow(root, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            isClippingEnabled = true
            elevation = dp(14).toFloat()
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        var selectedRow: View? = null
        choices.forEach { choice ->
            val selected = choice.value == selectedValue
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = rowHeight
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rowBackground()
                isSelected = selected
                isFocusable = true
                isClickable = true
                contentDescription = listOfNotNull(choice.label, choice.detail?.takeIf { it.isNotBlank() }).joinToString(". ")
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = "android.widget.RadioButton"
                        info.isCheckable = true
                        info.isChecked = selected
                    }
                }
                setOnClickListener {
                    popup.dismiss()
                    onSelect(choice.value)
                }
            }
            val text = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            text.addView(TextView(activity).apply {
                this.text = choice.label
                textSize = if (television) 16f else 15f
                setTextColor(primary)
                typeface = Typeface.create(if (selected) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            choice.detail?.takeIf { it.isNotBlank() && !choice.inlineDetail }?.let { detail ->
                text.addView(TextView(activity).apply {
                    this.text = detail
                    textSize = if (television) 13f else 12f
                    setTextColor(secondary)
                    includeFontPadding = false
                    setPadding(0, dp(4), 0, 0)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            choice.detail?.takeIf { it.isNotBlank() && choice.inlineDetail }?.let { detail ->
                row.addView(TextView(activity).apply {
                    this.text = detail
                    textSize = if (television) 13f else 12f
                    setTextColor(secondary)
                    includeFontPadding = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(10)
                })
            }
            row.addView(ImageView(activity).apply {
                setImageResource(R.drawable.menu_compact_check)
                imageTintList = ColorStateList.valueOf(accent)
                visibility = if (selected) View.VISIBLE else View.INVISIBLE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(12) })
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (selected && selectedRow == null) selectedRow = row
        }

        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (actions.isEmpty()) View.GONE else View.VISIBLE
        }
        val actionRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        if (actions.isNotEmpty()) {
            footer.addView(View(activity).apply {
                setBackgroundColor(activity.pluxyColor(R.color.border_subtle))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(6)
                bottomMargin = dp(4)
            })
            actions.forEach { action ->
                actionRow.addView(TextView(activity).apply {
                    text = action.label
                    textSize = if (television) 14f else 13f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    minimumHeight = rowHeight
                    setPadding(dp(6), dp(8), dp(6), dp(8))
                    setTextColor(accent)
                    background = rowBackground()
                    isFocusable = true
                    isClickable = true
                    setOnClickListener {
                        popup.dismiss()
                        action.onClick()
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            footer.addView(actionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun visibleFrame(): Rect {
            val frame = Rect()
            anchor.getWindowVisibleDisplayFrame(frame)
            val decor = activity.window.decorView
            val position = IntArray(2)
            decor.getLocationOnScreen(position)
            val windowFrame = Rect(position[0], position[1], position[0] + decor.width, position[1] + decor.height)
            val insets = ViewCompat.getRootWindowInsets(decor)?.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
            )
            if (insets != null) {
                windowFrame.left += insets.left
                windowFrame.top += insets.top
                windowFrame.right -= insets.right
                windowFrame.bottom -= insets.bottom
            }
            if (frame.isEmpty) frame.set(windowFrame)
            else if (!windowFrame.isEmpty) frame.intersect(windowFrame)
            return frame
        }

        data class Position(val width: Int, val height: Int, val xOffset: Int, val yOffset: Int)

        fun position(): Position {
            val frame = visibleFrame()
            val menuWidth = minOf(dp(if (television) 340 else 300), (frame.width() - gap * 2).coerceAtLeast(1))
            val maximumHeight = minOf(dp(if (television) 420 else 380), (frame.height() - gap * 2).coerceAtLeast(1))
            val contentWidth = (menuWidth - root.paddingLeft - root.paddingRight).coerceAtLeast(1)
            val widthSpec = View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY)
            val naturalHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            heading.measure(widthSpec, naturalHeight)
            footer.measure(widthSpec, naturalHeight)
            list.measure(widthSpec, naturalHeight)
            val fixedHeight = root.paddingTop + root.paddingBottom + heading.measuredHeight +
                if (footer.visibility == View.GONE) 0 else footer.measuredHeight
            scroll.layoutParams.height = minOf(list.measuredHeight, (maximumHeight - fixedHeight).coerceAtLeast(0))
            root.measure(
                View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maximumHeight, View.MeasureSpec.AT_MOST),
            )
            val menuHeight = root.measuredHeight.coerceIn(1, maximumHeight)
            val anchorPosition = IntArray(2)
            anchor.getLocationOnScreen(anchorPosition)
            val left = if (anchor.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                anchorPosition[0] + anchor.width - menuWidth
            } else anchorPosition[0]
            val x = left.coerceIn(frame.left + gap, (frame.right - gap - menuWidth).coerceAtLeast(frame.left + gap))
            val below = anchorPosition[1] + anchor.height + dp(5)
            val above = anchorPosition[1] - dp(5) - menuHeight
            val preferredY = when {
                below + menuHeight <= frame.bottom - gap -> below
                above >= frame.top + gap -> above
                else -> anchorPosition[1] - menuHeight / 2
            }
            val y = preferredY.coerceIn(frame.top + gap, (frame.bottom - gap - menuHeight).coerceAtLeast(frame.top + gap))
            return Position(menuWidth, menuHeight, x - anchorPosition[0], y - anchorPosition[1] - anchor.height)
        }

        var lastPosition = position()
        val observer = anchor.rootView.viewTreeObserver
        val detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                popup.dismiss()
            }
        }
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            if (!anchor.isAttachedToWindow || activity.isFinishing || activity.isDestroyed) {
                popup.dismiss()
            } else if (popup.isShowing) {
                val next = position()
                if (next != lastPosition) {
                    lastPosition = next
                    popup.update(anchor, next.xOffset, next.yOffset, next.width, next.height)
                }
            }
        }
        popup.setOnDismissListener {
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(layoutListener)
            anchor.removeOnAttachStateChangeListener(detachListener)
            if (restoreAnchorFocus && anchor.isAttachedToWindow && !activity.isFinishing && !activity.isDestroyed) anchor.requestFocus()
            onDismiss?.invoke()
        }
        observer.addOnGlobalLayoutListener(layoutListener)
        anchor.addOnAttachStateChangeListener(detachListener)
        popup.width = lastPosition.width
        popup.height = lastPosition.height
        popup.showAsDropDown(anchor, lastPosition.xOffset, lastPosition.yOffset, Gravity.LEFT)
        root.post {
            if (!popup.isShowing) return@post
            val first = selectedRow ?: list.getChildAt(0) ?: actionRow.getChildAt(0)
            first?.requestFocus()
            if (first?.parent === list) scroll.smoothScrollTo(0, (first.top - (scroll.height - first.height) / 2).coerceAtLeast(0))
        }
    }
}
