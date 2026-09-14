package com.virtualdap.host.container

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/** Same-UID lifecycle events from hosted processes to the host's UI process. */
class ContainerEventProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == Process.myUid()) { "Foreign container event caller" }
        if (method in setOf("started", "activity-resumed", "activity-paused") && !arg.isNullOrBlank()) {
            val pid = extras?.getInt("pid", -1) ?: -1
            require(pid > 0 && pid == Binder.getCallingPid() && arg.length <= 255) { "Invalid container lifecycle event" }
            if (method == "started") ContainerRuntime.appStarted(arg, pid)
            else {
                val identity = extras?.getString("identity").orEmpty()
                val activity = extras?.getString("activity").orEmpty()
                require(identity.length in 1..64 && activity.length in 1..512) { "Invalid activity event" }
                ContainerRuntime.activityChanged(ContainerActivity(arg, pid, identity, activity), method == "activity-resumed")
            }
        }
        return Bundle.EMPTY
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
