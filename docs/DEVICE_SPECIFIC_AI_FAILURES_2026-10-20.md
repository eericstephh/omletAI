# گزارش «اپ روی بعضی گوشی‌های قوی کار نمی‌کند» — ۲۰۲۶-۱۰-۲۰

این گزارش علت‌های **وابسته به دستگاه** را بررسی و رفع می‌کند: جاهایی که کد
اپ روی یک گوشی کار می‌کند و روی گوشی دیگر (با همان قدرت سخت‌افزاری) می‌میرد.

---

## ۱. چه چیزهایی بررسی و **رد** شد

| فرضیه | نتیجهٔ بررسی |
|---|---|
| **page size ۱۶KB** (اندروید ۱۵+) | ✅ **سالم است.** هر ۱۴ کتابخانهٔ native با `p_align=16384` (اسکریپت `check_16kb.py`)، و `zipalign -c -P 16` هم «Verification successful» می‌دهد. |
| **معماری پردازنده** | ✅ arm64-v8a فقط، و گوشی‌های قوی arm64 هستند. |
| **APIهای اجباری اندروید ۱۴/۱۵** | ✅ هیچ `registerReceiver`، `PendingIntent` یا `foregroundService` در پروژه نیست. |
| **مجوز رسانه** | ✅ از Photo Picker مدرن با fallback درست استفاده می‌شود. |
| **راه‌اندازی اپ** | ✅ `Application`/`MainActivity` مسیر خطرناکی ندارند. |
| **MediaPipe** | ✅ به‌صورت پیش‌فرض روی **CPU** اجرا می‌شود (`Delegate` تنظیم نشده ⇒ CPU)، پس ریسک درایور GPU ندارد. |

پس علت جای دیگری است — و همان‌جایی که گوشی‌های قوی **مسیر متفاوتی** می‌روند.

---

## ۲. علت اصلی: انتخاب رمزگذار ویدیو

### چه اتفاقی می‌افتاد

`VideoFramePipeline` (که AI Upscale و ردیابی حرکت از آن استفاده می‌کنند) سه
چیز را **مستقل از هم** انتخاب می‌کرد، در حالی که `MediaCodec.configure` فقط وقتی
موفق است که هر سه با هم بخوانند:

```kotlin
val yuvFormat = selectEncoderColorFormat(MIME_AVC)   // همهٔ رمزگذارها را می‌پرسد
encoder = MediaCodec.createEncoderByType(MIME_AVC)     // رمزگذار پیش‌فرض را برمی‌دارد
encoder.configure(formatWith(yuvFormat), ...)          // امیدواریم جور باشند
```

سه ایراد:

1. **فرمت رنگ ممکن است متعلق به رمزگذار دیگری باشد.** پروب می‌گفت «اگر *هر*
   رمزگذاری planar یا **flexible** را پشتیبانی کند، planar را برگردان». اما
   `createEncoderByType` رمزگذار **پیش‌فرض** پلتفرم را می‌سازد — که اغلب
   `c2.android.avc.encoder` نرم‌افزاری است و **فقط**
   `COLOR_FormatYUV420Flexible` می‌پذیرد. پاسخ flexible اصلاً برای رمزگذار
   ByteBuffer معتبر نیست (فقط با Surface کار می‌کند). نتیجه:
   `IllegalArgumentException: unsupported color format` و **کل مسیر AI روی
   اولین فریم می‌مرد**.
2. **ابعاد هرگز بررسی نمی‌شد.** اپ تا ۳۸۴۰×۲۱۶۰ (4K) درخواست می‌کرد. بسیاری از
   گوشی‌ها رمزگذار سخت‌افزاری AVC را روی ۱۹۲۰×۱۰۸۰ محدود می‌کنند ⇒
   `configure` throw می‌شد.
3. **نمونهٔ ساخته‌شده همان رمزگذاری نبود که پروب شده بود.**

### چرا «گوشی‌های قوی» بیشتر می‌خوردند

ترتیب codecها و رمزگذار پیش‌فرض، **به سازندهٔ دستگاه** بستگی دارد (Exynos،
Snapdragon، MediaTek، Tensor). یک گوشی که رمزگذار نرم‌افزاری را به‌عنوان
پیش‌فرض می‌گذارد، در حالی که گوشی دیگر رمزگذار سخت‌افزاری planar دارد، دقیقاً
همین مسیر را می‌شکند — بدون هیچ الگویی که کاربر بتواند ببیند.

