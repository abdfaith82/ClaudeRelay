package com.relay.agents

import android.os.Bundle
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
    val manual = vm.manualRequest
    if (manual != null) {
        ManualScreen(manual) { vm.answerManual(it) }
        return
    }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("الوكلاء") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("التشغيل") })
        }
        if (tab == 0) AgentsTab(vm) else RunTab(vm)
    }
}

@Composable
fun AgentsTab(vm: RelayViewModel) {
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf(Provider.GEMINI) }
    var model by remember { mutableStateOf(Provider.GEMINI.defaultModel) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("أضف عدة وكلاء من مزوّدين مختلفين. يدعم التطبيق OpenAI (نماذج ChatGPT عبر API) وClaude وGemini ومزوّدين آخرين. عند تعطل وكيل ينتقل تلقائياً إلى التالي، ويمكن استئناف المهمة من آخر خطوة محفوظة.",
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Provider.values().forEach { p ->
                    FilterChip(
                        selected = provider == p,
                        onClick = { provider = p; model = p.defaultModel },
                        label = { Text(p.label) }
                    )
                }
            }
            Text(
                if (provider.manual) "وكيل يدوي: عند حاجة الفريق إليه يفتح موقع الدردشة داخل التطبيق، تسجّل دخولك بنفسك، تلصق المهمة وترسلها، ثم تلصق الرد. يُستخدم كاحتياطي أخير."
                else "مفتاح API لـ ${provider.label} من: ${provider.keyUrl}. ملاحظة: اشتراك ChatGPT أو Claude لا يتضمن رصيد API تلقائياً.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(name, { name = it }, label = { Text("اسم الوكيل") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (!provider.manual) {
                OutlinedTextField(key, { key = it }, label = { Text("مفتاح API") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, visualTransformation = PasswordVisualTransformation())
            }
            OutlinedTextField(model, { model = it }, label = { Text(if (provider.manual) "رابط موقع الدردشة" else "النموذج") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = { vm.addAgent(name, key, model, provider); name = ""; key = "" }, modifier = Modifier.fillMaxWidth()) {
                Text("إضافة وكيل")
            }
            Divider()
        }
        items(vm.agents, key = { it.id }) { a ->
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
                    Switch(checked = a.enabled, onCheckedChange = { vm.toggleAgent(a.id) })
                    TextButton(onClick = { vm.removeAgent(a.id) }) { Text("حذف") }
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
                    Text("• ${it.agent.name}: $s", style = MaterialTheme.typography.bodySmall)
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
