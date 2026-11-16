package com.relay.agents

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.text.font.FontFamily
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

private fun titleFor(dest: Int, current: Project?): String = when (dest) {
    0 -> tr("الوكلاء")
    1 -> current?.name ?: tr("جلسة جديدة")
    2 -> tr("المشاريع")
    3 -> tr("الإعدادات")
    4 -> tr("الكود")
    8 -> tr("المحادثة")
    6 -> tr("الرفع إلى GitHub")
    7 -> tr("المحاكي والسحابة")
    else -> tr("الملفات")
}

/** إجراءات المشروع المشتركة بين شريط الأعلى وقائمة المشاريع: تصدير/مشاركة. */
class ProjectActions(
    private val ctx: Context,
    private val vm: RelayViewModel,
    private val exportMd: (String, String) -> Unit,
    private val exportZip: (Map<String, String>, String) -> Unit
) {
    fun share(p: Project) {
        val text = vm.markdownOf(p.id) ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, p.name)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        ctx.startActivity(Intent.createChooser(send, tr("مشاركة")))
    }

    fun exportMarkdown(p: Project) {
        vm.markdownOf(p.id)?.let { exportMd(it, ProjectExport.safeFileName(p.name, "md")) }
    }

    fun codeCount(p: Project): Int = if (p.checkpoint.coding) vm.codeFiles(p.id).size else 0

    fun exportCode(p: Project) {
        val files = vm.codeFiles(p.id)
        if (files.isNotEmpty()) exportZip(files, ProjectExport.safeFileName(p.name, "zip"))
    }
}

@Composable
fun rememberProjectActions(vm: RelayViewModel): ProjectActions {
    val ctx = LocalContext.current
    var pendingText by remember { mutableStateOf<String?>(null) }
    var pendingZip by remember { mutableStateOf<Map<String, String>?>(null) }
    val md = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        writeToUri(ctx, uri, pendingText); pendingText = null
    }
    val zip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        writeZipToUri(ctx, uri, pendingZip); pendingZip = null
    }
    return remember(vm, ctx) {
        ProjectActions(ctx, vm,
            { text, name -> pendingText = text; md.launch(name) },
            { files, name -> pendingZip = files; zip.launch(name) })
    }
}

/** قائمة خيارات المشروع (تثبيت، مشاركة، إعادة تسمية، تصدير، حذف). */
@Composable
fun ProjectMenu(
    p: Project,
    vm: RelayViewModel,
    actions: ProjectActions,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit
) {
    val codeCount = remember(p.updatedAt, expanded) { if (expanded) actions.codeCount(p) else 0 }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(if (p.pinned) tr("إلغاء التثبيت") else tr("تثبيت")) },
            leadingIcon = { Text("📌") },
            onClick = { vm.togglePin(p.id); onDismiss() }
        )
        DropdownMenuItem(text = { Text(tr("مشاركة")) }, leadingIcon = { Icon(Icons.Default.Share, null) },
            onClick = { actions.share(p); onDismiss() })
        DropdownMenuItem(text = { Text(tr("إعادة تسمية")) }, leadingIcon = { Icon(Icons.Default.Edit, null) },
            onClick = { onRename(); onDismiss() })
        DropdownMenuItem(text = { Text(tr("تصدير .md")) }, leadingIcon = { Text("📄") },
            onClick = { actions.exportMarkdown(p); onDismiss() })
        if (codeCount > 0) {
            DropdownMenuItem(text = { Text(tr("تصدير الكود ZIP")) }, leadingIcon = { Text("🗜️") },
                onClick = { actions.exportCode(p); onDismiss() })
        }
        DropdownMenuItem(
            text = { Text(tr("حذف"), color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            enabled = !(vm.running && p.id == vm.currentProjectId),
            onClick = { vm.deleteProject(p.id); onDismiss() }
        )
    }
}

@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("إعادة تسمية")) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(tr("اسم المشروع")) }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name) }) { Text(tr("حفظ")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("إلغاء")) } }
    )
}

