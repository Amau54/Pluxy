package com.pluxy.server.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDoneException
import android.database.sqlite.SQLiteOpenHelper
import android.database.DatabaseErrorHandler
import android.database.DefaultDatabaseErrorHandler
import android.util.Log
import java.io.File
import org.json.JSONObject

internal class LibraryIndex(context: Context) : SQLiteOpenHelper(context, "library.sqlite3", null, 1, DatabaseErrorHandler { db ->
    // Conserver l'index avant la récupération standard d'Android.
    val file = File(db.path)
    file.copyTo(File(file.parentFile, file.name + ".invalid-" + System.currentTimeMillis()))
    DefaultDatabaseErrorHandler().onCorruption(db)
}) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE media (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE state (ready INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun load(): List<MediaEntry>? {
        val db = readableDatabase
        db.rawQuery("SELECT ready FROM state", null).use { if (!it.moveToFirst()) return null }
        return buildList {
            db.rawQuery("SELECT payload FROM media ORDER BY id", null).use { rows ->
                while (rows.moveToNext()) {
                    try { add(MediaEntry.fromPersistedJson(JSONObject(rows.getString(0)))) }
                    catch (error: org.json.JSONException) { Log.w("PluxyIndex", "Entrée invalide ignorée", error) }
                }
            }
        }
    }

    fun save(items: List<MediaEntry>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("CREATE TEMP TABLE IF NOT EXISTS present (id TEXT PRIMARY KEY)")
            db.execSQL("DELETE FROM present")
            db.compileStatement("SELECT payload FROM media WHERE id=?").use { read ->
                db.compileStatement("INSERT OR REPLACE INTO media VALUES (?,?)").use { write ->
                    db.compileStatement("INSERT OR IGNORE INTO present VALUES (?)").use { present ->
                        items.forEach { item ->
                            val payload = item.persistedJson().toString()
                            read.bindString(1, item.id)
                            val old = try { read.simpleQueryForString() } catch (_: SQLiteDoneException) { null }
                            if (old != payload) { write.bindString(1, item.id); write.bindString(2, payload); write.executeInsert() }
                            present.bindString(1, item.id); present.executeInsert()
                        }
                    }
                }
            }
            db.execSQL("DELETE FROM media WHERE id NOT IN (SELECT id FROM present)")
            db.execSQL("DELETE FROM state")
            db.execSQL("INSERT INTO state VALUES (1)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
