package me.henneke.wearauthn.fido.ctap2

import kotlinx.coroutines.delay
import me.henneke.wearauthn.*
import me.henneke.wearauthn.fido.context.*
import me.henneke.wearauthn.fido.context.AuthenticatorAction.*
import me.henneke.wearauthn.fido.ctap2.CtapError.*
import me.henneke.wearauthn.fido.u2f.WEB_AUTHN_RAW_BASIC_ATTESTATION_CERT
import me.henneke.wearauthn.fido.u2f.signWithWebAuthnBatchAttestationKey
import kotlin.experimental.or

@ExperimentalUnsignedTypes
object Authenticator : Logging {
    override val TAG = "Ctap2Authenticator"

    suspend fun handle(context: AuthenticatorContext, rawRequest: ByteArray): ByteArray {
        context.status = AuthenticatorStatus.PROCESSING
        return try {
            if (rawRequest.isEmpty())
                CTAP_ERR(InvalidLength, "Empty CBOR request")
            if (rawRequest.size > 1 + MAX_CBOR_MSG_SIZE)
                CTAP_ERR(RequestTooLarge, "CBOR request exceeds maximal size: ${rawRequest.size}")
            val rawRequestIterator = rawRequest.iterator()
            val rawCommand = rawRequestIterator.next()
            val command = RequestCommand.fromByte(rawCommand)
                ?: CTAP_ERR(InvalidCommand, "Unsupported command: $rawCommand")
            when (command) {
                RequestCommand.MakeCredential -> {
                    i { "MakeCredential called" }
                    val params = fromCborToEnd(rawRequestIterator)
                        ?: CTAP_ERR(InvalidCbor, "Invalid CBOR in MakeCredential request")
                    handleMakeCredential(context, params)
                }
                RequestCommand.GetAssertion -> {
                    i { "GetAssertion called" }
                    val params = fromCborToEnd(rawRequestIterator)
                        ?: CTAP_ERR(InvalidCbor, "Invalid CBOR in GetAssertion request")
                    handleGetAssertion(context, params)
                }
                RequestCommand.GetNextAssertion -> {
                    i { "GetNextAssertion called" }
                    if (rawRequest.size != 1)
                        CTAP_ERR(InvalidLength, "Non-empty params for GetNextAssertion")
                    handleGetNextAssertion(context)
                }
                RequestCommand.GetInfo -> {
                    i { "GetInfo called" }
                    if (rawRequest.size != 1)
                        CTAP_ERR(InvalidLength, "Non-empty params for GetInfo")
                    handleGetInfo(context)
                }
                RequestCommand.ClientPIN -> {
                    i { "ClientPIN called" }
                    val params = fromCborToEnd(rawRequestIterator)
                        ?: CTAP_ERR(InvalidCbor, "Invalid CBOR in ClientPIN request")
                    handleClientPIN(params)
                }
                RequestCommand.Reset -> {
                    i { "Reset called" }
                    if (rawRequest.size != 1)
                        CTAP_ERR(InvalidLength, "Non-empty params for Reset")
                    handleReset(context)
                }
                RequestCommand.Selection -> {
                    i { "Selection called" }
                    if (rawRequest.size != 1)
                        CTAP_ERR(InvalidLength, "Non-empty params for Selection")
                    handleSelection(context)
                }
            }.also { v { "-> $it" } }.toCtapSuccessResponse()
        } catch (e: CtapErrorException) {
            byteArrayOf(e.error.value)
        } finally {
            context.status = AuthenticatorStatus.IDLE
        }
    }

