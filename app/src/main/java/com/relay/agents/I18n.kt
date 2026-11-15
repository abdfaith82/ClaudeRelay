package com.relay.agents

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * تدويل خفيف: النص الأصلي بالعربية هو المفتاح، والإنجليزية من القاموس.
 * - tr("نص")            نص ثابت.
 * - trf("نص %s", a)     نص بمعاملات (قالب يُترجم ثم تُملأ المعاملات).
 * اللغة حالة Compose، فتتحدث الواجهة فور تغييرها. اللغات الإضافية تُضاف بقاموس جديد بنفس المفاتيح.
 */
object I18n {
    /** اللغة الفعلية الحالية: "ar" أو "en". */
    var lang by mutableStateOf("ar")

    val isRtl: Boolean get() = lang == "ar"

    /** يحوّل اختيار المستخدم (system/ar/en) إلى لغة فعلية. */
    fun resolve(choice: String): String = when (choice) {
        "ar", "en" -> choice
        else -> if (Locale.getDefault().language == "ar") "ar" else "en"
    }

    fun tr(ar: String): String = if (lang == "ar") ar else (Translations.EN[ar] ?: ar)

    fun trf(ar: String, vararg args: Any?): String =
        String.format(Locale.US, if (lang == "ar") ar else (Translations.EN[ar] ?: ar), *args)
}


/** تفضيلات المظهر (حالة Compose): system / light / dark. */
object AppPrefs {
    var theme by mutableStateOf("system")
}

fun tr(ar: String): String = I18n.tr(ar)

fun trf(ar: String, vararg args: Any?): String = I18n.trf(ar, *args)

