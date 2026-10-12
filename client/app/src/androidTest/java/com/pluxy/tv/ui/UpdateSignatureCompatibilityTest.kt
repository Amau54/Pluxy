package com.pluxy.tv.ui

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.pluxy.server.updates.AppUpdates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class UpdateSignatureCompatibilityTest {
    @Test
    fun downloadedArchiveIncludesTheVerifiedInstalledCertificate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val partial = File(context.cacheDir, "signature-check.part")
        try {
            File(context.applicationInfo.sourceDir).copyTo(partial, overwrite = true)
            val flags = AppUpdates::class.java.methods.single { it.name.startsWith("signatureFlags") }
                .invoke(AppUpdates) as Int
            val archive = context.packageManager.getPackageArchiveInfo(partial.absolutePath, flags)
            assertNotNull(archive)
            val installed = context.packageManager.getPackageInfo(context.packageName, flags)
            val certificates = AppUpdates::class.java.methods.single { it.name.startsWith("certificates") }
            val expected = certificates.invoke(AppUpdates, installed) as Set<*>
            assertFalse(expected.isEmpty())
            assertEquals(expected, certificates.invoke(AppUpdates, archive))
        } finally {
            partial.delete()
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun legacySignaturesRemainVerifiedWhenSigningInfoIsMissing() {
        if (Build.VERSION.SDK_INT < 28) return
        val info = PackageInfo().apply {
            signingInfo = null
            signatures = arrayOf(Signature(byteArrayOf(1, 2, 3)))
        }
        // Internal JVM names vary by build variant; invoke the installed implementation.
        val method = AppUpdates::class.java.methods.single { it.name.startsWith("certificates") }
        assertEquals(
            setOf("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"),
            method.invoke(AppUpdates, info),
        )
    }
}
