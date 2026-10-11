package com.pluxy.server.library

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedContractTest {
    @Test fun `serveurs Android et PC partagent les titres de reference`() {
        val resource = requireNotNull(javaClass.getResourceAsStream("/filenames.json"))
        val cases = resource.bufferedReader().use { JSONArray(it.readText()) }
        for (index in 0 until cases.length()) {
            val row = cases.getJSONObject(index)
            val parsed = FilenameParser.parse(row.getString("file"))
            assertEquals(row.getString("file"), row.getString("title"), parsed.title)
            assertEquals(if (row.isNull("year")) null else row.getInt("year"), parsed.year)
        }
    }
}
