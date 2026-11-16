package com.relay.agents

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** الملف الشخصي: الاسم والتفضيلات التي تُطبَّق على كل المحادثات والمهام. */
@Composable
fun ProfileTab(vm: RelayViewModel) {
    var name by remember { mutableStateOf(vm.profileName) }
    var prefs by remember { mutableStateOf(vm.profilePrefs) }
    val dirty = name.trim() != vm.profileName || prefs.trim() != vm.profilePrefs
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr("ماذا نناديك؟"), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(tr("ما التفضيلات الشخصية التي يجب مراعاتها في الردود؟"), style = MaterialTheme.typography.titleMedium)
        Text(tr("تُطبَّق التفضيلات على كل المحادثات والمهام."), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(prefs, { prefs = it }, minLines = 5, modifier = Modifier.fillMaxWidth())
        Button(enabled = dirty, onClick = { vm.saveProfile(name, prefs) }, modifier = Modifier.fillMaxWidth()) {
            Text(tr("حفظ الملف الشخصي"))
        }
        if (!dirty && (vm.profileName.isNotEmpty() || vm.profilePrefs.isNotEmpty())) {
            Text(tr("✅ محفوظ ويُطبَّق الآن."), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CapRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(desc, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked, onChange)
        }
    }
}

/** القدرات: مفاتيح تشغيل لما يفعله التطبيق، على نمط شاشة Capabilities في تطبيق Claude. */
@Composable
fun CapabilitiesTab(vm: RelayViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CapRow(tr("وضع البرمجة افتراضياً"),
            tr("تبدأ المهام الجديدة بتخطيط هندسي وملفات كاملة قابلة للتصدير."),
            vm.defaultCoding) { vm.updateCapability("coding", it) }
        CapRow(tr("التفكير العميق افتراضياً"),
            tr("لكل خطوة مسودة ثم نقد من وكيل آخر ثم تحسين. يضاعف عدد الطلبات."),
            vm.defaultThinking) { vm.updateCapability("thinking", it) }
        CapRow(tr("التبديل بين النماذج عند التوقف"),
            tr("إذا توقف وكيل في المحادثة يكمل وكيل آخر. عند الإيقاف تتوقف المحادثة بدل ذلك. (المهام تبدّل دائماً.)"),
            vm.chatFailover) { vm.updateCapability("failover", it) }
        CapRow(tr("الذاكرة"),
            tr("استخدام ذاكرة المشاريع المحفوظة في المحادثة والمهام."),
            vm.chatMemory) { vm.updateCapability("memory", it) }
        CapRow(tr("الميزات السحابية"),
            tr("السماح بالاتصال بـ GitHub والمعاينة والبناء السحابي. أوقفها لقطع كل اتصال عدا مزوّدي الذكاء الاصطناعي."),
            vm.cloudEnabled) { vm.updateCapability("cloud", it) }

        Text(tr("أسلوب التنسيق الافتراضي في المحادثة"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        val modes = listOf(
            Triple(ChatMode.AUTO, tr("تلقائي"), tr("وكيل واحد يجيب والباقي احتياطي.")),
            Triple(ChatMode.COMPARE, tr("مقارنة"), tr("عدة وكلاء بالتوازي ثم دمج. دقة أعلى، حصة أكبر.")),
            Triple(ChatMode.PIPELINE, tr("مسودة ← نقد ← تحسين"), tr("مراجعة متبادلة بين وكيلين."))
        )
        modes.forEach { (m, title, desc) ->
            Card(Modifier.fillMaxWidth().selectable(selected = vm.chatMode == m, onClick = { vm.selectChatMode(m) })) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = vm.chatMode == m, onClick = { vm.selectChatMode(m) })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(desc, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private val DOCS = listOf(
    "بداية سريعة" to "أضف وكيلاً واحداً على الأقل بمفتاح API رسمي من تبويب الوكلاء، ثم ابدأ من المحادثة أو من تشغيل مهمة. كل شيء يُحفظ على جهازك.",
    "الوكلاء والتبديل التلقائي" to "كل وكيل له مفتاحه ونموذجه. عند حد المعدّل أو عطل يكمل وكيل آخر نفس الخطوة تلقائياً، مع راحة متزايدة للوكيل المتعثر، ويُعطَّل الوكيل ذو المفتاح المرفوض.",
    "المهام ونقاط الاستئناف" to "تُقسَّم المهمة إلى خطوات وتُحفظ نقطة استئناف بعد كل خطوة، فتكمل من حيث توقفت حتى بعد إغلاق التطبيق. أوامر المحادثة: /task ثم الهدف.",
    "أساليب التنسيق في المحادثة" to "تلقائي: وكيل يجيب مع احتياطي. مقارنة: عدة وكلاء بالتوازي ثم دمج. مسودة ← نقد ← تحسين: مراجعة متبادلة. وتستطيع إضافة تعليمات تنسيق بلغتك.",
    "الرفع إلى GitHub" to "اختر ملفات أو مجلدات من الهاتف أو الجلسات (مع المخفية). الاستراتيجية: سحب ومقارنة ← فحص الأسرار ← commit واحد ذري ← تحديث الفرع بدون force ← التحقق من نجاح البناء.",
    "المحاكي والسحابة" to "معاينة واجهات HTML داخل إطار جهاز، وبناء APK سحابياً عبر GitHub Actions ثم تنزيله من صفحة التشغيل. لا يوجد محاكي أندرويد حقيقي داخل الهاتف.",
    "الأمان والخصوصية" to "مفاتيح API ورمز GitHub مشفّرة بمفتاح داخل Android Keystore ولا تُكتب في ملفات المشاريع. النسخ الاحتياطية مشفّرة بكلمة مرور. الاتصال الوحيد هو بمزوّديك وGitHub الذي تضبطه."
)

@Composable
fun DocsTab() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DOCS.forEach { (title, body) ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr(title), style = MaterialTheme.typography.titleSmall)
                    Text(tr(body), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
