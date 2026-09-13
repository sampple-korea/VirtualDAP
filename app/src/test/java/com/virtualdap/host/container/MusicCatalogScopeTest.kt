package com.virtualdap.host.container

import com.virtualdap.host.model.MusicAppCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicCatalogScopeTest {
    @Test fun audioEngineReferenceIsNotPresentedAsAMusicService() {
        val packages = MusicAppCatalog.popularApps.map { it.packageName }.toSet()
        assertFalse(packages.contains("com.extreamsd.usbaudioplayerpro"))
        assertTrue(packages.contains("com.apple.android.music"))
        assertTrue(packages.contains("com.spotify.music"))
    }
}
