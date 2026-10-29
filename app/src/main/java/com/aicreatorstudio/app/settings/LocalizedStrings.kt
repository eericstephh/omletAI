package com.aicreatorstudio.app.settings

import java.util.concurrent.ConcurrentHashMap

/**
 * 2026-09-09 — in-process UI string localization.
 *
 * The editor UI has thousands of HARDCODED Persian string literals
 * scattered across composables. Converting every one to res/string
 * resources in one pass is unrealistic, and the Android resource
 * system cannot help hardcoded strings anyway. This object provides
 * a pragmatic middle path:
 *
 *   - [t] resolves the string for the CURRENT app language
 *     ([AppLanguage.savedTag], applied per-Activity in
 *     attachBaseContext and cached here per composition root).
 *   - Fallback chain: requested tag → "en" → the key itself
 *     (Persian literals stay as keys, so an unlisted key renders the
 *     original Persian — never a blank).
 *   - Composables call `t("Settings")` / `t("Download")` etc.; keys
 *     ARE the default Persian source strings.
 *
 * Language is process-global; the Settings screen recreates the
 * Activity after a change, which re-seeds [currentTag] from prefs.
 */
object LocalizedStrings {

    /** BCP-47 tag seeded per Activity from [AppLanguage.savedTag]. */
    @Volatile
    var currentTag: String = AppLanguage.DEFAULT_TAG

    /** Called by the host Activity once per (re)creation. */
    fun seed(context: android.content.Context) {
        currentTag = AppLanguage.savedTag(context)
    }