    private suspend fun handleMakeCredential(
        context: AuthenticatorContext,
        params: CborValue
    ): CborValue {
        val clientDataHash =
            params.getRequired(MAKE_CREDENTIAL_CLIENT_DATA_HASH).unbox<ByteArray>()

        val rp = params.getRequired(MAKE_CREDENTIAL_RP)
        val rpId: String = rp.getRequired("id").unbox()
        val rpIdHash = rpId.sha256()
        val rpName = rp.getOptional("name")?.unbox<String>()?.truncate(64)
        // Ensure that the RP icon is a string, even though we do not use it.
        rp.getOptional("icon")?.unbox<String>()

        val user = params.getRequired(MAKE_CREDENTIAL_USER)
        val userId = user.getRequired("id").unbox<ByteArray>()
        if (userId.size > 64)
            CTAP_ERR(InvalidLength, "userId too long: ${userId.size}")
        val userName = user.getRequired("name").unbox<String>().truncate(64)
        val userDisplayName = user.getOptional("displayName")?.unbox<String>()?.truncate(64)
        val userIcon = user.getOptional("icon")?.unbox<String>()

        val pubKeyCredParams =
            params.getRequired(MAKE_CREDENTIAL_PUB_KEY_CRED_PARAMS).unbox<Array<CborValue>>()
        val excludeList =
            params.getOptional(MAKE_CREDENTIAL_EXCLUDE_LIST)?.unbox<Array<CborValue>>()
        val extensions =
            params.getOptional(MAKE_CREDENTIAL_EXTENSIONS)?.unbox<Map<String, CborValue>>()
        val options = params.getOptional(MAKE_CREDENTIAL_OPTIONS)

        // Chrome and Windows Hello use special dummy requests to request a touch from an
        // authenticator in various situations, such as to confirm a reset request is sent to the
        // correct device or to prevent websites from verifying the availability of a given
        // credential without user interaction.
        // https://cs.chromium.org/chromium/src/device/fido/make_credential_task.cc?l=66&rcl=eb40dba9a062951578292de39424d7479f723463
        if ((rpId == ".dummy" && userName == "dummy") /* Chrome */ ||
            (rpId == "SelectDevice" && userName == "SelectDevice") /* Windows Hello */) {
            i { "GetTouch request received" }
            val requestInfo = context.makeCtap2RequestInfo(PLATFORM_GET_TOUCH, rpId)
            val followUpInClient = context.confirmRequestWithUser(requestInfo) == true
            if (followUpInClient)
                return DUMMY_MAKE_CREDENTIAL_RESPONSE
            else
                CTAP_ERR(OperationDenied)
        }

        // Step 2 (unclear in the spec)
        // We do not support any PIN protocols
        if (params.getOptional(MAKE_CREDENTIAL_PIN_AUTH) != null)
            CTAP_ERR(PinAuthInvalid, "pinAuth sent with MakeCredential")

        // Step 3
        var foundCompatibleAlgorithm = false
        for (pubKeyCredParam in pubKeyCredParams) {
            if (pubKeyCredParam.getRequired("type").unbox<String>() == "public-key" &&
                pubKeyCredParam.getRequired("alg").unbox<Long>() == COSE_ID_ES256
            )
                foundCompatibleAlgorithm = true
        }
        if (!foundCompatibleAlgorithm)
            CTAP_ERR(UnsupportedAlgorithm)

        // Step 4
        var requireResidentKey = false
        var requireUserVerification = false
        if (options != null) {
            if (options.getOptional("rk")?.unbox<Boolean>() == true)
                requireResidentKey = true
            if (options.getOptional("up") != null)
                CTAP_ERR(InvalidOption, "Option 'up' specified for MakeCredential")
            if (options.getOptional("uv")?.unbox<Boolean>() == true)
                requireUserVerification = true
        }

        // Step 5
        // We only validate the extension inputs here, the actual processing is done later.
        val activeExtensions = parseExtensionInputs(
            extensions = extensions,
            action = REGISTER,
            canUseDisplay = context.isHidTransport,
            requireUserPresence = true
        )

        // Step 6 (has to come after Step 9 once credProtect has been implemented, but the spec is
        // unclear on this)
        if (excludeList != null) {
            i { "Exclude list present" }
            for (cborCredential in excludeList) {
                if (Credential.fromCborCredential(cborCredential, rpIdHash, context) == null)
                    continue
                val requestInfo =
                    context.makeCtap2RequestInfo(
                        action = REGISTER_CREDENTIAL_EXCLUDED,
                        rpId = rpId,
                        rpName = rpName
                    )
                val revealRegistration = context.confirmRequestWithUser(requestInfo) == true
                if (revealRegistration)
                    CTAP_ERR(CredentialExcluded)
                else
                    CTAP_ERR(OperationDenied)
            }
        }

        // Step 7
        if (requireUserVerification && context.getUserVerificationState() != true)
            CTAP_ERR(InvalidOption)

        // Step 8 & 9
        if (requireUserVerification && !context.verifyUser())
            CTAP_ERR(OperationDenied)

        // At this point, user verification has been performed if requested.
        val userVerified = requireUserVerification

        // If UV is available, require it when a resident key is requested.
        if (requireResidentKey && context.getUserVerificationState() == true && !userVerified)
            CTAP_ERR(PinRequired)

        // Step 11
        val requestInfo = context.makeCtap2RequestInfo(
            action = REGISTER,
            rpId = rpId,
            rpName = rpName,
            userName = userName,
            userDisplayName = userDisplayName,
            addResidentKeyHint = requireResidentKey,
        )

        if (context.confirmRequestWithUser(requestInfo) != true)
            CTAP_ERR(OperationDenied)

        // At this point, user verification and presence check have been performed if requested.
        val userPresent = true

        // Step 12
        val (keyAlias, attestationType) =
            context.getOrCreateFreshWebAuthnCredential(
                createResidentKey = requireResidentKey,
                createHmacSecret = activeExtensions.containsKey(Extension.HmacSecret),
                attestationChallenge = clientDataHash
            ) ?: CTAP_ERR(KeyStoreFull, "Failed to create WebAuthnCredential")

        val credential = WebAuthnCredential(
            keyAlias = keyAlias,
            rpIdHash = rpIdHash,
            rpName = rpName,
            userId = userId,
            userDisplayName = userDisplayName,
            userName = userName,
            userIcon = userIcon
        )

        // Step 13
        if (requireResidentKey)
            context.setResidentCredential(
                rpId = rpId,
                userId = userId,
                credential = credential,
                userVerified = userVerified
            )

        // Step 14
        val extensionOutputs = processExtensions(
            extensions = activeExtensions,
            credential = credential,
            userPresent = userPresent,
            userVerified = userVerified,
            action = REGISTER
        )

        val credentialPublicKey = credential.ctap2PublicKeyRepresentation
        if (credentialPublicKey == null) {
            credential.delete(context)
            CTAP_ERR(Other, "Failed to get raw public key")
        }

        // As per
        // https://www.w3.org/TR/webauthn/#credentialcreationdata-attestationconveyancepreferenceoption
        // self attestation is only indicated to the client if the AAGUID consists of zero bytes.
        val aaguid = when (attestationType) {
            AttestationType.SELF -> SELF_ATTESTATION_AAGUID
            AttestationType.BASIC -> BASIC_ATTESTATION_AAGUID
            AttestationType.ANDROID_KEYSTORE -> ANDROID_KEYSTORE_ATTESTATION_AAGUID
        }
        val attestedCredentialData =
            aaguid + credential.keyHandle.size.toUShort()
                .bytes() + credential.keyHandle + credentialPublicKey.toCbor()

        val flags = FLAGS_AT_INCLUDED or
                (if (extensionOutputs != null) FLAGS_ED_INCLUDED else 0) or
                // userPresent is always true in authenticatorMakeCredential
                (if (userPresent) FLAGS_USER_PRESENT else 0) or
                (if (userVerified) FLAGS_USER_VERIFIED else 0)

        val authenticatorData =
            rpIdHash + flags.bytes() + 0.toUInt().bytes() + attestedCredentialData +
                    (extensionOutputs?.toCbor() ?: byteArrayOf())

        context.initCounter(keyAlias)

        val attestationStatement = CborTextStringMap(
            when (attestationType) {
                AttestationType.SELF -> mapOf(
                    "alg" to CborLong(COSE_ID_ES256),
                    "sig" to CborByteString(credential.sign(authenticatorData, clientDataHash))
                )
                AttestationType.BASIC -> mapOf(
                    "alg" to CborLong(COSE_ID_ES256),
                    "sig" to CborByteString(
                        signWithWebAuthnBatchAttestationKey(
                            authenticatorData,
                            clientDataHash
                        )
                    ),
                    "x5c" to CborArray(arrayOf(CborByteString(WEB_AUTHN_RAW_BASIC_ATTESTATION_CERT)))
                )
                AttestationType.ANDROID_KEYSTORE -> mapOf(
                    "alg" to CborLong(COSE_ID_ES256),
                    "sig" to CborByteString(credential.sign(authenticatorData, clientDataHash)),
                    "x5c" to credential.androidKeystoreAttestation
                )
            }
        )

        context.notifyUser(requestInfo)

        return CborLongMap(
            mapOf(
                MAKE_CREDENTIAL_RESPONSE_AUTH_DATA to CborByteString(authenticatorData),
                MAKE_CREDENTIAL_RESPONSE_FMT to CborTextString(attestationType.format),
                MAKE_CREDENTIAL_RESPONSE_ATT_STMT to attestationStatement
            )
        )
    }

