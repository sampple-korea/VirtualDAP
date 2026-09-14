package com.virtualdap.host.container

import com.virtualdap.host.model.MusicAppCatalog
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicCatalogScopeTest {
    @Test fun catalogIncludesMajorMusicServices() {
        val packages = MusicAppCatalog.popularApps.map { it.packageName }.toSet()
        assertTrue(packages.contains("com.apple.android.music"))
        assertTrue(packages.contains("com.spotify.music"))
    }
}
