package com.root.fakeloc.hook.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.root.fakeloc.core.runtime.LocationBackend
import com.root.fakeloc.core.runtime.RuntimeEnvironment
import com.root.fakeloc.service.MockDaemonService

/** Read-only bridge: each read checks Binder identity and the current explicit target list. */
class HookStateProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method != "snapshot") throw IllegalArgumentException("Unsupported bridge operation")
        val app = requireNotNull(context)
        val uid = Binder.getCallingUid()
        val claimed = arg.orEmpty()
        val own = uid == Process.myUid()
        val token = Binder.clearCallingIdentity()
        val callerPackages = try { app.packageManager.getPackagesForUid(uid)?.toSet().orEmpty() } finally { Binder.restoreCallingIdentity(token) }
        if (!own && !HookAccessPolicy.canRead(callerPackages, RuntimeEnvironment.targets(app), claimed)) return Bundle().apply { putBoolean("active", false) }
        if (!own) HookStateStore.report(app, claimed)
        val frame = HookStateStore.frame
        return if (RuntimeEnvironment.backend(app) == LocationBackend.HOOK && MockDaemonService.isServiceRunning && frame?.fresh() == true) frame.bundle().apply { putBoolean("allowed", true) }
        else Bundle().apply { putBoolean("active", false); putBoolean("allowed", true) }
    }
    override fun getType(uri: Uri) = "application/vnd.com.root.fakeloc.snapshot"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
