package com.relay.agents

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** إعدادات موصلات الإشعارات. الروابط والرموز مشفّرة بـ Keystore لأنها تحمل أسراراً. */
class ConnectorConfig(private val st: Storage) {
    var webhookUrl by mutableStateOf(SecretStore.decrypt(st.setting("cn_webhook", "")))
    var webhookOn by mutableStateOf(st.setting("cn_webhook_on", "0") == "1")
    var tgToken by mutableStateOf(SecretStore.decrypt(st.setting("cn_tg_token", "")))
    var tgChat by mutableStateOf(st.setting("cn_tg_chat", ""))
    var tgOn by mutableStateOf(st.setting("cn_tg_on", "0") == "1")
    var ntfyUrl by mutableStateOf(SecretStore.decrypt(st.setting("cn_ntfy", "")))
    var ntfyOn by mutableStateOf(st.setting("cn_ntfy_on", "0") == "1")

    fun save() {
        st.saveSetting("cn_webhook", SecretStore.encrypt(webhookUrl.trim()))
        st.saveSetting("cn_webhook_on", if (webhookOn) "1" else "0")
        st.saveSetting("cn_tg_token", SecretStore.encrypt(tgToken.trim()))
        st.saveSetting("cn_tg_chat", tgChat.trim())
        st.saveSetting("cn_tg_on", if (tgOn) "1" else "0")
        st.saveSetting("cn_ntfy", SecretStore.encrypt(ntfyUrl.trim()))
        st.saveSetting("cn_ntfy_on", if (ntfyOn) "1" else "0")
    }

    fun configured(kind: String): Boolean = when (kind) {
        "webhook" -> webhookUrl.trim().startsWith("https://")
        "telegram" -> tgToken.isNotBlank() && tgChat.isNotBlank()
        else -> ntfyUrl.trim().startsWith("https://")
    }

    fun active(kind: String): Boolean = configured(kind) && when (kind) {
        "webhook" -> webhookOn
        "telegram" -> tgOn
        else -> ntfyOn
    }
}

/** يرسل نص الإشعار إلى الموصلات المفعّلة (Webhook لـ Slack/Discord، تيليجرام، ntfy). */
object Notifier {
    private fun post(url: String, body: ByteArray, contentType: String): Result<Unit> = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", contentType)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
        } finally {
            c.disconnect()
        }
    }

    suspend fun send(kind: String, c: ConnectorConfig, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        val msg = text.take(1500)
        when (kind) {
            "webhook" -> post(
                c.webhookUrl.trim(),
                JSONObject().put("text", msg).put("content", msg).toString().toByteArray(Charsets.UTF_8),
                "application/json"
            )
            "telegram" -> post(
                "https://api.telegram.org/bot${c.tgToken.trim()}/sendMessage",
                JSONObject().put("chat_id", c.tgChat.trim()).put("text", msg).toString().toByteArray(Charsets.UTF_8),
                "application/json"
            )
            else -> post(c.ntfyUrl.trim(), msg.toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8")
        }
    }

    /** يرسل لكل موصل فعّال؛ الإخفاقات لا تؤثر على المهمة. */
    suspend fun broadcast(c: ConnectorConfig, text: String) {
        for (k in listOf("webhook", "telegram", "ntfy")) {
            if (c.active(k)) send(k, c, text)
        }
    }
}

private class Conn(val id: String, val icon: String, val title: String, val desc: String, val enabled: Boolean)

@Composable
private fun StatusChip(on: Boolean) {
    AssistChip(onClick = {}, label = { Text(if (on) tr("● مفعّل") else tr("متاح")) })
}