### فیکس

فایل جدید `ai/media/VideoEncoderCapability.kt`:

* انتخاب (رمزگذار، فرمت رنگ، اندازه) **یک تصمیم واحد** است.
* فقط فرمت‌های سازگار با ByteBuffer پذیرفته می‌شوند (planar یا semi-planar)؛
  `flexible` **هرگز** انتخاب نمی‌شود.
* رمزگذار با `MediaCodec.createByCodecName` ساخته می‌شود — همانی که پروب شده.
* ابعاد به محدودهٔ واقعی رمزگذار **کلمپ** می‌شوند و نسبت تصویر حفظ می‌شود؛
  اگر رمزگذار ۴K را نپذیرد، به‌جای شکست، بهترین اندازهٔ ممکن ساخته می‌شود.
* `VideoCapabilities.isSizeSupported` (پاسخ خود فریم‌ورک) بر بازهٔ اعلام‌شده
  مقدم است.

`EsrganUpscalePipeline` هم حالا **قبل از رندر** می‌پرسد دستگاه چه ابعادی را
می‌تواند رمزگذاری کند؛ قبلاً یک بیت‌مپ ۴K (۳۳ مگابایت) برای هر فریم کشیده
می‌شد و بعد رمزگذار آن را رد می‌کرد.

---

## ۳. علت دوم: شتاب‌دهندهٔ GPU فقط روی پرچم‌داران

### چه اتفاقی می‌افتاد

`EsrganUpscalePipeline` وقتی `gpuSupportsCompute && targetScale == 4` بود
(delegate گرافیکی TFLite) را فعال می‌کرد — و این شرط **فقط** روی گوشی‌های
پرچم‌دار برقرار است (`DeviceCapabilityDetector.isFlagship` = رم بالا + ۶ هسته +
GLES 3 + API 33+). یعنی دقیقاً همان گوشی‌هایی که کاربر می‌گوید مشکل دارند.

`CompatibilityList().isDelegateSupportedOnThisDevice` فقط **نسخهٔ OpenGL ES** را
بررسی می‌کند و هیچ چیزی دربارهٔ این نمی‌گوید که آیا مسیر compute آن GPU
واقعاً کار می‌کند. شیدرها **تنبل** کامپایل می‌شوند (در اولین inference)، و روی
درایوری که GLES 3.1+ اعلام می‌کند ولی compute شکسته دارد، همان inference اول
شکست می‌خورد — و در delegate گرافیکی این شکست اغلب یک **abort نیتیو** است که
هیچ `try/catch`ی نمی‌تواند بگیرد.

### فیکس

delegate گرافیکی حالا **هنگام بارگذاری یک‌بار اعتبارسنجی می‌شود**: یک inference
واقعی با شکل ورودی خودِ مدل (۵۰×۵۰) اجرا می‌شود تا همان شیدرهایی کامپایل شوند
که در اجرای واقعی استفاده می‌شوند. اگر آن warm-up شکست بخورد، delegate دور
ریخته و مفسر روی CPU ساخته می‌شود. هزینهٔ خطا یک بار بارگذاری بیهوده است، نه
کل اجرای Upscale.

---

## ۴. گزارش تشخیصی حالا می‌گوید دستگاه چه می‌تواند

«AI روی این گوشی کار نمی‌کند» بدون هیچ اطلاعات دیگری هیچ کمکی نمی‌کند. گزارش
تشخیصی (تنظیمات ← گزارش‌های فنی) حالا یک خط جدید دارد:

```
media: encoders=OMX.qcom.video.encoder.avc[1920-4096x1080-4096 fmt=I420+NV12];c2.android.avc.encoder[64-1920x64-1080 fmt=flex]
```

این خط دقیقاً می‌گوید دستگاه چه رمزگذاری با چه فرمت و چه ابعادی دارد — یعنی
اگر مشکلی باقی بماند، گزارش **قابل‌اقدام** است.

---

## ۵. تشخیص از گزارش واقعی دستگاه (Xiaomi 2107113SG، اندروید ۱۴)

کاربر یک گزارش تشخیصی واقعی فرستاد. سه خطای جداگانه در آن بود — و هر سه جداگانه
قابل ریشه‌یابی بودند.