    private suspend fun handleGetAssertion(
        context: AuthenticatorContext,
        params: CborValue
    ): CborValue {
        val clientDataHash = params.getRequired(GET_ASSERTION_CLIENT_DATA_HASH).unbox<ByteArray>()

        val rpId = params.getRequired(GET_ASSERTION_RP_ID).unbox<String>()
        val rpIdHash = rpId.sha256()

        val allowList = params.getOptional(GET_ASSERTION_ALLOW_LIST)?.unbox<Array<CborValue>>()
        val extensions =
            params.getOptional(GET_ASSERTION_EXTENSIONS)?.unbox<Map<String, CborValue>>()
        val options = params.getOptional(GET_ASSERTION_OPTIONS)

        // Step 3
        var requireUserPresence = true
        var requireUserVerification = false
        if (options != null) {
            if (options.getOptional("rk") != null)
                CTAP_ERR(InvalidOption, "Option 'rk' specified for GetAssertion")
            if (options.getOptional("up")?.unbox<Boolean>() == false)
                requireUserPresence = false
            if (options.getOptional("uv")?.unbox<Boolean>() == true)
                requireUserVerification = true
        }

        // Step 2, 5 or 6 (CTAP 2.1 is unclear about the case of an internal UV-only authenticator).
        // We do not support any PIN protocols
        if (params.getOptional(GET_ASSERTION_PIN_AUTH) != null)
            CTAP_ERR(PinAuthInvalid, "pinAuth sent with GetAssertion")

        // Step 4
        // We only validate the extension inputs here, the actual processing is done later.
        val activeExtensions = parseExtensionInputs(
            extensions = extensions,
            action = AUTHENTICATE,
            canUseDisplay = context.isHidTransport,
            requireUserPresence = requireUserPresence
        ).toMutableMap()

        // hmac-secret requires user presence, but the spec is not clear on whether this has to be
        // obtained separately
        if (activeExtensions.containsKey(Extension.HmacSecret))
            requireUserPresence = true
        if (activeExtensions.containsKey(Extension.TxAuthSimple)) {
            if (!requireUserPresence && !requireUserVerification)
                requireUserPresence = true
        }

        // Step 5
        if (requireUserVerification && context.getUserVerificationState() != true)
            CTAP_ERR(UnsupportedOption)

        // Step 6
        if (requireUserVerification && !context.verifyUser())
            CTAP_ERR(OperationDenied)

        // At this point, user verification has been performed if requested.
        val userVerified = requireUserVerification

        // Step 7 (part 1)
        val useResidentKey = allowList == null
        val applicableCredentials = if (!useResidentKey) {
            check(allowList != null)
            if (allowList.isEmpty()) {
                // Step 1 of the spec does not list this case, hence we treat it as if there were
                // no credentials found.
                i { "Allow list is empty" }
                emptySequence()
            } else {
                allowList.asSequence().mapNotNull { cborCredential ->
                    Credential.fromCborCredential(cborCredential, rpIdHash, context)
                }.map { credential -> context.lookupAndReplaceWithResidentCredential(credential) }
            }
        } else {
            // Locate all rk credentials bound to the provided rpId
            i { "Locating resident credentials" }
            context.getResidentKeyUserIdsForRpId(rpIdHash).asSequence()
                .mapNotNull { userId -> context.getResidentCredential(rpIdHash, userId) }
                .sortedByDescending { it.creationDate }
        }.filter {
            // If the hmac-secret extension is requested, we must only offer credentials that were
            // created with hmac-secret enabled.
            if (activeExtensions.containsKey(Extension.HmacSecret))
                (it as? WebAuthnCredential)?.hasHmacSecret == true
            else
                true
        }.toList()

        val credentialsToUse = if (useResidentKey) {
            applicableCredentials.toList()
        } else {
            listOfNotNull(applicableCredentials.firstOrNull())
        }
        val numberOfCredentials = credentialsToUse.size
        i { "Continuing with $numberOfCredentials credentials" }

        // Step 8

        // txAuthSimple leads to a prompt that the user has to confirm. This prompt has to be shown
        // before the usual user presence check as per spec, but we omit it if there are no
        // applicable credentials.
        if (activeExtensions.containsKey(Extension.TxAuthSimple) && numberOfCredentials > 0) {
            val txAuthSimpleInput =
                activeExtensions[Extension.TxAuthSimple] as TxAuthSimpleAuthenticateInput
            // The actual prompt confirmed by the user differs from the requested prompt only by
            // potentially containing additional newlines. We simply replace the extension input
            // with the prompt that was actually shown to keep extension handling simple.
            val actualPrompt = context.confirmTransactionWithUser(rpId, txAuthSimpleInput.prompt)
                ?: CTAP_ERR(OperationDenied)
            activeExtensions[Extension.TxAuthSimple] = TxAuthSimpleAuthenticateInput(actualPrompt)
            // Introduce a small delay between the transaction confirmation and the usual
            // GetAssertion confirmation, otherwise the user may inadvertently confirm both with one
            // tap.
            delay(500)
        }

        // Since requests requiring user verification may have asked the user to confirm their
        // device credentials, we upgrade them to also require user presence. Also unlock user data.
        if (userVerified) {
            requireUserPresence = true
            for (credential in credentialsToUse) {
                if (credential is WebAuthnCredential) {
                    context.authenticateUserFor {
                        credential.unlockUserInfoIfNecessary()
                    }
                }
            }
        }

        val requestInfo = if (numberOfCredentials > 0) {
            val firstCredential = credentialsToUse.first() as? WebAuthnCredential
            val singleCredential = numberOfCredentials == 1
            check(!singleCredential implies useResidentKey)
            context.makeCtap2RequestInfo(
                action = AUTHENTICATE,
                rpId = rpId,
                rpName = firstCredential?.rpName,
                userName = if (singleCredential) firstCredential?.userName else null,
                userDisplayName = if (singleCredential) firstCredential?.userDisplayName else null,
                addResidentKeyHint = !singleCredential
            )
        } else {
            // We have not found any credentials, ask the user for permission to reveal this fact.
            context.makeCtap2RequestInfo(AUTHENTICATE_NO_CREDENTIALS, rpId)
        }
        if (requireUserPresence && context.confirmRequestWithUser(requestInfo) != true)
            CTAP_ERR(OperationDenied)
        if (!requireUserPresence)
            i { "Processing silent GetAssertion request" }

        // Step 7, case of no credentials: Has to be performed *after* a presence check to remain
        // compatible with CTAP 2 and Chromium.
        // https://source.chromium.org/chromium/chromium/src/+/master:device/fido/get_assertion_request_handler.cc;l=69;drc=c4d7a7f9940c98c7c00442b883dc6b442875ee1e
        if (numberOfCredentials == 0) {
            context.notifyUser(requestInfo)
            CTAP_ERR(NoCredentials)
        }

        // Step 9
        // At this point, user presence and verification have been performed if requested.
        val userPresent = requireUserPresence

        // If the transport does not allow for interactive credential selection or if silent
        // authentication is requested, return a list of assertions for all applicable credentials.
        // Otherwise, let the user select one to return an assertion for.
        return if (!context.isHidTransport || !requireUserPresence) {
            // Step 10
            val assertionOperationsIterator = credentialsToUse
                .mapIndexed { credentialCounter, nextCredential ->
                    val extensionOutputs = processExtensions(
                        extensions = activeExtensions,
                        credential = nextCredential,
                        userPresent = userPresent,
                        userVerified = userVerified,
                        action = AUTHENTICATE
                    )
                    nextCredential.assertWebAuthn(
                        clientDataHash = clientDataHash,
                        extensionOutputs = extensionOutputs,
                        userPresent = userPresent,
                        userVerified = userVerified,
                        numberOfCredentials = if (credentialCounter == 0) numberOfCredentials else null,
                        userSelected = false,
                        // The credential ID must be returned unless the allow list contains exactly
                        // one credential.
                        returnCredential = allowList?.size != 1,
                        context = context
                    )
                }.iterator()
            assertionOperationsIterator.next().also {
                if (numberOfCredentials > 1) {
                    // Cache remaining assertions for subsequent GetNextAssertion requests
                    context.getNextAssertionBuffer = assertionOperationsIterator
                    context.getNextAssertionRequestInfo = requestInfo
                } else {
                    // We return the only assertion and thus indicate success to the user
                    context.notifyUser(requestInfo)
                }
            }
        } else {
            val credential = context.chooseCredential(credentialsToUse)
                ?: CTAP_ERR(OperationDenied)
            val extensionOutputs = processExtensions(
                extensions = activeExtensions,
                credential = credential,
                userPresent = userPresent,
                userVerified = userVerified,
                action = AUTHENTICATE
            )
            credential.assertWebAuthn(
                clientDataHash = clientDataHash,
                extensionOutputs = extensionOutputs,
                userPresent = userPresent,
                userVerified = userVerified,
                numberOfCredentials = 1,
                // Let the platform skip the confirmation step if the user explicitly selected a
                // credential.
                userSelected = credentialsToUse.size > 1,
                // The credential ID must be returned unless the allow list contains exactly one
                // credential.
                returnCredential = allowList?.size != 1,
                context = context
            )
        }
    }

