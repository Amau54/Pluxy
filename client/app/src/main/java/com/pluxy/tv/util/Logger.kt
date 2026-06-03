package com.pluxy.tv.util

import android.util.Log
import java.util.ArrayDeque

/** Journal en mémoire (anneau) consultable depuis l'écran « Infos & logs ». */
object Logger {
    private const val MAX = 200
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun log(tag: String, msg: String) {
        val t = System.currentTimeMillis() % 100000
        val line = "%05d  [%s] %s".format(t, tag, msg)
        if (lines.size >= MAX) lines.pollFirst()
        lines.addLast(line)
        Log.d("Pluxy/$tag", msg)
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
