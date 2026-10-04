package com.pluxy.server.library

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticMediaPermissionTest {
    @Test fun `Android 23 through 32 require storage permission for every automatic source`() {
        for (api in listOf(23, 28, 29, 32)) {
            for (kind in LibrarySourceKind.entries) {
                assertEquals("android.permission.READ_EXTERNAL_STORAGE", AutomaticMediaPermission.required(api, kind))
            }
        }
    }

    @Test fun `Android 33 and newer require each source permission independently`() {
        for (api in listOf(33, 34, 35, 36)) {
            assertEquals("android.permission.READ_MEDIA_VIDEO", AutomaticMediaPermission.required(api, LibrarySourceKind.VIDEO))
            assertEquals("android.permission.READ_MEDIA_IMAGES", AutomaticMediaPermission.required(api, LibrarySourceKind.PHOTO))
            assertEquals("android.permission.READ_MEDIA_AUDIO", AutomaticMediaPermission.required(api, LibrarySourceKind.AUDIO))
        }
    }
}
