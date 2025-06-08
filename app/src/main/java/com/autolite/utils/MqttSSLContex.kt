package com.autolite.utils

import android.util.Log
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

object MqttConfigHolder {
    private const val kTag = "mqttSslContext"

    private var lastSslHash: String? = null
    var mqttSslContext: SSLContext? = null
        private set

    fun initSslContextIfNeeded(p12Bytes: ByteArray, p12Password: CharArray, caBytes: ByteArray): Boolean {
        val newHash = (p12Bytes.contentHashCode().toString() + caBytes.contentHashCode().toString())

        if (newHash == lastSslHash && mqttSslContext != null) {
            LogUtils.log(Log.DEBUG, kTag, "证书哈希值相同，SSLContext 无需重新初始化")
            return true
        }else {
            LogUtils.log(Log.DEBUG, kTag, "证书发生变化，SSLContext 重新初始化")
        }

        return try {
            // 客户端证书
            val keyStore = KeyStore.getInstance("PKCS12").apply {
                load(p12Bytes.inputStream(), p12Password)
            }
            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, p12Password)
            }

            // CA证书
            val caCertificate = CertificateFactory.getInstance("X.509").generateCertificate(caBytes.inputStream())
            val caKeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("ca", caCertificate)
            }
            val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(caKeyStore)
            }

            mqttSslContext = SSLContext.getInstance("TLSv1.2").apply {
                init(keyManagerFactory.keyManagers, trustManagerFactory.trustManagers, null)
            }
            lastSslHash = newHash
            LogUtils.log(Log.DEBUG, "SslInitializer", "SSLContext 初始化成功")
            true
        } catch (e: Exception) {
            LogUtils.log(Log.ERROR, "SslInitializer", "SSLContext 初始化失败: ${e.message}")
            false
        }
    }
}