### خطای ۱: گزارش کرش stack trace نداشت

```
crash/uncaught_exception: DefaultDispatcher-worker-8: NullPointerException
  !! NullPointerException: Attempt to invoke virtual method
  'java.lang.Object java.util.concurrent.ConcurrentHashMap.put(...)' on a null object reference
```

گزارش می‌گفت **چه** اتفاقی افتاد ولی **کجا** اصلاً نگفته بود. ریشه: رکورد لاگ فقط
`ThreadName: ExceptionClass` را نگه می‌داشت و `LogSanitizer.describeThrowable` فقط
`ClassName: message` می‌نویسد — **بدون فریم‌ها**.

**فیکس:** `CrashReporter` حالا فریم‌های stack trace را داخل پیام رویداد می‌گذارد (با
بودجهٔ ۲۰۰۰ کاراکتر به‌جای ۵۰۰، تا فریم‌ها truncate نشوند). گزارش بعدی دقیقاً
می‌گوید کدام خط کرش کرده.

### خطای ۲: سه کش سراسری در برابر بازچینش static initializer در R8 آسیب‌پذیر بودند

سه کش فرایندی به‌صورت `private val` بودند — یعنی **فیلد static** که در `<clinit>`
مقدار می‌گیرد. R8 می‌تواند ترتیب static initializerها را با class merging عوض کند،
و فیلدی که **قبل** از اجرای initializerاش خوانده شود، `null` است.

کش تصویر بندانگشتی صفحهٔ اصلی دقیقاً از همان نوع است و نوشتنش از همان worker
اتریال می‌شود که فریم‌ها را decode می‌کند → **همان `ConcurrentHashMap.put` روی
receiver تهی**.

**فیکس:** هر سه کش پشت یک holder با `by lazy` رفتند (مقدار در **اولین دسترسی** و زیر
قفل ساخته می‌شود، پس هرگز `null` دیده نمی‌شود)، و lookupها به `computeIfAbsent`
(اتمیک) تغییر کردند.

### خطای ۳: «Model integrity check failed» — کاملاً اشتباه

```
ai_tool/model_ensure_failed: موتور هوش مصنوعی نتوانست مدل را اجرا کرد... | tool=matting
ai_tool/run_failed: Model integrity check failed: موتور هوش مصنوعی نتوانست مدل را اجرا کرد... | tool=matting
```

**مدل اصلاً مشکل نداشت.** SHA-256 فایل همراه APK با lockfile **بایت‌به‌بایت** یکی
است (بررسی شد)، و `installWithManifest` **موفق** بود. خطا از `createFromOptions`
مدیاپایپ می‌آمد — یعنی گراف روی این دستگاه init نشد.

و بدتر: `NativeEngineFailure.classify` استثنای اصلی را **دور می‌ریخت** و فقط یک
جملهٔ عمومی («برنامه‌های پس‌زمینه را ببندید») می‌داد که اصلاً علت نبود.

**فیکس (سه چیز):**
1. **fallback به delegate پردازنده.** مدیاپایپ به‌صورت پیش‌فرض به یک context
   کارآمد OpenGL ES نیاز دارد که بعضی دستگاه‌ها (همین خانوادهٔ MIUI/Redmi) نمی‌دهند.
   حالا موتور اول با delegate پیش‌فرض تلاش می‌کند و در صورت شکست **با
   `Delegate.CPU`** (XNNPACK — بدون وابستگی به GPU) دوباره تلاش می‌کند. برای هر دو
   موتور: ماتینگ و Auto Frame.
2. **علت فنی واقعی نگه داشته می‌شود** (`lastLoadErrorDetail`) و در متن وضعیت و
   گزارش تشخیصی می‌آید.
3. **عنوان درست شد**: «Model integrity check failed» →
   «موتور تشخیص سوژه اجرا نشد»، و وجود/حجم فایل مدل **جداگانه** بررسی می‌شود
   (مدیاپایپ فایل گمشده را هم به‌صورت خطای مبهم init گزارش می‌کند).

---

## ۵.۲ تشخیص قطعی با REA روی دستگاه واقعی (۲۰۲۱-۱۰-۲۱)

### ابزار

REA (Reverse Engineering Agent — `morluto/rea`) نصب و راه‌اندازی شد:

