package com.relay.agents

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
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
        val settings = Storage(this)
        I18n.lang = I18n.resolve(settings.setting("lang", "system"))
        AppPrefs.theme = settings.setting("theme", "system")
        setContent {
            val dark = when (AppPrefs.theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            val ctx = LocalContext.current
            val scheme = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            CompositionLocalProvider(
                LocalLayoutDirection provides if (I18n.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                MaterialTheme(colorScheme = scheme) {
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
}

@Composable
fun CrashScreen(text: String, onContinue: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(tr("حدث انهيار في التشغيل السابق"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text(tr("متابعة")) }
    }
}

/** تسجيل الدخول مرة واحدة: تدخل بنفسك والجلسة (الكوكيز) تبقى محفوظة في التطبيق لاستخدامها في وكيل الويب. */
@Composable
fun LoginScreen(url: String, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("سجّل دخولك في الموقع ثم اضغط «تم». الجلسة تبقى محفوظة في التطبيق. إن رفض الموقع الدخول بحساب Google داخل التطبيق فاستخدم البريد وكلمة المرور أو الرابط السحري."),
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
        }, modifier = Modifier.fillMaxWidth()) { Text(tr("تم")) }
    }
}

/** شاشة الوكيل اليدوي: تسجّل الدخول وترسل وتنسخ بنفسك؛ التطبيق لا يحقن نصاً ولا يقرأ الصفحة. */
@Composable
fun ManualScreen(req: RelayViewModel.ManualRequest, onResult: (String?) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var reply by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(trf("المطلوب منك: %s", req.agent.name), style = MaterialTheme.typography.titleSmall)
        Text(tr("١) انسخ المهمة  ٢) الصقها وأرسلها في الموقع أدناه  ٣) انسخ الرد والصقه هنا"), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { clipboard.setText(AnnotatedString(req.prompt)) }) { Text(tr("نسخ المهمة")) }
            OutlinedButton(onClick = { onResult(null) }) { Text(tr("تخطي")) }
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
        OutlinedTextField(reply, { reply = it }, label = { Text(tr("الصق رد الموقع هنا")) },
            modifier = Modifier.fillMaxWidth(), maxLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { reply = clipboard.getText()?.text ?: "" }) { Text(tr("لصق من الحافظة")) }
            Button(onClick = { onResult(reply) }, enabled = reply.isNotBlank()) { Text(tr("إرسال الرد")) }
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
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(tr("الوكلاء")) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(tr("التشغيل")) })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(trf("المشاريع (%s)", vm.projects.size)) })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text(tr("الإعدادات")) })
        }
        when (tab) {
            0 -> AgentsTab(vm) { loginUrl = it }
            1 -> RunTab(vm)
            2 -> ProjectsTab(vm) { tab = 1 }
            else -> SettingsTab()
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
    val ctx = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }
    var pass by remember { mutableStateOf("") }
    var restoreText by remember { mutableStateOf<String?>(null) }
    var backupText by remember { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        writeToUri(ctx, uri, backupText); backupText = null
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            restoreText = runCatching {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (restoreText != null) { pass = ""; dialog = "restore" }
            else Toast.makeText(ctx, tr("تعذّر قراءة الملف"), Toast.LENGTH_SHORT).show()
        }
    }
    if (dialog != null) {
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (dialog == "backup") tr("نسخة احتياطية مشفّرة") else tr("استعادة الوكلاء")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (dialog == "backup") tr("المفاتيح تُشفَّر بكلمة مرور تختارها (4 أحرف فأكثر). لا يمكن استرجاعها إن نسيتها.")
                        else tr("أدخل كلمة مرور النسخة الاحتياطية."),
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(pass, { pass = it }, label = { Text(tr("كلمة المرور")) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton(enabled = pass.length >= 4, onClick = {
                    if (dialog == "backup") {
                        backupText = vm.exportAgentsBackup(pass)
                        saveLauncher.launch("claude-relay-agents.relay")
                    } else {
                        vm.importAgentsBackup(restoreText ?: "", pass)
                            .onSuccess { Toast.makeText(ctx, trf("تمت الاستعادة: %s وكيل جديد", it), Toast.LENGTH_LONG).show() }
                            .onFailure { Toast.makeText(ctx, tr("كلمة المرور خاطئة أو الملف تالف"), Toast.LENGTH_LONG).show() }
                    }
                    dialog = null; pass = ""
                }) { Text(tr("تأكيد")) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(tr("إلغاء")) } }
        )
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(tr("اختر المزوّد وأضف مفتاحه. المزوّدون المجانيون يعطونك مفتاحاً مجانياً من موقعهم. كل وكيل بمفتاح مستقل، وإذا توقف يكمل التالي."),
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
                if (provider.manual) tr("وكيل يدوي: عند حاجة الفريق إليه يفتح موقع الدردشة داخل التطبيق، تسجّل دخولك بنفسك، تلصق المهمة وترسلها، ثم تلصق الرد. يُستخدم كاحتياطي أخير.")
                else trf("مفتاح %s من: %s", provider.label, provider.keyUrl),
                style = MaterialTheme.typography.bodySmall
            )
            if (provider.manual) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "Claude" to "https://claude.ai",
                        "ChatGPT" to "https://chatgpt.com",
                        "Gemini" to "https://gemini.google.com"
                    ).forEach { (n, u) ->
                        AssistChip(onClick = { model = u; if (name.isBlank()) name = trf("%s (ويب)", n) }, label = { Text(n) })
                    }
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text(tr("اسم الوكيل")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (!provider.manual) {
                OutlinedTextField(key, { key = it }, label = { Text(tr("مفتاح API")) }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, visualTransformation = PasswordVisualTransformation())
            }
            OutlinedTextField(model, { model = it }, label = { Text(if (provider.manual) tr("رابط موقع الدردشة") else tr("النموذج")) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (!provider.manual) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            msg = tr("جارٍ الجلب…")
                            ClaudeClient.listModels(provider, key.trim())
                                .onSuccess { found = it; msg = trf("%s نموذجاً متاحاً لمفتاحك، اضغط لاختيار واحد", it.size) }
                                .onFailure { found = emptyList(); msg = trf("تعذّر الجلب: %s", it.message?.take(140)) }
                        }
                    },
                    enabled = key.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(tr("جلب النماذج المتاحة لمفتاحي")) }
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
                Text(tr("إضافة وكيل"))
            }
            if (provider.manual) {
                OutlinedButton(onClick = { onLogin(model) }, modifier = Modifier.fillMaxWidth(), enabled = model.startsWith("http")) {
                    Text(tr("تسجيل الدخول الآن"))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pass = ""; dialog = "backup" }, enabled = vm.agents.isNotEmpty()) {
                    Text(tr("💾 حفظ الوكلاء"))
                }
                OutlinedButton(onClick = { openLauncher.launch(arrayOf("*/*")) }) { Text(tr("♻️ استعادة")) }
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
                    if (a.provider.manual) TextButton(onClick = { onLogin(a.model) }) { Text(tr("دخول")) }
                    else TextButton(onClick = { editing = !editing }) { Text(tr("نموذج")) }
                    Switch(checked = a.enabled, onCheckedChange = { vm.toggleAgent(a.id) })
                    TextButton(onClick = { vm.removeAgent(a.id) }) { Text(tr("حذف")) }
                }
                if (editing) {
                    Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(editModel, { editModel = it }, label = { Text(tr("النموذج")) },
                            modifier = Modifier.weight(1f), singleLine = true)
                        TextButton(onClick = { vm.updateModel(a.id, editModel); editing = false }) { Text(tr("حفظ")) }
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
    Toast.makeText(ctx, if (ok) tr("تم التصدير") else tr("تعذّر التصدير"), Toast.LENGTH_SHORT).show()
}

