package com.relay.agents

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalContext
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
    var notionToken by mutableStateOf(SecretStore.decrypt(st.setting("cn_notion_token", "")))
    var notionPage by mutableStateOf(st.setting("cn_notion_page", ""))
    var notionOn by mutableStateOf(st.setting("cn_notion_on", "0") == "1")
    var googleClientId by mutableStateOf(st.setting("cn_g_client", ""))
    var googleName by mutableStateOf(st.setting("cn_g_name", ""))
    var googleEmail by mutableStateOf(st.setting("cn_g_email", ""))

    fun save() {
        st.saveSetting("cn_webhook", SecretStore.encrypt(webhookUrl.trim()))
        st.saveSetting("cn_webhook_on", if (webhookOn) "1" else "0")
        st.saveSetting("cn_tg_token", SecretStore.encrypt(tgToken.trim()))
        st.saveSetting("cn_tg_chat", tgChat.trim())
        st.saveSetting("cn_tg_on", if (tgOn) "1" else "0")
        st.saveSetting("cn_ntfy", SecretStore.encrypt(ntfyUrl.trim()))
        st.saveSetting("cn_ntfy_on", if (ntfyOn) "1" else "0")
        st.saveSetting("cn_notion_token", SecretStore.encrypt(notionToken.trim()))
        st.saveSetting("cn_notion_page", notionPage.trim())
        st.saveSetting("cn_notion_on", if (notionOn) "1" else "0")
        st.saveSetting("cn_g_client", googleClientId.trim())
        st.saveSetting("cn_g_name", googleName)
        st.saveSetting("cn_g_email", googleEmail)
    }

    fun configured(kind: String): Boolean = when (kind) {
        "webhook" -> webhookUrl.trim().startsWith("https://")
        "telegram" -> tgToken.isNotBlank() && tgChat.isNotBlank()
        "notion" -> notionToken.isNotBlank() && notionId() != null
        else -> ntfyUrl.trim().startsWith("https://")
    }

    fun active(kind: String): Boolean = configured(kind) && when (kind) {
        "webhook" -> webhookOn
        "telegram" -> tgOn
        "notion" -> notionOn
        else -> ntfyOn
    }

    /** معرّف صفحة Notion (32 خانة سداسية) من رابط الصفحة أو المعرّف مباشرة. */
    fun notionId(): String? = Regex("[0-9a-fA-F]{32}").findAll(notionPage.replace("-", "")).lastOrNull()?.value
}

