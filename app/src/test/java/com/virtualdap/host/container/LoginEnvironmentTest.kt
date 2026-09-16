package com.virtualdap.host.container

import com.virtualdap.host.model.GoogleServiceCatalog
import com.virtualdap.host.model.MusicAppCatalog
import org.junit.Assert.*
import org.junit.Test

class LoginEnvironmentTest {
    @Test fun dependenciesRemainSeparateFromMusicAppsAndDoNotImplyCompatibility() {
        val dependencies = GoogleServiceCatalog.packages.map { it.packageName }
        assertEquals(dependencies.size, dependencies.toSet().size)
        assertTrue(dependencies.containsAll(listOf("com.google.android.gms", "com.google.android.gsf", "com.android.vending")))
        assertTrue(MusicAppCatalog.popularApps.none { GoogleServiceCatalog.contains(it.packageName) })
        assertFalse(GoogleServiceCatalog.contains("com.google.android.gms.untrusted"))
    }

    @Test fun musicListHidesDependenciesButLifecycleRetainsThem() {
        val music = ContainerApp("com.google.android.apps.youtube.music", "YouTube Music", 34)
        val services = GoogleServiceCatalog.packages.map { ContainerApp(it.packageName, it.name, 34) }
        val snapshot = ContainerSnapshot(applications = services + music)
        assertEquals(listOf(music), snapshot.musicApplications)
        assertEquals(services.size + 1, snapshot.applications.size)
        val activity = ContainerActivity(services[0].packageName, 123, "auth", "LoginActivity")
        assertEquals(activity, snapshot.activityChanged(activity, true).foregroundActivity)
    }

    @Test fun servicesAloneDoNotHideEmptyMusicState() {
        val snapshot = ContainerSnapshot(applications = listOf(ContainerApp("com.google.android.gms", "Services", 34)))
        assertTrue(snapshot.musicApplications.isEmpty())
        assertTrue(snapshot.hostServices.isEmpty())
    }
}