@Composable
fun SettingsTab() {
    val ctx = LocalContext.current
    val storage = remember { Storage(ctx) }
    var langChoice by remember { mutableStateOf(storage.setting("lang", "system")) }
    var themeChoice by remember { mutableStateOf(storage.setting("theme", "system")) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(tr("اللغة"), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("system" to tr("النظام"), "ar" to "العربية", "en" to "English").forEach { (code, label) ->
                FilterChip(
                    selected = langChoice == code,
                    onClick = {
                        langChoice = code
                        storage.saveSetting("lang", code)
                        I18n.lang = I18n.resolve(code)
                    },
                    label = { Text(label) }
                )
            }
        }
        Text(tr("المظهر"), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("system" to tr("النظام"), "light" to tr("فاتح"), "dark" to tr("داكن")).forEach { (code, label) ->
                FilterChip(
                    selected = themeChoice == code,
                    onClick = {
                        themeChoice = code
                        storage.saveSetting("theme", code)
                        AppPrefs.theme = code
                    },
                    label = { Text(label) }
                )
            }
        }
        Divider()
        Text(tr("الأمان والخصوصية"), style = MaterialTheme.typography.titleSmall)
        Text(
            tr("مفاتيح API مشفّرة داخل الجهاز (Android Keystore) ولا تُرسل إلا إلى مزوّد الذكاء الاصطناعي الذي أضفته. لا توجد خوادم وسيطة ولا تتبّع."),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            tr("الوكلاء والمشاريع والمرفقات تُخزَّن محلياً فقط على هذا الجهاز."),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun writeZipToUri(ctx: Context, uri: Uri?, files: Map<String, String>?) {
    if (uri == null || files == null) return
    val ok = runCatching {
        ctx.contentResolver.openOutputStream(uri)?.use { CodeExtractor.writeZip(files, it) } != null
    }.getOrDefault(false)
    Toast.makeText(ctx, if (ok) tr("تم تصدير المشروع") else tr("تعذّر التصدير"), Toast.LENGTH_SHORT).show()
}

@Composable
fun ProjectsTab(vm: RelayViewModel, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    var pendingZip by remember { mutableStateOf<Map<String, String>?>(null) }
    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        writeZipToUri(ctx, uri, pendingZip); pendingZip = null
    }
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
            Text(tr("كل المشاريع محفوظة تلقائياً، ويتعرف عليها الوكلاء كذاكرة ويبنون عليها في المهام الجديدة."),
                style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { pending = vm.allProjectsJson(); jsonLauncher.launch("claude-relay-projects.json") },
                enabled = vm.projects.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(tr("تصدير كل المشاريع (JSON)")) }
        }
        if (vm.projects.isEmpty()) {
            item { Text(tr("لا توجد مشاريع بعد. ابدأ مهمة من تبويب التشغيل.")) }
        }
        items(vm.projects, key = { it.id }) { p ->
            val steps = p.checkpoint.steps
            val done = steps.count { it.status == StepStatus.DONE }
            val codeCount = remember(p.updatedAt) { if (p.checkpoint.coding) vm.codeFiles(p.id).size else 0 }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${if (p.completed) tr("✅ مكتمل") else tr("⏸ غير مكتمل")} • $done/${steps.size} • ${fmt.format(Date(p.updatedAt))}" +
                            if (p.id == vm.currentProjectId) tr(" • الحالي") else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { if (vm.openProject(p.id)) onOpen() }, enabled = !vm.running) { Text(tr("فتح")) }
                        TextButton(onClick = {
                            pending = vm.markdownOf(p.id)
                            mdLauncher.launch(ProjectExport.safeFileName(p.name, "md"))
                        }) { Text(tr("تصدير .md")) }
                        TextButton(onClick = {
                            val text = vm.markdownOf(p.id) ?: return@TextButton
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, p.name)
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            ctx.startActivity(Intent.createChooser(send, tr("مشاركة المشروع")))
                        }) { Text(tr("مشاركة")) }
                        if (codeCount > 0) {
                            TextButton(onClick = {
                                pendingZip = vm.codeFiles(p.id)
                                zipLauncher.launch(ProjectExport.safeFileName(p.name, "zip"))
                            }) { Text(trf("كود ZIP (%s)", codeCount)) }
                        }
                        TextButton(onClick = { vm.deleteProject(p.id) }, enabled = !(vm.running && p.id == vm.currentProjectId)) { Text(tr("حذف")) }
                    }
                }
            }
        }
    }
}

