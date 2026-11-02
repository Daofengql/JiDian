package com.root.fakeloc.hook.bridge

/** Android UIDs, rather than caller-supplied package names, authorize bridge reads. */
object HookAccessPolicy {
    fun validTarget(name: String, ownPackage: String): Boolean = name != ownPackage && name != "android" &&
        name != "com.android.systemui" && name.length <= 200 && name.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))

    fun canRead(callerPackages: Set<String>, selected: Set<String>, claimedPackage: String): Boolean =
        claimedPackage in callerPackages && claimedPackage in selected
}