/** يرسل نص الإشعار إلى الموصلات المفعّلة (Webhook لـ Slack/Discord، تيليجرام، ntfy). */
object Notifier {
    private fun post(url: String, body: ByteArray, contentType: String, headers: Map<String, String> = emptyMap()): Result<Unit> = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", contentType)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
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
            "notion" -> {
                val id = c.notionId() ?: return@withContext Result.failure<Unit>(IOException("Invalid Notion page"))
                val blocks = org.json.JSONArray()
                text.take(10_000).chunked(1900).forEach { part ->
                    blocks.put(
                        JSONObject().put("object", "block").put("type", "paragraph").put(
                            "paragraph", JSONObject().put("rich_text", org.json.JSONArray().put(
                                JSONObject().put("type", "text").put("text", JSONObject().put("content", part))))
                        )
                    )
                }
                post(
                    "https://api.notion.com/v1/blocks/$id/children",
                    JSONObject().put("children", blocks).toString().toByteArray(Charsets.UTF_8),
                    "application/json",
                    mapOf("Authorization" to "Bearer ${c.notionToken.trim()}", "Notion-Version" to "2022-06-28")
                )
            }
            else -> post(c.ntfyUrl.trim(), msg.toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8")
        }
    }

    /** يرسل لكل موصل فعّال؛ الإخفاقات لا تؤثر على المهمة. */
    suspend fun broadcast(c: ConnectorConfig, text: String) {
        for (k in listOf("webhook", "telegram", "ntfy", "notion")) {
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
        add(Conn("google", "🔑", "Google", tr("تسجيل الدخول بحساب Google (الهوية فقط)."), cn.googleEmail.isNotEmpty()))
        add(Conn("webhook", "🔔", "Slack / Discord Webhook", tr("إشعار برابط Webhook عند اكتمال مهمة."), cn.active("webhook")))
        add(Conn("telegram", "✈️", "Telegram", tr("إشعار عبر بوت تيليجرام عند اكتمال مهمة."), cn.active("telegram")))
        add(Conn("notion", "📝", "Notion", tr("حفظ نتيجة كل مهمة في صفحة Notion."), cn.active("notion")))
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
                            c.id == "google" -> {
                                OutlinedButton(onClick = { open = if (open == c.id) null else c.id }) {
                                    Text(if (open == c.id) tr("إخفاء") else if (c.enabled) tr("إدارة") else tr("ربط"))
                                }
                                if (open == c.id) GoogleBody(vm, cn)
                            }
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
                                        "notion" -> {
                                            OutlinedTextField(cn.notionToken, { cn.notionToken = it }, label = { Text(tr("رمز تكامل Notion")) },
                                                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                                            OutlinedTextField(cn.notionPage, { cn.notionPage = it }, label = { Text(tr("رابط الصفحة أو معرّفها (شاركها مع التكامل)")) },
                                                singleLine = true, modifier = Modifier.fillMaxWidth())
                                        }
                                        else -> {
                                            OutlinedTextField(cn.ntfyUrl, { cn.ntfyUrl = it }, label = { Text(tr("رابط الموضوع (https://ntfy.sh/…)")) },
                                                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                                        }
                                    }
                                    val on = when (c.id) { "webhook" -> cn.webhookOn; "telegram" -> cn.tgOn; "notion" -> cn.notionOn; else -> cn.ntfyOn }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Switch(on, {
                                            when (c.id) { "webhook" -> cn.webhookOn = it; "telegram" -> cn.tgOn = it; "notion" -> cn.notionOn = it; else -> cn.ntfyOn = it }
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

private const val DEBUG_SHA1 = "F9:E5:91:69:90:90:C4:70:18:CD:0A:E1:18:A9:D3:C1:90:E1:60:38"

@Composable
private fun GoogleBody(vm: RelayViewModel, cn: ConnectorConfig) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    OutlinedTextField(cn.googleClientId, { cn.googleClientId = it }, label = { Text("Web client ID") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    if (cn.googleEmail.isNotEmpty()) {
        Text("✅ " + cn.googleName + " — " + cn.googleEmail, style = MaterialTheme.typography.bodyMedium)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy && cn.googleClientId.isNotBlank() && vm.cloudEnabled, onClick = {
            cn.save()
            scope.launch {
                busy = true
                val r = GoogleAuth.signIn(ctx, cn.googleClientId.trim())
                r.onSuccess {
                    cn.googleName = it.name; cn.googleEmail = it.email; cn.save()
                    if (vm.profileName.isBlank()) vm.saveProfile(it.givenName.ifEmpty { it.name }, vm.profilePrefs)
                    msg = tr("✅ تم تسجيل الدخول")
                }.onFailure { msg = "❌ " + (it.message ?: it.javaClass.simpleName) }
                busy = false
            }
        }) { Text(tr("تسجيل الدخول بحساب Google")) }
        if (cn.googleEmail.isNotEmpty()) {
            OutlinedButton(onClick = {
                scope.launch {
                    GoogleAuth.signOut(ctx)
                    cn.googleName = ""; cn.googleEmail = ""; cn.save(); msg = ""
                }
            }) { Text(tr("تسجيل الخروج")) }
        }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall)
    Text(tr("لتفعيل الدخول: في Google Cloud Console أنشئ عميل OAuth من نوع Android بحزمة التطبيق وبصمة SHA-1 أدناه، وأنشئ عميلاً آخر من نوع Web وانسخ معرّفه هنا. الدخول يعطي هويتك (الاسم والبريد) فقط، لا وصولاً إلى Drive أو Gmail."),
        style = MaterialTheme.typography.bodySmall)
    SelectionContainer {
        Text("Package: com.relay.agents\nSHA-1: $DEBUG_SHA1", style = MaterialTheme.typography.bodySmall)
    }
}