    private fun handleGetNextAssertion(context: AuthenticatorContext): CborValue {
        if (context.getNextAssertionBuffer?.hasNext() != true || context.getNextAssertionRequestInfo == null)
            CTAP_ERR(NotAllowed)
        val nextAssertion = context.getNextAssertionBuffer!!.next()
        if (context.getNextAssertionBuffer?.hasNext() != true) {
            context.getNextAssertionRequestInfo?.let { context.notifyUser(it) }
            context.getNextAssertionBuffer = null
            context.getNextAssertionRequestInfo = null
        }
        return nextAssertion
    }

    private fun handleGetInfo(context: AuthenticatorContext): CborValue {
        val optionsMap = mutableMapOf(
            "plat" to CborBoolean(false),
            "rk" to CborBoolean(true),
            "up" to CborBoolean(true)
        )
        context.getUserVerificationState()?.let { optionsMap["uv"] = CborBoolean(it) }
        return CborLongMap(
            mapOf(
                GET_INFO_RESPONSE_VERSIONS to CborArray(
                    arrayOf(
                        CborTextString("FIDO_2_0"),
                        CborTextString("U2F_V2")
                    )
                ),
                GET_INFO_RESPONSE_EXTENSIONS to
                        if (context.isHidTransport)
                            Extension.identifiersAsCbor
                        else
                            Extension.noDisplayIdentifiersAsCbor,
                GET_INFO_RESPONSE_AAGUID to CborByteString(BASIC_ATTESTATION_AAGUID),
                GET_INFO_RESPONSE_OPTIONS to CborTextStringMap(optionsMap),
                GET_INFO_RESPONSE_MAX_MSG_SIZE to CborLong(MAX_CBOR_MSG_SIZE),
                // This value is chosen such that most credential lists will fit into a single
                // request while still staying well below the maximal message size when taking
                // the maximal credential ID length into account.
                GET_INFO_RESPONSE_MAX_CREDENTIAL_COUNT_IN_LIST to CborLong(5),
                // Our credential IDs consist of
                // * a signature (32 bytes)
                // * a nonce (32 bytes)
                // * a null byte (WebAuthn only)
                // * the rpName truncated to 64 UTF-16 code units (every UTF-16 code unit can be
                //   coded on at most three UTF-8 bytes)
                GET_INFO_RESPONSE_MAX_CREDENTIAL_ID_LENGTH to CborLong(32 + 32 + 1 + 3 * 64),
                GET_INFO_RESPONSE_TRANSPORTS to CborArray(
                    arrayOf(
                        CborTextString("nfc"),
                        CborTextString("usb")
                    )
                ),
                GET_INFO_RESPONSE_ALGORITHMS to CborArray(
                    arrayOf(
                        CborTextStringMap(
                            mapOf(
                                "alg" to CborLong(-7),
                                "type" to CborTextString("public-key")
                            )
                        )
                    )
                ),
            )
        )
    }

