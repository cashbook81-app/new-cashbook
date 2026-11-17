package com.example.update

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.SecurityManager
import com.example.data.remote.update.AppReleaseInfo
import com.example.data.remote.update.AppUpdateChecker
import com.example.data.remote.update.GitHubAssetDto
import com.example.data.remote.update.GitHubReleaseDto
import com.example.data.remote.update.GitHubReleasesApi
import com.example.data.remote.update.UpdateCheckResult
import com.example.data.remote.update.compareSemver
import com.example.data.remote.update.normalizeVersionString
import com.example.data.remote.update.parseGitHubRepoSlug
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.update.AppUpdateDialog
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response

private class FakeGitHubReleasesApi(
    private val handler: suspend (owner: String, repo: String) -> GitHubReleaseDto
) : GitHubReleasesApi {
    override suspend fun getLatestRelease(owner: String, repo: String): GitHubReleaseDto {
        return handler(owner, repo)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AppUpdateCheckerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var securityManager: SecurityManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        securityManager = SecurityManager(context)
    }

    @Test
    fun compareSemver_handlesNumericSegmentsAndPrereleases() {
        assertTrue(compareSemver("1.0.1", "1.0.0") > 0)
        assertTrue(compareSemver("1.10.0", "1.2.0") > 0)
        assertTrue(compareSemver("v2.0.0", "1.9.9") > 0)
        assertTrue(compareSemver("1.0.0", "1.0.0-rc1") > 0)
        assertEquals(0, compareSemver("v1.2.0", "1.2"))
        assertTrue(compareSemver("1.0.0", "1.0.1") < 0)
    }

    @Test
    fun parseGitHubRepoSlug_parsesSlugAndUrlsCleanly() {
        val fromSlug = parseGitHubRepoSlug("cbo-bank/cash-control")
        assertNotNull(fromSlug)
        assertEquals("cbo-bank", fromSlug?.owner)
        assertEquals("cash-control", fromSlug?.repo)

        val fromUrl = parseGitHubRepoSlug("https://github.com/cbo-bank/cash-control.git/")
        assertNotNull(fromUrl)
        assertEquals("cbo-bank/cash-control", fromUrl?.fullSlug)

        val fromReleasesUrl = parseGitHubRepoSlug("https://github.com/cbo-bank/cash-control/releases/latest")
        assertNotNull(fromReleasesUrl)
        assertEquals("cbo-bank/cash-control", fromReleasesUrl?.fullSlug)

        assertNull(parseGitHubRepoSlug(""))
        assertNull(parseGitHubRepoSlug("onlyowner"))
        assertNull(parseGitHubRepoSlug("bad owner/repo"))
    }

    @Test
    fun normalizeVersionString_stripsLeadingV() {
        assertEquals("1.4.2", normalizeVersionString("v1.4.2"))
        assertEquals("2.0.0", normalizeVersionString(" V2.0.0 "))
    }

    @Test
    fun checkForUpdate_returnsNotConfiguredWhenRepoMissing() = runTest {
        securityManager.setGitHubRepoOverride("")
        val checker = AppUpdateChecker(
            securityManager = securityManager,
            api = FakeGitHubReleasesApi { _, _ -> error("Should not be called") },
            currentVersionName = "1.0.0",
            buildConfigRepoSlug = ""
        )

        val result = checker.checkForUpdate()
        assertTrue(result is UpdateCheckResult.NotConfigured)
    }

    @Test
    fun checkForUpdate_findsNewerApkAssetAndRespectsSkipFlag() = runTest {
        securityManager.setGitHubRepoOverride("bankorg/cash-control")
        securityManager.setSkippedUpdateVersion("")

        val fakeApi = FakeGitHubReleasesApi { owner, repo ->
            assertEquals("bankorg", owner)
            assertEquals("cash-control", repo)
            GitHubReleaseDto(
                tagName = "v1.2.0",
                name = "Cash Control v1.2.0",
                body = "- Vault reconciliation speedups\n- Branch audit polish",
                htmlUrl = "https://github.com/bankorg/cash-control/releases/tag/v1.2.0",
                assets = listOf(
                    GitHubAssetDto(
                        name = "checksums.txt",
                        browserDownloadUrl = "https://github.com/bankorg/cash-control/releases/download/v1.2.0/checksums.txt",
                        size = 256L
                    ),
                    GitHubAssetDto(
                        name = "CashControl-v1.2.0.apk",
                        browserDownloadUrl = "https://github.com/bankorg/cash-control/releases/download/v1.2.0/CashControl-v1.2.0.apk",
                        size = 18_874_368L
                    )
                )
            )
        }

        val checker = AppUpdateChecker(
            securityManager = securityManager,
            api = fakeApi,
            currentVersionName = "1.0.0",
            buildConfigRepoSlug = ""
        )

        val firstCheck = checker.checkForUpdate(ignoreSkippedVersion = false)
        assertTrue(firstCheck is UpdateCheckResult.UpdateAvailable)
        val available = firstCheck as UpdateCheckResult.UpdateAvailable
        assertEquals("1.2.0", available.release.versionName)
        assertEquals("CashControl-v1.2.0.apk", available.release.assetName)
        assertTrue(available.release.hasDirectApkAsset)
        assertTrue(available.release.downloadUrl.endsWith(".apk"))

        // Skipping v1.2.0 suppresses automatic prompts, while manual check still shows it
        securityManager.setSkippedUpdateVersion("1.2.0")
        val suppressedCheck = checker.checkForUpdate(ignoreSkippedVersion = false)
        assertTrue(suppressedCheck is UpdateCheckResult.UpToDate)

        val manualCheck = checker.checkForUpdate(ignoreSkippedVersion = true)
        assertTrue(manualCheck is UpdateCheckResult.UpdateAvailable)
    }

    @Test
    fun checkForUpdate_ignoresPrereleasesAndHandles404() = runTest {
        securityManager.setGitHubRepoOverride("bankorg/cash-control")

        val prereleaseChecker = AppUpdateChecker(
            securityManager = securityManager,
            api = FakeGitHubReleasesApi { _, _ ->
                GitHubReleaseDto(tagName = "v2.0.0-beta1", prerelease = true)
            },
            currentVersionName = "1.0.0",
            buildConfigRepoSlug = ""
        )
        assertTrue(prereleaseChecker.checkForUpdate() is UpdateCheckResult.UpToDate)

        val notFoundChecker = AppUpdateChecker(
            securityManager = securityManager,
            api = FakeGitHubReleasesApi { _, _ ->
                throw HttpException(
                    Response.error<GitHubReleaseDto>(
                        404,
                        "{}".toResponseBody("application/json".toMediaType())
                    )
                )
            },
            currentVersionName = "1.0.0",
            buildConfigRepoSlug = ""
        )
        val errorResult = notFoundChecker.checkForUpdate()
        assertTrue(errorResult is UpdateCheckResult.Error)
        assertTrue((errorResult as UpdateCheckResult.Error).message.contains("No published GitHub releases"))
    }

    @Test
    fun appUpdateDialog_displaysReleaseDetailsAndTriggersCallbacks() {
        var skipped: String? = null
        var dismissed = false
        var downloadTriggered = false

        val release = AppReleaseInfo(
            versionName = "1.1.0",
            tagName = "v1.1.0",
            releaseTitle = "Cash Control v1.1.0",
            releaseNotes = "Added automatic GitHub version release notifications.",
            downloadUrl = "https://github.com/bankorg/cash-control/releases/download/v1.1.0/CashControl-v1.1.0.apk",
            releasePageUrl = "https://github.com/bankorg/cash-control/releases/tag/v1.1.0",
            assetName = "CashControl-v1.1.0.apk",
            assetSizeBytes = 15_728_640L,
            publishedAt = "2026-11-17T08:00:00Z",
            hasDirectApkAsset = true
        )

        composeRule.setContent {
            MyApplicationTheme {
                AppUpdateDialog(
                    currentVersion = "1.0.0",
                    release = release,
                    onDismiss = { dismissed = true },
                    onSkipVersion = { skipped = it },
                    onDownloadClick = { downloadTriggered = true }
                )
            }
        }

        composeRule.onNodeWithText("Update Available").assertIsDisplayed()
        composeRule.onNodeWithText("v1.0.0  ->  v1.1.0").assertIsDisplayed()
        composeRule.onNodeWithText("15.0 MB").assertIsDisplayed()
        composeRule.onNodeWithTag("update_download_button").performClick()
        assertTrue(downloadTriggered)
        assertTrue(dismissed)

        composeRule.onNodeWithTag("update_skip_button").performClick()
        assertEquals("1.1.0", skipped)
    }
}
