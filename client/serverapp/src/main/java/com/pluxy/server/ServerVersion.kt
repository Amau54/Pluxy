package com.pluxy.server

import android.content.Context

internal fun serverApplicationVersion(context: Context): String {
    @Suppress("DEPRECATION")
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return info.versionName.orEmpty().ifBlank { "unknown" }
}
