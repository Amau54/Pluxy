package com.pluxy.tv.ui

import android.content.Context
import com.pluxy.tv.PluxyApplication
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/** Chaque bibliothèque retrouve son affichage et ses filtres sur le serveur choisi. */
object LibraryPreferences {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val queryAdapter = moshi.adapter(TvLibraryContent.Query::class.java)
    private val collectionAdapter = moshi.adapter<List<LibraryCollection>>(
        Types.newParameterizedType(List::class.java, LibraryCollection::class.java),
    )
    private fun prefs(context: Context) = context.getSharedPreferences("library_browser", Context.MODE_PRIVATE)
    private fun key(context: Context, suffix: String, serverUrl: String = PluxyApplication.serverBaseUrl(context)) = "${serverUrl.trimEnd('/')}:$suffix"

    fun query(context: Context, destination: String): TvLibraryContent.Query {
        var query = runCatching { prefs(context).getString(key(context, "query:$destination"), null)?.let(queryAdapter::fromJson) }
            .getOrNull()?.copy(destination = destination, search = "", searchScope = "all")
            ?: initialQuery(context, destination)
        val migration = key(context, "photo-tree-default")
        if (destination == "photo" && !prefs(context).getBoolean(migration, false)) {
            if (query.photoView == "albums") { query = query.copy(photoView = "folders"); saveQuery(context, query) }
            prefs(context).edit().putBoolean(migration, true).apply()
        }
        return query
    }

    private fun initialQuery(context: Context, destination: String): TvLibraryContent.Query {
        val legacy = context.getSharedPreferences("pluxy_prefs", Context.MODE_PRIVATE)
        val selected = PluxyApplication.libraryTab(context)
        val query = TvLibraryContent.Query(destination = destination,
            sort = if (selected == destination) legacy.getString("lib_sort", "title").orEmpty() else "title",
            watchedFilter = if (selected == destination && destination in setOf("movie", "series", "clip")) legacy.getString("lib_watched", "all").orEmpty() else "all")
        saveQuery(context, query)
        return query
    }

    fun saveQuery(context: Context, query: TvLibraryContent.Query) {
        prefs(context).edit().putString(
            key(context, "query:${query.destination}"),
            queryAdapter.toJson(query.copy(search = "", searchScope = "all")),
        ).apply()
    }

    fun photoFolders(context: Context): Pair<Set<String>, Set<String>> =
        prefs(context).getStringSet(key(context, "photo-folders-known"), emptySet()).orEmpty().toSet() to
            prefs(context).getStringSet(key(context, "photo-folders-collapsed"), emptySet()).orEmpty().toSet()

    fun savePhotoFolders(context: Context, known: Set<String>, collapsed: Set<String>) {
        prefs(context).edit().putStringSet(key(context, "photo-folders-known"), known.toSet())
            .putStringSet(key(context, "photo-folders-collapsed"), collapsed.toSet()).apply()
    }

    fun view(context: Context, destination: String): String =
        prefs(context).getString(key(context, "view:$destination"), "library").orEmpty()
            .takeIf { it in setOf("discover", "library", "collections") } ?: "library"

    fun saveView(context: Context, destination: String, view: String) = prefs(context).edit()
        .putString(key(context, "view:$destination"), view).apply()

    fun compact(context: Context): Boolean = prefs(context).getBoolean("compact", true)
    fun setCompact(context: Context, value: Boolean) = prefs(context).edit().putBoolean("compact", value).apply()

    fun collections(context: Context, serverUrl: String = PluxyApplication.serverBaseUrl(context)): List<LibraryCollection> = runCatching {
        prefs(context).getString(key(context, "collections", serverUrl), null)?.let(collectionAdapter::fromJson)
    }.getOrNull().orEmpty()

    fun saveCollections(context: Context, collections: List<LibraryCollection>) = prefs(context).edit()
        .putString(key(context, "collections"), collectionAdapter.toJson(collections)).apply()

    fun lastLibrary(context: Context): String = prefs(context)
        .getString(key(context, "last-library"), "movie").orEmpty()
        .takeIf { it in setOf("movie", "series", "clip", "photo", "audio") } ?: "movie"

    fun rememberLibrary(context: Context, kind: String) {
        if (kind != "home") prefs(context).edit().putString(key(context, "last-library"), kind).apply()
    }
}
