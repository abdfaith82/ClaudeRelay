package com.relay.agents

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** إعدادات GitHub المشتركة بين شاشتي الرفع والمحاكي السحابي. الرمز مشفّر بـ Keystore. */
class GhConfig(private val st: Storage) {
    var token by mutableStateOf(SecretStore.decrypt(st.setting("gh_token", "")))
    var owner by mutableStateOf(st.setting("gh_owner", "abdfaith82"))
    var repo by mutableStateOf(st.setting("gh_repo", "ClaudeRelay"))
    var branch by mutableStateOf(st.setting("gh_branch", "main"))
    var workflow by mutableStateOf(st.setting("gh_workflow", "build.yml"))

    val ready: Boolean get() = token.isNotBlank() && owner.isNotBlank() && repo.isNotBlank() && branch.isNotBlank()

    fun save() {
        st.saveSetting("gh_token", SecretStore.encrypt(token.trim()))
        st.saveSetting("gh_owner", owner.trim())
        st.saveSetting("gh_repo", repo.trim())
        st.saveSetting("gh_branch", branch.trim())
        st.saveSetting("gh_workflow", workflow.trim())
    }
}

private fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun GhConnectionFields(cfg: GhConfig, withWorkflow: Boolean) {
    val ctx = LocalContext.current
    var saved by remember { mutableStateOf(false) }
    OutlinedTextField(
        cfg.token, { cfg.token = it; saved = false },
        label = { Text(tr("رمز GitHub (PAT)")) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true, modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(cfg.owner, { cfg.owner = it; saved = false }, label = { Text(tr("المالك")) },
            singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(cfg.repo, { cfg.repo = it; saved = false }, label = { Text(tr("المستودع")) },
            singleLine = true, modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(cfg.branch, { cfg.branch = it; saved = false }, label = { Text(tr("الفرع")) },
            singleLine = true, modifier = Modifier.weight(1f))
        if (withWorkflow) {
            OutlinedTextField(cfg.workflow, { cfg.workflow = it; saved = false }, label = { Text(tr("ملف سير العمل")) },
                singleLine = true, modifier = Modifier.weight(1f))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { cfg.save(); saved = true }) { Text(tr("حفظ")) }
        TextButton(onClick = {
            openUrl(ctx, "https://github.com/settings/tokens/new?scopes=repo,workflow&description=ClaudeRelay")
        }) { Text(tr("إنشاء رمز")) }
        if (saved) Text(tr("✅ تم الحفظ (مشفّر)"), style = MaterialTheme.typography.bodySmall)
    }
}

/** شاشة الرفع إلى GitHub: خطوة 1 سحب ومقارنة، خطوة 2 رفع التغييرات فقط (بدون force). */
@Composable
fun GitHubTab(vm: RelayViewModel, cfg: GhConfig) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val candidates = remember(vm.projects) { vm.projects.filter { it.checkpoint.coding }.sortedByDescending { it.updatedAt } }
    var selected by remember { mutableStateOf<String?>(null) }
    val sel = selected ?: candidates.firstOrNull()?.id
    var prefix by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Update from Claude Relay") }
    var strategy by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var diff by remember { mutableStateOf<GitDiff?>(null) }
    var commitUrl by remember { mutableStateOf("") }
    val log = remember { mutableStateListOf<String>() }

    fun localFiles(): Map<String, String> {
        val id = sel ?: return emptyMap()
        val p = prefix.trim().trim('/')
        return vm.codeFiles(id).mapKeys { if (p.isEmpty()) it.key else "$p/${it.key}" }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(tr("الاتصال بـ GitHub")) {
            GhConnectionFields(cfg, withWorkflow = false)
            Text(tr("الرمز يُحفظ مشفّراً على جهازك فقط ولا يُكتب في أي ملف."), style = MaterialTheme.typography.bodySmall)
        }

        SectionCard(tr("المشروع المراد رفعه")) {
            if (candidates.isEmpty()) {
                Text(tr("لا توجد مشاريع برمجة بعد. فعّل وضع البرمجة وأنشئ مشروعاً أولاً."))
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    candidates.forEach { p ->
                        FilterChip(selected = sel == p.id, onClick = { selected = p.id; diff = null },
                            label = { Text(p.name, maxLines = 1) })
                    }
                }
                OutlinedTextField(prefix, { prefix = it; diff = null }, label = { Text(tr("مجلد فرعي في المستودع (اختياري)")) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(message, { message = it }, label = { Text(tr("رسالة الـ commit")) },
                    modifier = Modifier.fillMaxWidth())
            }
        }

        SectionCard(tr("استراتيجية الرفع")) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = strategy == 0, onClick = { strategy = 0 }, label = { Text(tr("سحب ومقارنة ثم رفع")) })
                FilterChip(selected = strategy == 1, onClick = { strategy = 1 }, label = { Text(tr("رفع مباشر")) })
            }
            Text(
                if (strategy == 0) tr("تُقارن ملفاتك بالمستودع أولاً وتراجع التغييرات، ثم ترفعها.")
                else tr("يُرفع مباشرة؛ التغييرات فقط تُرسل ولا تُحذف ملفات المستودع."),
                style = MaterialTheme.typography.bodySmall
            )
            Text(tr("دائماً: commit واحد ذري، بدون force push، وبدون حذف ملفات موجودة في المستودع."),
                style = MaterialTheme.typography.bodySmall)

            val canRun = cfg.ready && sel != null && !busy
            OutlinedButton(
                enabled = canRun,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val files = localFiles()
                    scope.launch {
                        busy = true
                        val r = withContext(Dispatchers.IO) { GitHubClient.remote(cfg.token.trim(), cfg.owner.trim(), cfg.repo.trim(), cfg.branch.trim()) }
                        r.onSuccess {
                            val d = GitHubClient.diff(files, it.files)
                            diff = d
                            log.add(trf("مقارنة: جديد %s، معدّل %s، متطابق %s", d.added.size, d.modified.size, d.same.size))
                        }.onFailure { log.add("❌ " + (it.message ?: it.toString())) }
                        busy = false
                    }
                }
            ) { Text(tr("الخطوة 1: سحب ومقارنة")) }

            val d = diff
            if (d != null) {
                Text(trf("جديد: %s • معدّل: %s • متطابق: %s", d.added.size, d.modified.size, d.same.size),
                    style = MaterialTheme.typography.bodyMedium)
                d.changed.take(30).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }

            Button(
                enabled = canRun && (strategy == 1 || (d != null && d.changed.isNotEmpty())),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val files = localFiles()
                    scope.launch {
                        busy = true
                        val r = withContext(Dispatchers.IO) {
                            GitHubClient.push(cfg.token.trim(), cfg.owner.trim(), cfg.repo.trim(), cfg.branch.trim(), files, message.ifBlank { "Update" })
                        }
                        r.onSuccess {
                            if (it.count == 0) log.add(tr("لا تغييرات للرفع."))
                            else {
                                commitUrl = it.commitUrl
                                log.add(trf("✅ تم رفع %s ملف في commit واحد", it.count))
                            }
                            diff = null
                        }.onFailure { log.add("❌ " + (it.message ?: it.toString())) }
                        busy = false
                    }
                }
            ) { Text(tr("الخطوة 2: رفع إلى GitHub")) }

            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (log.isNotEmpty() || commitUrl.isNotEmpty()) {
            SectionCard(tr("السجل")) {
                SelectionContainer {
                    Column { log.takeLast(12).forEach { Text(it, style = MaterialTheme.typography.bodySmall) } }
                }
                if (commitUrl.isNotEmpty()) {
                    TextButton(onClick = { openUrl(ctx, commitUrl) }) { Text(tr("فتح الـ commit")) }
                }
            }
        }
    }
}

