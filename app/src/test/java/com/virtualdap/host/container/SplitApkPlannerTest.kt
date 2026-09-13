package com.virtualdap.host.container

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import top.niunaijun.blackbox.utils.SplitApkPlanner
import top.niunaijun.blackbox.utils.SplitApkPlanner.Part

class SplitApkPlannerTest {
    private fun part(split: String? = null, abis: Set<String> = emptySet(), pkg: String = "fixture.music", version: Long = 1) =
        Part(File("untrusted-filename-${split ?: "base"}.apk"), pkg, split, version, abis)

    @Test fun keepsFeatureModulesAndAllNonAbiResourceConfigurations() {
        val parts = listOf(part(), part("decoder"), part("config.en"), part("config.ko"),
            part("config.xhdpi"), part("config.xxhdpi"), part("decoder.config.en"),
            part("config.arm64_v8a", setOf("arm64-v8a")), part("config.x86_64", setOf("x86_64")))
        val selected = SplitApkPlanner.select(parts, listOf("x86_64", "arm64-v8a"))
        assertEquals("x86_64", selected.abi)
        assertEquals(7, selected.splits.size)
        assertTrue(selected.splits.any { it.splitName == "decoder" })
        assertFalse(selected.splits.any { it.splitName == "config.arm64_v8a" })
    }

    @Test fun picksOneCommonAbiAcrossBaseAndFeatureModules() {
        val parts = listOf(part(abis = setOf("x86_64", "arm64-v8a")),
            part("decoder", setOf("arm64-v8a")))
        assertEquals("arm64-v8a", SplitApkPlanner.select(parts, listOf("x86_64", "arm64-v8a")).abi)
        assertThrows(IllegalArgumentException::class.java) { SplitApkPlanner.select(parts, listOf("x86_64")) }
    }

    @Test fun selectsAbiForEveryFeatureConfigGroup() {
        val parts = listOf(part(), part("config.x86_64"), part("config.arm64_v8a"),
            part("decoder"), part("decoder.config.arm64_v8a"))
        val result = SplitApkPlanner.select(parts, listOf("x86_64", "arm64-v8a"))
        assertEquals("arm64-v8a", result.abi)
        assertEquals(setOf("config.arm64_v8a", "decoder", "decoder.config.arm64_v8a"),
            result.splits.map { it.splitName }.toSet())
    }

    @Test fun rejectsMixedOrAmbiguousSets() {
        for (parts in listOf(
            listOf(part(), part()),
            listOf(part("config.en")),
            listOf(part(), part("config.en"), part("config.en")),
            listOf(part(), part("decoder", pkg = "other.music")),
            listOf(part(), part("decoder", version = 2)),
            listOf(part(), part("config.x86_64", setOf("arm64-v8a"))),
        )) {
            assertThrows(IllegalArgumentException::class.java) { SplitApkPlanner.select(parts, listOf("x86_64")) }
        }
    }
}
