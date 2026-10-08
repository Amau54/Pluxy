package com.pluxy.server

import android.content.Context

enum class ApplicationMode(val key: String) {
    READER("reader"), SERVER("server");

    companion object {
        const val EXTRA_CHOOSE_MODE = "choose_application_mode"
        fun parse(value: String?): ApplicationMode? = entries.firstOrNull { it.key == value }
        fun read(context: Context): ApplicationMode? = parse(context
            .getSharedPreferences("pluxy_application_mode", Context.MODE_PRIVATE).getString("mode", null))
        fun save(context: Context, mode: ApplicationMode) {
            context.getSharedPreferences("pluxy_application_mode", Context.MODE_PRIVATE)
                .edit().putString("mode", mode.key).apply()
        }
    }
}
