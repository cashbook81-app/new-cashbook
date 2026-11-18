package com.example.data.local

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "security_settings")

enum class BiometricAvailability {
    AVAILABLE,
    NONE_ENROLLED,
    UNAVAILABLE
}

data class PersistedBundleCount(
    val denominationMinor: Long,
    val bundles: Int,
    val notesPerBundle: Int,
    val looseNotes: Int
)

data class PersistedPettyCount(
    val denominationMinor: Long,
    val quantity: Int
)

data class PersistedDenominationCounts(
    val bundles: List<PersistedBundleCount> = emptyList(),
    val petty: List<PersistedPettyCount> = emptyList()
) {
    fun hasNonZeroCounts(): Boolean {
        return bundles.any { it.bundles > 0 || it.looseNotes > 0 } || petty.any { it.quantity > 0 }
    }
}

class SecurityManager(private val context: Context) {

    private val BIOMETRIC_ENABLED_KEY = booleanPreferencesKey("biometric_enabled")
    private val REQUIRE_BIOMETRIC_ON_SUBMIT_KEY = booleanPreferencesKey("require_biometric_on_submit")
    private val AUTO_LOCK_TIMEOUT_MIN_KEY = intPreferencesKey("auto_lock_timeout_minutes")
    private val LAST_VERIFIED_TS_KEY = longPreferencesKey("last_verified_timestamp")
    private val GITHUB_REPO_OVERRIDE_KEY = stringPreferencesKey("github_repo_override")
    private val SKIPPED_UPDATE_VERSION_KEY = stringPreferencesKey("skipped_update_version")

    private fun workingCountsKey(userId: String) = stringPreferencesKey("working_counts_${userId.ifBlank { "default" }}")
    private fun cachedProfileKey(userId: String) = stringPreferencesKey("cached_profile_${userId.ifBlank { "default" }}")
    private fun biometricPromptedKey(userId: String) = booleanPreferencesKey("biometric_prompted_${userId.ifBlank { "default" }}")
    private fun acknowledgedAlertsKey(userId: String) = stringPreferencesKey("acknowledged_alerts_${userId.ifBlank { "default" }}")

    private val allowedAuthenticators =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun isBiometricEnabled(): Flow<Boolean> {
        return context.dataStore.data.map { preferences ->
            preferences[BIOMETRIC_ENABLED_KEY] ?: false
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[BIOMETRIC_ENABLED_KEY] = enabled
        }
    }

    fun isRequireBiometricOnSubmit(): Flow<Boolean> {
        return context.dataStore.data.map { preferences ->
            preferences[REQUIRE_BIOMETRIC_ON_SUBMIT_KEY] ?: true
        }
    }

