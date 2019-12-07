package me.henneke.wearauthn.fido.context

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import android.security.keystore.UserNotAuthenticatedException
import android.text.Html
import android.text.Spanned
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import com.google.android.gms.common.util.Hex
import kotlinx.coroutines.*
import me.henneke.wearauthn.base64
import me.henneke.wearauthn.escapeHtml
import me.henneke.wearauthn.fido.context.AuthenticatorAction.*
import me.henneke.wearauthn.fido.ctap2.AttestationType
import me.henneke.wearauthn.fido.ctap2.CborValue
import me.henneke.wearauthn.fido.ctap2.CtapError
import me.henneke.wearauthn.fido.ctap2.CtapErrorException
import me.henneke.wearauthn.fido.u2f.resolveAppIdHash
import me.henneke.wearauthn.ui.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val TAG = "AuthenticatorContext"

private const val COUNTERS_PREFERENCE_FILE = "counters"
private val COUNTERS_WRITE_LOCK = Object()

private const val CACHED_CREDENTIAL_ALIAS_PREFERENCE_KEY = "cached_credential_key_alias"
private val CACHED_CREDENTIAL_ALIAS_WRITE_LOCK = Object()

const val FUSE_CREATED_PREFERENCE_KEY = "fuse_created"
private const val USE_ANDROID_ATTESTATION_PREFERENCE_KEY = "use_android_attestation"

private const val RESIDENT_KEY_PREFERENCE_FILE_PREFIX = "rp_id_hash_"
private const val RESIDENT_KEY_RP_ID_HASHES_FILE = "rp_id_hashes"

enum class AuthenticatorAction {
    AUTHENTICATE,
    AUTHENTICATE_NO_CREDENTIALS,
    REGISTER,
    REGISTER_CREDENTIAL_EXCLUDED,
    REQUIREMENTS_NOT_MET_CHROME
}

sealed class RequestInfo(open val action: AuthenticatorAction) {
    protected abstract val formattedRp: String?
    protected abstract val shortRp: String?
    protected abstract val formattedUser: String?
    protected abstract val formattedAdditionalInfo: String
    protected abstract val shortAdditionalInfo: String

    private val formattedRpPart by lazy { if (formattedRp != null) " to $formattedRp" else "" }
    private val shortRpPart by lazy { if (shortRp != null) " to $shortRp" else "" }
    private val formattedUserPart by lazy { if (formattedUser != null) " as $formattedUser" else "" }

    val confirmationPrompt: Spanned
        get() = Html.fromHtml(
            when (action) {
                AUTHENTICATE -> "Authenticate$formattedRpPart$formattedUserPart?$formattedAdditionalInfo"
                AUTHENTICATE_NO_CREDENTIALS -> "Reveal that you are not registered?"
                REGISTER -> "Register$formattedRpPart$formattedUserPart?$formattedAdditionalInfo"
                REGISTER_CREDENTIAL_EXCLUDED -> "Reveal previous registration or error$formattedRpPart?"
                REQUIREMENTS_NOT_MET_CHROME -> "You are not registered to the site or it requires single-factor mode to be enabled.<br/>Show a more detailed error message in the browser?"
            }, Html.FROM_HTML_MODE_LEGACY
        )

    val successMessage: String
        get() = when (action) {
            AUTHENTICATE -> "Authenticated$shortRpPart$shortAdditionalInfo"
            AUTHENTICATE_NO_CREDENTIALS -> "Revealed that you are not registered"
            REGISTER -> "Registered$shortRpPart$shortAdditionalInfo"
            REGISTER_CREDENTIAL_EXCLUDED -> "Revealed previous registration or error$shortRpPart"
            REQUIREMENTS_NOT_MET_CHROME -> "Not registered or error encountered"
        }
}

data class U2fRequestInfo(override val action: AuthenticatorAction, val appIdHash: ByteArray) :
    RequestInfo(action) {
    override val formattedRp = resolveAppIdHash(appIdHash)?.let { "<br/><b>$it</b><br/>" }
    override val shortRp = resolveAppIdHash(appIdHash)
    override val formattedUser: String? = null
    override val formattedAdditionalInfo = ""
    override val shortAdditionalInfo = ""
}

