package com.relay.agents

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.unit.dp
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: RelayViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crashFile = File(filesDir, "crash.txt")
        val crash = if (crashFile.exists()) runCatching { crashFile.readText() }.getOrNull() else null
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var crashText by remember { mutableStateOf(crash) }
                    if (crashText != null) {
                        CrashScreen(crashText!!) {
                            crashFile.delete(); crashText = null
                        }
                    } else {
                        RelayScreen(vm)
                    }
                }
            }
        }
    }
}

@Composable
fun CrashScreen(text: String, onContinue: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("حدث انهيار في التشغيل السابق", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("متابعة") }
    }
}

/** تسجيل الدخول مرة واحدة: تدخل بنفسك والجلسة (الكوكيز) تبقى محفوظة في التطبيق لاستخدامها في وكيل الويب. */
@Composable
fun LoginScreen(url: String, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("سجّل دخولك في الموقع ثم اضغط «تم». الجلسة تبقى محفوظة في التطبيق. إن رفض الموقع الدخول بحساب Google داخل التطبيق فاستخدم البريد وكلمة المرور أو الرابط السحري.",
            style = MaterialTheme.typography.bodySmall)
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    loadUrl(url)
                }
            }
        )
        Button(onClick = {
            android.webkit.CookieManager.getInstance().flush()
            onDone()
        }, modifier = Modifier.fillMaxWidth()) { Text("تم") }
    }
}

/** شاشة الوكيل اليدوي: تسجّل الدخول وترسل وتنسخ بنفسك؛ التطبيق لا يحقن نصاً ولا يقرأ الصفحة. */
@Composable
fun ManualScreen(req: RelayViewModel.ManualRequest, onResult: (String?) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var reply by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("المطلوب منك: ${req.agent.name}", style = MaterialTheme.typography.titleSmall)
        Text("١) انسخ المهمة  ٢) الصقها وأرسلها في الموقع أدناه  ٣) انسخ الرد والصقه هنا", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { clipboard.setText(AnnotatedString(req.prompt)) }) { Text("نسخ المهمة") }
            OutlinedButton(onClick = { onResult(null) }) { Text("تخطي") }
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    loadUrl(req.agent.model)
                }
            }
        )
        OutlinedTextField(reply, { reply = it }, label = { Text("الصق رد الموقع هنا") },
            modifier = Modifier.fillMaxWidth(), maxLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { reply = clipboard.getText()?.text ?: "" }) { Text("لصق من الحافظة") }
            Button(onClick = { onResult(reply) }, enabled = reply.isNotBlank()) { Text("إرسال الرد") }
        }
    }
}

@Composable
fun RelayScreen(vm: RelayViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    var loginUrl by remember { mutableStateOf<String?>(null) }
    val manual = vm.manualRequest
    if (manual != null) {
        ManualScreen(manual) { vm.answerManual(it) }
        return
    }
    val lu = loginUrl
    if (lu != null) {
        LoginScreen(lu) { loginUrl = null }
        return
    }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("الوكلاء") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("التشغيل") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("المشاريع (${vm.projects.size})") })
        }
        when (tab) {
            0 -> AgentsTab(vm) { loginUrl = it }
            1 -> RunTab(vm)
            else -> ProjectsTab(vm) { tab = 1 }
        }
    }
}

@Composable
fun AgentsTab(vm: RelayViewModel, onLogin: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf(Provider.GEMINI) }
    var model by remember { mutableStateOf(Provider.GEMINI.defaultModel) }
    var found by remember { mutableStateOf<List<String>>(emptyList()) }
    var msg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("اختر المزوّد وأضف مفتاحه. المزوّدون المجانيون يعطونك مفتاحاً مجانياً من موقعهم. كل وكيل بمفتاح مستقل، وإذا توقف يكمل التالي.",
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Provider.values().forEach { p ->
                    FilterChip(
                        selected = provider == p,
                        onClick = { provider = p; model = p.defaultModel; found = emptyList(); msg = "" },
                        label = { Text(p.label) }
                    )
                }
            }
            Text(
                if (provider.manual) "وكيل يدوي: عند حاجة الفريق إليه يفتح موقع الدردشة داخل التطبيق، تسجّل دخولك بنفسك، تلصق المهمة وترسلها، ثم تلصق الرد. يُستخدم كاحتياطي أخير."
                else "مفتاح ${provider.label} من: ${provider.keyUrl}",
                style = MaterialTheme.typography.bodySmall
            )
            if (provider.manual) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "Claude" to "https://claude.ai",
                        "ChatGPT" to "https://chatgpt.com",
                        "Gemini" to "https://gemini.google.com"
                    ).forEach { (n, u) ->
                        AssistChip(onClick = { model = u; if (name.isBlank()) name = "$n (ويب)" }, label = { Text(n) })
                    }
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text("اسم الوكيل") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (!provider.manual) {
                OutlinedTextField(key, { key = it }, label = { Text("مفتاح API") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, visualTransformation = PasswordVisualTransformation())
            }
            OutlinedTextField(model, { model = it }, label = { Text(if (provider.manual) "رابط موقع الدردشة" else "النموذج") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (!provider.manual) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            msg = "جارٍ الجلب…"
                            ClaudeClient.listModels(provider, key.trim())
                                .onSuccess { found = it; msg = "${it.size} نموذجاً متاحاً لمفتاحك، اضغط لاختيار واحد" }
                                .onFailure { found = emptyList(); msg = "تعذّر الجلب: ${it.message?.take(140)}" }
                        }
                    },
                    enabled = key.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("جلب النماذج المتاحة لمفتاحي") }
                if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall)
                if (found.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        found.take(60).forEach { m ->
                            FilterChip(selected = model == m, onClick = { model = m }, label = { Text(m) })
                        }
                    }
                }
            }
            Button(onClick = { vm.addAgent(name, key, model, provider); name = ""; key = "" }, modifier = Modifier.fillMaxWidth()) {
                Text("إضافة وكيل")
            }
            if (provider.manual) {
                OutlinedButton(onClick = { onLogin(model) }, modifier = Modifier.fillMaxWidth(), enabled = model.startsWith("http")) {
                    Text("تسجيل الدخول الآن")
                }
            }
            Divider()
        }
        items(vm.agents, key = { it.id }) { a ->
            var editing by remember { mutableStateOf(false) }
            var editModel by remember(a.model) { mutableStateOf(a.model) }
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (a.provider.manual) "${a.provider.label} • ${a.model}"
                            else "${a.provider.label} • ${a.model} • ••••${a.apiKey.takeLast(4)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (a.provider.manual) TextButton(onClick = { onLogin(a.model) }) { Text("دخول") }
                    else TextButton(onClick = { editing = !editing }) { Text("نموذج") }
                    Switch(checked = a.enabled, onCheckedChange = { vm.toggleAgent(a.id) })
                    TextButton(onClick = { vm.removeAgent(a.id) }) { Text("حذف") }
                }
                if (editing) {
                    Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(editModel, { editModel = it }, label = { Text("النموذج") },
                            modifier = Modifier.weight(1f), singleLine = true)
                        TextButton(onClick = { vm.updateModel(a.id, editModel); editing = false }) { Text("حفظ") }
                    }
                }
            }
        }
    }
}

