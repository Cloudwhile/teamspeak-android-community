package com.cloudwhile.tscc.ui.i18n

import androidx.compose.ui.text.font.FontStyle
import com.cloudwhile.tscc.data.model.AppLanguage
import com.cloudwhile.tscc.data.model.AudioRoute
import com.cloudwhile.tscc.data.model.VoiceMode
import com.cloudwhile.tscc.data.update.AppUpdateManager
import com.cloudwhile.tscc.data.update.formatBytesHexLower
import com.cloudwhile.tscc.data.util.BBCodeParser
import com.cloudwhile.tscc.data.util.Ts3UriParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class I18nComplianceTest {

    private val cjkRegex = Regex("[\\u4E00-\\u9FFF]")

    @Test
    fun enAppStringsContainsZeroCjkCharactersAndNoBlankEntries() {
        for (field in AppStrings::class.java.declaredFields) {
            if (field.type != String::class.java) continue
            field.isAccessible = true
            val value = field.get(EnAppStrings) as String
            assertTrue("EnAppStrings.${field.name} must not be blank", value.isNotBlank())
            assertFalse(
                "EnAppStrings.${field.name} leaked CJK text: $value",
                cjkRegex.containsMatchIn(value)
            )
        }

        val dynamicSamples = listOf(
            EnAppStrings.moderatedChannelBanner(25, 0),
            EnAppStrings.returnToDefaultChannel("Lobby"),
            EnAppStrings.identityManageSub("Primary", 8, 1),
            EnAppStrings.identityManageSub("Primary", 20, 3),
            EnAppStrings.newVersionBadge("1.2.0"),
            EnAppStrings.channelCountAndSecurity(1, 8),
            EnAppStrings.channelCountAndSecurity(6, 15)
        )
        for (sample in dynamicSamples) {
            assertTrue(sample.isNotBlank())
            assertFalse("EnAppStrings dynamic formatter leaked CJK: $sample", cjkRegex.containsMatchIn(sample))
        }
    }

    @Test
    fun zhAppStringsContainsLocalizedChineseForAllTranslatableFields() {
        val brandInvariants = setOf("appTitle")
        for (field in AppStrings::class.java.declaredFields) {
            if (field.type != String::class.java) continue
            field.isAccessible = true
            val value = field.get(ZhAppStrings) as String
            assertTrue("ZhAppStrings.${field.name} must not be blank", value.isNotBlank())
            if (field.name !in brandInvariants) {
                assertTrue(
                    "ZhAppStrings.${field.name} is missing Chinese localization: $value",
                    cjkRegex.containsMatchIn(value)
                )
            }
        }

        val zhDynamicSamples = listOf(
            ZhAppStrings.moderatedChannelBanner(25, 0),
            ZhAppStrings.returnToDefaultChannel("大厅"),
            ZhAppStrings.identityManageSub("默认身份", 8, 1),
            ZhAppStrings.newVersionBadge("1.2.0"),
            ZhAppStrings.channelCountAndSecurity(4, 8)
        )
        for (sample in zhDynamicSamples) {
            assertTrue("ZhAppStrings dynamic formatter missing CJK: $sample", cjkRegex.containsMatchIn(sample))
        }
    }

    @Test
    fun englishPluralizationAndDefaultIdentityLocalizationFollowSpec() {
        assertTrue(EnAppStrings.identityManageSub("Main", 8, 1).contains("1 key saved"))
        assertFalse(EnAppStrings.identityManageSub("Main", 8, 1).contains("1 keys"))
        assertTrue(EnAppStrings.identityManageSub("Main", 8, 2).contains("2 keys saved"))

        assertEquals("1 channel · Lv.8", EnAppStrings.channelCountAndSecurity(1, 8))
        assertEquals("4 channels · Lv.12", EnAppStrings.channelCountAndSecurity(4, 12))

        assertEquals("Default Identity", EnAppStrings.localizeIdentityDisplayName("默认身份"))
        assertEquals("默认身份", ZhAppStrings.localizeIdentityDisplayName("Default Identity"))
        assertEquals("SquadLeader", EnAppStrings.localizeIdentityDisplayName("SquadLeader"))
        assertEquals("Default Identity", EnAppStrings.localizeIdentityDisplayName("   "))
        assertEquals("默认身份", ZhAppStrings.localizeIdentityDisplayName(null))
    }

    @Test
    fun domainModelLocalizedLabelsHaveCleanBilingualCoverage() {
        VoiceMode.entries.forEach { mode ->
            assertFalse(cjkRegex.containsMatchIn(mode.localizedLabel(true)))
            assertTrue(cjkRegex.containsMatchIn(mode.localizedLabel(false)))
        }
        AudioRoute.entries.forEach { route ->
            assertFalse(cjkRegex.containsMatchIn(route.localizedLabel(true)))
            assertTrue(cjkRegex.containsMatchIn(route.localizedLabel(false)))
        }
        assertEquals("System", AppLanguage.SYSTEM.localizedLabel(true))
        assertEquals("跟随系统", AppLanguage.SYSTEM.localizedLabel(false))
    }

    @Test
    fun turkishAndArabicDefaultLocalesDoNotBreakCaseMappingOrHexDigests() {
        val original = Locale.getDefault()
        try {
            // Turkish locale ('I'.lowercase() == 'ı', 'i'.uppercase() == 'İ')
            Locale.setDefault(Locale("tr", "TR"))

            assertFalse(resolveAppStrings(AppLanguage.SYSTEM, Locale("zh", "CN")).isEnglish)
            assertTrue(resolveAppStrings(AppLanguage.SYSTEM, Locale("ZH", "TW")).isEnglish.not())
            assertTrue(resolveAppStrings(AppLanguage.SYSTEM, Locale("tr", "TR")).isEnglish)

            val annotated = BBCodeParser.parse("[I]italic[/I] [COLOR=WHITE]text[/COLOR]")
            assertEquals("italic text", annotated.text)
            assertTrue(annotated.spanStyles.any { it.item.fontStyle == FontStyle.Italic })

            val parsedUri = Ts3UriParser.parse(
                "ts3server://voice.example.com?NICKNAME=Player&CHANNELPASSWORD=secret&PRIVILEGEKEY=tok123"
            )
            assertNotNull(parsedUri)
            assertEquals("Player", parsedUri?.nickname)
            assertEquals("secret", parsedUri?.channelPassword)
            assertEquals("tok123", parsedUri?.privilegeKey)

            assertTrue(AppUpdateManager.compareVersions("1.0.0-RC.2", "1.0.0-PREVIEW.1") > 0)

            // Arabic locale (formats %d/%x digits as Eastern Arabic numerals if Locale.ROOT is omitted)
            Locale.setDefault(Locale("ar", "EG"))
            val hex = formatBytesHexLower(
                byteArrayOf(0x00, 0x12, 0x34, 0x56, 0x78, 0x9a.toByte(), 0xbc.toByte(), 0xfe.toByte())
            )
            assertEquals("00123456789abcfe", hex)
        } finally {
            Locale.setDefault(original)
        }
    }
}
