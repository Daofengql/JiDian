package com.root.fakeloc.core.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.root.fakeloc.core.root.RootShell
import com.root.fakeloc.hook.HookStatus
import com.root.fakeloc.hook.bridge.HookStateStore
import org.json.JSONArray
import org.json.JSONObject

enum class LocationBackend { HOOK, ROOT }

object RuntimeEnvironment {
    fun preferences(context: Context) = context.getSharedPreferences("mock-ui", Context.MODE_PRIVATE)
    // Opting into Root is explicit. Upgrading or opening Hook mode never launches SU.
    fun backend(context: Context): LocationBackend = runCatching {
        LocationBackend.valueOf(preferences(context).getString("location-backend", "HOOK")!!)
    }.getOrDefault(LocationBackend.HOOK)

    fun targets(context: Context): Set<String> = preferences(context).getStringSet("hook-targets", setOf("com.chaoxing.mobile"))!!.toSet()
    fun moduleActive(context: Context) = HookStatus.isActive() || HookStateStore.connectedTargets(targets(context)).isNotEmpty()
    fun ready(context: Context) = when (backend(context)) {
        LocationBackend.HOOK -> moduleActive(context) && targets(context).isNotEmpty()
        LocationBackend.ROOT -> RootShell.environmentReady && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    fun putState(context: Context, state: JSONObject): JSONObject = state.apply {
        put("backend", backend(context).name)
        put("backendReady", ready(context))
        put("moduleActive", moduleActive(context))
        put("hookTargets", JSONArray(targets(context).sorted()))
        put("hookConnectedTargets", JSONArray(HookStateStore.connectedTargets(targets(context)).sorted()))
    }
}