private fun writeToUri(ctx: Context, uri: Uri?, text: String?) {
    if (uri == null || text == null) return
    val ok = runCatching {
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null
    }.getOrDefault(false)
    Toast.makeText(ctx, if (ok) "تم التصدير" else "تعذّر التصدير", Toast.LENGTH_SHORT).show()
}

@Composable
fun ProjectsTab(vm: RelayViewModel, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<String?>(null) }
    val mdLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        writeToUri(ctx, uri, pending); pending = null
    }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        writeToUri(ctx, uri, pending); pending = null
    }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("كل المشاريع محفوظة تلقائياً، ويتعرف عليها الوكلاء كذاكرة ويبنون عليها في المهام الجديدة.",
                style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { pending = vm.allProjectsJson(); jsonLauncher.launch("claude-relay-projects.json") },
                enabled = vm.projects.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("تصدير كل المشاريع (JSON)") }
        }
        if (vm.projects.isEmpty()) {
            item { Text("لا توجد مشاريع بعد. ابدأ مهمة من تبويب التشغيل.") }
        }
        items(vm.projects, key = { it.id }) { p ->
            val steps = p.checkpoint.steps
            val done = steps.count { it.status == StepStatus.DONE }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${if (p.completed) "✅ مكتمل" else "⏸ غير مكتمل"} • $done/${steps.size} • ${fmt.format(Date(p.updatedAt))}" +
                            if (p.id == vm.currentProjectId) " • الحالي" else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { if (vm.openProject(p.id)) onOpen() }, enabled = !vm.running) { Text("فتح") }
                        TextButton(onClick = {
                            pending = vm.markdownOf(p.id)
                            mdLauncher.launch(ProjectExport.safeFileName(p.name, "md"))
                        }) { Text("تصدير .md") }
                        TextButton(onClick = {
                            val text = vm.markdownOf(p.id) ?: return@TextButton
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, p.name)
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            ctx.startActivity(Intent.createChooser(send, "مشاركة المشروع"))
                        }) { Text("مشاركة") }
                        TextButton(onClick = { vm.deleteProject(p.id) }, enabled = !(vm.running && p.id == vm.currentProjectId)) { Text("حذف") }
                    }
                }
            }
        }
    }
}

@Composable
fun RunTab(vm: RelayViewModel) {
    var goal by remember { mutableStateOf("") }
    val cp = vm.checkpoint
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(goal, { goal = it }, label = { Text("الهدف / المهمة") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.start(goal) }, enabled = !vm.running && vm.agents.any { it.enabled }) { Text("ابدأ") }
                if (cp != null && cp.finalAnswer.isEmpty()) {
                    OutlinedButton(onClick = { vm.resume() }, enabled = !vm.running) { Text("استئناف") }
                }
                if (vm.running) OutlinedButton(onClick = { vm.stop() }) { Text("إيقاف") }
                if (cp != null && !vm.running) TextButton(onClick = { vm.reset() }) { Text("مسح") }
            }
            if (vm.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (vm.states.isNotEmpty()) {
            item {
                Text("حالة الوكلاء", style = MaterialTheme.typography.titleSmall)
                vm.states.forEach {
                    val s = when {
                        it.disabled -> "معطّل"
                        it.cooldownUntil > System.currentTimeMillis() -> "في راحة"
                        else -> "جاهز"
                    }
                    Text("• ${it.agent.name}: $s ${it.note}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (cp != null) {
            item { Text("الخطوات: ${cp.goal}", style = MaterialTheme.typography.titleSmall) }
            items(cp.steps) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${if (s.status == StepStatus.DONE) "✅" else "⬜"} ${s.title}")
                        if (s.doneBy.isNotEmpty()) Text("بواسطة: ${s.doneBy}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (cp.finalAnswer.isNotEmpty()) {
                item {
                    Text("النتيجة النهائية", style = MaterialTheme.typography.titleSmall)
                    SelectionContainer { Text(cp.finalAnswer) }
                }
            }
            item { Text("السجل", style = MaterialTheme.typography.titleSmall) }
            items(cp.log.takeLast(40).reversed()) { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
