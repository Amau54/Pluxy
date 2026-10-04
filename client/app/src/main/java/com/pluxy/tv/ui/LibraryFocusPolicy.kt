package com.pluxy.tv.ui

internal enum class LibraryFocusIntent { KEEP, RESTORE, FIRST }

/** Restoring a saved target takes precedence over the initial-load fallback. */
internal fun libraryFocusIntent(
    restoreRequested: Boolean,
    firstRequested: Boolean,
    hasSavedTarget: Boolean,
    navigatedSinceRequest: Boolean,
    gridHasFocus: Boolean,
): LibraryFocusIntent = when {
    navigatedSinceRequest && !gridHasFocus -> LibraryFocusIntent.KEEP
    restoreRequested && hasSavedTarget -> LibraryFocusIntent.RESTORE
    firstRequested && !navigatedSinceRequest -> LibraryFocusIntent.FIRST
    else -> LibraryFocusIntent.KEEP
}

/** An explicit search/restoration can focus its recovery action until the user navigates away. */
internal fun shouldFocusLibraryEmptyAction(
    restoreRequested: Boolean,
    firstRequested: Boolean,
    navigatedSinceRequest: Boolean,
    gridHasFocus: Boolean,
    hasCurrentFocus: Boolean,
): Boolean = gridHasFocus ||
    (!navigatedSinceRequest && (restoreRequested || firstRequested || !hasCurrentFocus))
