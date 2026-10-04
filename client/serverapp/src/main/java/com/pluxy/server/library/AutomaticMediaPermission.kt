package com.pluxy.server.library

/** A permission-filtered MediaStore cursor is not a complete catalogue. */
internal object AutomaticMediaPermission {
    fun required(apiLevel: Int, kind: LibrarySourceKind): String = if (apiLevel < 33) {
        "android.permission.READ_EXTERNAL_STORAGE"
    } else when (kind) {
        LibrarySourceKind.VIDEO -> "android.permission.READ_MEDIA_VIDEO"
        LibrarySourceKind.PHOTO -> "android.permission.READ_MEDIA_IMAGES"
        LibrarySourceKind.AUDIO -> "android.permission.READ_MEDIA_AUDIO"
    }
}