data class Ctap2RequestInfo(
    override val action: AuthenticatorAction,
    val rpId: String,
    val rpName: String? = null,
    val userName: String? = null,
    val userDisplayName: String? = null,
    val requiresUserVerification: Boolean = false,
    val usesResidentKey: Boolean = false
) :
    RequestInfo(action) {

    override val formattedRp = if (!rpName.isNullOrBlank())
        "<br/><b>${rpId.escapeHtml()}</b><br/>(“${rpName.escapeHtml()}”)<br/>"
    else
        "<br/><b>${rpId.escapeHtml()}</b><br/>"

    override val shortRp = rpId

    override val formattedUser = if (!userName.isNullOrBlank())
        userName.escapeHtml()
    else if (!userDisplayName.isNullOrBlank())
        userDisplayName.escapeHtml()
    else null

    override val formattedAdditionalInfo = if (requiresUserVerification || usesResidentKey) {
        "<br/>"
    } else {
        ""
    } + if (requiresUserVerification) {
        when (action) {
            AUTHENTICATE -> "<br/>You may have to reconfirm your screen lock."
            REGISTER -> "<br/>You may have to reconfirm your screen lock to log in and <b>loose access if you disable it</b>."
            else -> ""
        }
    } else {
        ""
    } + if (usesResidentKey) {
        when (action) {
            AUTHENTICATE -> "<br/>You will be asked to select an account."
            REGISTER -> "<br/>Your association with the site will be stored on your watch."
            else -> ""
        }
    } else {
        ""
    }

    override val shortAdditionalInfo = if (requiresUserVerification && action == REGISTER) {
        ". Do not disable the screen lock."
    } else {
        ""
    }
}

fun refreshCachedWebAuthnCredentialIfNecessary(context: Context) {
    synchronized(CACHED_CREDENTIAL_ALIAS_WRITE_LOCK) {
        val keyAlias = getCachedCredentialKeyAlias(context)
        if (keyAlias == null) {
            val newKeyAlias = generateWebAuthnCredential()
            if (newKeyAlias == null) {
                Log.e(TAG, "Failed to refresh WebAuthn credential cache")
            } else {
                setCachedCredentialKeyAlias(
                    context,
                    newKeyAlias
                )
                Log.i(TAG, "Refreshed the credential cache")
            }
        }
    }
}

private fun getCachedCredentialKeyAlias(context: Context): String? {
    return context.defaultSharedPreferences.getString(
        CACHED_CREDENTIAL_ALIAS_PREFERENCE_KEY, null
    )
}

private fun setCachedCredentialKeyAlias(context: Context, keyAlias: String?) {
    context.defaultSharedPreferences.edit {
        putString(CACHED_CREDENTIAL_ALIAS_PREFERENCE_KEY, keyAlias)
    }
}

enum class AuthenticatorStatus {
    IDLE,
    PROCESSING,
    WAITING_FOR_UP
}

enum class AuthenticatorSpecialStatus {
    RESET,
    USER_NOT_AUTHENTICATED
}

@ExperimentalUnsignedTypes
abstract class AuthenticatorContext(val isHidTransport: Boolean) {
    abstract fun notifyUser(info: RequestInfo)
    abstract fun handleSpecialStatus(specialStatus: AuthenticatorSpecialStatus)
    abstract suspend fun confirmWithUser(info: RequestInfo): Boolean

    // We use cached credentials only over NFC, where low latency responses are very important
    private val useCachedCredential = !isHidTransport
    var status: AuthenticatorStatus =
        AuthenticatorStatus.IDLE
    var getNextAssertionBuffer: Iterator<CborValue>? = null
    var getNextAssertionRequestInfo: RequestInfo? = null

    private lateinit var counterPrefs: SharedPreferences
    private lateinit var context: Context

