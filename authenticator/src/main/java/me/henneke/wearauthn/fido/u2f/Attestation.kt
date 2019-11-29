package me.henneke.wearauthn.fido.u2f

import me.henneke.wearauthn.bytes
import me.henneke.wearauthn.fido.context.U2FLocalCredential
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

@ExperimentalUnsignedTypes
val U2F_RAW_BATCH_ATTESTATION_CERT = ubyteArrayOf(
    0x30u, 0x82u, 0x01u, 0xdeu, 0x30u, 0x82u, 0x01u, 0x85u, 0xa0u, 0x03u, 0x02u, 0x01u, 0x02u,
    0x02u, 0x14u, 0x5eu, 0xf6u, 0xaeu, 0x83u, 0xcbu, 0x7eu, 0xdeu, 0x5eu, 0xa5u, 0x71u, 0x0fu,
    0x20u, 0xf6u, 0xf9u, 0x3eu, 0xb3u, 0x29u, 0x28u, 0x4du, 0x57u, 0x30u, 0x0au, 0x06u, 0x08u,
    0x2au, 0x86u, 0x48u, 0xceu, 0x3du, 0x04u, 0x03u, 0x02u, 0x30u, 0x45u, 0x31u, 0x16u, 0x30u,
    0x14u, 0x06u, 0x03u, 0x55u, 0x04u, 0x0au, 0x0cu, 0x0du, 0x57u, 0x65u, 0x61u, 0x72u, 0x41u,
    0x75u, 0x74u, 0x68u, 0x6eu, 0x20u, 0x55u, 0x32u, 0x46u, 0x31u, 0x2bu, 0x30u, 0x29u, 0x06u,
    0x03u, 0x55u, 0x04u, 0x03u, 0x0cu, 0x22u, 0x57u, 0x65u, 0x61u, 0x72u, 0x41u, 0x75u, 0x74u,
    0x68u, 0x6eu, 0x20u, 0x55u, 0x32u, 0x46u, 0x20u, 0x53u, 0x6fu, 0x66u, 0x74u, 0x77u, 0x61u,
    0x72u, 0x65u, 0x20u, 0x41u, 0x74u, 0x74u, 0x65u, 0x73u, 0x74u, 0x61u, 0x74u, 0x69u, 0x6fu,
    0x6eu, 0x30u, 0x1eu, 0x17u, 0x0du, 0x31u, 0x39u, 0x31u, 0x31u, 0x30u, 0x32u, 0x31u, 0x32u,
    0x31u, 0x33u, 0x35u, 0x32u, 0x5au, 0x17u, 0x0du, 0x33u, 0x39u, 0x30u, 0x37u, 0x32u, 0x30u,
    0x31u, 0x32u, 0x31u, 0x33u, 0x35u, 0x32u, 0x5au, 0x30u, 0x45u, 0x31u, 0x16u, 0x30u, 0x14u,
    0x06u, 0x03u, 0x55u, 0x04u, 0x0au, 0x0cu, 0x0du, 0x57u, 0x65u, 0x61u, 0x72u, 0x41u, 0x75u,
    0x74u, 0x68u, 0x6eu, 0x20u, 0x55u, 0x32u, 0x46u, 0x31u, 0x2bu, 0x30u, 0x29u, 0x06u, 0x03u,
    0x55u, 0x04u, 0x03u, 0x0cu, 0x22u, 0x57u, 0x65u, 0x61u, 0x72u, 0x41u, 0x75u, 0x74u, 0x68u,
    0x6eu, 0x20u, 0x55u, 0x32u, 0x46u, 0x20u, 0x53u, 0x6fu, 0x66u, 0x74u, 0x77u, 0x61u, 0x72u,
    0x65u, 0x20u, 0x41u, 0x74u, 0x74u, 0x65u, 0x73u, 0x74u, 0x61u, 0x74u, 0x69u, 0x6fu, 0x6eu,
    0x30u, 0x59u, 0x30u, 0x13u, 0x06u, 0x07u, 0x2au, 0x86u, 0x48u, 0xceu, 0x3du, 0x02u, 0x01u,
    0x06u, 0x08u, 0x2au, 0x86u, 0x48u, 0xceu, 0x3du, 0x03u, 0x01u, 0x07u, 0x03u, 0x42u, 0x00u,
    0x04u, 0x7fu, 0x52u, 0xefu, 0x11u, 0x1cu, 0x71u, 0xfdu, 0x3au, 0xbeu, 0x19u, 0x31u, 0x0du,
    0x7fu, 0x76u, 0x0bu, 0xdbu, 0x68u, 0x62u, 0x72u, 0x2eu, 0xe5u, 0xb5u, 0x27u, 0xdbu, 0x34u,
    0x42u, 0xbbu, 0x1bu, 0x89u, 0x69u, 0x87u, 0xb1u, 0x19u, 0x49u, 0x24u, 0x98u, 0x19u, 0x1du,
    0x80u, 0x31u, 0x77u, 0xbeu, 0xb7u, 0x38u, 0xd3u, 0x03u, 0x0cu, 0xf7u, 0xdfu, 0x6du, 0xb2u,
    0x15u, 0x8cu, 0x04u, 0x61u, 0xf3u, 0xb7u, 0xdfu, 0x0du, 0x90u, 0xa8u, 0x1eu, 0xc6u, 0x73u,
    0xa3u, 0x53u, 0x30u, 0x51u, 0x30u, 0x1du, 0x06u, 0x03u, 0x55u, 0x1du, 0x0eu, 0x04u, 0x16u,
    0x04u, 0x14u, 0x48u, 0x61u, 0xf8u, 0x72u, 0xefu, 0xc1u, 0x4fu, 0x4fu, 0xf6u, 0x69u, 0xe8u,
    0x9fu, 0x0bu, 0xa4u, 0x80u, 0xe7u, 0x36u, 0x00u, 0xe0u, 0x4eu, 0x30u, 0x1fu, 0x06u, 0x03u,
    0x55u, 0x1du, 0x23u, 0x04u, 0x18u, 0x30u, 0x16u, 0x80u, 0x14u, 0x48u, 0x61u, 0xf8u, 0x72u,
    0xefu, 0xc1u, 0x4fu, 0x4fu, 0xf6u, 0x69u, 0xe8u, 0x9fu, 0x0bu, 0xa4u, 0x80u, 0xe7u, 0x36u,
    0x00u, 0xe0u, 0x4eu, 0x30u, 0x0fu, 0x06u, 0x03u, 0x55u, 0x1du, 0x13u, 0x01u, 0x01u, 0xffu,
    0x04u, 0x05u, 0x30u, 0x03u, 0x01u, 0x01u, 0xffu, 0x30u, 0x0au, 0x06u, 0x08u, 0x2au, 0x86u,
    0x48u, 0xceu, 0x3du, 0x04u, 0x03u, 0x02u, 0x03u, 0x47u, 0x00u, 0x30u, 0x44u, 0x02u, 0x20u,
    0x1au, 0x7bu, 0x4fu, 0x28u, 0xf8u, 0xa0u, 0xabu, 0x4eu, 0x12u, 0x72u, 0x0au, 0xedu, 0x34u,
    0x4au, 0xa4u, 0x64u, 0x37u, 0xc9u, 0x16u, 0x41u, 0x8cu, 0x3fu, 0xa1u, 0xfeu, 0x04u, 0x14u,
    0x91u, 0xbfu, 0x03u, 0x6fu, 0xc3u, 0xefu, 0x02u, 0x20u, 0x01u, 0x29u, 0xd0u, 0x18u, 0xe7u,
    0xd0u, 0x0eu, 0xcbu, 0xc3u, 0x16u, 0x7fu, 0x02u, 0xb9u, 0x9fu, 0x88u, 0x92u, 0xdbu, 0x22u,
    0xb4u, 0x80u, 0xb4u, 0x70u, 0x99u, 0x8cu, 0x32u, 0x6du, 0x84u, 0x47u, 0x0fu, 0x9au, 0x00u,
    0x64u
)
    .toByteArray()
