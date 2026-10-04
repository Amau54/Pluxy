package com.pluxy.tv.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LibraryPaginationTest {
    private fun item(id: String) = MediaItem(id, id, "", "mp4", 1, 1.0)

    @Test fun multiplePagesKeepTheSameRevisionAndEveryItem() = runBlocking {
        val requests = mutableListOf<Pair<Int, String?>>()
        val result = collectLibraryPages { offset, revision ->
            requests += offset to revision
            if (offset == 0) LibraryPage(listOf(item("a"), item("b")), 3, "rev", 2)
            else LibraryPage(listOf(item("c")), 3, "rev", null)
        }
        assertEquals(listOf("a", "b", "c"), result.map { it.id })
        assertEquals(listOf(0 to null, 2 to "rev"), requests)
    }

    @Test fun changedRevisionCannotPublishAMixedSnapshot() = runBlocking {
        val error = runCatching {
            collectLibraryPages { offset, _ ->
                if (offset == 0) LibraryPage(listOf(item("a")), 2, "old", 1)
                else LibraryPage(listOf(item("b")), 2, "new", null)
            }
        }.exceptionOrNull()
        assertEquals(409, (error as ApiException).code)
    }

    @Test fun missingTailAndDuplicateItemsAreRejected() = runBlocking {
        for (second in listOf(emptyList(), listOf(item("a")))) {
            val error = runCatching {
                collectLibraryPages { offset, _ ->
                    if (offset == 0) LibraryPage(listOf(item("a")), 2, "rev", 1)
                    else LibraryPage(second, 2, "rev", null)
                }
            }.exceptionOrNull()
            assertEquals(409, (error as ApiException).code)
        }
    }

    @Test fun emptyNonAdvancingPageCannotLoopForever() = runBlocking {
        val error = runCatching {
            collectLibraryPages { _, _ -> LibraryPage(emptyList(), 3, "rev", 0) }
        }.exceptionOrNull()
        assertEquals(409, (error as ApiException).code)
    }
}