    /** Keys are the ORIGINAL Persian literals used across the UI. */
    private val en = mapOf(
        // Settings
        //
        // 2026-10-17 PHASE 3 — these keys used to be ENGLISH literals
        // ("Appearance", "Export", "General", …), the only entries in
        // the table not keyed by their Persian source string. That made
        // the Settings screen mixed-language: its chrome came out in
        // English while the rest of the app was Persian-authored. The
        // call sites now pass the Persian source strings like every
        // other screen, so the keys follow the table convention and
        // English/Arabic still resolve to real translations.
        "تنظیمات" to "Settings",
        "مدل‌های هوش مصنوعی و دانلودها" to "AI Models & Downloads",
        "در حال بررسی…" to "Checking…",
        "نصب شده" to "Installed",
        "دانلود" to "Download",
        "در حال دانلود…" to "Downloading…",
        "اتصال هوش مصنوعی" to "AI connection",
        "ظاهر" to "Appearance",
        "خروجی" to "Export",
        "شتاب سخت‌افزاری" to "Hardware acceleration",
        "برای خروجی سریع‌تر از GPU استفاده کن (ممکن است باتری بیشتری مصرف کند)." to
            "Use the GPU for faster exports (may use more battery).",
        "عمومی" to "General",
        "ذخیره خودکار" to "Auto save",
        "تغییرات هر ۳۰ ثانیه به‌طور خودکار ذخیره شوند." to
            "Save your changes automatically every 30 seconds.",
        "بازخورد لمسی" to "Haptic feedback",
        "لرزش هنگام تعامل با تایم‌لاین" to "Vibrate on timeline interactions",
        "کیفیت پیش‌فرض خروجی" to "Default export quality",
        "فونت پیش‌فرض متن" to "Default text font",
        "حالت تیره" to "Dark",
        "حالت روشن" to "Light",
        "سیستم" to "System",

        // Editor dock / categories
        "ویرایش" to "Edit",
        "صدا" to "Audio",
        "تصویر" to "Visual",
        "متن" to "Text",
        "هوش مصنوعی" to "AI",
        "نسبت" to "Ratio",
        "ترنزیشن" to "Transitions",
        "انیمیشن" to "Animate",
        "برش" to "Split",
        "تکثیر" to "Duplicate",
        "حذف" to "Delete",
        "تریم" to "Trim",
        "سرعت" to "Speed",
        "فایل صوتی" to "Audio File",
        "پاکسازی صدا" to "Voice Clean",
        "فیلترها" to "Filters",
        "رنگ" to "Color",
        "افکت" to "Effects",
        "شفافیت" to "Opacity",
        "کراپ" to "Crop",
        "پس‌زمینه" to "Background",
        "اورلی" to "Overlay",
        "پاراگراف" to "Paragraph",
        "زیرنویس AI" to "AI Subtitles",
        "افزایش کیفیت" to "Upscale",
        "حذف پس‌زمینه" to "BG Remove",
        "جداسازی افراد و جایگزینی پس‌زمینه" to "Isolate people and replace the background",
        "اتوفریم" to "Auto Frame",

        // Subtitle pipeline states
        "آماده" to "Ready",
        "در حال آماده‌سازی..." to "Preparing…",
        "در حال استخراج صدا..." to "Extracting audio…",
        "در حال رونویسی..." to "Transcribing…",
        "در حال ساخت زیرنویس..." to "Building subtitles…",
        "در حال لغو..." to "Cancelling…",
        "Cancel" to "Cancel",

        // Text panel
        "استایل" to "Style",
        "افزودن متن" to "Add Text",
        "اعمال در محل پلی‌هد" to "Apply at playhead",
        "اعمال روی متن انتخاب‌شده" to "Apply to selected text",
        "اعمال روی همه متن‌ها" to "Apply to ALL texts",
        "فونت" to "Font",
        "اندازه" to "Size",
        "رنگ متن" to "Text color",
        "استایل‌های آماده" to "Presets",
        "استروک (خط دور)" to "Stroke (outline)",
        "سایه" to "Shadow",
        "باکس پس‌زمینه" to "Background box",
        "روشن" to "On",
        "خاموش" to "Off",

        // Subtitles card
        "زبان گفتار (Speech language)" to "Speech language",
        "استایل تکست زیرنویس فارسی" to "Persian subtitle style",
        "ابتدا زیرنویس بسازید یا یکی را انتخاب کنید" to
            "Create a subtitle or select one first",

        // What's New / changelog sheet (behind the Home notification bell).
        // NOTE: only the sheet's CHROME is listed here. The release notes
        // themselves are authored content owned by `AppChangelogProvider`
        // and are intentionally not translated at render time — localising
        // them means localising that data, not adding keys here.
        "تغییرات و قابلیت‌های جدید" to "What's New",
        "متوجه شدم" to "Got it",
        "جدید" to "New",
        "نسخه جاری" to "Current version",
        "نسخه" to "Version",

        // Common
        "Save" to "Save",
        "Delete" to "Delete",
        "Close" to "Close",
        "Download model" to "Download model",

        // Home (2026-10 redesign: the screen is now Persian-authored, so
        // these keys exist to keep the English experience complete).
        "املت" to "Omelet",
        "استودیو ویرایش ویدیو" to "Video editing studio",
        "پروژه جدید" to "New project",
        "افزودن ویدیو" to "Add video",
        "پروژه‌های من" to "Your projects",
        "هنوز پروژه‌ای نساخته‌اید" to "No projects yet",
        "برای شروع، یک پروژه تازه بسازید یا ویدیویی اضافه کنید." to
            "Create a new project or add a video to get started.",
        "حذف پروژه" to "Delete project",
        "این پروژه برای همیشه حذف می‌شود و قابل بازگردانی نیست." to
            "This project will be permanently deleted and cannot be restored.",
        "انصراف" to "Cancel",
        "تغییرات و آپدیت‌های جدید" to "What's new",
        "کانال تلگرام" to "Telegram channel",
        "به‌زودی" to "Coming soon",
        "بازگشت" to "Back",
        "ابزار ساخت پروژه به‌زودی اضافه می‌شود." to "Project setup tools are coming soon.",

        // Import video
        "افزودن ویدیو" to "Add video",
        "یک ویدیو از دستگاه خود انتخاب کنید" to "Select a video from your device",
        "نام فایل، مدت، رزولوشن و حجم آن را پیش از افزودن به پروژه نشان می‌دهیم." to
            "We'll show its file name, duration, resolution, and size before you add it to a project.",
        "انتخاب ویدیو" to "Select video",
        "در حال خواندن اطلاعات ویدیو…" to "Reading video info…",
        "مدت" to "Duration",
        "رزولوشن" to "Resolution",
        "حجم فایل" to "File size",
        "در پروژه جدید ذخیره شد" to "Saved to a new project",
        "انتخاب ویدیوی دیگر" to "Choose different video",
        "ذخیره در پروژه" to "Save to project",
        "دسترسی به حافظه لازم است" to "Storage access needed",
        "دسترسی به ویدیوها رد شد. برای افزودن ویدیو آن را از تنظیمات برنامه فعال کنید." to
            "Video access was denied. Enable it from app settings to import a video.",
        "املت برای افزودن ویدیو به دسترسی شما نیاز دارد." to
            "Omelet needs access to your videos to import one.",
        "باز کردن تنظیمات" to "Open settings",
        "اجازه دادن" to "Grant access",

        // 2026-10-20 — Home screen AI capability catalogue.
        //
        // The home screen lists every AI tool the app ships (one
        // tile each, with its own icon). These keys are the Persian
        // source strings of that catalogue, exactly like every other
        // screen.
        "قابلیت‌های هوش مصنوعی" to "AI capabilities",
        "زیرنویس هوشمند" to "Smart subtitles",
        "زیرنویس فارسی با Whisper، کاملاً آفلاین" to "Persian subtitles with Whisper, fully offline",
        "متن‌یابی هوشمند" to "Smart text extraction",
        "استخراج متن فارسی، عربی و انگلیسی از تصویر" to "Extract Persian, Arabic and English text from the frame",
        "هایلایت هوشمند" to "Smart highlights",
        "پیدا کردن بهترین لحظه‌های ویدیو" to "Find the best moments in your video",
        "قاب‌بندی خودکار" to "Auto framing",
        "قاب ۹:۱۶ که سوژه را در کادر نگه می‌دارد" to "A 9:16 frame that keeps the subject in shot",
        "ری‌فریم هوشمند" to "Smart reframe",
        "تغییر قاب ویدیو با پیش‌نمایش و تأیید" to "Reframe the video with a preview first",
        "ردیابی حرکت" to "Motion tracking",
        "چسباندن متن و استیکر به سوژهٔ در حال حرکت" to "Pin text and stickers to a moving subject",
        "ابره‌دقت ۲× و ۴× با شبکهٔ عصبی، آفلاین" to "Neural 2x and 4x upscaling, fully offline",
        "پاک‌سازی صدا" to "Voice cleanup",
        "حذف نویز و صدای اضافهٔ پس‌زمینه" to "Remove background noise and hiss",
        "بهبود صدا" to "Voice enhancement",
        "متعادل‌سازی بلندی و گرم کردن صدای خام" to "Even out loudness and warm up raw audio",
        "برش سکوت" to "Silence cut",
        "حذف خودکار سکوت‌ها با تشخیص گفتار" to "Cut silence automatically with speech detection",
        "سینک ضرب" to "Beat sync",
        "پیشنهاد برش‌های هماهنگ با ریتم موزیک" to "Suggest cuts that land on the beat")