/** قاموس الإنجليزية (المفتاح = النص العربي الأصلي). منفصل ليسهل اختباره بلا Compose. */
object Translations {
    val EN: Map<String, String> = mapOf(
        "حدث انهيار في التشغيل السابق" to "The app crashed on the previous run",
        "متابعة" to "Continue",
        "سجّل دخولك في الموقع ثم اضغط «تم». الجلسة تبقى محفوظة في التطبيق. إن رفض الموقع الدخول بحساب Google داخل التطبيق فاستخدم البريد وكلمة المرور أو الرابط السحري." to
            "Sign in to the site, then tap “Done”. The session stays saved in the app. If the site refuses Google sign-in inside the app, use email and password or the magic link.",
        "تم" to "Done",
        "المطلوب منك: %s" to "Needed from you: %s",
        "١) انسخ المهمة  ٢) الصقها وأرسلها في الموقع أدناه  ٣) انسخ الرد والصقه هنا" to
            "1) Copy the task  2) Paste and send it on the site below  3) Copy the reply and paste it here",
        "نسخ المهمة" to "Copy task",
        "تخطي" to "Skip",
        "الصق رد الموقع هنا" to "Paste the site's reply here",
        "لصق من الحافظة" to "Paste from clipboard",
        "إرسال الرد" to "Send reply",
        "القائمة" to "Menu",
        "خيارات المشروع" to "Project options",
        "تثبيت" to "Pin",
        "إلغاء التثبيت" to "Unpin",
        "إعادة تسمية" to "Rename",
        "تصدير الكود ZIP" to "Export code ZIP",
        "جلسة جديدة" to "New session",
        "بحث" to "Search",
        "الكود" to "Code",
        "الملفات" to "Files",
        "المثبّتة" to "Pinned",
        "الأخيرة" to "Recents",
        "المشاريع" to "Projects",
        "اسم المشروع" to "Project name",
        "نسخ" to "Copy",
        "إغلاق" to "Close",
        "لا توجد ملفات كود بعد. فعّل وضع البرمجة وابدأ مشروعاً." to "No code files yet. Enable coding mode and start a project.",
        "لا توجد نتائج" to "No results",
        "رمز GitHub (PAT)" to "GitHub token (PAT)",
        "المالك" to "Owner",
        "المستودع" to "Repository",
        "الفرع" to "Branch",
        "ملف سير العمل" to "Workflow file",
        "إنشاء رمز" to "Create token",
        "✅ تم الحفظ (مشفّر)" to "✅ Saved (encrypted)",
        "الاتصال بـ GitHub" to "GitHub connection",
        "الرمز يُحفظ مشفّراً على جهازك فقط ولا يُكتب في أي ملف." to "The token is stored encrypted on your device only and never written to any file.",
        "المشروع المراد رفعه" to "Project to upload",
        "لا توجد مشاريع برمجة بعد. فعّل وضع البرمجة وأنشئ مشروعاً أولاً." to "No coding projects yet. Enable coding mode and create a project first.",
        "مجلد فرعي في المستودع (اختياري)" to "Subfolder in repository (optional)",
        "رسالة الـ commit" to "Commit message",
        "استراتيجية الرفع" to "Upload strategy",
        "سحب ومقارنة ثم رفع" to "Pull & compare, then push",
        "رفع مباشر" to "Direct push",
        "تُقارن ملفاتك بالمستودع أولاً وتراجع التغييرات، ثم ترفعها." to "Your files are compared with the repository first so you can review changes, then pushed.",
        "يُرفع مباشرة؛ التغييرات فقط تُرسل ولا تُحذف ملفات المستودع." to "Pushed directly; only changes are sent and repository files are never deleted.",
        "دائماً: commit واحد ذري، بدون force push، وبدون حذف ملفات موجودة في المستودع." to "Always: a single atomic commit, no force push, no deletion of existing repository files.",
        "مقارنة: جديد %s، معدّل %s، متطابق %s" to "Compare: %s new, %s modified, %s identical",
        "الخطوة 1: سحب ومقارنة" to "Step 1: Pull & compare",
        "جديد: %s • معدّل: %s • متطابق: %s" to "New: %s • Modified: %s • Identical: %s",
        "لا تغييرات للرفع." to "Nothing to push.",
        "✅ تم رفع %s ملف في commit واحد" to "✅ Pushed %s files in one commit",
        "الخطوة 2: رفع إلى GitHub" to "Step 2: Push to GitHub",
        "فتح الـ commit" to "Open commit",
        "لا توجد عمليات بناء بعد." to "No builds yet.",
        "معاينة الجهاز" to "Device preview",
        "تدوير" to "Rotate",
        "اختر ملف HTML من مشروع برمجة أو أدخل رابطاً للمعاينة." to "Pick an HTML file from a coding project or enter a URL to preview.",
        "رابط" to "URL",
        "تحميل" to "Load",
        "هذه معاينة ويب لواجهات HTML. تطبيقات أندرويد الأصلية تُبنى سحابياً أدناه وتُثبَّت كـ APK على جهازك." to "This is a web preview for HTML UIs. Native Android apps are built in the cloud below and installed as an APK on your device.",
        "البناء السحابي (GitHub Actions)" to "Cloud build (GitHub Actions)",
        "✅ بدأ البناء. جارٍ المتابعة…" to "✅ Build started. Following…",
        "تشغيل البناء" to "Run build",
        "تحديث الحالة" to "Refresh status",
        "نجح" to "Succeeded",
        "نزّل ملف app-debug من قسم Artifacts في صفحة التشغيل ثم ثبّته." to "Download app-debug from the Artifacts section on the run page, then install it.",
        "فتح صفحة التشغيل" to "Open run page",
        "مكتبات التشغيل والربط (الإصدارات المثبّتة)" to "Runtime & integration libraries (pinned versions)",
        "الرفع إلى GitHub" to "Upload to GitHub",
        "المحاكي والسحابة" to "Emulator & Cloud",
        "الموصلات والسحابة" to "Connectors & Cloud",
        "هاتف" to "Phone",
        "هاتف صغير" to "Small phone",
        "لوحي" to "Tablet",
        "الوكلاء" to "Agents",
        "التشغيل" to "Run",
        "المشاريع (%s)" to "Projects (%s)",
        "الإعدادات" to "Settings",
        "تعذّر قراءة الملف" to "Could not read the file",
        "نسخة احتياطية مشفّرة" to "Encrypted backup",
        "استعادة الوكلاء" to "Restore agents",
        "المفاتيح تُشفَّر بكلمة مرور تختارها (4 أحرف فأكثر). لا يمكن استرجاعها إن نسيتها." to
            "Keys are encrypted with a password you choose (4+ characters). It cannot be recovered if you forget it.",
        "أدخل كلمة مرور النسخة الاحتياطية." to "Enter the backup password.",
        "كلمة المرور" to "Password",
        "تمت الاستعادة: %s وكيل جديد" to "Restored: %s new agent(s)",
        "كلمة المرور خاطئة أو الملف تالف" to "Wrong password or corrupted file",
        "تأكيد" to "Confirm",
        "إلغاء" to "Cancel",
        "اختر المزوّد وأضف مفتاحه. المزوّدون المجانيون يعطونك مفتاحاً مجانياً من موقعهم. كل وكيل بمفتاح مستقل، وإذا توقف يكمل التالي." to
            "Pick a provider and add its key. Free providers give you a free key from their site. Each agent has its own key; if one stops, the next continues.",
        "وكيل يدوي: عند حاجة الفريق إليه يفتح موقع الدردشة داخل التطبيق، تسجّل دخولك بنفسك، تلصق المهمة وترسلها، ثم تلصق الرد. يُستخدم كاحتياطي أخير." to
            "Manual agent: when the team needs it, the chat site opens inside the app; you sign in yourself, paste the task and send it, then paste the reply. Used as the last fallback.",
        "مفتاح %s من: %s" to "%s key from: %s",
        "%s (ويب)" to "%s (web)",
        "اسم الوكيل" to "Agent name",
        "مفتاح API" to "API key",
        "رابط موقع الدردشة" to "Chat site URL",
        "النموذج" to "Model",
        "جارٍ الجلب…" to "Fetching…",
        "%s نموذجاً متاحاً لمفتاحك، اضغط لاختيار واحد" to "%s models available for your key, tap one to select",
        "تعذّر الجلب: %s" to "Fetch failed: %s",
        "جلب النماذج المتاحة لمفتاحي" to "Fetch models available for my key",
        "إضافة وكيل" to "Add agent",
        "تسجيل الدخول الآن" to "Sign in now",
        "💾 حفظ الوكلاء" to "💾 Back up agents",
        "♻️ استعادة" to "♻️ Restore",
        "دخول" to "Sign in",
        "نموذج" to "Model",
        "حذف" to "Delete",
        "حفظ" to "Save",
        "تم التصدير" to "Exported",
        "تعذّر التصدير" to "Export failed",
        "تم تصدير المشروع" to "Project exported",
        "كل المشاريع محفوظة تلقائياً، ويتعرف عليها الوكلاء كذاكرة ويبنون عليها في المهام الجديدة." to
            "All projects are saved automatically. Agents recognize them as memory and build on them in new tasks.",
        "تصدير كل المشاريع (JSON)" to "Export all projects (JSON)",
        "لا توجد مشاريع بعد. ابدأ مهمة من تبويب التشغيل." to "No projects yet. Start a task from the Run tab.",
        " • الحالي" to " • Current",
        "فتح" to "Open",
        "تصدير .md" to "Export .md",
        "مشاركة المشروع" to "Share project",
        "مشاركة" to "Share",
        "كود ZIP (%s)" to "Code ZIP (%s)",
        "الهدف / المهمة" to "Goal / task",
        "💻 وضع البرمجة" to "💻 Coding mode",
        "تخطيط هندسي، ملفات كاملة بمسارات، مراجعة وإصلاح، ثم تصدير المشروع ZIP" to
            "Engineering plan, complete files with paths, review & fix, then export the project as ZIP",
        "🧠 التفكير العميق" to "🧠 Deep thinking",
        "كل خطوة: مسودة ثم نقد من وكيل آخر ثم تحسين (جودة أعلى، واستهلاك أكبر للحصص)" to
            "Each step: draft, critique by another agent, then refine (higher quality, more quota use)",
        "📎 إرفاق ملفات (نص، صور، ZIP، PDF، فيديو)" to "📎 Attach files (text, images, ZIP, PDF, video)",
        "جارٍ تجهيز الملفات…" to "Preparing files…",
        "إزالة" to "Remove",
        "مرفقات المشروع الحالي: %s" to "Current project attachments: %s",
        "ابدأ" to "Start",
        "استئناف" to "Resume",
        "إيقاف" to "Stop",
        "مسح" to "Clear",
        "حالة الوكلاء" to "Agent status",
        "معطّل" to "Disabled",
        "في راحة" to "Cooling down",
        "جاهز" to "Ready",
        "الخطوات: %s" to "Steps: %s",
        "بواسطة: %s" to "By: %s",
        "النتيجة النهائية" to "Final result",
        "السجل" to "Log",
        "✅ مكتمل" to "✅ Done",
        "⏸ غير مكتمل" to "⏸ Incomplete",
        "حرفاً" to "chars",
        // الإعدادات
        "اللغة" to "Language",
        "المظهر" to "Theme",
        "النظام" to "System",
        "العربية" to "العربية",
        "فاتح" to "Light",
        "داكن" to "Dark",
        "الأمان والخصوصية" to "Security & privacy",
        "مفاتيح API مشفّرة داخل الجهاز (Android Keystore) ولا تُرسل إلا إلى مزوّد الذكاء الاصطناعي الذي أضفته. لا توجد خوادم وسيطة ولا تتبّع." to
            "API keys are encrypted on-device (Android Keystore) and only sent to the AI provider you added. No intermediary servers, no tracking.",
        "الوكلاء والمشاريع والمرفقات تُخزَّن محلياً فقط على هذا الجهاز." to
            "Agents, projects and attachments are stored locally on this device only.",
        // سجل التشغيل
        "⛔ كل الوكلاء معطّلون (تحقق من المفاتيح والنماذج)" to "⛔ All agents are disabled (check keys and models)",
        "⏳ كل الوكلاء في راحة، انتظار %s ثانية" to "⏳ All agents are cooling down, waiting %s s",
        "🖐️ %s: بانتظار تنفيذك اليدوي في الويب" to "🖐️ %s: waiting for your manual step on the web",
        "⏭️ تخطي %s (راحة 10 دقائق)" to "⏭️ Skipped %s (10 min cooldown)",
        "❌ %s: عُطّل (%s: %s)" to "❌ %s disabled (%s: %s)",
        "⚠️ %s توقف (%s: %s) — راحة %sث ويكمل الوكيل التالي" to "⚠️ %s stopped (%s: %s) — cooling %ss, next agent continues",
        "⛔ لا يوجد وكلاء مفعّلون" to "⛔ No enabled agents",
        "🖼️ تحليل %s صور/لقطات…" to "🖼️ Analyzing %s images/frames…",
        "🖼️ تم وصف الصور بواسطة %s" to "🖼️ Images described by %s",
        "⚠️ تعذّر تحليل الصور؛ سيُكمل الفريق بدونها" to "⚠️ Image analysis failed; the team continues without it",
        "⚠️ لا يوجد وكيل رؤية (Claude أو Gemini) فارتباط الصور مهمل؛ أضف وكيل Gemini مجانياً لتحليلها" to
            "⚠️ No vision agent (Claude or Gemini): images ignored. Add a free Gemini agent to analyze them",
        "🧠 تخطيط الهدف…" to "🧠 Planning the goal…",
        "⚠️ رد غير مفهوم من %s (محاولة %s): %s" to "⚠️ Unclear reply from %s (attempt %s): %s",
        "❌ تعذّر فهم الخطة بعد محاولتين" to "❌ Could not parse the plan after two attempts",
        "📋 الخطة (%s): %s خطوات" to "📋 Plan (%s): %s steps",
        "▶️ استئناف من الخطوة %s" to "▶️ Resuming from step %s",
        "🔎 نقد الخطوة %s (%s كتب المسودة)…" to "🔎 Critiquing step %s (draft by %s)…",
        "🛠️ تحسين الخطوة %s بعد نقد %s…" to "🛠️ Refining step %s after critique by %s…",
        "✅ خطوة %s اكتملت بواسطة %s" to "✅ Step %s done by %s",
        "🧩 تجميع النتيجة النهائية…" to "🧩 Assembling the final result…",
        "🏁 انتهى (%s)" to "🏁 Finished (%s)"
    )
}
