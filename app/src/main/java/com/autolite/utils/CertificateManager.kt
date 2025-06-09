package com.autolite.utils

import android.content.Context
import android.util.Log
import com.autolite.model.InitViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class CertCheckResult(
    val status: Status,
    val message: String = ""
) {
    enum class Status {
        CASuccess,
        CAGetFailed,
        CADecodeFailed,
        CAisRevoked,
        CheckCertRevokedError,
        SSLError
    }
}

object CertificateManager  {
    private const val kTag = "CertificateManager"

    //证书验证的主函数：
    suspend fun getAndCheckCA(context: Context, ID: String, retryIfRevoked: Boolean = true): CertCheckResult {
        val clientEnPath = File(context.filesDir, "$ID.en")
        val caEnPath = File(context.filesDir, "ca.en")
        val baseUrl = "https://***REMOVED***/certs/${ID}/en_${ID}"
        val clientEnUrl = "$baseUrl/${ID}.en"
        val caEnUrl = "$baseUrl/ca.en"

        //1获取证书
        val result = checkAndDownloadCerts(clientEnPath,caEnPath,clientEnUrl,caEnUrl)
        if (result.status != CertCheckResult.Status.CASuccess) {
            return result
        }

        //2解密证书
        val p12Bytes = decryptCertFile(clientEnPath, ID)
        val caBytes = decryptCertFile(caEnPath, ID)
        if (p12Bytes == null || caBytes == null) {
            LogUtils.log(Log.ERROR, kTag, "证书解密失败，删除本地缓存")
            deleteCertFiles(clientEnPath, caEnPath)
            return CertCheckResult(CertCheckResult.Status.CADecodeFailed, "证书解密失败")
        }

        //3加载并验证证书
        val checkCertResult = checkCertRevoked(p12Bytes, ID)

        //如果是证书被吊销，自动重试一次
        if (checkCertResult.status == CertCheckResult.Status.CAisRevoked) {
            deleteCertFiles(clientEnPath, caEnPath)

            //
            InitViewModel.SharedMqttState.hasCertCheckFailedOnce = true

            // 自动重试一次
            return if (retryIfRevoked) {
                LogUtils.log(Log.DEBUG, kTag, "检测到吊销，尝试重新获取证书")
                getAndCheckCA(context, ID, retryIfRevoked = false)
            } else {
                CertCheckResult(CertCheckResult.Status.CAisRevoked, "证书已被吊销")
            }
        }

        if (checkCertResult.status != CertCheckResult.Status.CASuccess) {
            LogUtils.log(Log.ERROR, kTag, "证书验证失败，删除本地缓存")
            deleteCertFiles(clientEnPath, caEnPath)
            return checkCertResult
        }

        // 初始化 SSL
        if(!MqttConfigHolder.initSslContextIfNeeded(p12Bytes, ID.toCharArray(), caBytes)){
            deleteCertFiles(clientEnPath, caEnPath)
            return CertCheckResult(CertCheckResult.Status.SSLError, "SSL 初始化失败")
        }

        return CertCheckResult(CertCheckResult.Status.CASuccess, checkCertResult.message)
    }

    //删除ca文件
    private fun deleteCertFiles(clientEnPath: File, caEnPath: File) {
        if (clientEnPath.exists()) {
            val deleted = clientEnPath.delete()
            if (deleted) {
                LogUtils.log(Log.DEBUG, kTag, "客户端证书删除成功")
            } else {
                LogUtils.log(Log.WARN, kTag, "客户端证书删除失败")
            }
        }

        if (caEnPath.exists()) {
            val deleted = caEnPath.delete()
            if (deleted) {
                LogUtils.log(Log.DEBUG, kTag, "CA证书删除成功")
            } else {
                LogUtils.log(Log.WARN, kTag, "CA证书删除失败")
            }
        }
    }

    //1. 获取证书：如果存在则直接校验，如果不存在则需要下载，下载三次，如果还不成功则返回下载失败
    private suspend fun checkAndDownloadCerts(clientEnPath:File, caEnPath:File, clientEnUrl:String, caEnUrl:String): CertCheckResult {
        val clientDownloaded = if (!clientEnPath.exists()) {
            downloadFile(clientEnUrl, clientEnPath)
        } else {
            LogUtils.log(Log.DEBUG, kTag, "客户端证书已存在")
            true
        }

        val caDownloaded = if (!caEnPath.exists()) {
            downloadFile(caEnUrl, caEnPath)
        } else {
            LogUtils.log(Log.DEBUG, kTag, "CA证书已存在")
            true
        }

        if (!clientDownloaded || !caDownloaded) {
            LogUtils.log(Log.ERROR, kTag, "证书文件下载失败")
            return CertCheckResult(CertCheckResult.Status.CAGetFailed, "证书文件下载失败")
        }

        return CertCheckResult(CertCheckResult.Status.CASuccess)
    }

    //2. 解密证书，失败则返回空
    private fun decryptCertFile(EnPath:File, ID: String): ByteArray? {
        val key = generateKeyFromString(ID)
        if (key.isEmpty()) {
            LogUtils.log(Log.ERROR, kTag, "密钥生成失败")
            return null
        }
        val decodeBytes = FileInputStream(EnPath).use { aesDecryptInMemory(it, key) }
        if (decodeBytes.isEmpty()) {
            LogUtils.log(Log.ERROR, kTag, "解密证书文件失败")
            return null
        }
        return decodeBytes
    }

