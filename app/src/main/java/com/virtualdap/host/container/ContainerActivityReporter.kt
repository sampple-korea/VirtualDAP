package com.virtualdap.host.container

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Process
import java.util.UUID
import java.util.WeakHashMap

/** Lifecycle evidence from the hosted application, not a claim that login or playback works. */
internal class ContainerActivityReporter(
    private val context: Context,
    private val hostPackage: String,
    private val packageName: String,
) : Application.ActivityLifecycleCallbacks {
    private val identities = WeakHashMap<Activity, String>()

    private fun report(method: String, activity: Activity) {
        val identity = identities.getOrPut(activity) { UUID.randomUUID().toString() }
        val extras = Bundle().apply {
            putInt("pid", Process.myPid())
            putString("identity", identity)
            putString("activity", activity.javaClass.name)
        }
        runCatching {
            context.contentResolver.call(Uri.parse("content://$hostPackage.container.events"),
                method, packageName, extras)
        }.onFailure {
            android.util.Log.w("VirtualDAP-Container", "Could not report activity lifecycle", it)
        }
    }

    override fun onActivityResumed(activity: Activity) = report("activity-resumed", activity)
    override fun onActivityPaused(activity: Activity) = report("activity-paused", activity)
    override fun onActivityDestroyed(activity: Activity) {
        report("activity-paused", activity)
        identities.remove(activity)
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
