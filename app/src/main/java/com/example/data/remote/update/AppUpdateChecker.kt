package com.example.data.remote.update

import com.example.BuildConfig
import com.example.data.local.SecurityManager
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class GitHubAssetDto(
    val name: String = "",
    @param:Json(name = "browser_download_url")
    val browserDownloadUrl: String = "",
    val size: Long = 0L,
    @param:Json(name = "content_type")
    val contentType: String? = null
)

@JsonClass(generateAdapter = true)
data class GitHubReleaseDto(
    @param:Json(name = "tag_name")
    val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    @param:Json(name = "html_url")
    val htmlUrl: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @param:Json(name = "published_at")
    val publishedAt: String? = null,
    val assets: List<GitHubAssetDto> = emptyList()
)

interface GitHubReleasesApi {
    @Headers(
        "Accept: application/vnd.github+json",
        "X-GitHub-Api-Version: 2022-11-28",
        "User-Agent: CashControl-Android"
    )
    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun getLatestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): GitHubReleaseDto
}

data class GitHubRepoSlug(
    val owner: String,
    val repo: String
) {
    val fullSlug: String get() = "$owner/$repo"
}

data class AppReleaseInfo(
    val versionName: String,
    val tagName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val releasePageUrl: String,
    val assetName: String?,
    val assetSizeBytes: Long?,
    val publishedAt: String?,
    val hasDirectApkAsset: Boolean
)

sealed interface UpdateCheckResult {
    data class UpdateAvailable(
        val currentVersion: String,
        val release: AppReleaseInfo
    ) : UpdateCheckResult

    data class UpToDate(
        val currentVersion: String,
        val latestVersion: String
    ) : UpdateCheckResult

    data object NotConfigured : UpdateCheckResult

    data class Error(val message: String) : UpdateCheckResult
}

private val VALID_GITHUB_SEGMENT_REGEX = Regex("^[A-Za-z0-9._-]+$")

fun parseGitHubRepoSlug(raw: String?): GitHubRepoSlug? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null

    var normalized = trimmed
        .removePrefix("https://github.com/")
        .removePrefix("http://github.com/")
        .removePrefix("github.com/")
        .removePrefix("git@github.com:")
        .trim('/')

    if (normalized.endsWith(".git", ignoreCase = true)) {
        normalized = normalized.dropLast(4)
    }

    val segments = normalized.split('/').filter { it.isNotBlank() }
    if (segments.size < 2) return null

    val owner = segments[0]
    val repo = segments[1]
    if (!VALID_GITHUB_SEGMENT_REGEX.matches(owner) || !VALID_GITHUB_SEGMENT_REGEX.matches(repo)) {
        return null
    }
    return GitHubRepoSlug(owner = owner, repo = repo)
}

fun normalizeVersionString(raw: String): String {
    return raw.trim()
        .removePrefix("v")
        .removePrefix("V")
        .trim()
}

private data class ParsedVersion(
    val numbers: List<Int>,
    val preRelease: String?
)

private fun parseVersion(raw: String): ParsedVersion {
    val clean = normalizeVersionString(raw)
    val hyphenIdx = clean.indexOf('-')
    val core = if (hyphenIdx >= 0) clean.substring(0, hyphenIdx) else clean
    val preRelease = if (hyphenIdx >= 0) {
        clean.substring(hyphenIdx + 1).trim().takeIf { it.isNotEmpty() }
    } else {
        null
    }
    val numbers = core.split('.')
        .mapNotNull { segment ->
            val digits = segment.takeWhile { it.isDigit() }
            digits.toIntOrNull()
        }
        .ifEmpty { listOf(0) }
    return ParsedVersion(numbers = numbers, preRelease = preRelease)
}

fun compareSemver(candidate: String, current: String): Int {
    val left = parseVersion(candidate)
    val right = parseVersion(current)
    val maxLen = maxOf(left.numbers.size, right.numbers.size)
    for (i in 0 until maxLen) {
        val l = left.numbers.getOrElse(i) { 0 }
        val r = right.numbers.getOrElse(i) { 0 }
        if (l != r) return l.compareTo(r)
    }
    return when {
        left.preRelease == null && right.preRelease == null -> 0
        left.preRelease == null -> 1 // stable release > pre-release
        right.preRelease == null -> -1
        else -> left.preRelease.compareTo(right.preRelease, ignoreCase = true)
    }
}

