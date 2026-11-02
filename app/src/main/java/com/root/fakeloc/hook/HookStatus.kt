package com.root.fakeloc.hook

/** Safe to call without the framework; only this method is replaced in the module app. */
object HookStatus {
    @JvmStatic fun isActive(): Boolean = false
}
