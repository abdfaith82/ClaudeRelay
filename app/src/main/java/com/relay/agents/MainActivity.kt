package com.relay.agents

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
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

@Composable
fun RelayScreen(vm: RelayViewModel) {
    var tab by remember { mutableIntStateOf(0) }
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
    var model by remember { mutableStateOf("claude-sonnet-4-5") }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("أضف وكيلاً بمفتاح Claude API (من console.anthropic.com). كل وكيل بمفتاح مستقل، وإذا توقف يكمل التالي.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it }, label = { Text("اسم الوكيل") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(key, { key = it }, label = { Text("مفتاح API") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(model, { model = it }, label = { Text("النموذج") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = { vm.addAgent(name, key, model); name = ""; key = "" }, modifier = Modifier.fillMaxWidth()) {
                Text("إضافة وكيل")
            }
            Divider()
        }
        items(vm.agents, key = { it.id }) { a ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.titleSmall)
                        Text("${a.model} • ••••${a.apiKey.takeLast(4)}", style = MaterialTheme.typography.bodySmall)
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