    private val ar = mapOf(
        "تنظیمات" to "الإعدادات",
        "مدل‌های هوش مصنوعی و دانلودها" to "نماذج الذكاء الاصطناعي والتنزيلات",
        "در حال بررسی…" to "جارٍ الفحص…",
        "نصب شده" to "مثبت",
        "دانلود" to "تنزيل",
        "در حال دانلود…" to "جارٍ التنزيل…",
        // Settings chrome (see the `en` table for the PHASE 3 note).
        "اتصال هوش مصنوعی" to "اتصال الذكاء الاصطناعي",
        "ظاهر" to "المظهر",
        "خروجی" to "التصدير",
        "شتاب سخت‌افزاری" to "تسريع الأجهزة",
        "برای خروجی سریع‌تر از GPU استفاده کن (ممکن است باتری بیشتری مصرف کند)." to
            "استخدم GPU لتصدير أسرع (قد يستهلك بطارية أكثر).",
        "عمومی" to "عام",
        "ذخیره خودکار" to "حفظ تلقائي",
        "تغییرات هر ۳۰ ثانیه به‌طور خودکار ذخیره شوند." to
            "احفظ تغييراتك تلقائيًا كل ٣٠ ثانية.",
        "بازخورد لمسی" to "الاستجابة اللمسية",
        "لرزش هنگام تعامل با تایم‌لاین" to "الاهتزاز عند التفاعل مع المخطط الزمني",
        "کیفیت پیش‌فرض خروجی" to "جودة التصدير الافتراضية",
        "فونت پیش‌فرض متن" to "الخط الافتراضي للنص",
        "حالت تیره" to "الوضع الداكن",
        "حالت روشن" to "الوضع الفاتح",
        "سیستم" to "النظام",
        "ویرایش" to "تحرير",
        "صدا" to "الصوت",
        "تصویر" to "الصورة",
        "متن" to "النص",
        "هوش مصنوعی" to "الذكاء الاصطناعي",
        "نسبت" to "النسبة",
        "ترنزیشن" to "الانتقالات",
        "انیمیشن" to "الحركة",
        "برش" to "تقسيم",
        "تکثیر" to "تكرار",
        "حذف" to "حذف",
        "تریم" to "اقتطاع",
        "سرعت" to "السرعة",
        "فایل صوتی" to "ملف صوتي",
        "پاکسازی صدا" to "تنقية الصوت",
        "فیلترها" to "المرشحات",
        "رنگ" to "اللون",
        "افکت" to "التأثيرات",
        "شفافیت" to "الشفافية",
        "کراپ" to "القص",
        "پس‌زمینه" to "الخلفية",
        "اورلی" to "الطبقة",
        "پاراگراف" to "فقرة",
        "زیرنویس AI" to "ترجمة AI",
        "افزایش کیفیت" to "ترقية الدقة",
        "حذف پس‌زمینه" to "إزالة الخلفية",
        "اتوفریم" to "التأطير التلقائي",
        "آماده" to "جاهز",
        "در حال آماده‌سازی..." to "جارٍ التحضير…",
        "در حال استخراج صدا..." to "جارٍ استخراج الصوت…",
        "در حال رونویسی..." to "جارٍ النسخ…",
        "در حال ساخت زیرنویس..." to "جارٍ إنشاء الترجمة…",
        "در حال لغو..." to "جارٍ الإلغاء…",
        "استایل" to "النمط",
        "افزودن متن" to "إضافة نص",
        "فونت" to "الخط",
        "رنگ متن" to "لون النص",
        "روشن" to "مفعّل",
        "خاموش" to "معطّل",
        "ذخیره" to "حفظ",
        // What's New / changelog sheet — chrome only; see the `en` table.
        "تغییرات و قابلیت‌های جدید" to "الجديد والتحديثات",
        "متوجه شدم" to "فهمت",
        "جدید" to "جديد",
        "نسخه جاری" to "الإصدار الحالي",
        "نسخه" to "إصدار",
        // Home (see the `en` table for why these are listed).
        "املت" to "أومليت",
        "استودیو ویرایش ویدیو" to "استوديو تحرير الفيديو",
        "پروژه جدید" to "مشروع جديد",
        "افزودن ویدیو" to "إضافة فيديو",
        "پروژه‌های من" to "مشاريعي",
        "هنوز پروژه‌ای نساخته‌اید" to "لم تنشئ أي مشروع بعد",
        "برای شروع، یک پروژه تازه بسازید یا ویدیویی اضافه کنید." to
            "للبدء، أنشئ مشروعًا جديدًا أو أضف مقطع فيديو.",
        "حذف پروژه" to "حذف المشروع",
        "این پروژه برای همیشه حذف می‌شود و قابل بازگردانی نیست." to
            "سيُحذف هذا المشروع نهائيًا ولا يمكن استعادته.",
        "انصراف" to "إلغاء",
        "تغییرات و آپدیت‌های جدید" to "الجديد",
        "کانال تلگرام" to "قناة تيليجرام",
        "به‌زودی" to "قريبًا",
        "بازگشت" to "رجوع",
        "ابزار ساخت پروژه به‌زودی اضافه می‌شود." to "ستتوفر أدوات إنشاء المشروع قريبًا.",
        // Import video
        "افزودن ویدیو" to "إضافة فيديو",
        "یک ویدیو از دستگاه خود انتخاب کنید" to "اختر مقطع فيديو من جهازك",
        "انتخاب ویدیو" to "اختيار فيديو",
        "در حال خواندن اطلاعات ویدیو…" to "جارٍ قراءة معلومات الفيديو…",
        "مدت" to "المدة",
        "رزولوشن" to "الدقة",
        "حجم فایل" to "حجم الملف",
        "در پروژه جدید ذخیره شد" to "تم الحفظ في مشروع جديد",
        "انتخاب ویدیوی دیگر" to "اختيار فيديو آخر",
        "ذخیره در پروژه" to "حفظ في المشروع",
        "دسترسی به حافظه لازم است" to "يلزم الوصول إلى التخزين",
        "باز کردن تنظیمات" to "فتح الإعدادات",
        "اجازه دادن" to "السماح",
        // 2026-10-20 — Home screen AI capability catalogue.
        "قابلیت‌های هوش مصنوعی" to "قدرات الذكاء الاصطناعي",
        "زیرنویس هوشمند" to "ترجمة ذكية",
        "زیرنویس فارسی با Whisper، کاملاً آفلاین" to "ترجمة فارسية عبر Whisper، دون اتصال",
        "متن‌یابی هوشمند" to "استخراج نص ذكي",
        "استخراج متن فارسی، عربی و انگلیسی از تصویر" to "استخراج النص الفارسي والعربي والإنجليزي من الصورة",
        "هایلایت هوشمند" to "أبرز اللحظات",
        "جداسازی افراد و جایگزینی پس‌زمینه" to "عزل الأشخاص واستبدال الخلفية",
        "پیدا کردن بهترین لحظه‌های ویدیو" to "اكتشاف أفضل لحظات الفيديو",
        "قاب‌بندی خودکار" to "تأطير تلقائي",
        "قاب ۹:۱۶ که سوژه را در کادر نگه می‌دارد" to "إطار 9:16 يُبقي الهدف داخل الكادر",
        "ری‌فریم هوشمند" to "إعادة تأطير ذكية",
        "تغییر قاب ویدیو با پیش‌نمایش و تأیید" to "تغيير إطار الفيديو مع معاينة قبل التطبيق",
        "ردیابی حرکت" to "تتبّع الحركة",
        "چسباندن متن و استیکر به سوژهٔ در حال حرکت" to "تثبيت النص والملصقات على هدف متحرك",
        "ابره‌دقت ۲× و ۴× با شبکهٔ عصبی، آفلاین" to "دقة فائقة 2× و4× بشبكة عصبية، دون اتصال",
        "پاک‌سازی صدا" to "تنظيف الصوت",
        "حذف نویز و صدای اضافهٔ پس‌زمینه" to "إزالة الضجيج وأصوات الخلفية",
        "بهبود صدا" to "تحسين الصوت",
        "متعادل‌سازی بلندی و گرم کردن صدای خام" to "معادلة مستوى الصوت وتحسين التسجيل الخام",
        "برش سکوت" to "قطع الصمت",
        "حذف خودکار سکوت‌ها با تشخیص گفتار" to "إزالة الصمت تلقائيًا عبر كشف الكلام",
        "سینک ضرب" to "مزامنة الإيقاع",
        "پیشنهاد برش‌های هماهنگ با ریتم موزیک" to "اقتراح قطعات تناسب إيقاع الموسيقى")

