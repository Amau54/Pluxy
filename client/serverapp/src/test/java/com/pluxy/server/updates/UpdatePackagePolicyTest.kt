package com.pluxy.server.updates

import org.junit.Assert.*
import org.junit.Test

class UpdatePackagePolicyTest {
    private val installed = UpdatePackageIdentity("com.pluxy.tv", "1.7.27", 81, setOf("trusted"))
    private val latest = installed.copy(version = "1.7.28", code = 82)

    @Test fun matchingUpgradeIsAcceptedButNoSignatureCheckCanBeSkipped() {
        assertNull(UpdatePackagePolicy.failure(installed, latest, latest))
        assertEquals(UpdatePackageFailure.DIFFERENT_SIGNATURE,
            UpdatePackagePolicy.failure(installed, latest, latest.copy(certificates = setOf("other"))))
        assertEquals(UpdatePackageFailure.DIFFERENT_SIGNATURE,
            UpdatePackagePolicy.failure(installed, latest.copy(certificates = setOf("other")), latest))
        assertEquals(UpdatePackageFailure.UNREADABLE_SIGNATURE,
            UpdatePackagePolicy.failure(installed.copy(certificates = emptySet()), latest, latest))
    }

    @Test fun staleDownloadAndAlreadyInstalledUpdatesHaveDifferentExplanations() {
        assertEquals(UpdatePackageFailure.STALE_DOWNLOAD, UpdatePackagePolicy.failure(installed, latest, installed))
        assertEquals(UpdatePackageFailure.ALREADY_CURRENT, UpdatePackagePolicy.failure(latest, latest, latest))
        assertEquals(UpdatePackageFailure.WRONG_APPLICATION,
            UpdatePackagePolicy.failure(installed, latest, latest.copy(packageName = "com.other")))
    }
}