    private fun handleClientPIN(params: CborValue): CborValue {
        val pinProtocol = params.getRequired(CLIENT_PIN_PIN_PROTOCOL).unbox<Long>()
        if (pinProtocol != 1L)
            CTAP_ERR(InvalidParameter, "Unsupported pinProtocol: $pinProtocol")

        val subCommand = params.getRequired(CLIENT_PIN_SUB_COMMAND).unbox<Long>()
        if (subCommand != CLIENT_PIN_SUB_COMMAND_GET_KEY_AGREEMENT)
            CTAP_ERR(InvalidCommand, "Unsupported ClientPIN subcommand: $subCommand")

        return CborLongMap(
            mapOf(
                CLIENT_PIN_GET_KEY_AGREEMENT_RESPONSE_KEY_AGREEMENT to authenticatorKeyAgreementKey
            )
        )
    }

    private suspend fun handleReset(context: AuthenticatorContext): Nothing? {
        // The FIDO conformance tests demand reset capabilities over any protocol. In order to test
        // the authenticator behavior with UV configured, the UV status also needs to be reenabled
        // after a reset. In order to pass the conformance tests, it is thus required to use the
        // following code variant:
        //
        // context.deleteAllData()
        // context.armUserVerificationFuse()
        // return null

        // Deny reset requests over NFC since there is now way to confirm them with the user.
        if (!context.isHidTransport)
            CTAP_ERR(NotAllowed)
        if (context.requestReset()) {
            return null
        } else {
            context.handleSpecialStatus(AuthenticatorSpecialStatus.RESET)
            CTAP_ERR(OperationDenied)
        }
    }