    //3. 加载并验证证书：获取剩余时长并返回，失败则返回验证失败，等待后续添加吊销后重新下载一次的逻辑
    private suspend fun checkCertRevoked(bytes: ByteArray, ID: String): CertCheckResult {

        val p12P = ID.toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        return try {
            withContext(Dispatchers.IO) {
                keyStore.load(bytes.inputStream(), p12P)
            }

            val alias = keyStore.aliases().nextElement()
            val clientCert = keyStore.getCertificate(alias) as X509Certificate

            val crl = withContext(Dispatchers.IO) {
                val url = URL("https://***REMOVED***/crl/crl.pem")
                val inputStream = url.openStream()
                CertificateFactory.getInstance("X.509").generateCRL(inputStream) as X509CRL
            }

            if (crl.isRevoked(clientCert)) {
                LogUtils.log(Log.WARN, kTag, "客户端证书已被吊销")
                return CertCheckResult(CertCheckResult.Status.CAisRevoked, "证书已被吊销")
            }

            val remaining = formatRemainingTime(clientCert.notAfter)
            return CertCheckResult(CertCheckResult.Status.CASuccess, remaining)

        } catch (e: Exception) {
            LogUtils.log(Log.WARN, kTag, "P12 证书加载失败: ${e.message}")
            e.printStackTrace()
            CertCheckResult(CertCheckResult.Status.CheckCertRevokedError)
        }
    }

    //1. 获取下载证书
    private suspend fun downloadFile(urlStr: String, destFile: File): Boolean = withContext(Dispatchers.IO) {
        // 切换到 IO 线程执行下载操作
        try {
            if (destFile.exists()) {
                val deleted = destFile.delete()
                if (deleted) {
                    LogUtils.log(Log.DEBUG, kTag, "证书删除成功")
                } else {
                    LogUtils.log(Log.WARN, kTag, "证书删除失败")
                }
            }

            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            connection.doInput = true

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                LogUtils.log(Log.DEBUG, kTag, "下载成功：${destFile.name}")
                connection.disconnect()
                return@withContext true
            } else {
                LogUtils.log(Log.DEBUG, kTag, "下载失败：$urlStr，code=${connection.responseCode}")
                connection.disconnect()
                return@withContext false
            }
        } catch (e: Exception) {
            LogUtils.log(Log.DEBUG, kTag, "异常下载 $urlStr: ${e.message}")
            return@withContext false
        }
    }

    //2.解密:计算字符串的SHA-256哈希
    private fun hashString(input: String): ByteArray {
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
        return sha256.digest(input.toByteArray(Charsets.UTF_8))
    }

    //2.解密:
    private fun generateKeyFromString(inputString: String): ByteArray {
        return try {
            // 计算字符串的哈希值
            val stringHash = hashString(inputString)

            // 将哈希值每个字节加1，并将结果转换为 ByteArray
            val transformedHash = stringHash.map {
                ((it.toInt() + 1) % 256).toByte()
            }.toByteArray()

            // 使用前16字节
            transformedHash.take(16).toByteArray()

        } catch (e: Exception) {
            // 捕获任何异常并记录日志
            LogUtils.log(Log.ERROR, kTag, "生成密钥时发生异常: ${e.message}")
            ByteArray(0)  // 返回空字节数组表示生成密钥失败
        }
    }

    // 2.解密文件并在内存中处理（不保存到文件）
    private fun aesDecryptInMemory(inputStream: InputStream, key: ByteArray): ByteArray {
        try {
            // 读取加密文件，获取IV（前16字节）
            val iv = ByteArray(16) // AES的IV长度是16字节
            val bytesRead = inputStream.read(iv) // 读取IV
            if (bytesRead != 16) {
                LogUtils.log(Log.ERROR, kTag, "IV长度不正确，解密失败")
                return ByteArray(0)  // 返回空字节数组
            }

            // 使用AES解密
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val ivSpec = IvParameterSpec(iv)
            val secretKey = SecretKeySpec(key, "AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

            CipherInputStream(inputStream, cipher).use { cipherInputStream ->
                ByteArrayOutputStream().use { outputStream ->
                    val buffer = ByteArray(4096)
                    var bytesReadInLoop: Int
                    while (cipherInputStream.read(buffer).also { bytesReadInLoop = it } != -1) {
                        outputStream.write(buffer, 0, bytesReadInLoop)
                    }
                    return outputStream.toByteArray()
                }
            }

        } catch (e: Exception) {
            LogUtils.log(Log.ERROR, kTag, "解密失败: ${e.message}")
        }

        // 出现任何错误时返回空字节数组
        return ByteArray(0)
    }

    //3. 验证
    private fun formatRemainingTime(notAfter: Date): String {
        val now = Date()
        val diff = notAfter.time - now.time
        if (diff <= 0) return "CAisTimeout"

        val days = TimeUnit.MILLISECONDS.toDays(diff)
        val hours = TimeUnit.MILLISECONDS.toHours(diff) % 24
        return "剩余时长:${days}天${hours}小时"
    }

}