@ExperimentalUnsignedTypes
private val U2F_RAW_BATCH_ATTESTATION_KEY = ubyteArrayOf(
    0x30u, 0x81u, 0x87u, 0x02u, 0x01u, 0x00u, 0x30u, 0x13u, 0x06u, 0x07u, 0x2au, 0x86u, 0x48u,
    0xceu, 0x3du, 0x02u, 0x01u, 0x06u, 0x08u, 0x2au, 0x86u, 0x48u, 0xceu, 0x3du, 0x03u, 0x01u,
    0x07u, 0x04u, 0x6du, 0x30u, 0x6bu, 0x02u, 0x01u, 0x01u, 0x04u, 0x20u, 0x79u, 0x9eu, 0x14u,
    0x6eu, 0x59u, 0x59u, 0xceu, 0x89u, 0xbfu, 0xe2u, 0xc6u, 0xebu, 0x23u, 0x17u, 0x2du, 0xc2u,
    0x08u, 0xfbu, 0xd4u, 0x8fu, 0x7du, 0xa5u, 0x07u, 0x9fu, 0x82u, 0x56u, 0xafu, 0x26u, 0x83u,
    0x8au, 0x5eu, 0xa8u, 0xa1u, 0x44u, 0x03u, 0x42u, 0x00u, 0x04u, 0x7fu, 0x52u, 0xefu, 0x11u,
    0x1cu, 0x71u, 0xfdu, 0x3au, 0xbeu, 0x19u, 0x31u, 0x0du, 0x7fu, 0x76u, 0x0bu, 0xdbu, 0x68u,
    0x62u, 0x72u, 0x2eu, 0xe5u, 0xb5u, 0x27u, 0xdbu, 0x34u, 0x42u, 0xbbu, 0x1bu, 0x89u, 0x69u,
    0x87u, 0xb1u, 0x19u, 0x49u, 0x24u, 0x98u, 0x19u, 0x1du, 0x80u, 0x31u, 0x77u, 0xbeu, 0xb7u,
    0x38u, 0xd3u, 0x03u, 0x0cu, 0xf7u, 0xdfu, 0x6du, 0xb2u, 0x15u, 0x8cu, 0x04u, 0x61u, 0xf3u,
    0xb7u, 0xdfu, 0x0du, 0x90u, 0xa8u, 0x1eu, 0xc6u, 0x73u
)
    .toByteArray()

