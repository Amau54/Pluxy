package com.pluxy.server.updates

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateReleaseTest {
    private val hash = "a".repeat(64)
    private fun manifest() = JSONObject("""{"schema":1,"channel":"debug","artifacts":[{"name":"Pluxy-debug.apk","package":"com.pluxy.tv","version_name":"1.7.24","version_code":78,"min_sdk":23,"sha256":"$hash","signing_cert_sha256":["$hash"]}]}""")
    @Test fun publishedApkKeepsItsPackageVersionAndSignature() {
        val release = UpdateRelease.parse(manifest().toString(), "com.pluxy.tv", 23)
        assertEquals(78L, release.code); assertEquals("1.7.24", release.version)
        assertEquals(setOf(hash), release.certificates)
    }
    @Test fun wrongPackageUnsupportedAndroidAndAmbiguousArtifactsAreRejected() {
        assertThrows(Exception::class.java) { UpdateRelease.parse(manifest().toString(), "com.other.app", 36) }
        assertThrows(Exception::class.java) { UpdateRelease.parse(manifest().toString(), "com.pluxy.tv", 22) }
        val duplicate = manifest().apply { getJSONArray("artifacts").put(getJSONArray("artifacts").getJSONObject(0)) }
        assertThrows(Exception::class.java) { UpdateRelease.parse(duplicate.toString(), "com.pluxy.tv", 36) }
    }
    @Test fun incompleteOrDifferentChannelMetadataCannotTriggerInstallation() {
        val badHash = manifest().apply { getJSONArray("artifacts").getJSONObject(0).put("sha256", "bad") }
        assertThrows(Exception::class.java) { UpdateRelease.parse(badHash.toString(), "com.pluxy.tv", 36) }
        val noCert = manifest().apply { getJSONArray("artifacts").getJSONObject(0).remove("signing_cert_sha256") }
        assertThrows(Exception::class.java) { UpdateRelease.parse(noCert.toString(), "com.pluxy.tv", 36) }
        assertThrows(Exception::class.java) { UpdateRelease.parse(manifest().put("channel", "release").toString(), "com.pluxy.tv", 36) }
    }
}
