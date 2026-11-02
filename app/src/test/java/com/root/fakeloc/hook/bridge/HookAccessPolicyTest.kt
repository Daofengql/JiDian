package com.root.fakeloc.hook.bridge

import org.junit.Assert.*
import org.junit.Test

class HookAccessPolicyTest {
    @Test fun onlyTheSelectedCallingUidCanReadSnapshots() {
        val targets = setOf("com.example.selected")
        assertTrue(HookAccessPolicy.canRead(setOf("com.example.selected"), targets, "com.example.selected"))
        assertFalse(HookAccessPolicy.canRead(setOf("com.example.other"), targets, "com.example.selected"))
        assertFalse(HookAccessPolicy.canRead(setOf("com.example.other"), targets, "com.example.other"))
        assertFalse(HookAccessPolicy.canRead(setOf("com.example.selected"), emptySet(), "com.example.selected"))
    }
    @Test fun targetSelectionRejectsFrameworkProcessesAndInvalidNames() {
        val own = "com.root.fakeloc"
        assertTrue(HookAccessPolicy.validTarget("com.example.app_2", own))
        for (target in listOf(own, "android", "com.android.systemui", "", "../app", "com.example;id", "app"))
            assertFalse(target, HookAccessPolicy.validTarget(target, own))
    }
}