    private suspend fun handleSelection(context: AuthenticatorContext): Nothing? {
        val info = context.makeCtap2RequestInfo(PLATFORM_GET_TOUCH, "")
        return when (context.confirmRequestWithUser(info)) {
            true -> null
            false -> CTAP_ERR(OperationDenied)
            null -> CTAP_ERR(UserActionTimeout)
        }
    }

    private fun parseExtensionInputs(
        extensions: Map<String, CborValue>?,
        action: AuthenticatorAction,
        canUseDisplay: Boolean,
        requireUserPresence: Boolean
    ): Map<Extension, ExtensionInput> {
        if (extensions == null)
            return mapOf()
        return extensions.filterKeys { identifier ->
            identifier in Extension.identifiers
        }.map {
            val extension = Extension.fromIdentifier(it.key)
            // parseInput throws an appropriate exception if the input is not of the correct form.
            Pair(
                extension,
                extension.parseInput(
                    it.value,
                    action,
                    canUseDisplay = canUseDisplay,
                    requireUserPresence = requireUserPresence
                )
            )
        }.toMap()
    }

    private fun processExtensions(
        extensions: Map<Extension, ExtensionInput>,
        credential: Credential,
        userPresent: Boolean,
        userVerified: Boolean,
        action: AuthenticatorAction
    ): CborValue? {
        val extensionOutputs = extensions.mapValues {
            val extension = it.key
            processExtension(
                extension,
                it.value,
                credential,
                userPresent,
                userVerified,
                action
            )
        }
        return if (extensionOutputs.isEmpty())
            null
        else
            CborTextStringMap(extensionOutputs.mapKeys { it.key.identifier })
    }

