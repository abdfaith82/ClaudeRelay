package com.relay.agents

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** نسخة احتياطية مشفّرة للوكلاء (المفاتيح داخلها): PBKDF2-SHA256 + AES-256-GCM بكلمة مرور يحددها المستخدم. */
object BackupCrypto {
    private const val MAGIC = "RLY1"
    private const val ITER = 120_000

    private fun key(pass: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(pass, salt, ITER, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    fun encrypt(plain: String, pass: String): String {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(pass.toCharArray(), salt), GCMParameterSpec(128, iv))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val enc = Base64.getEncoder().encodeToString(salt + iv + ct)
        return "$MAGIC:$enc"
    }

    /** يرمي استثناءً عند كلمة مرور خاطئة أو ملف تالف. */
    fun decrypt(text: String, pass: String): String {
        val t = text.trim()
        require(t.startsWith("$MAGIC:")) { "ليست نسخة احتياطية صالحة" }
        val all = Base64.getDecoder().decode(t.removePrefix("$MAGIC:"))
        require(all.size > 28) { "ملف تالف" }
        val salt = all.copyOfRange(0, 16)
        val iv = all.copyOfRange(16, 28)
        val ct = all.copyOfRange(28, all.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(pass.toCharArray(), salt), GCMParameterSpec(128, iv))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }
}

object AgentBackup {
    fun toJson(agents: List<AgentConfig>): String =
        JSONObject().put("version", 1).put("agents", JSONArray(agents.map { it.toJson() })).toString()

    fun fromJson(text: String): List<AgentConfig> {
        val a = JSONObject(text).getJSONArray("agents")
        return (0 until a.length()).map { AgentConfig.fromJson(a.getJSONObject(it)) }
    }

    /** دمج بلا تكرار (نفس المزوّد والمفتاح/الرابط)؛ يرجع القائمة الجديدة وعدد المضاف. */
    fun merge(existing: List<AgentConfig>, incoming: List<AgentConfig>): Pair<List<AgentConfig>, Int> {
        fun sig(a: AgentConfig) = a.provider.name + "|" + (if (a.provider.manual) a.model else a.apiKey)
        val seen = existing.map { sig(it) }.toMutableSet()
        val added = incoming.filter { seen.add(sig(it)) }
        return (existing + added) to added.size
    }
}
