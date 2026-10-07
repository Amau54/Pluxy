package com.pluxy.server.library

import java.util.Locale

/** Source choisie explicitement par l'utilisateur dans le sélecteur Android. */
enum class LibrarySourceKind(val publicKind: String) {
    VIDEO("movie"),
    PHOTO("photo"),
    AUDIO("audio"),
}

data class LibrarySource(
    val kind: LibrarySourceKind,
    val treeUri: String,
    /** Titre de collection choisi sur le téléphone, préfixe les dossiers vus sur la TV. */
    val title: String = "",
    val category: String = "auto",
) {
    val isAutomatic: Boolean get() = treeUri == AUTOMATIC_MEDIASTORE_URI

    companion object {
        const val AUTOMATIC_MEDIASTORE_URI = "mediastore://automatic"

        fun automatic(kind: LibrarySourceKind) = LibrarySource(kind, AUTOMATIC_MEDIASTORE_URI)
    }
}

/** Liste blanche volontairement compacte des formats servis par le téléphone. */
object MediaSupport {
    private val videoExtensions = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "webm", "wmv", "flv",
        "mpg", "mpeg",
    )
    private val photoExtensions = setOf(
        "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "dng",
    )
    private val audioExtensions = setOf(
        "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wave", "amr",
        "3gp", "aiff", "aif",
    )

    fun extension(name: String?): String = name.orEmpty()
        .substringAfterLast('.', "")
        .lowercase(Locale.ROOT)

    fun supports(kind: LibrarySourceKind, extension: String): Boolean = when (kind) {
        LibrarySourceKind.VIDEO -> extension in videoExtensions
        LibrarySourceKind.PHOTO -> extension in photoExtensions
        LibrarySourceKind.AUDIO -> extension in audioExtensions
    }

    fun mimeFor(kind: String, container: String): String = when (kind) {
        "photo" -> when (container) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heif"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "dng" -> "image/x-adobe-dng"
            else -> "application/octet-stream"
        }
        "audio" -> when (container) {
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "ogg", "oga", "opus" -> "audio/ogg"
            "wav", "wave" -> "audio/wav"
            "amr" -> "audio/amr"
            "3gp" -> "audio/3gpp"
            "aiff", "aif" -> "audio/aiff"
            else -> "application/octet-stream"
        }
        else -> when (container) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "ts", "m2ts" -> "video/mp2t"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "wmv" -> "video/x-ms-wmv"
            "flv" -> "video/x-flv"
            "mpg", "mpeg" -> "video/mpeg"
            else -> "application/octet-stream"
        }
    }
}