- **نصب:** `npx -y rea-agents@6.4.0` (نسخهٔ pin‌شدهٔ skill). پکیج در رجیستری هست (latest 6.7.0).
- **وضعیت:** `rea doctor --client opencode --json` → ثبت MCP برای opencode **missing** است
  (هیچ بخش `mcp` در `opencode.jsonc` نیست). skill هم‌تراز است (v37، ۱۷۰ ابزار).
- **plan ثبت:** یک تغییر — افزودن ثبت REA به
  `C:\Users\eric steph\.config\opencode\opencode.jsonc` با backup در
  `opencode.jsonc.rea.backup`. (این plan هنوز **اعمال نشده** — نیاز به تأیید دارد.)
- **provider بی‌adb:** REA دنبال `adb` در PATH می‌گردد و پیدا نمی‌کند. با
  `REA_ADB_PATH=C:\android\platform-tools\adb.exe` کار می‌کند.
- **provider اندروید (jadx):** غیرفعال — به `jadx-headless-mcp 0.7.1` JAR نیاز دارد که
  REA خودش نصب نمی‌کند و **روی npm هم وجود ندارد** (بیشینهٔ ۰.۷.۰). مسیر
  `inspect-artifact` بدون provider کار می‌کند و روی APK ریلز خودمان Evidence داد.

### شواهد

دستگاه کاربر (`c3f38b01` = Xiaomi 2107113SG، اندروید ۱۴، MIUI V816.0.22.0) وصل بود.
`read-adb-logcat --buffer crash` **توم‌استون واقعی** را داد:

```
Fatal signal 6 (SIGABRT) in tid 14694 (DefaultDispatch), pid 13927
Abort message: 'JNI DETECTED ERROR IN APPLICATION: non-zero capacity for nullptr pointer: 1
    in call to NewDirectByteBuffer
    from android.media.ImageReader$SurfaceImage$SurfacePlane[]
           ImageReader$SurfaceImage.nativeCreatePlanes(int, int, long)'
```

فریم‌های اپ (از build **debug** که نام‌ها obfuscate نشده‌اند):

```
com.aicreatorstudio.app.ai.engines.MediaPipeMattingEngine$process$2.invokeSuspend
  com.aicreatorstudio.app.ai.media.SequentialFrameSampler.sample
    com.aicreatorstudio.app.ai.media.VideoFramePipeline.imageToBitmap
      com.aicreatorstudio.app.ai.media.VideoFramePipeline.yuv420ToBitmap
```

تاریخچهٔ کرش روی دستگاه — **هر سه کرش یکی‌اند** و در هر دو build (debug و release) رخ می‌دهند، پس باگ کد است نه باگ بیلد:

| زمان | بسته |
|---|---|
| ۲۰-۱۰ ۱۵:۴۶ | `app.debug` |
| ۲۰-۱۰ ۲۲:۵۲:۱۸ | `app` — **۴۷ ثانیه بعد از گزارشی که فرستاده شد** |
| ۲۱-۱۰ ۰۲:۴۴:۲۱ | `app` (ریلز جدید) |

یعنی: گزارش تشخیصی ساخته شد → ۴۷ ثانیه بعد اپ **ناپدید شد**.

### ریشه

`MediaMetadataRetriever` در AOSP ابعاد **نمایشی** را برمی‌گرداند (برای چرخش ۹۰/۲۷۰ محورها را عوض می‌کند)، ولی decoder مدیاکدک **اندازهٔ کدشده و crop‌شده** را از track format می‌نویسد.

`ImageReader` با ابعاد retriever ساخته می‌شد ⇒ برای ویدیوی **پرتره** انتظار فریمی با اندازه‌ای داشت که decoder تولید نمی‌کرد ⇒ `nativeCreatePlanes` یک plane با **آدرس null و capacity=1** ساخت ⇒ CheckJNI در ART یک `abort()` نیتیو کرد.

`abort()` نیتیو **قابل catch نیست** ⇒ کل پروسه مرد. «حذف پس‌زمینه» اپ را ناپدید می‌کرد.

### فیکس (سه لایه)