    suspend fun setRequireBiometricOnSubmit(required: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[REQUIRE_BIOMETRIC_ON_SUBMIT_KEY] = required
        }
    }

    fun getAutoLockTimeoutMinutes(): Flow<Int> {
        return context.dataStore.data.map { preferences ->
            preferences[AUTO_LOCK_TIMEOUT_MIN_KEY] ?: 5
        }
    }

    suspend fun setAutoLockTimeoutMinutes(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_LOCK_TIMEOUT_MIN_KEY] = minutes
        }
    }

    fun getLastVerifiedTimestamp(): Flow<Long?> {
        return context.dataStore.data.map { preferences ->
            preferences[LAST_VERIFIED_TS_KEY]
        }
    }

    suspend fun recordBiometricVerification(timestamp: Long = System.currentTimeMillis()) {
        context.dataStore.edit { preferences ->
            preferences[LAST_VERIFIED_TS_KEY] = timestamp
        }
    }

    fun getGitHubRepoOverrideFlow(): Flow<String> {
        return context.dataStore.data.map { preferences ->
            preferences[GITHUB_REPO_OVERRIDE_KEY].orEmpty()
        }
    }

    suspend fun getGitHubRepoOverride(): String {
        return context.dataStore.data.first()[GITHUB_REPO_OVERRIDE_KEY].orEmpty()
    }

    suspend fun setGitHubRepoOverride(repo: String) {
        val cleaned = repo.trim()
        context.dataStore.edit { preferences ->
            if (cleaned.isEmpty()) {
                preferences.remove(GITHUB_REPO_OVERRIDE_KEY)
            } else {
                preferences[GITHUB_REPO_OVERRIDE_KEY] = cleaned
            }
        }
    }

    suspend fun getSkippedUpdateVersion(): String {
        return context.dataStore.data.first()[SKIPPED_UPDATE_VERSION_KEY].orEmpty()
    }

    suspend fun setSkippedUpdateVersion(version: String) {
        val cleaned = version.trim()
        context.dataStore.edit { preferences ->
            if (cleaned.isEmpty()) {
                preferences.remove(SKIPPED_UPDATE_VERSION_KEY)
            } else {
                preferences[SKIPPED_UPDATE_VERSION_KEY] = cleaned
            }
        }
    }

    suspend fun saveCachedUserProfile(userId: String, profile: com.example.data.remote.model.UserDto) {
        if (userId.isBlank()) return
        val json = JSONObject().apply {
            put("fullName", profile.fullName)
            put("email", profile.email)
            put("staffId", profile.staffId)
            put("branchId", profile.branchId)
            put("role", profile.role)
            put("photoBase64", profile.photoBase64)
            put("active", profile.active)
            put("canCreateCashSession", profile.canCreateCashSession)
            put("canEditOwnDraft", profile.canEditOwnDraft)
            put("canFinalizeDailyClosing", profile.canFinalizeDailyClosing)
            put("canViewOwnBranchHistory", profile.canViewOwnBranchHistory)
            put("biometricEnabled", profile.biometricEnabled)
            put("requireBiometricOnSubmit", profile.requireBiometricOnSubmit)
            put("autoLockTimeoutMinutes", profile.autoLockTimeoutMinutes)
        }
        context.dataStore.edit { preferences ->
            preferences[cachedProfileKey(userId)] = json.toString()
        }
    }

    suspend fun readBootstrapPreferences(userId: String?): Pair<Boolean, com.example.data.remote.model.UserDto?> {
        val prefs = context.dataStore.data.first()
        val biometricEnabled = prefs[BIOMETRIC_ENABLED_KEY] ?: false
        val cachedProfile = if (!userId.isNullOrBlank()) {
            parseCachedUserProfile(prefs[cachedProfileKey(userId)])
        } else {
            null
        }
        return biometricEnabled to cachedProfile
    }

    suspend fun getCachedUserProfile(userId: String): com.example.data.remote.model.UserDto? {
        if (userId.isBlank()) return null
        val raw = context.dataStore.data.first()[cachedProfileKey(userId)] ?: return null
        return parseCachedUserProfile(raw)
    }

    private fun parseCachedUserProfile(raw: String?): com.example.data.remote.model.UserDto? {
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(raw)
            com.example.data.remote.model.UserDto(
                fullName = obj.optString("fullName", "Staff Member"),
                email = obj.optString("email", ""),
                staffId = obj.optString("staffId", "CSO001"),
                branchId = obj.optString("branchId", "BRANCH001"),
                role = obj.optString("role", "CSO"),
                photoBase64 = obj.optString("photoBase64", ""),
                active = obj.optBoolean("active", true),
                canCreateCashSession = obj.optBoolean("canCreateCashSession", true),
                canEditOwnDraft = obj.optBoolean("canEditOwnDraft", true),
                canFinalizeDailyClosing = obj.optBoolean("canFinalizeDailyClosing", true),
                canViewOwnBranchHistory = obj.optBoolean("canViewOwnBranchHistory", true),
                biometricEnabled = obj.optBoolean("biometricEnabled", false),
                requireBiometricOnSubmit = obj.optBoolean("requireBiometricOnSubmit", true),
                autoLockTimeoutMinutes = obj.optInt("autoLockTimeoutMinutes", 5)
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun hasPromptedBiometricEnrollment(userId: String): Boolean {
        if (userId.isBlank()) return false
        return context.dataStore.data.first()[biometricPromptedKey(userId)] ?: false
    }

    suspend fun setPromptedBiometricEnrollment(userId: String, prompted: Boolean = true) {
        if (userId.isBlank()) return
        context.dataStore.edit { preferences ->
            preferences[biometricPromptedKey(userId)] = prompted
        }
    }

    suspend fun getAcknowledgedAlertKeys(userId: String): Set<String> {
        if (userId.isBlank()) return emptySet()
        val raw = context.dataStore.data.first()[acknowledgedAlertsKey(userId)] ?: return emptySet()
        return try {
            val array = JSONArray(raw)
            buildSet {
                for (i in 0 until array.length()) {
                    val item = array.optString(i)
                    if (item.isNotBlank()) add(item)
                }
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    suspend fun markAlertKeyAcknowledged(userId: String, alertKey: String) {
        if (userId.isBlank() || alertKey.isBlank()) return
        context.dataStore.edit { preferences ->
            val key = acknowledgedAlertsKey(userId)
            val existing = try {
                val arr = JSONArray(preferences[key] ?: "[]")
                List(arr.length()) { idx -> arr.optString(idx) }.filter { it.isNotBlank() }
            } catch (_: Exception) {
                emptyList()
            }
            val updated = (existing.filterNot { it == alertKey } + alertKey).takeLast(25)
            preferences[key] = JSONArray(updated).toString()
        }
    }

    suspend fun saveWorkingCounts(userId: String, counts: PersistedDenominationCounts) {
        val root = JSONObject().apply {
            val bundlesArray = JSONArray()
            counts.bundles.forEach { b ->
                bundlesArray.put(
                    JSONObject().apply {
                        put("denominationMinor", b.denominationMinor)
                        put("bundles", b.bundles)
                        put("notesPerBundle", b.notesPerBundle)
                        put("looseNotes", b.looseNotes)
                    }
                )
            }
            val pettyArray = JSONArray()
            counts.petty.forEach { p ->
                pettyArray.put(
                    JSONObject().apply {
                        put("denominationMinor", p.denominationMinor)
                        put("quantity", p.quantity)
                    }
                )
            }
            put("bundles", bundlesArray)
            put("petty", pettyArray)
        }
        context.dataStore.edit { preferences ->
            preferences[workingCountsKey(userId)] = root.toString()
        }
    }

    suspend fun getWorkingCounts(userId: String): PersistedDenominationCounts? {
        val raw = context.dataStore.data.first()[workingCountsKey(userId)] ?: return null
        return try {
            val root = JSONObject(raw)
            val bundlesJson = root.optJSONArray("bundles")
            val pettyJson = root.optJSONArray("petty")
            val bundles = buildList {
                if (bundlesJson != null) {
                    for (i in 0 until bundlesJson.length()) {
                        val obj = bundlesJson.getJSONObject(i)
                        add(
                            PersistedBundleCount(
                                denominationMinor = obj.optLong("denominationMinor", 0L),
                                bundles = obj.optInt("bundles", 0),
                                notesPerBundle = obj.optInt("notesPerBundle", 1000).coerceAtLeast(1),
                                looseNotes = obj.optInt("looseNotes", 0)
                            )
                        )
                    }
                }
            }
            val petty = buildList {
                if (pettyJson != null) {
                    for (i in 0 until pettyJson.length()) {
                        val obj = pettyJson.getJSONObject(i)
                        add(
                            PersistedPettyCount(
                                denominationMinor = obj.optLong("denominationMinor", 0L),
                                quantity = obj.optInt("quantity", 0)
                            )
                        )
                    }
                }
            }
            PersistedDenominationCounts(bundles = bundles, petty = petty)
        } catch (_: Exception) {
            null
        }
    }

    fun canAuthenticate(): Int {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(allowedAuthenticators)
    }

    fun getBiometricAvailability(): BiometricAvailability {
        return when (canAuthenticate()) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NONE_ENROLLED
            else -> BiometricAvailability.UNAVAILABLE
        }
    }

    fun showBiometricPrompt(
        activity: FragmentActivity,
        title: String = "Biometric Authentication",
        subtitle: String = "Verify your fingerprint or biometric credential",
        onSuccess: (BiometricPrompt.AuthenticationResult) -> Unit,
        onError: (Int, CharSequence) -> Unit,
        onFailed: () -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(context)
        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(errorCode, errString)
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess(result)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onFailed()
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(allowedAuthenticators)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}