@Composable
fun RunTab(vm: RelayViewModel) {
    var goal by remember { mutableStateOf("") }
    var coding by remember { mutableStateOf(false) }
    var thinking by remember { mutableStateOf(false) }
    val cp = vm.checkpoint
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.addFiles(uris)
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(goal, { goal = it }, label = { Text(tr("الهدف / المهمة")) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("💻 وضع البرمجة"), style = MaterialTheme.typography.titleSmall)
                    Text(
                        tr("تخطيط هندسي، ملفات كاملة بمسارات، مراجعة وإصلاح، ثم تصدير المشروع ZIP"),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = coding, onCheckedChange = { coding = it }, enabled = !vm.running)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("🧠 التفكير العميق"), style = MaterialTheme.typography.titleSmall)
                    Text(
                        tr("كل خطوة: مسودة ثم نقد من وكيل آخر ثم تحسين (جودة أعلى، واستهلاك أكبر للحصص)"),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = thinking, onCheckedChange = { thinking = it }, enabled = !vm.running)
            }
            OutlinedButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                enabled = !vm.running && !vm.processing,
                modifier = Modifier.fillMaxWidth()
            ) { Text(tr("📎 إرفاق ملفات (نص، صور، ZIP، PDF، فيديو)")) }
            if (vm.processing) {
                Text(tr("جارٍ تجهيز الملفات…"), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            vm.pending.forEachIndexed { i, a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "• ${a.name} — ${a.note.ifEmpty { "${a.text.length} ${tr("حرفاً")}" }}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.removePending(i) }, enabled = !vm.running) { Text(tr("إزالة")) }
                }
            }
            if (vm.pending.isEmpty() && !vm.projectAttachments.isEmpty) {
                Text(
                    trf("مرفقات المشروع الحالي: %s", vm.projectAttachments.items.joinToString { it.name }),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.start(goal, coding, thinking) }, enabled = !vm.running && vm.agents.any { it.enabled }) { Text(tr("ابدأ")) }
                if (cp != null && cp.finalAnswer.isEmpty()) {
                    OutlinedButton(onClick = { vm.resume() }, enabled = !vm.running) { Text(tr("استئناف")) }
                }
                if (vm.running) OutlinedButton(onClick = { vm.stop() }) { Text(tr("إيقاف")) }
                if (cp != null && !vm.running) TextButton(onClick = { vm.reset() }) { Text(tr("مسح")) }
            }
            if (vm.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (vm.states.isNotEmpty()) {
            item {
                Text(tr("حالة الوكلاء"), style = MaterialTheme.typography.titleSmall)
                vm.states.forEach {
                    val s = when {
                        it.disabled -> tr("معطّل")
                        it.cooldownUntil > System.currentTimeMillis() -> tr("في راحة")
                        else -> tr("جاهز")
                    }
                    Text("• ${it.agent.name}: ${tr(s)} ${it.note}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (cp != null) {
            item { Text(trf("الخطوات: %s", cp.goal), style = MaterialTheme.typography.titleSmall) }
            items(cp.steps) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${if (s.status == StepStatus.DONE) "✅" else "⬜"} ${s.title}")
                        if (s.doneBy.isNotEmpty()) Text(trf("بواسطة: %s", s.doneBy), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (cp.finalAnswer.isNotEmpty()) {
                item {
                    Text(tr("النتيجة النهائية"), style = MaterialTheme.typography.titleSmall)
                    SelectionContainer { Text(cp.finalAnswer) }
                }
            }
            item { Text(tr("السجل"), style = MaterialTheme.typography.titleSmall) }
            items(cp.log.takeLast(40).reversed()) { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
