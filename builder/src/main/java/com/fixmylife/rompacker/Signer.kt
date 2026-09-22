package com.fixmylife.rompacker

import android.content.Context
import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Signs generated APKs with one key that is created on first use and kept in
 * app-private storage. Same key every time = you can rebuild a game (new icon,
 * patched ROM) and install it over the old one without losing saves.
 */
class Signer(context: Context) {

    private val dir = File(context.filesDir, "signing").apply { mkdirs() }
    private val keyFile = File(dir, "key.pk8")
    private val certFile = File(dir, "cert.der")

    fun sign(unsigned: File, output: File) {
        val (key, cert) = loadOrCreate()
        val signerConfig = ApkSigner.SignerConfig.Builder("ROMPACK", key, listOf(cert)).build()
        output.delete()
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsigned)
            .setOutputApk(output)
            .setMinSdkVersion(24)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }

    @Synchronized
    private fun loadOrCreate(): Pair<PrivateKey, X509Certificate> {
        if (keyFile.isFile && certFile.isFile) {
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            val cert = certFile.inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }
            return key to cert
        }

        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=fixmylife ROM Packer, O=fixmylife")
        val holder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now),
            Date(now - TimeUnit.DAYS.toMillis(1)),
            Date(now + TimeUnit.DAYS.toMillis(365L * 30)),
            subject,
            keyPair.public,
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
        val cert = JcaX509CertificateConverter().getCertificate(holder)

        keyFile.writeBytes(keyPair.private.encoded)
        certFile.writeBytes(cert.encoded)
        return keyPair.private to cert
    }
}
