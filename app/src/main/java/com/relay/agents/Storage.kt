package com.relay.agents

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * تخزين الوكلاء ونقطة الاستئناف.
 * مفاتيح API لا تُحفظ كنص صريح: تُشفّر باستخدام AES-256-GCM ومفتاح محفوظ في Android Keystore.
 */
class Storage(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("relay", Context.MODE_PRIVATE)
    private val cpFile get() = File(ctx.filesDir, "checkpoint.json")

    /** آخر رسالة خطأ للتحميل/الحفظ (للعرض في الواجهة)؛ null إذا نجحت العملية الأخيرة. */
    var lastError: String? = null
        private set

    fun loadAgents(): List<AgentConfig> {
        lastError = null
        val encrypted = prefs.getString(ENCRYPTED_AGENTS, null)
        if (encrypted != null) {
            // لا نرجع إلى نص صريح إذا تعذر فك التشفير؛ ذلك قد يخفي تلفاً أو مشكلة في المفتاح.
            return runCatching {
                val plain = decrypt(encrypted)
                val arr = JSONArray(plain)
                (0 until arr.length()).map { AgentConfig.fromJson(arr.getJSONObject(it)) }
            }.getOrElse {
                lastError = "تعذّر فك تشفير الوكلاء المحفوظين (قد يكون مفتاح Keystore فُقد). أعد إضافة الوكلاء ومفاتيحهم."
                emptyList()
            }
        }

        // ترحيل لمرة واحدة من إصدارات التطبيق القديمة التي خزنت المفاتيح كنص صريح.
        val legacy = prefs.getString(LEGACY_AGENTS, null) ?: return emptyList()
        val migrated = runCatching {
            val arr = JSONArray(legacy)
            (0 until arr.length()).map { AgentConfig.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        if (migrated.isEmpty()) {
            prefs.edit().remove(LEGACY_AGENTS).apply()
            return migrated
        }
        // فشل الترحيل لا يجب أن يُسقط التطبيق عند الإقلاع: نبقي النص القديم ونحاول لاحقاً.
        if (!saveAgents(migrated)) {
            lastError = "تعذّر تشفير الإعدادات القديمة الآن؛ ستُعاد المحاولة لاحقاً."
        }
        return migrated
    }

    /** يحفظ الوكلاء مشفّرين. يرجع false عند الفشل (ويضبط lastError) بدل رمي استثناء يُسقط التطبيق. */
    fun saveAgents(list: List<AgentConfig>): Boolean = try {
        val json = JSONArray(list.map { it.toJson() }).toString()
        val ciphertext = encrypt(json)
        val ok = prefs.edit()
            .putString(ENCRYPTED_AGENTS, ciphertext)
            .remove(LEGACY_AGENTS)
            .commit()
        if (!ok) lastError = "تعذّر حفظ إعدادات الوكلاء المشفّرة."
        ok
    } catch (e: Exception) {
        lastError = "تعذّر تشفير/حفظ الوكلاء: ${e.javaClass.simpleName}"
        false
    }

    fun loadCheckpoint(): Checkpoint? = runCatching {
        if (!cpFile.exists()) null else Checkpoint.fromJson(JSONObject(cpFile.readText()))
    }.getOrNull()

    fun saveCheckpoint(cp: Checkpoint) {
        val tmp = File(ctx.filesDir, "checkpoint.json.tmp")
        tmp.writeText(cp.toJson().toString())
        if (!tmp.renameTo(cpFile)) {
            tmp.copyTo(cpFile, overwrite = true)
            tmp.delete()
        }
    }

    fun clearCheckpoint() {
        cpFile.delete()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        // نخزّن IV قبل النص المشفّر؛ GCM يتحقق أيضاً من سلامة المحتوى عند فك التشفير.
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > GCM_IV_BYTES) { "بيانات الوكلاء المشفّرة غير مكتملة" }
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val encrypted = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val LEGACY_AGENTS = "agents"
        const val ENCRYPTED_AGENTS = "agents_encrypted_v1"
        const val KEY_ALIAS = "claude_relay_agents_key_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
