package com.apex.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.CRC32C
import java.util.zip.Checksum
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 加密工具类，提供哈希、加密、编码等常用加密功能
 */
object CryptoUtils {

    private const val AES_ALGORITHM = "AES/CBC/PKCS5Padding"
    private const val AES_KEY_ALGORITHM = "AES"
    private const val IV_SIZE = 16
    private const val HEX_CHARS = "0123456789abcdef"
    private const val ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    private val MORSE_CODE = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".",
        'F' to "..-.", 'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---",
        'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---",
        'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-",
        'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--",
        'Z' to "--..",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
        '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----."
    )

    private val MORSE_REVERSE = MORSE_CODE.entries.associate { it.value to it.key }

    /**
     * 计算字符串的 SHA-256 哈希值
     *
     * @param input 输入字符串
     * @return SHA-256 十六进制哈希字符串
     */
    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return byteArrayToHex(hashBytes)
    }

    /**
     * 计算字符串的 SHA-384 哈希值。
     *
     * @param input 输入字符串
     * @return SHA-384 十六进制哈希字符串
     */
    fun sha384(input: String): String {
        val digest = MessageDigest.getInstance("SHA-384")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return byteArrayToHex(hashBytes)
    }

    /**
     * 计算字符串的 SHA-512 哈希值
     *
     * @param input 输入字符串
     * @return SHA-512 十六进制哈希字符串
     */
    fun sha512(input: String): String {
        val digest = MessageDigest.getInstance("SHA-512")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return byteArrayToHex(hashBytes)
    }

    /**
     * 计算字符串的 MD5 哈希值
     *
     * @param input 输入字符串
     * @return MD5 十六进制哈希字符串
     */
    fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return byteArrayToHex(hashBytes)
    }

    /**
     * 使用 HMAC-SHA256 算法计算消息认证码
     *
     * @param data 待认证的数据
     * @param key 密钥
     * @return HMAC-SHA256 十六进制字符串
     */
    fun hmacSha256(data: String, key: String): String {
        val secretKeySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(secretKeySpec)
        val hmacBytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return byteArrayToHex(hmacBytes)
    }

    /**
     * 使用 HMAC-SHA512 算法计算消息认证码。
     *
     * @param data 待认证的数据
     * @param key 密钥
     * @return HMAC-SHA512 十六进制字符串
     */
    fun hmacSha512(data: String, key: String): String {
        val secretKeySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA512")
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(secretKeySpec)
        val hmacBytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return byteArrayToHex(hmacBytes)
    }

    /**
     * 将字符串进行 Base64 编码
     *
     * @param input 输入字符串
     * @return Base64 编码后的字符串
     */
    fun base64Encode(input: String): String {
        return Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))
    }

    /**
     * 将 Base64 编码的字符串解码为原始字符串
     *
     * @param input Base64 编码的字符串
     * @return 解码后的原始字符串
     */
    fun base64Decode(input: String): String {
        return String(Base64.getDecoder().decode(input), Charsets.UTF_8)
    }

    /**
     * 将字节数组进行 Base64 编码。
     *
     * @param input 输入字节数组
     * @return Base64 编码后的字符串
     */
    fun base64Encode(input: ByteArray): String {
        return Base64.getEncoder().encodeToString(input)
    }

    /**
     * 将 Base64 字符串解码为字节数组。
     *
     * @param input Base64 字符串
     * @return 解码后的字节数组
     */
    fun base64DecodeToBytes(input: String): ByteArray {
        return Base64.getDecoder().decode(input)
    }

    /**
     * 将字符串进行 URL 安全的 Base64 编码。
     *
     * @param input 输入字符串
     * @return URL 安全 Base64 字符串
     */
    fun base64UrlEncode(input: String): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(input.toByteArray(Charsets.UTF_8))
    }

    /**
     * 将 URL 安全的 Base64 字符串解码。
     *
     * @param input URL 安全 Base64 字符串
     * @return 解码后的字符串
     */
    fun base64UrlDecode(input: String): String {
        return String(Base64.getUrlDecoder().decode(input), Charsets.UTF_8)
    }

    /**
     * 使用 AES 算法加密字符串
     *
     * @param plainText 明文
     * @param secretKey 密钥（将使用 SHA-256 哈希生成 256 位密钥）
     * @return Base64 编码的密文（包含 IV）
     */
    fun aesEncrypt(plainText: String, secretKey: String): String {
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(secretKey.toByteArray(Charsets.UTF_8))
        val secretKeySpec = SecretKeySpec(keyBytes, AES_KEY_ALGORITHM)

        val iv = ByteArray(IV_SIZE)
        SecureRandom().nextBytes(iv)
        val ivSpec = IvParameterSpec(iv)

        val cipher = Cipher.getInstance(AES_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivSpec)
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(IV_SIZE + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, IV_SIZE)
        System.arraycopy(encrypted, 0, combined, IV_SIZE, encrypted.size)

        return Base64.getEncoder().encodeToString(combined)
    }

    /**
     * 使用 AES 算法解密 Base64 编码的密文
     *
     * @param cipherText Base64 编码的密文（含 IV）
     * @param secretKey 密钥（必须与加密时使用的密钥一致）
     * @return 解密后的明文
     */
    fun aesDecrypt(cipherText: String, secretKey: String): String {
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(secretKey.toByteArray(Charsets.UTF_8))
        val secretKeySpec = SecretKeySpec(keyBytes, AES_KEY_ALGORITHM)

        val combined = Base64.getDecoder().decode(cipherText)
        if (combined.size < IV_SIZE) throw IllegalArgumentException("Invalid cipher text")

        val iv = combined.copyOfRange(0, IV_SIZE)
        val ivSpec = IvParameterSpec(iv)
        val encrypted = combined.copyOfRange(IV_SIZE, combined.size)

        val cipher = Cipher.getInstance(AES_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec)
        val decrypted = cipher.doFinal(encrypted)

        return String(decrypted, Charsets.UTF_8)
    }

    /**
     * 使用 AES 算法加密字符串（密码派生密钥）。
     *
     * @param plaintext 明文
     * @param password 密码
     * @return Base64 编码的密文（含盐值+IV）
     */
    fun encryptString(plaintext: String, password: String): String {
        val salt = SecureRandom().generateSeed(16)
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(salt + password.toByteArray(Charsets.UTF_8))
        val secretKeySpec = SecretKeySpec(keyBytes, AES_KEY_ALGORITHM)

        val iv = ByteArray(IV_SIZE)
        SecureRandom().nextBytes(iv)
        val ivSpec = IvParameterSpec(iv)
        val cipher = Cipher.getInstance(AES_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivSpec)
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(16 + IV_SIZE + encrypted.size)
        System.arraycopy(salt, 0, combined, 0, 16)
        System.arraycopy(iv, 0, combined, 16, IV_SIZE)
        System.arraycopy(encrypted, 0, combined, 16 + IV_SIZE, encrypted.size)

        return Base64.getEncoder().encodeToString(combined)
    }

    /**
     * 使用 AES 算法解密由 encryptString 加密的字符串。
     *
     * @param ciphertext Base64 编码的密文（含盐值+IV）
     * @param password 密码
     * @return 解密后的明文
     */
    fun decryptString(ciphertext: String, password: String): String {
        val combined = Base64.getDecoder().decode(ciphertext)
        if (combined.size < 32) throw IllegalArgumentException("Invalid cipher text")

        val salt = combined.copyOfRange(0, 16)
        val iv = combined.copyOfRange(16, 32)
        val ivSpec = IvParameterSpec(iv)
        val encrypted = combined.copyOfRange(32, combined.size)

        val keyBytes = MessageDigest.getInstance("SHA-256").digest(salt + password.toByteArray(Charsets.UTF_8))
        val secretKeySpec = SecretKeySpec(keyBytes, AES_KEY_ALGORITHM)
        val cipher = Cipher.getInstance(AES_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec)
        val decrypted = cipher.doFinal(encrypted)

        return String(decrypted, Charsets.UTF_8)
    }

    /**
     * 使用 RSA 算法加密字符串。
     *
     * @param plaintext 明文
     * @param publicKeyBase64 Base64 编码的公钥
     * @return Base64 编码的密文
     */
    fun rsaEncrypt(plaintext: String, publicKeyBase64: String): String {
        val keySpec = X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64))
        val keyFactory = java.security.KeyFactory.getInstance("RSA")
        val publicKey = keyFactory.generatePublic(keySpec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(encrypted)
    }

    /**
     * 使用 RSA 算法解密字符串。
     *
     * @param ciphertext Base64 编码的密文
     * @param privateKeyBase64 Base64 编码的私钥
     * @return 解密后的明文
     */
    fun rsaDecrypt(ciphertext: String, privateKeyBase64: String): String {
        val keySpec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64))
        val keyFactory = java.security.KeyFactory.getInstance("RSA")
        val privateKey = keyFactory.generatePrivate(keySpec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        val decrypted = cipher.doFinal(Base64.getDecoder().decode(ciphertext))
        return String(decrypted, Charsets.UTF_8)
    }

    /**
     * 生成 RSA 密钥对。
     *
     * @param size 密钥大小（位），默认 2048
     * @return 包含公钥和私钥的 Pair（Base64 编码）
     */
    fun generateRsaKeyPair(size: Int = 2048): Pair<String, String> {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(size, SecureRandom())
        val keyPair = generator.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val privateKey = Base64.getEncoder().encodeToString(keyPair.private.encoded)
        return Pair(publicKey, privateKey)
    }

    /**
     * 生成 AES 密钥（Base64 编码）。
     *
     * @param keySize 密钥大小（位），默认 256
     * @return Base64 编码的密钥
     */
    fun generateAesKey(keySize: Int = 256): String {
        val generator = KeyGenerator.getInstance("AES")
        generator.init(keySize, SecureRandom())
        val key = generator.generateKey()
        return Base64.getEncoder().encodeToString(key.encoded)
    }

    /**
     * 生成指定长度的随机字母数字字符串
     *
     * @param length 字符串长度
     * @return 随机字符串
     */
    fun generateRandomString(length: Int): String {
        if (length < 0) throw IllegalArgumentException("length must be non-negative, got $length")
        if (length == 0) return ""
        val random = SecureRandom()
        val result = StringBuilder(length)
        for (i in 0 until length) {
            val index = random.nextInt(ALPHANUMERIC.length)
            result.append(ALPHANUMERIC[index])
        }
        return result.toString()
    }

    /**
     * 生成随机 UUID（通用唯一标识符）
     *
     * @return UUID 字符串
     */
    fun generateUUID(): String {
        return UUID.randomUUID().toString()
    }

    /**
     * 使用简单 XOR 算法混淆字符串
     *
     * @param input 原始字符串
     * @return 混淆后的 Base64 字符串
     */
    fun simpleObfuscate(input: String): String {
        val key = 0x5A
        val bytes = input.toByteArray(Charsets.UTF_8)
        for (i in bytes.indices) {
            bytes[i] = (bytes[i].toInt() xor key).toByte()
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * 还原使用简单 XOR 算法混淆的字符串
     *
     * @param input 混淆后的 Base64 字符串
     * @return 还原后的原始字符串
     */
    fun simpleDeobfuscate(input: String): String {
        val key = 0x5A
        val bytes = Base64.getDecoder().decode(input)
        for (i in bytes.indices) {
            bytes[i] = (bytes[i].toInt() xor key).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * 将字节数组转换为十六进制字符串
     *
     * @param bytes 字节数组
     * @return 十六进制字符串
     */
    fun byteArrayToHex(bytes: ByteArray): String {
        val result = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val unsigned = b.toInt() and 0xFF
            result.append(HEX_CHARS[unsigned shr 4])
            result.append(HEX_CHARS[unsigned and 0x0F])
        }
        return result.toString()
    }

    /**
     * 将十六进制字符串转换为字节数组
     *
     * @param hex 十六进制字符串
     * @return 字节数组
     */
    fun hexToByteArray(hex: String): ByteArray {
        if (hex.length % 2 != 0) throw IllegalArgumentException("hex string must have even length")
        val len = hex.length / 2
        val result = ByteArray(len)
        for (i in 0 until len) {
            val high = Character.digit(hex[i * 2], 16)
            val low = Character.digit(hex[i * 2 + 1], 16)
            if (high == -1 || low == -1) throw IllegalArgumentException("invalid hex character")
            result[i] = ((high shl 4) or low).toByte()
        }
        return result
    }

    /**
     * 生成安全的随机密钥字节数组
     *
     * @param keySize 密钥大小（字节数）
     * @return 随机密钥字节数组
     */
    fun generateSecureRandomBytes(keySize: Int): ByteArray {
        val random = SecureRandom()
        val bytes = ByteArray(keySize)
        random.nextBytes(bytes)
        return bytes
    }

    /**
     * 计算字符串的 CRC32 校验和
     *
     * @param input 输入字符串
     * @return CRC32 十六进制校验和字符串
     */
    fun crc32(input: String): String {
        val crc = CRC32()
        crc.update(input.toByteArray(Charsets.UTF_8))
        val checksum = crc.value
        return byteArrayToHex(
            byteArrayOf(
                (checksum shr 24).toByte(),
                (checksum shr 16).toByte(),
                (checksum shr 8).toByte(),
                checksum.toByte()
            )
        )
    }

    /**
     * 计算字符串的 CRC64 校验和（基于 CRC32C，高位+低位）。
     *
     * @param input 输入字符串
     * @return CRC64 十六进制校验和字符串
     */
    fun crc64(input: String): String {
        val crc32c = CRC32C()
        crc32c.update(input.toByteArray(Charsets.UTF_8))
        val value = crc32c.value
        val bytes = byteArrayOf(
            (value shr 56).toByte(),
            (value shr 48).toByte(),
            (value shr 40).toByte(),
            (value shr 32).toByte(),
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
        return byteArrayToHex(bytes)
    }

    /**
     * 计算字符串的简单校验和（所有字节求和取模）。
     *
     * @param input 输入字符串
     * @return 校验和值
     */
    fun checksum(input: String): Int {
        return input.toByteArray(Charsets.UTF_8).sumOf { it.toInt() and 0xFF } and 0xFFFF
    }

    /**
     * 生成随机字节数组。
     *
     * @param length 长度
     * @return 随机字节数组
     */
    fun randomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    /**
     * 生成随机十六进制字符串。
     *
     * @param length 需要的十六进制字符数
     * @return 随机十六进制字符串
     */
    fun randomHexString(length: Int): String {
        val bytes = ByteArray((length + 1) / 2)
        SecureRandom().nextBytes(bytes)
        return byteArrayToHex(bytes).take(length)
    }

    /**
     * 生成随机字母数字字符串。
     *
     * @param length 字符串长度
     * @return 随机字符串
     */
    fun randomAlphanumeric(length: Int): String {
        return generateRandomString(length)
    }

    /**
     * 使用 XOR 加密/解密字节数组。
     *
     * @param input 输入字节数组
     * @param key 密钥字节数组
     * @return XOR 处理后的字节数组
     */
    fun xor(input: ByteArray, key: ByteArray): ByteArray {
        val result = ByteArray(input.size)
        for (i in input.indices) {
            result[i] = (input[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return result
    }

    /**
     * 使用 XOR 加密/解密字符串。
     *
     * @param input 输入字符串
     * @param key 密钥字符串
     * @return Base64 编码的 XOR 结果
     */
    fun xor(input: String, key: String): String {
        val result = xor(input.toByteArray(Charsets.UTF_8), key.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(result)
    }

    /**
     * 凯撒密码加密/解密。
     *
     * @param input 输入字符串
     * @param shift 偏移量
     * @return 移位后的字符串
     */
    fun caesarCipher(input: String, shift: Int): String {
        val effectiveShift = shift % 26
        return input.map { ch ->
            when {
                ch in 'A'..'Z' -> 'A' + (ch - 'A' + effectiveShift + 26) % 26
                ch in 'a'..'z' -> 'a' + (ch - 'a' + effectiveShift + 26) % 26
                else -> ch
            }
        }.joinToString("")
    }

    /**
     * ROT13 编码/解码。
     *
     * @param input 输入字符串
     * @return ROT13 处理后的字符串
     */
    fun rot13(input: String): String {
        return caesarCipher(input, 13)
    }

    /**
     * 将字节数组编码为十六进制字符串。
     *
     * @param input 输入字节数组
     * @return 十六进制字符串
     */
    fun hexEncode(input: ByteArray): String {
        return byteArrayToHex(input)
    }

    /**
     * 将十六进制字符串解码为字节数组。
     *
     * @param hex 十六进制字符串
     * @return 字节数组
     */
    fun hexDecode(hex: String): ByteArray {
        return hexToByteArray(hex)
    }

    /**
     * 计算字符串的简短指纹（前 8 个十六进制字符）。
     *
     * @param input 输入字符串
     * @return 8 字符指纹
     */
    fun fingerprint(input: String): String {
        return sha256(input).take(8)
    }

    /**
     * 哈希密码（含随机盐值）。
     *
     * @param password 密码
     * @return 格式为 salt:hash 的字符串
     */
    fun hashPassword(password: String): String {
        val salt = generateSecureRandomBytes(16)
        val hash = MessageDigest.getInstance("SHA-256").digest(salt + password.toByteArray(Charsets.UTF_8))
        return "${byteArrayToHex(salt)}:${byteArrayToHex(hash)}"
    }

    /**
     * 验证密码是否匹配 hashPassword 生成的哈希。
     *
     * @param password 待验证的密码
     * @param hash hashPassword 生成的字符串
     * @return 如果匹配返回 true
     */
    fun verifyPassword(password: String, hash: String): Boolean {
        val parts = hash.split(":")
        if (parts.size != 2) return false
        val salt = hexToByteArray(parts[0])
        val expectedHash = parts[1]
        val actualHash = MessageDigest.getInstance("SHA-256").digest(salt + password.toByteArray(Charsets.UTF_8))
        return byteArrayToHex(actualHash) == expectedHash
    }

    /**
     * 生成密码学安全的盐值。
     *
     * @param length 盐值长度（字节），默认 16
     * @return 十六进制编码的盐值
     */
    fun generateSalt(length: Int = 16): String {
        return byteArrayToHex(generateSecureRandomBytes(length))
    }

    /**
     * 使用 PBKDF2 派生密钥。
     *
     * @param password 密码
     * @param salt 盐值
     * @param iterations 迭代次数，默认 10000
     * @param keyLength 密钥长度（位），默认 256
     * @return 十六进制编码的派生密钥
     */
    fun pbkdf2(password: String, salt: String, iterations: Int = 10000, keyLength: Int = 256): String {
        val spec = PBEKeySpec(password.toCharArray(), salt.toByteArray(Charsets.UTF_8), iterations, keyLength)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = factory.generateSecret(spec)
        return byteArrayToHex(key.encoded)
    }

    /**
     * 加密文件（AES）。
     *
     * @param inputFile 输入文件
     * @param outputFile 输出文件
     * @param key Base64 编码的 AES 密钥
     * @return 加密成功返回 true
     */
    fun encryptFile(inputFile: File, outputFile: File, key: String): Boolean {
        return try {
            val keyBytes = Base64.getDecoder().decode(key)
            val secretKeySpec = SecretKeySpec(keyBytes, AES_KEY_ALGORITHM)
            val iv = ByteArray(IV_SIZE)
            SecureRandom().nextBytes(iv)
            val ivSpec = IvParameterSpec(iv)
            val cipher = Cipher.getInstance(AES_ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivSpec)

            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { output ->
                output.write(iv)
                FileInputStream(inputFile).use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } >= 0) {
                        val encrypted = cipher.update(buffer, 0, read)
                        if (encrypted != null) output.write(encrypted)
                    }
                    val finalBytes = cipher.doFinal()
                    if (finalBytes != null) output.write(finalBytes)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 解密文件（AES）。
     *
     * @param inputFile 输入文件（加密的）
     * @param outputFile 输出文件
     * @param key Base64 编码的 AES 密钥
     * @return 解密成功返回 true
     */
    fun decryptFile(inputFile: File, outputFile: File, key: String): Boolean {
        return try {
            val keyBytes = Base64.getDecoder().decode(key)
            val secretKeySpec = SecretKeySpec(keyBytes, AES_KEY_ALGORITHM)

            FileInputStream(inputFile).use { input ->
                val iv = ByteArray(IV_SIZE)
                if (input.read(iv) != IV_SIZE) throw IllegalArgumentException("Invalid file format")
                val ivSpec = IvParameterSpec(iv)
                val cipher = Cipher.getInstance(AES_ALGORITHM)
                cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec)

                outputFile.parentFile?.mkdirs()
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } >= 0) {
                        val decrypted = cipher.update(buffer, 0, read)
                        if (decrypted != null) output.write(decrypted)
                    }
                    val finalBytes = cipher.doFinal()
                    if (finalBytes != null) output.write(finalBytes)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 常量时间比较两个字符串（防止时序攻击）。
     *
     * @param a 第一个字符串
     * @param b 第二个字符串
     * @return 如果相等返回 true
     */
    fun secureCompare(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    /**
     * 将字符串转换为摩斯密码。
     *
     * @param input 输入字符串
     * @return 摩斯密码字符串（单词用 / 分隔）
     */
    fun toMorseCode(input: String): String {
        return input.uppercase().split(" ").joinToString(" / ") { word ->
            word.map { MORSE_CODE[it] ?: it.toString() }.joinToString(" ")
        }
    }

    /**
     * 将摩斯密码转换为字符串。
     *
     * @param morse 摩斯密码字符串
     * @return 解码后的字符串
     */
    fun fromMorseCode(morse: String): String {
        return morse.split(" / ").joinToString(" ") { word ->
            word.split(" ").map { MORSE_REVERSE[it] ?: it.firstOrNull() ?: '?' }.joinToString("")
        }
    }
}
