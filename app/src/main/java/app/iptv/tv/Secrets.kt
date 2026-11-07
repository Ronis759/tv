package app.iptv.tv

import android.util.Base64
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Зашифрованные адреса плейлистов. В коде лежат только шифртексты (AES-256-GCM, ключ из секретного кода через PBKDF2);
 * адрес появляется, только если введён верный код. Формат блока: base64( соль 16 | IV 12 | шифртекст + тег ).
 */
object Secrets {
    private const val ITER = 100000
    private val BLOBS = listOf(
        "CaEOJJ0KmXSRk60XHk8zmFejvlqalFqueWYwu7p4q1DW6DQxEtrc5E0WLRUba/TnxZA7puRX/kXS6k5v7z63RfvB5F5+4fOxylpfuLZowyQj6hOVPOfjsw==",
        "j1XbQ4mRcpldpukCNYuvBu7kZAUGItl23jBTr16B/6s6dmQReesN0SS2pUXC3uenATk4MUd5K3R3633mQuiG2tCV7yo1rpwtf9C6c0nQJ7Nmsml5VCxRgPx1"
    )

    /** Возвращает адрес для кода или null, если код не подошёл. Медленно (PBKDF2) — вызывать из фонового потока. */
    fun unlock(code: String): String? {
        for (b in BLOBS) {
            try {
                val d = Base64.decode(b, Base64.DEFAULT)
                val salt = Arrays.copyOfRange(d, 0, 16)
                val iv = Arrays.copyOfRange(d, 16, 28)
                val ct = Arrays.copyOfRange(d, 28, d.size)
                val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                    .generateSecret(PBEKeySpec(code.toCharArray(), salt, ITER, 256)).encoded
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                return String(c.doFinal(ct), Charsets.UTF_8)
            } catch (e: Exception) {
                // код не подошёл к этому блоку
            }
        }
        return null
    }
}
