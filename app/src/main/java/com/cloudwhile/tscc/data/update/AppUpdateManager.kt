package com.cloudwhile.tscc.data.update

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.cloudwhile.tscc.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ReleaseAssetInfo(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val matchedAbi: String
)

data class ReleaseUpdateInfo(
    val tagName: String,
    val versionName: String,
    val releaseName: String,
    val body: String,
    val htmlUrl: String,
    val publishedAt: String,
    val isPrerelease: Boolean,
    val selectedAsset: ReleaseAssetInfo?,
    val sha256SumsUrl: String?
)

enum class UpdateStatus {
    IDLE,
    CHECKING,
    AVAILABLE,
    UP_TO_DATE,
    DOWNLOADING,
    READY_TO_INSTALL,
    ERROR
}

data class UpdateUiState(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val latestRelease: ReleaseUpdateInfo? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val progress: Float = 0f,
    val downloadedFilePath: String? = null,
    val errorMessage: String? = null,
    val showDialog: Boolean = false
)

class AppUpdateManager(private val context: Context) {

    companion object {
        const val RELEASES_API_URL =
            "https://api.github.com/repos/Cloudwhile/teamspeak-android-community/releases?per_page=15"
        const val RELEASES_WEB_URL =
            "https://github.com/Cloudwhile/teamspeak-android-community/releases"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

        internal fun normalizeVersionString(raw: String): String {
            var v = raw.trim()
            if (v.startsWith("v", ignoreCase = true)) {
                v = v.substring(1).trim()
            }
            // Strip build metadata (+...) and local debug suffix (-debug)
            val plusIdx = v.indexOf('+')
            if (plusIdx >= 0) v = v.substring(0, plusIdx)
            if (v.endsWith("-debug", ignoreCase = true)) {
                v = v.dropLast(6)
            }
            // Normalize space before pre-release tag ("0.1.0 alpha1" -> "0.1.0-alpha1")
            v = v.replace(Regex("\\s+"), "-")
            return v
        }

        /**
         * Compares two semantic version strings (including pre-releases like `0.1.0-alpha1`,
         * `0.1.0-alpha2`, `0.1.0-alpha10`, `0.1.0-beta1`, `0.1.0-rc1`, `0.1.0`).
         *
         * Returns > 0 if [a] is newer than [b], < 0 if [a] is older than [b], 0 if equal.
         */
        fun compareVersions(a: String, b: String): Int {
            val normA = normalizeVersionString(a)
            val normB = normalizeVersionString(b)
            if (normA.equals(normB, ignoreCase = true)) return 0

            val (coreA, preA) = splitCoreAndPrerelease(normA)
            val (coreB, preB) = splitCoreAndPrerelease(normB)

            val maxCore = maxOf(coreA.size, coreB.size, 3)
            for (i in 0 until maxCore) {
                val numA = coreA.getOrElse(i) { 0 }
                val numB = coreB.getOrElse(i) { 0 }
                if (numA != numB) return numA.compareTo(numB)
            }

            // A stable release (no pre-release suffix) is strictly newer than any pre-release of the same core
            if (preA.isEmpty() && preB.isEmpty()) return 0
            if (preA.isEmpty()) return 1
            if (preB.isEmpty()) return -1

            val tokensA = tokenizePrerelease(preA)
            val tokensB = tokenizePrerelease(preB)
            val minLen = minOf(tokensA.size, tokensB.size)
            for (i in 0 until minLen) {
                val cmp = comparePreToken(tokensA[i], tokensB[i])
                if (cmp != 0) return cmp
            }
            return tokensA.size.compareTo(tokensB.size)
        }

        private fun splitCoreAndPrerelease(version: String): Pair<List<Int>, String> {
            val dashIdx = version.indexOf('-')
            val corePart = if (dashIdx >= 0) version.substring(0, dashIdx) else version
            val prePart = if (dashIdx >= 0) version.substring(dashIdx + 1) else ""
            val coreNums = corePart.split('.').map { it.toIntOrNull() ?: 0 }
            return coreNums to prePart
        }

        private sealed interface PreToken {
            data class Str(val value: String, val rank: Int) : PreToken
            data class Num(val value: Long) : PreToken
        }

        private fun stageRank(label: String): Int = when (label.lowercase(java.util.Locale.ROOT)) {
            "dev", "snapshot", "nightly" -> 0
            "alpha", "a" -> 1
            "beta", "b" -> 2
            "rc", "pre", "preview" -> 3
            else -> 4
        }

        private fun tokenizePrerelease(pre: String): List<PreToken> {
            val out = mutableListOf<PreToken>()
            val matcher = Regex("([A-Za-z]+)|([0-9]+)")
            for (part in pre.split('.', '-', '_')) {
                if (part.isEmpty()) continue
                for (m in matcher.findAll(part)) {
                    val alpha = m.groupValues[1]
                    val digits = m.groupValues[2]
                    if (alpha.isNotEmpty()) {
                        out.add(PreToken.Str(alpha.lowercase(java.util.Locale.ROOT), stageRank(alpha)))
                    } else if (digits.isNotEmpty()) {
                        out.add(PreToken.Num(digits.toLongOrNull() ?: 0L))
                    }
                }
            }
            return out
        }

        private fun comparePreToken(a: PreToken, b: PreToken): Int = when {
            a is PreToken.Num && b is PreToken.Num -> a.value.compareTo(b.value)
            a is PreToken.Str && b is PreToken.Str -> {
                if (a.rank != b.rank) a.rank.compareTo(b.rank)
                else a.value.compareTo(b.value)
            }
            // Numeric identifiers have lower precedence than non-numeric per SemVer 2.0
            a is PreToken.Num && b is PreToken.Str -> -1
            else -> 1
        }

        internal fun selectBestApkAsset(
            rawAssets: List<Triple<String, String, Long>>,
            supportedAbis: List<String>
        ): ReleaseAssetInfo? {
            val apkAssets = rawAssets.filter { (name, url, _) ->
                name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()
            }
            if (apkAssets.isEmpty()) return null

            // 1. Match device's preferred ABI order (e.g. arm64-v8a -> armeabi-v7a -> x86_64)
            for (abi in supportedAbis) {
                val hit = apkAssets.firstOrNull { (name, _, _) ->
                    name.contains(abi, ignoreCase = true)
                }
                if (hit != null) {
                    return ReleaseAssetInfo(
                        name = hit.first,
                        downloadUrl = hit.second,
                        sizeBytes = hit.third,
                        matchedAbi = abi
                    )
                }
            }

            // 2. Fallback to universal APK
            val universalHit = apkAssets.firstOrNull { (name, _, _) ->
                name.contains("universal", ignoreCase = true)
            }
            if (universalHit != null) {
                return ReleaseAssetInfo(
                    name = universalHit.first,
                    downloadUrl = universalHit.second,
                    sizeBytes = universalHit.third,
                    matchedAbi = "universal"
                )
            }

            // 3. Fallback to any non-ABI-tagged APK (single fat APK release)
            val genericHit = apkAssets.firstOrNull { (name, _, _) ->
                KNOWN_ABIS.none { abi -> name.contains(abi, ignoreCase = true) }
            } ?: apkAssets.first()

            return ReleaseAssetInfo(
                name = genericHit.first,
                downloadUrl = genericHit.second,
                sizeBytes = genericHit.third,
                matchedAbi = "universal"
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun dismissDialog() {
        _state.update { it.copy(showDialog = false) }
    }

    fun openDialogIfAvailable() {
        if (_state.value.latestRelease != null) {
            _state.update { it.copy(showDialog = true) }
        }
    }

    fun checkForUpdates(
        manual: Boolean,
        skippedVersion: String,
        isEnglish: Boolean,
        onManualFeedback: (message: String, isError: Boolean) -> Unit = { _, _ -> }
    ) {
        if (_state.value.status == UpdateStatus.DOWNLOADING) {
            if (manual) _state.update { it.copy(showDialog = true) }
            return
        }
        checkJob?.cancel()
        checkJob = scope.launch {
            _state.update { it.copy(status = UpdateStatus.CHECKING, errorMessage = null) }
            val result = withContext(Dispatchers.IO) { fetchLatestRelease(isEnglish) }
            result.fold(
                onSuccess = { latest ->
                    if (latest == null) {
                        _state.update {
                            it.copy(status = UpdateStatus.UP_TO_DATE, showDialog = false)
                        }
                        if (manual) {
                            onManualFeedback(
                                if (isEnglish) "No published releases yet" else "暂无已发布的版本记录",
                                false
                            )
                        }
                        return@fold
                    }

                    val isNewer = compareVersions(latest.versionName, BuildConfig.VERSION_NAME) > 0
                    if (!isNewer) {
                        _state.update {
                            it.copy(
                                status = UpdateStatus.UP_TO_DATE,
                                latestRelease = latest,
                                showDialog = false
                            )
                        }
                        if (manual) {
                            onManualFeedback(
                                if (isEnglish) {
                                    "You are on the latest version (v${BuildConfig.VERSION_NAME})"
                                } else {
                                    "当前已是最新版本（v${BuildConfig.VERSION_NAME}）"
                                },
                                false
                            )
                        }
                    } else {
                        val cachedApk = findReusableCachedApk(latest.selectedAsset)
                        val shouldPrompt = manual ||
                            !normalizeVersionString(latest.versionName).equals(
                                normalizeVersionString(skippedVersion),
                                ignoreCase = true
                            )
                        _state.update {
                            it.copy(
                                status = if (cachedApk != null) {
                                    UpdateStatus.READY_TO_INSTALL
                                } else {
                                    UpdateStatus.AVAILABLE
                                },
                                latestRelease = latest,
                                downloadedFilePath = cachedApk?.absolutePath,
                                downloadedBytes = cachedApk?.length() ?: 0L,
                                totalBytes = latest.selectedAsset?.sizeBytes ?: 0L,
                                progress = if (cachedApk != null) 1f else 0f,
                                errorMessage = null,
                                showDialog = shouldPrompt
                            )
                        }
                    }
                },
                onFailure = { err ->
                    val msg = err.message ?: if (isEnglish) "Network error" else "网络请求失败"
                    _state.update {
                        it.copy(status = UpdateStatus.ERROR, errorMessage = msg)
                    }
                    if (manual) {
                        onManualFeedback(
                            if (isEnglish) "Update check failed: $msg" else "检查更新失败：$msg",
                            true
                        )
                    }
                }
            )
        }
    }

    fun startDownload(
        isEnglish: Boolean,
        onReadyToInstall: (File) -> Unit
    ) {
        val release = _state.value.latestRelease ?: return
        val asset = release.selectedAsset ?: run {
            _state.update {
                it.copy(
                    status = UpdateStatus.ERROR,
                    errorMessage = if (isEnglish) {
                        "No installable APK found in this release; please open GitHub page."
                    } else {
                        "该版本未附带 APK 安装包，请前往 GitHub 发布页查看。"
                    }
                )
            }
            return
        }

        findReusableCachedApk(asset)?.let { cached ->
            _state.update {
                it.copy(
                    status = UpdateStatus.READY_TO_INSTALL,
                    downloadedFilePath = cached.absolutePath,
                    downloadedBytes = cached.length(),
                    totalBytes = cached.length(),
                    progress = 1f,
                    errorMessage = null
                )
            }
            onReadyToInstall(cached)
            return
        }

        if (_state.value.status == UpdateStatus.DOWNLOADING) return

        downloadJob?.cancel()
        downloadJob = scope.launch {
            _state.update {
                it.copy(
                    status = UpdateStatus.DOWNLOADING,
                    downloadedBytes = 0L,
                    totalBytes = asset.sizeBytes,
                    progress = 0f,
                    errorMessage = null,
                    showDialog = true
                )
            }

            val result = withContext(Dispatchers.IO) {
                downloadAndVerifyApk(release, asset, isEnglish) { readBytes, totalBytes ->
                    val pct = if (totalBytes > 0L) {
                        (readBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    } else 0f
                    _state.update { cur ->
                        cur.copy(
                            downloadedBytes = readBytes,
                            totalBytes = totalBytes,
                            progress = pct
                        )
                    }
                }
            }

            result.fold(
                onSuccess = { apkFile ->
                    _state.update {
                        it.copy(
                            status = UpdateStatus.READY_TO_INSTALL,
                            downloadedFilePath = apkFile.absolutePath,
                            downloadedBytes = apkFile.length(),
                            totalBytes = apkFile.length(),
                            progress = 1f,
                            errorMessage = null
                        )
                    }
                    onReadyToInstall(apkFile)
                },
                onFailure = { err ->
                    if (err is CancellationException) {
                        _state.update {
                            it.copy(
                                status = UpdateStatus.AVAILABLE,
                                downloadedBytes = 0L,
                                progress = 0f,
                                errorMessage = null
                            )
                        }
                    } else {
                        val msg = err.message ?: if (isEnglish) "Download failed" else "下载安装包失败"
                        _state.update {
                            it.copy(status = UpdateStatus.ERROR, errorMessage = msg)
                        }
                    }
                }
            )
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _state.update {
            it.copy(
                status = if (it.latestRelease != null) UpdateStatus.AVAILABLE else UpdateStatus.IDLE,
                downloadedBytes = 0L,
                progress = 0f,
                errorMessage = null
            )
        }
    }

    fun launchInstallerForReadyApk(): Boolean {
        val path = _state.value.downloadedFilePath ?: return false
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) return false
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            true
        }.getOrDefault(false)
    }

    private fun updatesDir(): File = File(context.cacheDir, "updates").apply { mkdirs() }

    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "TSCC-update.apk" }

    private fun findReusableCachedApk(asset: ReleaseAssetInfo?): File? {
        if (asset == null) return null
        val f = File(updatesDir(), sanitizeFileName(asset.name))
        if (!f.exists()) return null
        if (asset.sizeBytes > 0L && f.length() != asset.sizeBytes) return null
        return if (f.length() > 0L) f else null
    }

    private fun fetchLatestRelease(isEnglish: Boolean): Result<ReleaseUpdateInfo?> = runCatching {
        val rawJson = httpGetText(RELEASES_API_URL, isEnglish)
        val arr = json.parseToJsonElement(rawJson) as? JsonArray ?: return@runCatching null
        val supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty()

        val candidates = arr.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            if (obj["draft"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
            val tag = obj["tag_name"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (tag.isEmpty()) return@mapNotNull null

            val versionName = normalizeVersionString(tag)
            val releaseName = obj["name"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { tag }
            val body = obj["body"]?.jsonPrimitive?.content?.trim().orEmpty()
            val htmlUrl = obj["html_url"]?.jsonPrimitive?.content?.trim().orEmpty()
                .ifBlank { RELEASES_WEB_URL }
            val publishedAt = obj["published_at"]?.jsonPrimitive?.content?.trim().orEmpty()
            val isPrerelease = obj["prerelease"]?.jsonPrimitive?.booleanOrNull ?: versionName.contains('-')

            val assetsJson = obj["assets"] as? JsonArray ?: JsonArray(emptyList())
            var sha256Url: String? = null
            val parsedAssets = assetsJson.mapNotNull { aElem ->
                val aObj = aElem.jsonObject
                val name = aObj["name"]?.jsonPrimitive?.content?.trim().orEmpty()
                val url = aObj["browser_download_url"]?.jsonPrimitive?.content?.trim().orEmpty()
                val size = aObj["size"]?.jsonPrimitive?.longOrNull ?: 0L
                if (name.equals("SHA256SUMS.txt", ignoreCase = true) && url.isNotBlank()) {
                    sha256Url = url
                }
                if (name.isNotEmpty() && url.isNotBlank()) Triple(name, url, size) else null
            }

            ReleaseUpdateInfo(
                tagName = tag,
                versionName = versionName,
                releaseName = releaseName,
                body = body,
                htmlUrl = htmlUrl,
                publishedAt = publishedAt,
                isPrerelease = isPrerelease,
                selectedAsset = selectBestApkAsset(parsedAssets, supportedAbis),
                sha256SumsUrl = sha256Url
            )
        }

        candidates.maxWithOrNull { a, b -> compareVersions(a.versionName, b.versionName) }
    }

    private suspend fun downloadAndVerifyApk(
        release: ReleaseUpdateInfo,
        asset: ReleaseAssetInfo,
        isEnglish: Boolean,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Result<File> = runCatching {
        val dir = updatesDir()
        val safeName = sanitizeFileName(asset.name)
        val targetFile = File(dir, safeName)
        val partFile = File(dir, "$safeName.part")

        // Clean stale APKs from older versions
        dir.listFiles()?.forEach { existing ->
            if (existing.name != safeName && existing.name != partFile.name) {
                runCatching { existing.delete() }
            }
        }
        runCatching { partFile.delete() }

        val expectedSha256 = release.sha256SumsUrl?.let { sumsUrl ->
            runCatching {
                parseExpectedSha256(httpGetText(sumsUrl, isEnglish), asset.name)
            }.getOrNull()
        }

        val conn = openFollowingRedirects(
            urlString = asset.downloadUrl,
            accept = "application/octet-stream",
            isEnglish = isEnglish
        )
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException(
                    if (isEnglish) "HTTP error $code" else "HTTP 状态异常 ($code)"
                )
            }
            val total = conn.contentLengthLong.takeIf { it > 0L } ?: asset.sizeBytes
            var downloaded = 0L
            var lastEmitMs = 0L

            conn.inputStream.buffered(64 * 1024).use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (kotlin.coroutines.coroutineContext.isActive) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmitMs >= 60L || (total > 0L && downloaded >= total)) {
                            lastEmitMs = now
                            onProgress(downloaded, total)
                        }
                    }
                    if (!kotlin.coroutines.coroutineContext.isActive) {
                        throw CancellationException("Download cancelled")
                    }
                    output.flush()
                    output.fd.sync()
                }
            }

            if (expectedSha256 != null) {
                val actualSha256 = computeFileSha256Hex(partFile)
                if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    partFile.delete()
                    throw IllegalStateException(
                        if (isEnglish) {
                            "APK SHA-256 checksum mismatch"
                        } else {
                            "安装包 SHA-256 完整性校验不通过，请重试"
                        }
                    )
                }
            }

            if (targetFile.exists()) targetFile.delete()
            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }
            targetFile
        } finally {
            conn.disconnect()
        }
    }

    private fun parseExpectedSha256(sumsContent: String, assetFileName: String): String? {
        for (line in sumsContent.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(Regex("\\s+"), limit = 2)
            if (parts.size == 2) {
                val hash = parts[0].trim()
                val fileRef = parts[1].trim().removePrefix("*").substringAfterLast('/')
                if (fileRef.equals(assetFileName, ignoreCase = true) && hash.length == 64) {
                    return hash
                }
            }
        }
        return null
    }

    private fun computeFileSha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return formatBytesHexLower(digest.digest())
    }

    private fun httpGetText(urlString: String, isEnglish: Boolean): String {
        val conn = openFollowingRedirects(
            urlString = urlString,
            accept = "application/vnd.github+json",
            isEnglish = isEnglish
        )
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException(
                    if (isEnglish) "HTTP error $code" else "HTTP 状态异常 ($code)"
                )
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun openFollowingRedirects(
        urlString: String,
        accept: String,
        isEnglish: Boolean
    ): HttpURLConnection {
        var currentUrl = urlString
        for (hop in 0..5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", "TSCC-Android/${BuildConfig.VERSION_NAME}")
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val nextLoc = conn.getHeaderField("Location")
                conn.disconnect()
                if (nextLoc.isNullOrBlank()) break
                currentUrl = URL(URL(currentUrl), nextLoc).toString()
                continue
            }
            return conn
        }
        throw IllegalStateException(
            if (isEnglish) "Too many HTTP redirects" else "HTTP 重定向次数过多"
        )
    }
}

internal fun formatBytesHexLower(bytes: ByteArray): String =
    bytes.joinToString("") { java.lang.String.format(java.util.Locale.ROOT, "%02x", it.toInt() and 0xFF) }