    // tag → dictionary (en/fa share nothing: fa IS the key set).
    private val tables: Map<String, Map<String, String>> = mapOf(
        "en" to en,
        "ar" to ar
    )

    /** Resolve [key] for the current language (fallback: key itself). */
    fun t(key: String): String {
        val dict = tables[currentTag] ?: return key
        return dict[key] ?: key
    }

    /**
     * Batch lookup with a tiny per-tag cache for repeat calls.
     *
     * 2026-10-20 — the cache lives behind a `by lazy` holder.
     *
     * It used to be a plain `private val` on the `object`, i.e. a
     * static field initialised in the object's `<clinit>`. R8's class
     * merging can reorder static initialisers, and a field observed
     * before its initialiser ran is null — which is how a release
     * build ends up with
     *
     * ```
     * NullPointerException: ... ConcurrentHashMap.put(...) on a null object reference
     * ```
     *
     * A `by lazy` holder is initialised on FIRST ACCESS under a
     * lock, so its value can never be observed as null.
     */
    private object StringCache {
        val perTag: ConcurrentHashMap<String, ConcurrentHashMap<String, String>> =
            ConcurrentHashMap()
    }

    fun tCached(key: String): String {
        val tag = currentTag
        // computeIfAbsent is atomic — two threads racing on the same
        // tag cannot both create a map (and cannot lose one).
        val perTag = StringCache.perTag.computeIfAbsent(tag) { ConcurrentHashMap() }
        return perTag.computeIfAbsent(key) { t(key) }
    }
}