    fun commitContext(context: Context) {
        this.context = context
        // Even if the context changes, the SharedPreferences instance will remain the same - so we keep it
        if (!::counterPrefs.isInitialized) {
            counterPrefs = context.sharedPreferences(COUNTERS_PREFERENCE_FILE)
        }

        initAuthenticator(
            context
        )
    }

    fun getUserVerificationState(obeyTimeout: Boolean = false): Boolean? {
        return getUserVerificationState(
            context,
            obeyTimeout
        )
    }

    fun getOrCreateFreshWebAuthnCredential(
        residentKey: Boolean = false,
        attestationChallenge: ByteArray? = null
    ): Pair<String, AttestationType>? {
        val useAndroidAttestation =
            context.defaultSharedPreferences.getBoolean(
                USE_ANDROID_ATTESTATION_PREFERENCE_KEY, true
            )
        val actualAttestationChallenge = if (useAndroidAttestation) attestationChallenge else null

        if (useCachedCredential && !residentKey) {
            synchronized(CACHED_CREDENTIAL_ALIAS_WRITE_LOCK) {
                val keyAlias =
                    getCachedCredentialKeyAlias(context)
                if (keyAlias != null) {
                    setCachedCredentialKeyAlias(
                        context,
                        null
                    )
                    // attestationChallenge is ignored when using a cached key
                    return Pair(keyAlias, AttestationType.SELF)
                }
            }
        }

        val credential = generateWebAuthnCredential(
            residentKey,
            actualAttestationChallenge
        )
        if (credential == null) {
            if (actualAttestationChallenge == null) {
                Log.e(TAG, "Key generation failed without attestation; giving up")
                return null
            }
            // We failed to generate a Keystore key, which may be due to attestation failing on this
            // device. Since attestation is not essential, we fall back to self attestation for all
            // future key generations.
            Log.e(TAG, "Key generation failed; falling back to self attestation in the future")
            context.defaultSharedPreferences.edit {
                putBoolean(USE_ANDROID_ATTESTATION_PREFERENCE_KEY, false)
            }
            // Directly retry without attestation; explicitly set attestation challenge to null to
            // prevent an infinite loop in case shared preferences are flaky.
            return getOrCreateFreshWebAuthnCredential(residentKey, null)
        }
        return Pair(credential, AttestationType.ANDROID_KEYSTORE)
    }

    fun refreshCachedWebAuthnCredentialIfNecessary() {
        refreshCachedWebAuthnCredentialIfNecessary(context)
    }

    fun initCounter(keyAlias: String) {
        synchronized(COUNTERS_WRITE_LOCK) {
            setCounter(keyAlias, 0u)
        }
    }

    fun atomicallyIncrementAndGetCounter(keyAlias: String): UInt? {
        synchronized(COUNTERS_WRITE_LOCK) {
            val current = getCounter(keyAlias) ?: return null
            val new = current + 1u
            setCounter(keyAlias, new)
            return new
        }
    }

    fun isValidWebAuthnCredentialKeyAlias(keyAlias: String): Boolean {
        return getCounter(keyAlias) != null && isValidKeyAlias(
            keyAlias
        )
    }

    suspend fun <T> authenticateUserFor(block: () -> T): T? {
        // Confirm device credential and retry if block throws a UserNotAuthenticatedException.
        return try {
            block()
        } catch (e1: UserNotAuthenticatedException) {
            confirmDeviceCredentialInternal(updateAuthenticatorStatus = true)
            try {
                block()
            } catch (e2: UserNotAuthenticatedException) {
                null
            }
        }
    }

    suspend fun verifyUser(): Boolean {
        val keyguardManager = context.keyguardManager ?: return false
        if (keyguardManager.isDeviceLocked)
            return false
        return try {
            // Check whether user verification is configured; throws an exception if the user has
            // not authenticated during the timeout duration.
            getUserVerificationState(obeyTimeout = true) == true
        } catch (e: UserNotAuthenticatedException) {
            if (!isHidTransport) {
                handleSpecialStatus(AuthenticatorSpecialStatus.USER_NOT_AUTHENTICATED)
                false
            } else {
                confirmDeviceCredentialInternal(updateAuthenticatorStatus = true)
                try {
                    getUserVerificationState(obeyTimeout = true) == true
                } catch (e: UserNotAuthenticatedException) {
                    false
                }
            }
        }
    }