/** القائمة الجانبية: بحث، تنقل، المثبّتة، الأخيرة، وزر جلسة جديدة. */
@Composable
fun DrawerContent(
    vm: RelayViewModel,
    dest: Int,
    query: String,
    onQuery: (String) -> Unit,
    onNavigate: (Int) -> Unit,
    onOpenProject: (String) -> Unit,
    onNewSession: () -> Unit
) {
    val q = query.trim().lowercase()
    val all = vm.projects.sortedWith(compareByDescending<Project> { it.pinned }.thenByDescending { it.updatedAt })
    val filtered = if (q.isEmpty()) all else all.filter { (it.name + " " + it.checkpoint.goal).lowercase().contains(q) }
    val pinned = filtered.filter { it.pinned }
    val recent = filtered.filter { !it.pinned }.take(15)
    val nav = listOf(
        Triple(8, "المحادثة", "💬"), Triple(1, "التشغيل", "▶️"), Triple(0, "الوكلاء", "🤖"), Triple(2, "المشاريع", "🗂️"),
        Triple(4, "الكود", "💻"), Triple(5, "الملفات", "📄")
    )
    val cloudNav = listOf(Triple(6, "الرفع إلى GitHub", "☁️"), Triple(7, "المحاكي والسحابة", "📱"), Triple(3, "الإعدادات", "⚙️"))
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Claude Relay", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(8.dp))
        OutlinedTextField(
            query, onQuery,
            placeholder = { Text(tr("بحث")) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
            items(nav, key = { it.first }) { (id, label, icon) ->
                NavigationDrawerItem(
                    label = { Text(tr(label)) },
                    icon = { Text(icon) },
                    selected = dest == id,
                    onClick = { onNavigate(id) }
                )
            }
            item {
                Divider(Modifier.padding(vertical = 8.dp))
                Text(tr("الموصلات والسحابة"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
            }
            items(cloudNav, key = { it.first }) { (id, label, icon) ->
                NavigationDrawerItem(
                    label = { Text(tr(label)) },
                    icon = { Text(icon) },
                    selected = dest == id,
                    onClick = { onNavigate(id) }
                )
            }
            if (pinned.isNotEmpty()) {
                item { Text(tr("المثبّتة"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) }
                items(pinned, key = { "p" + it.id }) { p ->
                    NavigationDrawerItem(
                        label = { Text(p.name, maxLines = 1) },
                        icon = { Text("📌") },
                        selected = p.id == vm.currentProjectId && dest == 1,
                        onClick = { onOpenProject(p.id) }
                    )
                }
            }
            if (recent.isNotEmpty()) {
                item { Text(tr("الأخيرة"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) }
                items(recent, key = { "r" + it.id }) { p ->
                    NavigationDrawerItem(
                        label = { Text(p.name, maxLines = 1) },
                        icon = { Text(if (p.checkpoint.coding) "💻" else "💬") },
                        selected = p.id == vm.currentProjectId && dest == 1,
                        onClick = { onOpenProject(p.id) }
                    )
                }
            }
        }
        ExtendedFloatingActionButton(onClick = onNewSession, modifier = Modifier.padding(top = 8.dp)) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text(tr("جلسة جديدة"))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayScreen(vm: RelayViewModel) {
    var dest by remember { mutableIntStateOf(1) } // 0 وكلاء، 1 تشغيل، 2 مشاريع، 3 إعدادات، 4 كود، 5 ملفات
    var loginUrl by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<Project?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val actions = rememberProjectActions(vm)
    val appCtx = LocalContext.current
    val gh = remember { GhConfig(Storage(appCtx)) }

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

    val current = vm.projects.firstOrNull { it.id == vm.currentProjectId }
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(
                    vm, dest, query, { query = it },
                    onNavigate = { dest = it; scope.launch { drawer.close() } },
                    onOpenProject = { id -> if (vm.openProject(id)) dest = 1; scope.launch { drawer.close() } },
                    onNewSession = { if (!vm.running) vm.reset(); dest = 1; scope.launch { drawer.close() } }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(titleFor(dest, current), maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = tr("القائمة"))
                        }
                    },
                    actions = {
                        if (dest == 1 && current != null) {
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = tr("خيارات المشروع"))
                                }
                                ProjectMenu(current, vm, actions, menuOpen, { menuOpen = false }) { renameTarget = current }
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (dest) {
                    0 -> AgentsTab(vm) { loginUrl = it }
                    1 -> RunTab(vm)
                    2 -> ProjectsTab(vm, actions, query, false, { dest = 1 }) { renameTarget = it }
                    4 -> ProjectsTab(vm, actions, query, true, { dest = 1 }) { renameTarget = it }
                    5 -> FilesTab(vm, query)
                    8 -> ChatTab(vm) { dest = 1 }
                    6 -> GitHubTab(vm, gh)
                    7 -> EmulatorTab(vm, gh)
                    else -> SettingsTab()
                }
            }
        }
    }
    renameTarget?.let { p ->
        RenameDialog(p.name, onDismiss = { renameTarget = null }) { vm.renameProject(p.id, it); renameTarget = null }
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
fun ProjectsTab(
    vm: RelayViewModel,
    actions: ProjectActions,
    query: String,
    codingOnly: Boolean,
    onOpen: () -> Unit,
    onRename: (Project) -> Unit
) {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<String?>(null) }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        writeToUri(ctx, uri, pending); pending = null
    }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }
    val q = query.trim().lowercase()
    val list = vm.projects
        .filter { (!codingOnly || it.checkpoint.coding) && (q.isEmpty() || (it.name + " " + it.checkpoint.goal).lowercase().contains(q)) }
        .sortedWith(compareByDescending<Project> { it.pinned }.thenByDescending { it.updatedAt })

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
        if (list.isEmpty()) {
            item { Text(if (q.isEmpty()) tr("لا توجد مشاريع بعد. ابدأ مهمة من تبويب التشغيل.") else tr("لا توجد نتائج")) }
        }
        items(list, key = { it.id }) { p ->
            var menu by remember { mutableStateOf(false) }
            val steps = p.checkpoint.steps
            val done = steps.count { it.status == StepStatus.DONE }
            Card(Modifier.fillMaxWidth().clickable { if (vm.openProject(p.id)) onOpen() }) {
                Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text((if (p.pinned) "📌 " else "") + (if (p.checkpoint.coding) "💻 " else "") + p.name,
                            style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${if (p.completed) tr("✅ مكتمل") else tr("⏸ غير مكتمل")} • $done/${steps.size} • ${fmt.format(Date(p.updatedAt))}" +
                                if (p.id == vm.currentProjectId) tr(" • الحالي") else "",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = tr("خيارات المشروع")) }
                        ProjectMenu(p, vm, actions, menu, { menu = false }) { onRename(p) }
                    }
                }
            }
        }
    }
}

