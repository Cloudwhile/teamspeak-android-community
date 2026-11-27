package com.cloudwhile.tscc.data.util

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.cloudwhile.tscc.data.update.AppUpdateManager
import com.cloudwhile.tscc.data.update.ReleaseUpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReleaseNotesParserTest {

    private val cjkRegex = Regex("[\\u4E00-\\u9FFF]")

    @Test
    fun parsesGitHubAlertsHeadingsListsTablesAndInlineSpans() {
        val sample = """
            <!-- hidden comment -->
            > [!WARNING]
            > **Experimental Alpha**: `TS6` Live Streaming is unstable.

            ### Added
            - **Onboarding Wizard**: 3-step setup with `.ini` import
              and smooth permission prompts.
              - Nested detail item
            1. First ordered step

            | Package | ABI |
            | :--- | :--- |
            | `app.apk` | `arm64-v8a` |

            Visit [Releases](https://github.com/Cloudwhile/teamspeak-android-community/releases) for *more*.
        """.trimIndent()

        val blocks = ReleaseNotesParser.parseBlocks(sample)
        val alert = blocks.filterIsInstance<MdBlock.Alert>().firstOrNull()
        assertTrue(alert != null && alert.kind == MdAlertKind.WARNING)
        assertTrue(alert!!.lines.first().contains("Experimental Alpha"))

        val headings = blocks.filterIsInstance<MdBlock.Heading>()
        assertEquals(listOf(MdBlock.Heading(3, "Added")), headings)

        val bullets = blocks.filterIsInstance<MdBlock.BulletItem>()
        assertEquals(3, bullets.size)
        assertEquals(0, bullets[0].indentLevel)
        assertTrue(bullets[0].text.contains("and smooth permission prompts."))
        assertEquals(1, bullets[1].indentLevel)
        assertEquals("1.", bullets[2].marker)

        val tables = blocks.filterIsInstance<MdBlock.TableBlock>()
        assertEquals(1, tables.size)
        assertEquals(listOf("Package", "ABI"), tables[0].headers)

        val para = blocks.filterIsInstance<MdBlock.Paragraph>().single()
        val inline = ReleaseNotesParser.parseInline(para.text)
        assertEquals("Visit Releases for more.", inline.text)
        val urlAnn = inline.getStringAnnotations(ReleaseNotesParser.URL_TAG, 0, inline.length).single()
        assertEquals("https://github.com/Cloudwhile/teamspeak-android-community/releases", urlAnn.item)
        assertTrue(inline.spanStyles.any { it.item.fontWeight == FontWeight.SemiBold })
        assertTrue(inline.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun localizedReleaseNotesResolvesEnglishAndChineseCleanly() {
        val info = ReleaseUpdateInfo(
            tagName = "v0.1.0-alpha.2",
            versionName = "0.1.0-alpha.2",
            releaseName = "TSCC v0.1.0-alpha.2",
            body = "Fallback English body",
            bodyEn = "### Added\n- English changelog",
            bodyZh = "### 新增功能\n- 中文更新说明",
            htmlUrl = AppUpdateManager.RELEASES_WEB_URL,
            publishedAt = "2026-11-27T08:00:00Z",
            isPrerelease = true,
            selectedAsset = null,
            sha256SumsUrl = null
        )

        assertTrue(info.hasBilingualNotes)
        assertTrue(info.localizedNotes(isEnglish = true).contains("English changelog"))
        assertFalse(cjkRegex.containsMatchIn(info.localizedNotes(isEnglish = true)))
        assertTrue(info.localizedNotes(isEnglish = false).contains("中文更新说明"))
        assertTrue(AppUpdateManager.compareVersions("0.1.0-alpha.2", "0.1.0-alpha.1") > 0)
    }

    @Test
    fun alpha2CuratedChangelogsFollowNormativeEnglishAndBilingualSpec() {
        val rootDir = File("..").canonicalFile.takeIf { File(it, "changelogs").exists() }
            ?: File(".").canonicalFile

        val enFile = File(rootDir, "changelogs/v0.1.0-alpha.2.md")
        val zhFile = File(rootDir, "locales/changelogs/v0.1.0-alpha.2.zh-CN.md")
        assertTrue("Missing English changelog: ${enFile.absolutePath}", enFile.isFile)
        assertTrue("Missing Chinese changelog: ${zhFile.absolutePath}", zhFile.isFile)

        val enText = enFile.readText(Charsets.UTF_8)
        val zhText = zhFile.readText(Charsets.UTF_8)

        // English release notes must be 100% pure English with Alpha live streaming warning & standard sections
        assertFalse("English release notes must not contain CJK characters", cjkRegex.containsMatchIn(enText))
        val enBlocks = ReleaseNotesParser.parseBlocks(enText)
        val enWarn = enBlocks.filterIsInstance<MdBlock.Alert>().firstOrNull { it.kind == MdAlertKind.WARNING }
        assertTrue("English release notes must include [!WARNING] for Alpha Live Streaming", enWarn != null)
        assertTrue(enWarn!!.lines.joinToString(" ").contains("unstable Alpha", ignoreCase = true))

        val enHeadings = enBlocks.filterIsInstance<MdBlock.Heading>().map { it.text }
        assertEquals(listOf("Added", "Changed", "Fixed"), enHeadings)

        // Chinese release notes must contain localized Chinese and matching [!WARNING] block
        assertTrue(cjkRegex.containsMatchIn(zhText))
        val zhBlocks = ReleaseNotesParser.parseBlocks(zhText)
        val zhWarn = zhBlocks.filterIsInstance<MdBlock.Alert>().firstOrNull { it.kind == MdAlertKind.WARNING }
        assertTrue("Chinese release notes must include [!WARNING] for Alpha Live Streaming", zhWarn != null)
        assertTrue(zhWarn!!.lines.joinToString(" ").contains("Alpha 不稳定测试"))
    }
}