    fun armUserVerificationFuse() {
        armUserVerificationFuse(context)
    }

    suspend fun confirmDeviceCredential() {
        confirmDeviceCredentialInternal(updateAuthenticatorStatus = false)
    }

    private suspend fun confirmDeviceCredentialInternal(updateAuthenticatorStatus: Boolean) {
        if (updateAuthenticatorStatus)
            status = AuthenticatorStatus.WAITING_FOR_UP
        withContext(Dispatchers.Main) {
            val confirmCredentialJob = launch {
                suspendCoroutine<Nothing?> { continuation ->
                    val intent =
                        Intent(context, ConfirmDeviceCredentialActivity::class.java).apply {
                            putExtra(
                                EXTRA_CONFIRM_DEVICE_CREDENTIAL_RECEIVER,
                                object : ResultReceiver(Handler()) {
                                    override fun onReceiveResult(
                                        resultCode: Int,
                                        resultData: Bundle?
                                    ) {
                                        continuation.resume(null)
                                    }
                                })
                        }
                    context.startActivity(intent)
                }
            }
            delay(1_000)
            wink(context)
            confirmCredentialJob.join()
        }
        if (updateAuthenticatorStatus)
            status = AuthenticatorStatus.PROCESSING
    }

    suspend fun chooseCredential(credentials: List<LocalCredential>): LocalCredential? {
        check(isHidTransport)
        require(credentials.isNotEmpty())
        if (credentials.size == 1)
            return credentials.first()
        // If there is more than one credential to choose from, all of them must be resident.
        require(credentials.all { it is WebAuthnLocalCredential && it.isResident })
        val credentialsArray = credentials.map { it as WebAuthnLocalCredential }.toTypedArray()
        status = AuthenticatorStatus.WAITING_FOR_UP
        val credential = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<WebAuthnLocalCredential?> { continuation ->
                val dialog = CredentialChooserDialog(credentialsArray, context) {
                    continuation.resume(it)
                }
                dialog.show()
                continuation.invokeOnCancellation {
                    dialog.dismiss()
                }
            }
        }
        status = AuthenticatorStatus.PROCESSING
        return credential
    }

    suspend fun setResidentCredential(
        rpIdHash: ByteArray,
        userId: ByteArray,
        credential: WebAuthnLocalCredential,
        userVerified: Boolean
    ) {
        require(rpIdHash.size == 32)
        require(userId.size <= 64)
        val encodedUserId = userId.base64()
        val encodedKeyHandle = credential.keyHandle.base64()
        val serializedCredential = try {
            authenticateUserFor {
                credential.serialize(userVerified)
            } ?: throw CtapErrorException(CtapError.OperationDenied)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize user data: $e")
            throw CtapErrorException(CtapError.Other)
        }
        getResidentKeyPrefsForRpId(rpIdHash).edit {
            putString("uid+$encodedUserId", serializedCredential)
            putString("kh+$encodedKeyHandle", encodedUserId)
        }
        Log.i(TAG, "Resident credential stored for ${Hex.bytesToStringUppercase(rpIdHash)}")
    }

    fun lookupAndReplaceWithResidentCredential(credential: LocalCredential): LocalCredential {
        val encodedKeyHandle = credential.keyHandle.base64()
        val encodedUserId =
            getResidentKeyPrefsForRpId(credential.rpIdHash).getString("kh+$encodedKeyHandle", null)
                ?: return credential
        return (getResidentCredential(credential.rpIdHash, encodedUserId) ?: credential).also {
            if (it != credential)
                Log.i(TAG, "Replaced allowList credential with resident credential")
        }
    }

    fun getResidentCredential(
        rpIdHash: ByteArray,
        encodedUserId: String
    ): WebAuthnLocalCredential? {
        require(rpIdHash.size == 32)
        val serialized =
            getResidentKeyPrefsForRpId(rpIdHash).getString("uid+$encodedUserId", null)
                ?: return null
        return WebAuthnLocalCredential.deserialize(
            serialized,
            rpIdHash
        )
    }

    fun getResidentKeyUserIdsForRpId(rpIdHash: ByteArray): List<ByteArray> {
        require(rpIdHash.size == 32)
        val prefs = getResidentKeyPrefsForRpId(rpIdHash)
        return prefs.all.keys.filter { it.startsWith("uid+") }
            .mapNotNull { it.substring(4).base64() }.toList().also {
                Log.i(
                    TAG,
                    "Found ${it.size} resident keys for ${Hex.bytesToStringUppercase(rpIdHash)}"
                )
            }
    }

    private fun getResidentKeyPrefsForRpId(rpIdHash: ByteArray): SharedPreferences {
        val rpIdHashString = rpIdHash.base64()
        context.sharedPreferences(RESIDENT_KEY_RP_ID_HASHES_FILE).edit {
            putBoolean(rpIdHashString, true)
        }
        return context.sharedPreferences(RESIDENT_KEY_PREFERENCE_FILE_PREFIX + rpIdHashString)
    }

    suspend fun requestReset(): Boolean {
        return try {
            status = AuthenticatorStatus.WAITING_FOR_UP
            withContext(Dispatchers.Main) {
                suspendCoroutine<Boolean> { continuation ->
                    val intent =
                        Intent(context, ManageSpaceActivity::class.java).apply {
                            putExtra(
                                EXTRA_MANAGE_SPACE_RECEIVER,
                                object : ResultReceiver(Handler()) {
                                    override fun onReceiveResult(
                                        resultCode: Int,
                                        resultData: Bundle?
                                    ) {
                                        continuation.resume(resultCode == Activity.RESULT_OK)
                                    }
                                })
                        }
                    context.startActivity(intent)
                }
            }
        } finally {
            status = AuthenticatorStatus.PROCESSING
        }
    }

    private fun getCounter(keyAlias: String): UInt? {
        val counter = counterPrefs.getLong(keyAlias, -1)
        return if (counter >= 0) counter.toUInt() else null
    }

    private fun setCounter(keyAlias: String, counter: UInt) {
        counterPrefs.edit {
            putLong(keyAlias, counter.toLong())
        }
    }

    internal fun deleteCounter(keyAlias: String) {
        counterPrefs.edit {
            remove(keyAlias)
        }
    }

    internal suspend fun deleteAllData() {
        deleteAllData(
            context
        )
    }

    companion object {

        fun initAuthenticator(context: Context) {
            if (isKeystoreEmpty) {
                context.defaultSharedPreferences.edit {
                    putBoolean(FUSE_CREATED_PREFERENCE_KEY, false)
                }
                Log.i(TAG, "Set 'fuse_created' preference to false")
            }
            initMasterSigningKeyIfNecessary()
            // We create a dummy signature with the master signing key to get it cached.
            pokeMasterSigningKey()
        }

        @WorkerThread
        suspend fun deleteAllData(context: Context) {
            withContext(Dispatchers.IO) {
                context.defaultSharedPreferences.edit(commit = true) {
                    remove(CACHED_CREDENTIAL_ALIAS_PREFERENCE_KEY)
                    remove(FUSE_CREATED_PREFERENCE_KEY)
                }
                context.sharedPreferences(COUNTERS_PREFERENCE_FILE).edit(commit = true) {
                    clear()
                }
                for (rpIdHashString in context.sharedPreferences(RESIDENT_KEY_RP_ID_HASHES_FILE).all.keys) {
                    context.sharedPreferences(RESIDENT_KEY_RP_ID_HASHES_FILE + rpIdHashString)
                        .edit(commit = true) {
                            clear()
                        }
                }
                context.sharedPreferences(RESIDENT_KEY_RP_ID_HASHES_FILE).edit(commit = true) {
                    clear()
                }
                deleteAllKeys()

                initAuthenticator(context)
            }
        }

        fun isScreenLockEnabled(context: Context) = context.keyguardManager?.isDeviceSecure == true
    }
}