/** ملفات الكود المولَّدة في مشاريع وضع البرمجة، مع عرض ونسخ. */
@Composable
fun FilesTab(vm: RelayViewModel, query: String) {
    val clipboard = LocalClipboardManager.current
    val q = query.trim().lowercase()
    val data = remember(vm.projects) {
        vm.projects.filter { it.checkpoint.coding }.sortedByDescending { it.updatedAt }
            .map { it to vm.codeFiles(it.id) }.filter { it.second.isNotEmpty() }
    }
    var viewing by remember { mutableStateOf<Pair<String, String>?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (data.isEmpty()) {
            item { Text(tr("لا توجد ملفات كود بعد. فعّل وضع البرمجة وابدأ مشروعاً.")) }
        }
        data.forEach { (p, files) ->
            val shown = files.entries.filter { q.isEmpty() || it.key.lowercase().contains(q) }
            if (shown.isNotEmpty()) {
                item(key = "h" + p.id) { Text(p.name, style = MaterialTheme.typography.titleSmall) }
                items(shown, key = { p.id + "/" + it.key }) { e ->
                    Card(Modifier.fillMaxWidth().clickable { viewing = e.key to e.value }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(e.key, style = MaterialTheme.typography.bodyMedium)
                            Text("${e.value.length} ${tr("حرفاً")}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    viewing?.let { (path, content) ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(path, style = MaterialTheme.typography.titleSmall) },
            text = {
                SelectionContainer(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(content.take(20_000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(content)) }) { Text(tr("نسخ")) } },
            dismissButton = { TextButton(onClick = { viewing = null }) { Text(tr("إغلاق")) } }
        )
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