class AppUpdateChecker(
    private val securityManager: SecurityManager,
    private val api: GitHubReleasesApi = defaultGitHubReleasesApi(),
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val buildConfigRepoSlug: String = BuildConfig.GITHUB_RELEASE_REPO
) {

    suspend fun resolveRepoSlug(): GitHubRepoSlug? {
        val overrideRaw = securityManager.getGitHubRepoOverride()
        return parseGitHubRepoSlug(overrideRaw) ?: parseGitHubRepoSlug(buildConfigRepoSlug)
    }

    suspend fun checkForUpdate(ignoreSkippedVersion: Boolean = false): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            val slug = resolveRepoSlug() ?: return@withContext UpdateCheckResult.NotConfigured
            val currentNormalized = normalizeVersionString(currentVersionName).ifBlank { "1.0.0" }

            try {
                val release = api.getLatestRelease(slug.owner, slug.repo)
                if (release.draft || release.prerelease || release.tagName.isBlank()) {
                    return@withContext UpdateCheckResult.UpToDate(
                        currentVersion = currentNormalized,
                        latestVersion = currentNormalized
                    )
                }

                val latestVersion = normalizeVersionString(release.tagName)
                if (latestVersion.isBlank() || compareSemver(latestVersion, currentNormalized) <= 0) {
                    return@withContext UpdateCheckResult.UpToDate(
                        currentVersion = currentNormalized,
                        latestVersion = latestVersion.ifBlank { currentNormalized }
                    )
                }

                if (!ignoreSkippedVersion) {
                    val skipped = normalizeVersionString(securityManager.getSkippedUpdateVersion())
                    if (skipped.isNotEmpty() && skipped == latestVersion) {
                        return@withContext UpdateCheckResult.UpToDate(
                            currentVersion = currentNormalized,
                            latestVersion = latestVersion
                        )
                    }
                }

                val apkAsset = release.assets.firstOrNull { asset ->
                    asset.name.endsWith(".apk", ignoreCase = true) &&
                        asset.browserDownloadUrl.isNotBlank()
                }

                val fallbackPage = release.htmlUrl.ifBlank {
                    "https://github.com/${slug.owner}/${slug.repo}/releases/latest"
                }
                val downloadUrl = apkAsset?.browserDownloadUrl?.takeIf { it.isNotBlank() } ?: fallbackPage

                UpdateCheckResult.UpdateAvailable(
                    currentVersion = currentNormalized,
                    release = AppReleaseInfo(
                        versionName = latestVersion,
                        tagName = release.tagName,
                        releaseTitle = release.name?.trim().takeUnless { it.isNullOrEmpty() }
                            ?: "Cash Control v$latestVersion",
                        releaseNotes = release.body?.trim().takeUnless { it.isNullOrEmpty() }
                            ?: "A new Cash Control release is available on GitHub.",
                        downloadUrl = downloadUrl,
                        releasePageUrl = fallbackPage,
                        assetName = apkAsset?.name?.takeIf { it.isNotBlank() },
                        assetSizeBytes = apkAsset?.size?.takeIf { it > 0L },
                        publishedAt = release.publishedAt,
                        hasDirectApkAsset = apkAsset != null
                    )
                )
            } catch (e: HttpException) {
                val msg = when (e.code()) {
                    404 -> "No published GitHub releases found yet for ${slug.fullSlug}."
                    403 -> "GitHub API rate limit reached. Try again later."
                    else -> "GitHub update check failed (HTTP ${e.code()})."
                }
                UpdateCheckResult.Error(msg)
            } catch (e: Exception) {
                UpdateCheckResult.Error(
                    e.localizedMessage?.takeIf { it.isNotBlank() }
                        ?: "Unable to reach GitHub Releases."
                )
            }
        }

    companion object {
        private const val GITHUB_API_BASE_URL = "https://api.github.com/"

        fun defaultGitHubReleasesApi(): GitHubReleasesApi {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val moshi = Moshi.Builder().build()
            return Retrofit.Builder()
                .baseUrl(GITHUB_API_BASE_URL)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(GitHubReleasesApi::class.java)
        }
    }
}