/** شاشة الموصلات: ما هو مفعّل وما هو متاح، على نمط Integrations/Connectors. */
@Composable
fun ConnectorsTab(vm: RelayViewModel, gh: GhConfig, onNavigate: (Int) -> Unit) {
    val cn = vm.connectors
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    var testMsg by remember { mutableStateOf("") }

    val list = buildList {
        add(Conn("github", "🐙", "GitHub", tr("رفع الملفات وتشغيل البناء السحابي."), gh.ready))
        Provider.values().filter { !it.manual }.forEach { p ->
            val n = vm.agents.count { it.provider == p && it.enabled }
            add(Conn("prov:" + p.name, "🤖", p.label, trf("وكلاء الذكاء الاصطناعي (%s مفعّل).", n), n > 0))
        }
        add(Conn("webhook", "🔔", "Slack / Discord Webhook", tr("إشعار برابط Webhook عند اكتمال مهمة."), cn.active("webhook")))
        add(Conn("telegram", "✈️", "Telegram", tr("إشعار عبر بوت تيليجرام عند اكتمال مهمة."), cn.active("telegram")))
        add(Conn("ntfy", "📣", "ntfy", tr("إشعار فوري على هاتفك عبر ntfy.sh."), cn.active("ntfy")))
    }
    val q = query.trim().lowercase()
    val shown = list.filter { q.isEmpty() || (it.title + " " + it.desc).lowercase().contains(q) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(query, { query = it }, placeholder = { Text(tr("بحث في الموصلات")) },
            leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (!vm.cloudEnabled) {
            Text(tr("الميزات السحابية متوقفة: لن تُرسل الإشعارات ولن يعمل GitHub. فعّلها من القدرات."),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        for ((title, items) in listOf(tr("الموصلات المفعّلة") to shown.filter { it.enabled }, tr("الموصلات المتاحة") to shown.filter { !it.enabled })) {
            if (items.isEmpty()) continue
            Text(title, style = MaterialTheme.typography.titleSmall)
            items.forEach { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.icon, style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.title, style = MaterialTheme.typography.titleMedium)
                                Text(c.desc, style = MaterialTheme.typography.bodySmall)
                            }
                            StatusChip(c.enabled)
                        }
                        when {
                            c.id == "github" -> OutlinedButton(onClick = { onNavigate(6) }) { Text(tr("إدارة")) }
                            c.id.startsWith("prov:") -> OutlinedButton(onClick = { onNavigate(0) }) { Text(if (c.enabled) tr("إدارة") else tr("ربط")) }
                            else -> {
                                OutlinedButton(onClick = { open = if (open == c.id) null else c.id; testMsg = "" }) {
                                    Text(if (open == c.id) tr("إخفاء") else if (c.enabled) tr("إدارة") else tr("ربط"))
                                }
                                if (open == c.id) {
                                    when (c.id) {
                                        "webhook" -> {
                                            OutlinedTextField(cn.webhookUrl, { cn.webhookUrl = it }, label = { Text(tr("رابط Webhook (https)")) },
                                                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                                        }
                                        "telegram" -> {
                                            OutlinedTextField(cn.tgToken, { cn.tgToken = it }, label = { Text(tr("رمز البوت")) },
                                                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                                            OutlinedTextField(cn.tgChat, { cn.tgChat = it }, label = { Text("Chat ID") },
                                                singleLine = true, modifier = Modifier.fillMaxWidth())
                                        }
                                        else -> {
                                            OutlinedTextField(cn.ntfyUrl, { cn.ntfyUrl = it }, label = { Text(tr("رابط الموضوع (https://ntfy.sh/…)")) },
                                                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                                        }
                                    }
                                    val on = when (c.id) { "webhook" -> cn.webhookOn; "telegram" -> cn.tgOn; else -> cn.ntfyOn }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Switch(on, {
                                            when (c.id) { "webhook" -> cn.webhookOn = it; "telegram" -> cn.tgOn = it; else -> cn.ntfyOn = it }
                                            cn.save()
                                        })
                                        Spacer(Modifier.width(8.dp))
                                        Text(tr("إرسال إشعار عند اكتمال كل مهمة"), style = MaterialTheme.typography.bodySmall)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { cn.save(); testMsg = tr("✅ تم الحفظ (مشفّر)") }) { Text(tr("حفظ")) }
                                        OutlinedButton(enabled = cn.configured(c.id) && vm.cloudEnabled, onClick = {
                                            cn.save()
                                            scope.launch {
                                                testMsg = tr("⏳ جارٍ الإرسال…")
                                                val r = Notifier.send(c.id, cn, "✅ Claude Relay: test notification")
                                                testMsg = if (r.isSuccess) tr("✅ وصل الإشعار التجريبي") else "❌ " + (r.exceptionOrNull()?.message ?: "error")
                                            }
                                        }) { Text(tr("إرسال تجربة")) }
                                    }
                                    if (testMsg.isNotEmpty()) Text(testMsg, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (shown.isEmpty()) Text(tr("لا توجد نتائج"))
    }
}