    private fun processExtension(
        extension: Extension,
        input: ExtensionInput,
        credential: Credential,
        userPresent: Boolean,
        userVerified: Boolean,
        action: AuthenticatorAction
    ): CborValue {
        require(action == REGISTER || action == AUTHENTICATE)
        return when (extension) {
            Extension.HmacSecret -> {
                if (action == REGISTER) {
                    require(input is NoInput)
                    // hmac-secret has already been handled during credential creation
                    CborBoolean(true)
                } else {
                    require(input is HmacSecretAuthenticateInput)
                    require(credential is WebAuthnCredential)
                    val sharedSecret = agreeOnSharedSecret(input.keyAgreement)
                    val salt = decryptSalt(sharedSecret, input.saltEnc, input.saltAuth)
                        ?: CTAP_ERR(InvalidParameter, "Invalid saltAuth")
                    require(salt.size == 32 || salt.size == 64)
                    val output = if (salt.size == 32) {
                        val hmacOutput = credential.signWithHmacSecret(userVerified, salt)
                            ?: CTAP_ERR(NoCredentials, "HMAC secret is missing")
                        encryptHmacOutput(sharedSecret, hmacOutput).also {
                            check(it.size == 32)
                        }
                    } else {
                        val salt1 = salt.sliceArray(0 until 32)
                        val hmacOutput1 = credential.signWithHmacSecret(userVerified, salt1)
                            ?: CTAP_ERR(NoCredentials, "HMAC secret is missing")
                        val salt2 = salt.sliceArray(32 until 64)
                        val hmacOutput2 = credential.signWithHmacSecret(userVerified, salt2)
                            ?: CTAP_ERR(NoCredentials, "HMAC secret is missing")
                        encryptHmacOutput(sharedSecret, hmacOutput1 + hmacOutput2).also {
                            check(it.size == 64)
                        }
                    }
                    CborByteString(output)
                }
            }
            Extension.SupportedExtensions -> {
                require(action == REGISTER)
                require(input is NoInput)
                Extension.identifiersAsCbor
            }
            Extension.TxAuthSimple -> {
                require(action == AUTHENTICATE)
                require(input is TxAuthSimpleAuthenticateInput)
                // At this point, either we have returned an OperationDenied error or the user has
                // confirmed the prompt (with added line breaks).
                CborTextString(input.prompt)
            }
            Extension.UserVerificationMethod -> {
                require(input is NoInput)
                val keyProtectionType =
                    if (credential.isKeyMaterialInTEE) KEY_PROTECTION_HARDWARE or KEY_PROTECTION_TEE else KEY_PROTECTION_SOFTWARE
                val methods = mutableListOf<CborArray>()
                if (userPresent) {
                    methods.add(
                        CborArray(
                            arrayOf(
                                CborLong(USER_VERIFY_PRESENCE),
                                CborLong(keyProtectionType),
                                CborLong(MATCHER_PROTECTION_SOFTWARE)
                            )
                        )
                    )
                }
                if (userVerified) {
                    methods.add(
                        CborArray(
                            arrayOf(
                                CborLong(USER_VERIFY_PATTERN),
                                CborLong(keyProtectionType),
                                CborLong(MATCHER_PROTECTION_SOFTWARE)
                            )
                        )
                    )
                }
                CborArray(methods.toTypedArray())
            }
        }
    }
}