@ExperimentalUnsignedTypes
private val ATTESTATION_KEY by lazy {
    KeyFactory.getInstance("EC").generatePrivate(
        PKCS8EncodedKeySpec(U2F_RAW_BATCH_ATTESTATION_KEY))
}

@ExperimentalUnsignedTypes
fun signWithBatchAttestationKey(vararg data: ByteArray): ByteArray {
    val sign = Signature.getInstance("SHA256withECDSA")
    sign.initSign(ATTESTATION_KEY)
    for (datum in data) {
        sign.update(datum)
    }
    return sign.sign()
}

@ExperimentalUnsignedTypes
private val REFERENCE_ATTESTATION_CERT_TEMPLATE = ubyteArrayOf(
    0x30u, 0x81u, 0xB3u, 0xA0u, 0x03u, 0x02u, 0x01u, 0x02u, 0x02u, 0x01u, 0x01u, 0x30u, 0x0Au,
    0x06u, 0x08u, 0x2Au, 0x86u, 0x48u, 0xCEu, 0x3Du, 0x04u, 0x03u, 0x02u, 0x30u, 0x0Eu, 0x31u,
    0x0Cu, 0x30u, 0x0Au, 0x06u, 0x03u, 0x55u, 0x04u, 0x0Au, 0x0Cu, 0x03u, 0x55u, 0x32u, 0x46u,
    0x30u, 0x22u, 0x18u, 0x0Fu, 0x32u, 0x30u, 0x30u, 0x30u, 0x30u, 0x31u, 0x30u, 0x31u, 0x30u,
    0x30u, 0x30u, 0x30u, 0x30u, 0x30u, 0x5Au, 0x18u, 0x0Fu, 0x32u, 0x30u, 0x39u, 0x39u, 0x31u,
    0x32u, 0x33u, 0x31u, 0x32u, 0x33u, 0x35u, 0x39u, 0x35u, 0x39u, 0x5Au, 0x30u, 0x0Eu, 0x31u,
    0x0Cu, 0x30u, 0x0Au, 0x06u, 0x03u, 0x55u, 0x04u, 0x03u, 0x13u, 0x03u, 0x55u, 0x32u, 0x46u,
    0x30u, 0x59u, 0x30u, 0x13u, 0x06u, 0x07u, 0x2Au, 0x86u, 0x48u, 0xCEu, 0x3Du, 0x02u, 0x01u,
    0x06u, 0x08u, 0x2Au, 0x86u, 0x48u, 0xCEu, 0x3Du, 0x03u, 0x01u, 0x07u, 0x03u, 0x42u, 0x00u
).toByteArray()

@ExperimentalUnsignedTypes
private val REFERENCE_ATTESTATION_CERT_SIGNATURE_TEMPLATE = ubyteArrayOf(
    0x30u, 0x0Au, 0x06u, 0x08u, 0x2Au, 0x86u, 0x48u, 0xCEu, 0x3Du, 0x04u, 0x03u, 0x02u
).toByteArray()

@ExperimentalUnsignedTypes
fun createU2fSelfAttestationCert(credential: U2FLocalCredential): ByteArray {
    val certBody = REFERENCE_ATTESTATION_CERT_TEMPLATE + credential.u2fPublicKeyRepresentation!!
    val certSignaturePayload = credential.sign(certBody)
    val certSignaturePrefix = ubyteArrayOf(0x03u, (certSignaturePayload.size + 1).toUByte(), 0x00u).toByteArray()
    val certSignature = REFERENCE_ATTESTATION_CERT_SIGNATURE_TEMPLATE + certSignaturePrefix + certSignaturePayload
    val certHeader = ubyteArrayOf(0x30u, 0x82u).toByteArray() + (certBody.size + certSignature.size).toUShort().bytes()
    return certHeader + certBody + certSignature
}

