package com.relay.agents

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** شاشة المحادثة: دردشة مع فريق النماذج، وتحكم بأسلوب التنسيق بينها بتعليمات حرة. */
@Composable
fun ChatTab(vm: RelayViewModel, onOpenRun: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var panel by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val msgs = vm.chat

    LaunchedEffect(msgs.size, vm.chatBusy) {
        val last = msgs.size + (if (vm.chatBusy) 1 else 0) - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                modeLabel(vm.chatMode) + " • " + (if (vm.chatInclude.isEmpty()) tr("كل الوكلاء") else trf("%s وكيل", vm.chatInclude.size)),
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1
            )
            TextButton(onClick = { panel = !panel }) { Text(if (panel) tr("إخفاء التنسيق") else tr("🎛️ التنسيق")) }
            TextButton(enabled = msgs.isNotEmpty(), onClick = { vm.clearChat() }) { Text(tr("مسح")) }
        }

        if (panel) CoordinationPanel(vm)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (msgs.isEmpty() && !vm.chatBusy) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✳️", style = MaterialTheme.typography.displaySmall)
                    Text(tr("اسأل أو اطلب. يجيبك فريق النماذج ويتبدّل تلقائياً عند توقف أحدها."),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(tr("اكتب /task ثم الهدف لبدء مهمة تنسيق كاملة بنقاط استئناف."),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            LazyColumn(
                state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(msgs.size) { i -> Bubble(msgs[i]) }
                if (vm.chatBusy) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(vm.chatStatus.ifEmpty { tr("⏳ جارٍ التفكير…") }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                input, { input = it },
                placeholder = { Text(tr("اكتب رسالة…")) },
                modifier = Modifier.weight(1f), maxLines = 5
            )
            if (vm.chatBusy) {
                Button(onClick = { vm.stopChat() }) { Text(tr("إيقاف")) }
            } else {
                Button(enabled = input.isNotBlank(), onClick = {
                    val toRun = vm.sendChat(input)
                    input = ""
                    if (toRun) onOpenRun()
                }) { Text(tr("إرسال")) }
            }
        }
    }
}

@Composable
private fun modeLabel(m: ChatMode): String = when (m) {
    ChatMode.AUTO -> tr("تلقائي")
    ChatMode.COMPARE -> tr("مقارنة")
    ChatMode.PIPELINE -> tr("مسودة ← نقد ← تحسين")
}

@Composable
private fun CoordinationPanel(vm: RelayViewModel) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("أسلوب التنسيق بين النماذج"), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChatMode.values().forEach { m ->
                    FilterChip(selected = vm.chatMode == m, onClick = { vm.selectChatMode(m) }, label = { Text(modeLabel(m)) })
                }
            }
            Text(
                when (vm.chatMode) {
                    ChatMode.AUTO -> tr("وكيل واحد يجيب، وعند توقفه يكمل التالي تلقائياً.")
                    ChatMode.COMPARE -> tr("حتى 4 وكلاء يجيبون بالتوازي ثم تُدمج إجاباتهم في رد واحد. يستهلك حصة أكبر.")
                    ChatMode.PIPELINE -> tr("وكيل يكتب مسودة، وآخر ينقدها، ثم يُحسَّن الرد. 3 طلبات لكل رسالة.")
                },
                style = MaterialTheme.typography.bodySmall
            )

            Text(tr("الوكلاء المشاركون (بلا تحديد = الكل)"), style = MaterialTheme.typography.titleSmall)
            val usable = vm.agents.filter { it.enabled && !it.provider.manual }
            if (usable.isEmpty()) {
                Text(tr("لا يوجد وكلاء API مفعّلون. أضفهم من تبويب الوكلاء."), style = MaterialTheme.typography.bodySmall)
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    usable.forEach { a ->
                        FilterChip(selected = a.id in vm.chatInclude, onClick = { vm.toggleChatAgent(a.id) }, label = { Text(a.name, maxLines = 1) })
                    }
                }
            }

            OutlinedTextField(
                vm.chatInstructions, { vm.editChatInstructions(it) },
                label = { Text(tr("تعليماتك للتنسيق (اختياري)")) },
                placeholder = { Text(tr("مثال: اجعل الإجابات مختصرة، وراجع الأرقام قبل الرد")) },
                modifier = Modifier.fillMaxWidth(), maxLines = 4
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(vm.chatMemory, { vm.switchChatMemory(it) })
                Spacer(Modifier.width(8.dp))
                Text(tr("استخدام ذاكرة المشاريع المحفوظة"), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMsg) {
    val mine = m.role == "user"
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Card(
            Modifier.fillMaxWidth(0.92f),
            colors = CardDefaults.cardColors(
                containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!mine && m.by.isNotEmpty()) Text(m.by, style = MaterialTheme.typography.labelSmall)
                SelectionContainer { Text(m.text, style = MaterialTheme.typography.bodyMedium) }
                if (m.extras.isNotEmpty()) {
                    TextButton(onClick = { open = !open }) {
                        Text(if (open) tr("إخفاء الإجابات الفردية") else trf("عرض الإجابات الفردية (%s)", m.extras.size))
                    }
                    if (open) m.extras.forEach { (n, t) ->
                        Text(n, style = MaterialTheme.typography.labelMedium)
                        SelectionContainer { Text(t, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}