1. **اندازهٔ reader از track format** (`decoderOutputSize` — اندازهٔ کدشده با crop اعمال‌شده) گرفته می‌شود، در هر چهار محل ساخت reader.
2. **`probe()`** حالا اندازهٔ **کدشده** را از track format می‌گیرد، نه از retriever. این با قرارداد
   `VideoDimensions.resolve` هم‌راستا شد و یک باگ پنهان را هم اصلاح کرد: `displayWidth`
   قبلاً برای ویدیوی پرتره **کدشده** بود (دو بار swap) — یعنی **اندازهٔ خروجی export
   برای ویدیوی پرتره غلط بود** و ماسک ماتینگ در اندازهٔ غلط کامپوزیت می‌شد.
3. **گارد قطعی:** `image.getPlanes()` فقط روی تصویری صدا زده می‌شود که ابعادش با
   reader **بررسی شده** باشد. `getWidth()/getHeight()` دسترسی ساده‌اند و هرگز به
   بومی plane دست نمی‌زنند ⇒ ناهماهنگی با **خطای روشن** رد می‌شود، نه abort.

### تست روی دستگاه

`app/src/androidTest/.../DecoderSizeDiagnosticTest.kt` نوشته شد: اندازهٔ retriever را با
اندازهٔ track format مقایسه می‌کند و ثابت می‌کند reader باید از track format ساخته
شود. **برای اجرا باید دستگاه وصل باشد** (هنگام اجرا قطع بود).

---


| فایل | تغییر |
|---|---|
| `ai/media/VideoEncoderCapability.kt` | **جدید** — انتخاب رمزگذار/فرمت/اندازه (منطق خالص + پروب فریم‌ورک) |
| `ai/media/VideoFramePipeline.kt` | استفاده از انتخابگر؛ حذف پروب قدیمی؛ خطای فارسی روشن |
| `ai/upscale/EsrganUpscalePipeline.kt` | پرسیدن از دستگاه **قبل** از رندر؛ نام فایل با اندازهٔ واقعی |
| `ai/upscale/EsrganTiler.kt` | اعتبارسنجی delegate گرافیکی با warm-up و fallback به CPU |
| `logging/OmeletLogStore.kt` | `mediaSummary()` — قابلیت‌های رمزگذار دستگاه |
| `logging/LogReportBuilder.kt` | خط `media:` در گزارش |
| `viewmodel/DiagnosticsViewModel.kt` | پر کردن خط media |

### تست‌ها (جدید)
* `VideoEncoderCapabilityTest` — ۱۱ تست روی منطق خالص: رمزگذار flexible-only
  انتخاب نمی‌شود، فرمت رنگ متعلق به رمزگذار انتخابی است، 4K روی دستگاه 1080p
  **کلمپ** می‌شود (نه fail)، نسبت تصویر حفظ می‌شود، ابعاد فرد هستند، بزرگ‌ترین
  اندازهٔ ممکن انتخاب می‌شود.
* `VideoEncoderWiringTest` — تضمین می‌کند پایپ‌لاین دیگر از
  `createEncoderByType` استفاده نکند و warm-up بماند.
* `LogReportMediaSummaryTest` — خط media در گزارش.

---

## ۸. اعتبارسنجی

| بررسی | نتیجه |
|---|---|
| `:app:compileDebugKotlin` | ✅ |
| `:app:compileReleaseKotlin` | ✅ |
| `:app:testDebugUnitTest` | ✅ **۳۱۰۹ تست، ۰ خطا** (۲۳۷ کلاس) |
| `:app:assembleDebug` | ✅ APK ۱۱۶٫۹ مگابایت |
| `check_16kb.py` | ✅ ۱۴/۱۴ کتابخانه با `p_align=16384` |
| `zipalign -c -P 16` | ✅ Verification successful |

---

## ۹. محدودیت‌ها

* **هیچ دستگاه یا شبیه‌سازی در دسترس نبود.** منطق انتخاب رمزگذار کاملاً
  پوشش داده شده (تست خالص)، اما رفتار واقعی `MediaCodec` روی هر سازندهٔ
  گوشی فقط روی دستگاه قابل تأیید است.
* تشخیص «کدام گوشی دقیقاً» بدون دستگاه ممکن نیست؛ به همین دلیل گزارش
  تشخیصی غنی شد تا گزارش بعدی **دقیقاً** علت را نشان دهد.
* اگر پس از این فیکس‌ها هنوز مشکلی بود، خط `media:` در گزارش تشخیصی و بخش
  «آخرین خطا» دقیقاً می‌گویند کدام مرحله شکست خورده.
