package com.cloudwhile.tscc.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun `compareVersions orders alpha dot x beta dot x rc dot x and stable releases`() {
        assertTrue(AppUpdateManager.compareVersions("v0.1.0-alpha.2", "0.1.0-alpha.1") > 0)
        assertTrue(AppUpdateManager.compareVersions("0.1.0-alpha.10", "0.1.0-alpha.2") > 0)
        assertTrue(AppUpdateManager.compareVersions("0.1.0-beta.1", "0.1.0-alpha.10") > 0)
        assertTrue(AppUpdateManager.compareVersions("0.1.0-beta.2", "0.1.0-beta.1") > 0)
        assertTrue(AppUpdateManager.compareVersions("0.1.0-rc.1", "0.1.0-beta.5") > 0)
        assertTrue(AppUpdateManager.compareVersions("0.1.0", "0.1.0-rc.2") > 0)
        assertTrue(AppUpdateManager.compareVersions("1.0.0", "0.9.9") > 0)

        // Handles v-prefix and -debug suffix identically
        assertEquals(0, AppUpdateManager.compareVersions("v0.1.0-alpha.1", "0.1.0-alpha.1-debug"))
    }

    @Test
    fun `selectBestApkAsset picks exact device ABI first and falls back to universal`() {
        val assets = listOf(
            Triple("TSCC-v0.1.0-alpha.2-armeabi-v7a.apk", "https://example.com/v7a.apk", 13_000_000L),
            Triple("TSCC-v0.1.0-alpha.2-arm64-v8a.apk", "https://example.com/arm64.apk", 20_000_000L),
            Triple("TSCC-v0.1.0-alpha.2-x86_64.apk", "https://example.com/x64.apk", 22_000_000L),
            Triple("TSCC-v0.1.0-alpha.2-universal.apk", "https://example.com/uni.apk", 62_000_000L)
        )

        val arm64Pick = AppUpdateManager.selectBestApkAsset(assets, listOf("arm64-v8a", "armeabi-v7a"))
        assertNotNull(arm64Pick)
        assertEquals("arm64-v8a", arm64Pick!!.matchedAbi)
        assertEquals("TSCC-v0.1.0-alpha.2-arm64-v8a.apk", arm64Pick.name)

        val x64Pick = AppUpdateManager.selectBestApkAsset(assets, listOf("x86_64", "arm64-v8a"))
        assertNotNull(x64Pick)
        assertEquals("x86_64", x64Pick!!.matchedAbi)

        val fallbackPick = AppUpdateManager.selectBestApkAsset(assets, listOf("riscv64"))
        assertNotNull(fallbackPick)
        assertEquals("universal", fallbackPick!!.matchedAbi)
    }
}