private data class PreviewSrc(val key: String, val html: String?, val url: String?)

private data class DeviceProfile(val label: String, val w: Int, val h: Int)

private val LIBRARIES = listOf(
    "Android Gradle Plugin" to "8.2.2",
    "Kotlin" to "1.9.22",
    "Compose compiler" to "1.5.8",
    "Compose BOM" to "2024.02.00",
    "Gradle" to "8.5",
    "JDK" to "17",
    "minSdk / targetSdk" to "26 / 34",
    "Material 3" to "1.2.0",
    "Credential Manager" to "1.2.2"
)

/** شاشة المحاكي: معاينة واجهات الويب داخل إطار جهاز + بناء سحابي لملف APK عبر GitHub Actions. */
@Composable
fun EmulatorTab(vm: RelayViewModel, cfg: GhConfig) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val devices = remember {
        listOf(DeviceProfile("هاتف", 360, 780), DeviceProfile("هاتف صغير", 320, 640), DeviceProfile("لوحي", 800, 1280))
    }
    var device by remember { mutableIntStateOf(0) }
    var landscape by remember { mutableStateOf(false) }
    var src by remember { mutableStateOf<PreviewSrc?>(null) }
    var urlText by remember { mutableStateOf("https://") }
    var run by remember { mutableStateOf<RunInfo?>(null) }
    var cloudMsg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val htmlFiles = remember(vm.projects) {
        vm.projects.filter { it.checkpoint.coding }.sortedByDescending { it.updatedAt }.flatMap { p ->
            vm.codeFiles(p.id).filterKeys { it.endsWith(".html", true) || it.endsWith(".htm", true) }
                .map { PreviewSrc(p.name + " / " + it.key, it.value, null) }
        }
    }

    fun refresh() {
        scope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) { GitHubClient.latestRun(cfg.token.trim(), cfg.owner.trim(), cfg.repo.trim(), cfg.branch.trim()) }
            r.onSuccess { run = it; cloudMsg = if (it == null) tr("لا توجد عمليات بناء بعد.") else "" }
                .onFailure { cloudMsg = "❌ " + (it.message ?: it.toString()) }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(tr("معاينة الجهاز")) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                devices.forEachIndexed { i, d ->
                    FilterChip(selected = device == i, onClick = { device = i }, label = { Text("${tr(d.label)} ${d.w}×${d.h}") })
                }
                FilterChip(selected = landscape, onClick = { landscape = !landscape }, label = { Text(tr("تدوير")) })
            }
            val prof = devices[device]
            val w = if (landscape) prof.h else prof.w
            val h = if (landscape) prof.w else prof.h
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .fillMaxWidth(if (w > 500 || landscape) 1f else 0.7f)
                        .aspectRatio(w.toFloat() / h)
                        .clip(RoundedCornerShape(24.dp))
                        .border(BorderStroke(3.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(24.dp))
                ) {
                    val s = src
                    if (s == null) {
                        Text(tr("اختر ملف HTML من مشروع برمجة أو أدخل رابطاً للمعاينة."),
                            modifier = Modifier.align(Alignment.Center).padding(16.dp),
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { c ->
                                WebView(c).apply {
                                    settings.javaScriptEnabled = true
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    webViewClient = WebViewClient()
                                }
                            },
                            update = { w2 ->
                                if (w2.tag != s.key) {
                                    w2.tag = s.key
                                    if (s.html != null) w2.loadDataWithBaseURL(null, s.html, "text/html", "utf-8", null)
                                    else if (s.url != null) w2.loadUrl(s.url)
                                }
                            }
                        )
                    }
                }
            }
            if (htmlFiles.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    htmlFiles.forEach { f ->
                        FilterChip(selected = src?.key == f.key, onClick = { src = f }, label = { Text(f.key, maxLines = 1) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(urlText, { urlText = it }, label = { Text(tr("رابط")) }, singleLine = true, modifier = Modifier.weight(1f))
                Button(enabled = urlText.startsWith("https://") && urlText.length > 12,
                    onClick = { src = PreviewSrc(urlText.trim(), null, urlText.trim()) }) { Text(tr("تحميل")) }
            }
            Text(tr("هذه معاينة ويب لواجهات HTML. تطبيقات أندرويد الأصلية تُبنى سحابياً أدناه وتُثبَّت كـ APK على جهازك."),
                style = MaterialTheme.typography.bodySmall)
        }

        SectionCard(tr("البناء السحابي (GitHub Actions)")) {
            GhConnectionFields(cfg, withWorkflow = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = cfg.ready && !busy, onClick = {
                    scope.launch {
                        busy = true
                        val r = withContext(Dispatchers.IO) {
                            GitHubClient.dispatch(cfg.token.trim(), cfg.owner.trim(), cfg.repo.trim(), cfg.workflow.trim(), cfg.branch.trim())
                        }
                        r.onSuccess { cloudMsg = tr("✅ بدأ البناء. جارٍ المتابعة…") }
                            .onFailure { cloudMsg = "❌ " + (it.message ?: it.toString()) }
                        busy = false
                        if (r.isSuccess) {
                            repeat(24) {
                                delay(10_000)
                                val st = withContext(Dispatchers.IO) { GitHubClient.latestRun(cfg.token.trim(), cfg.owner.trim(), cfg.repo.trim(), cfg.branch.trim()) }
                                st.onSuccess { run = it }
                                if (st.getOrNull()?.done == true) return@launch
                            }
                        }
                    }
                }) { Text(tr("تشغيل البناء")) }
                OutlinedButton(enabled = cfg.ready && !busy, onClick = { refresh() }) { Text(tr("تحديث الحالة")) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (cloudMsg.isNotEmpty()) Text(cloudMsg, style = MaterialTheme.typography.bodySmall)
            run?.let { r ->
                val state = when {
                    !r.done -> "⏳ " + r.status
                    r.conclusion == "success" -> "✅ " + tr("نجح")
                    else -> "❌ " + r.conclusion
                }
                Text("$state — ${r.title}", style = MaterialTheme.typography.bodyMedium)
                if (r.done && r.conclusion == "success") {
                    Text(tr("نزّل ملف app-debug من قسم Artifacts في صفحة التشغيل ثم ثبّته."), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { openUrl(ctx, r.url) }) { Text(tr("فتح صفحة التشغيل")) }
            }
        }

        SectionCard(tr("مكتبات التشغيل والربط (الإصدارات المثبّتة)")) {
            LIBRARIES.forEach { (name, ver) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    Text(ver, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
