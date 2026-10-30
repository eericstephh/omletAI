# CapCut 19.9.0 — مهندسی معکوس «حذف پس‌زمینه» (ماسک/مَتینگ)

**پروژه:** Omelet / AI Creator Studio (`com.aicreatorstudio.app`)
**تاریخ:** 2026-10-25
**دامنه:** فقط قابلیت حذف پس‌زمینه (matting / remove background) در CapCut
`com.lemon.lvoverseas` نسخه 19.9.0 — تحلیل **استاتیک** روی آرتیفکت منتشرشده.
**وضعیت:** تحلیل شاهد-محور. هر ادعا با «مشاهده / استنباط / نامعلوم» برچسب خورده است.

---

## 0. محدوده و قاعده حقوقی

این سند فقط **رفتار و قرارداد** را مستند می‌کند تا مبنای یک پیاده‌سازی مستقل باشد.
هیچ کد، Lua، شیدر، فونت، وزن مدل یا دارایی CapCut داخل مخزن ما کپی نشده و نمی‌شود.

تفکیک صریح:

* **قابل استفاده (ایده/قرارداد/پارامتر):** نام و ترتیب گام‌ها، نام فیلدهای درفت،
  اعداد پیش‌فرض گراف الگوریتم، ترتیب فیلترهای پس‌پردازش ماسک. اینها *دانش فنی* هستند.
* **قابل بازنویسی مستقل:** الگوریتم‌های عمومی تصویر (dilate/erode/gaussian/spiral
  sampling). ریاضی این فیلترها عمومی است؛ ما نسخه خودمان را می‌نویسیم.
* **ممنوع:** کپی فایل‌های `matting_config.zip`، `MattingBlendV2.zip`، شیدرهای
  `shaderLib/*.frag`، اسکریپت‌های Lua، مدل‌های `tt_matting*`، هر رشته DRM/VIP.

شاهدهای خام (خارج از مخزن، روی دیسک محلی):
`G:\projects\edit\New folder (2)\AICreatorStudio\build\capcut-re\v19.9.0\`
(این پوشه build است و commit نمی‌شود.)

---

## 1. هویت آرتیفکت

| مورد | مقدار | نوع |
|---|---|---|
| فایل | `com.lemon.lvoverseas_19.9.0-19900002_..._apkmirror.com.apk` | مشاهده |
| اندازه | 322,464,224 بایت | مشاهده |
| SHA-256 | `3C74A9941237451953FE4ED1ABE477B7413D11D11A4B5B45A17441AFD30F2C38` | مشاهده |
| package / versionCode / versionName | `com.lemon.lvoverseas` / `19900002` / `19.9.0` | مشاهده (`aapt2 dump badging`) |
| minSdk / targetSdk | 23 / 36 | مشاهده |
| ABI | `arm64-v8a`, `armeabi-v7a` | مشاهده |
| محتوا | 28,051 entry، 39 dex، 327,786 کلاس، 1,465,477 متد، 345 `.so`، 1,172 asset | مشاهده |

روش: `scripts\capcut_re_inventory.ps1` (inventory + `apkanalyzer` + `aapt2` +
`dexdump` + NDK `llvm-strings/nm/readelf`). REA فقط برای هویت آرتیفکت
(`open_binary` / sha256) قابل استفاده بود؛ providerهای Android/ELF آن روی این
ماشین در دسترس نیستند (بخش 12).

---

## 2. معماری قابلیت (بالا به پایین)

```
UI (Compose/View)
  com.vega.edit.image.view.edits.matting.*        (پنل تصویر: SFImageEditMattingPanel …)
  com.vega.edit.image.view.edits.bgremove.*       (براش/استروک تصویر)
  com.vega.edit.matting.*                         (2,198 متد)  ← هسته منطق
      ├─ aibackground (864 متد)   ← تولید پس‌زمینه با AI
      ├─ chroma                   ← کروماکی
      ├─ singlefunc               ← حالت‌های تک‌کاره
      ├─ data / reporter
      └─ viewmodel                ← VideoMattingViewModel، SubVideoMattingViewModel …
کامپوننت‌ها
  ImageEditRemoveBGComponent            (حذف پس‌زمینه عکس)
  ImageEntitySegmentationComponent
  com.vega.cutsameedit.biz.edit.video.matting (52 متد، مسیر قالب/CutSame)
سرویس چهره‌ی درفت
  com.vega.edit.base.algorithm.MattingService   ← فقط ReqStruct می‌سازد
  com.vega.middlebridge.client.MattingClient    ← به Lyra می‌فرستد
موتور
  com.vega.middlebridge.swig.*  →  libmiddle-bridge.so (62,171,936 بایت)
  الگوریتم: گراف JSON + ماژول بومی (ai_matting، mobile_vos، interactive_matting …)
  رندر: libeffectsdk (GLMattingFilter) + افکت «AmazingFeature_DilateErode»
مدل‌ها
  هیچ وزن مَتینگی در APK نیست → دانلود در زمان اجرا (بخش 6)
```

**مشاهده:** در APK فقط دو فایل `.model` وجود دارد و هیچ‌کدام مَتینگ نیست:
`assets/js_clip128_v1.0.model` (13,068 B)، `assets/js_translation_v1.0.model`
(12,612 B). کل حجم asset ها 42,805,441 بایت است و بزرگ‌ترین‌ها JS/JSON پنل‌های
Lynx هستند، نه مدل.

---

## 3. قرارداد افزودن مَتینگ (مسیر ویدیو)

`MattingService.c(...)` یک `AddMattingReqStruct` می‌سازد و با
`MattingClient.b(session, req, callback)` اجرا می‌کند. فیلدها (مشاهده از
bytecode):

| فیلد ReqStruct | مقدار در کد | معنا (استنباط) |
|---|---|---|
| `segment_id` | `SegmentVideo.b()` | سگمنت هدف |
| `config_path` | `InnerResourceHelper.v(context)` | مسیر پوشه‌ی استخراج‌شده‌ی `assets/zip/matting_config.zip` (مشاهده: `v()` → فیلد `U` ← `zip/matting_config.zip`) |
| `matting_type` | پارامتر ورودی | نوع مَتینگ (`MATTING_TYPE_*`) |
| `matting_tag` | پارامتر، پیش‌فرض `""` | تگ/نوع دقیق‌تر برای گزارش |
| `interactive_time` | پارامتر `long` | زمان فریم مرجع برای مَتینگ تعاملی |
| `workspace` | پارامتر | پوشه‌ی کاری نتیجه |
| `photo_new_use_model` | `LvMattingPhotoUseNewModel.enable` | گیت AB برای مدل جدید عکس |
| `video_new_use_model` | `LvMattingVideoUseNewModel.enable` | گیت AB برای مدل جدید ویدیو |
| `custom_matting_id` | `segment.matting.customMattingId` | شناسه‌ی ماسک براشی (برای اشتراک بین سگمنت‌ها) |
| `enable_video_obj_param` | `true` فقط وقتی `FaceDetectConfig != null` | فعال‌سازی تشخیص چهره/آبجکت |
| `face_detect_interval` | `FaceDetectConfig.intervalMs` | فاصله‌ی تشخیص (ms) |
| `face_detect_limit` | `FaceDetectConfig.detectLimit` | سقف تعداد تشخیص |
| `face_threshold` | `FaceDetectConfig.threshold` | آستانه‌ی اطمینان |

پیش‌فرض‌های `FaceDetectConfig` (مشاهده از `toString`):
`FaceDetectConfig(intervalMs=…, detectLimit=…, threshold=…)`؛ مقدار عددی پیش‌فرض
پیدا نشد (بخش 11). سازنده در `ImageEditRemoveBGComponent.P4` از
`AIMattingVideoObjectConfig(...)` پر می‌شود
(`enable`, `enablePreview`, `faceDetectInterval`, `faceDetectLimit`,
`faceThreshold`).

### 3.1 سایر ReqStruct های همین سرویس (مشاهده)

| متد | ReqStruct | کاربرد |
|---|---|---|
| `c()` | `AddMattingReqStruct` | افزودن مَتینگ |
| `i()` | `AddCustomMattingReqStruct` | مَتینگ براشی/تعاملی |
| `h()` | `RestoreMattingFromDraftReqStruct` | بازسازی مَتینگ از درفت هنگام بازکردن پروژه |
| `f()` | `SetMattingCachePathReqStruct` | `cache_path = DirectoryUtil.D("cache")` |
| `g()` | `RemoveMattingTaskReqStruct` | حذف تسک |
| `b()` | `GetLatestTaskTypeReqStruct` | آخرین نوع تسک |
| `e()` | `GetHasUseQuickBrushReqStruct` | آیا کاربر «براش سریع» زده |
| `a()` | `AddMattingListenerReqStruct` | ثبت listener پیشرفت |
| — | `CopyMattingDataOnMattingEditReqStruct` | کپی ماسک بین سگمنت‌های لینک‌شده (workspace = `DirectoryUtil.Y() + "/" + MD5(...)`) |
| — | `GetNotDoneSegmentIDsReqStruct` | poll وضعیت (در `waitMatting`) |
| — | `ExportCurrentMattingFrameReqStruct` | خروجی PNG ماسک عکس |

---

## 4. مَتینگ براشی (Custom / Interactive)

`MattingService.i(...)` →
`AddCustomMattingReqStruct`:

| فیلد | مقدار |
|---|---|
| `segment_id`, `config_path`, `workspace` | مثل بالا |
| `matting_type` | نوع سفارشی |
| `interactive_time` | زمان فریم مرجع |
| `brush_type` | `LVVEMattingPenType.a` (int) |
| `brush_size` | اندازه‌ی قلم |
| `brush_points` | `VectorOfFloat` (نقاط نرمال‌شده) |
| `custom_matting_id` | شناسه‌ی مشترک ماسک |

### 4.1 انواع قلم (مشاهده، مقادیر از clinit)

| نام | مقدار `a` | معنا (استنباط) |
|---|---|---|
| `fast` | 1 | قلم سریع (VOS سبک) |
| `general` | 2 | قلم عمومی |
| `eraser` | 4 | پاک‌کن |
| `fasteraser` | 8 | پاک‌کن سریع |

مقادیر توان‌های دو هستند ⇒ `brush_type` در واقع **bitmask** است و می‌تواند
ترکیب شود. (استنباط قوی از مقادیر؛ رفتار ترکیبی تست نشده.)

ورودی گراف براشی: `matting_config/custom_matting/algorithmConfig.json`
(مشاهده) — سه ورودی تصویر (`imageProducer` کانال 0/1/2) به ترتیب
`input_0/1/2`، سپس:

```
input_1 → nh_script_0(script, mobilevos_packed) ─┐
input_2 → nh_script_0 ───────────────────────────┤
input_0 → mobile_vos_0(mobile_vos, mask_type=1) ─┤
nh_script_0 → mobile_vos_0 (input 1) ────────────┤
input_1 → nh_script_1(script) + mobile_vos_0 + nh_script_0 → خروجی نهایی
```

⇒ **سه ورودی** یعنی: تصویر، ماسک قبلی/راهنمای کاربر، و یک ورودی کمکی
(`script_pre` / `script_after` با `model_name=mobilevos_packed`). این یک
**VOS** (Video Object Segmentation) با ماسک راهنما است، نه یک segmentation
تک‌فریمی. (استنباط از ساختار گراف؛ معنای دقیق کانال 2 تأیید نشده.)

گراف تعاملی ساده‌تر: `interactive/algorithmConfig.json` →
یک نود `interactive_matting` با `interactive_matting_method=1`،
`interactive_matting_insidemem=1`، `model_name=tt_interactive_matting`، و سه
`imageProducer`.

---

## 5. مدل داده‌ی درفت (چه چیزی ذخیره می‌شود)

`com.vega.middlebridge.swig.Matting` (مشاهده‌ی کامل getter ها):

| getter | نوع | فیلد درفت | معنا |
|---|---|---|---|
| `getPath` | String | `matting.path` | مسیر ماسک (PNG) یا ویدیوی ماسک |
| `getExpansion` | **long** | `matting.expansion` | گسترش/جمع‌شدگی ماسک |
| `getFeather` | **long** | `matting.feather` | نرمی لبه |
| `getReverse` | boolean | `matting.reverse` | معکوس‌کردن انتخاب (پس‌زمینه به‌جای سوژه) |
| `getStrokes` | `VectorOfMattingStroke` | `matting.strokes` | استروک‌های براش (`MattingStroke.getColor → VectorOfDouble`) |
| `getCustomMattingId` | String | `matting.custom_matting_id` | شناسه‌ی ماسک براشی |
| `getInteractiveTime` | `VectorOfLongLong` | `matting.interactive_time` | فریم‌های مرجع |
| `getFlag` | int | `matting.flag` | bitmask وضعیت |
| `getBlendMode` | int | `matting.blend_mode` | حالت نمایش |
| `getHasUseQuickBrush` | boolean | `matting.has_use_quick_brush` | براش سریع استفاده شده |
| `getHasUseQuickEraser` | boolean | `matting.has_use_quick_eraser` | پاک‌کن سریع استفاده شده |

کلیدهای درفت در اسکریپت‌های بومی هم دیده می‌شوند (مشاهده، `libmiddle-bridge.so`):
`matting['path']`, `['expansion']`, `['feather']`, `['reverse']`, `['strokes']`,
`['enable_matting_stroke']`, `['has_use_quick_brush']`, `['has_use_quick_eraser']`.

کلیدهای تغییر/کثیفی (مشاهده، DEX): `is_change_matting_expansion`,
`is_change_matting_feather`, `is_customized_matting_feather`,
`is_intelligent_matting_feather`, `is_support_feather`.

فیلدهای اضافی که فقط در لایه‌ی JNI دیده می‌شوند و در کلاس Java عمومی نیستند
(مشاهده، رشته‌های `MattingModuleJNI`): `mask_video_path`, `cloud_product_fps`,
`is_cloud`, `is_copied_mask`, `copied_segment_id`, `enable_matting_stroke`,
`blend_color`, `node_name`.
⇒ یک مسیر **ماسک ویدیویی** و یک مسیر **محصول ابری** وجود دارد. (استنباط)

### 5.1 فلگ‌ها (bitmask، مشاهده)

| نام | مقدار |
|---|---|
| `enableFlag` | 1 |
| `aiMattingFlag` | 2 |
| `cusMattingFlag` | 4 |
| `headMattingFlag` | 8 |
| `interactiveFlag` | 16 |
| `tagMattingFlag` | 32 |
| `webAiMattingFlag` | 64 |
| `objModelMattingFlag` | 128 |

نام انواع مَتینگ (مشاهده، رشته‌های DEX): `MATTING_TYPE_NONE`, `_PORTRAIT`,
`_INTERACTIVE`, `_SALIENCY`, `_FACE`, `_HEAD`, `_FOLLOW`, `_COMBINE`, `_EXTRA`.
مقدار عددی هر کدام پیدا نشد (بخش 11).

### 5.2 حالت‌های بلند (مشاهده)

`LVVEMattingBlendMode` = `MattingBlendModeNormal`, `MattingBlendModePreview`,
`MattingBlendModeHide`.

---

## 6. چرخه‌ی مدل: هیچ وزنی در APK نیست

### 6.1 فهرست نیازمندی‌ها

`com.vega.libeffect.data.MattingModelDataKt.<clinit>` (مشاهده‌ی کامل bytecode):

```kotlin
val a: Map<String, List<String>> = mapOf(
    "tt_matting" to listOf(
        "tt_matting_video", "saliency_matting", "tt_matting_video_gru",
        "saliency_script_for_cc", "tt_matting", "tt_matting_large",
        "tt_matting_subject", "tt_interactive_matting", "mobilevos_packed",
        "tt_pet_matting_for_jianying", "tt_headseg_for_jianying",
        "ge_seg_lv_script", "tt_skeletonsquat", "tt_matting_video_preview",
        "tt_humanparsing_skeleton_for_jianying", "video_saliency_seg_bce",
        "video_saliency_seg_bce_preview", "tt_fsnew_base_jianying"
    )
)
```

⇒ ماژول `tt_matting` **۱۸ مدل** می‌خواهد که همه با هم دانلود/اعتبارسنجی می‌شوند.

### 6.2 مکانیزم آمادگی/دانلود

`com.vega.libeffect.model.EffectAiModelDownloader` (مشاهده‌ی bytecode؛ نام
متدها obfuscate شده‌اند و از رشته‌های `checkMattingModelReady$2..4` تشخیص داده شد):

1. `UseResourceSdk.b()` تعیین می‌کند از **resource pool جدید** استفاده شود یا
   `ResPoolRepository` قدیمی.
2. مسیر مدل با `ResPoolRepository.E(modelName)` یا
   `AlgorithmResourceFinder.realFindResourceUri(0, null, name)` گرفته می‌شود.
   مقدار بازگشتی می‌تواند sentinel باشد:
   `asset://not_found` | `asset://md5_error` | `asset://not_initialized`.
3. اعتبار: `h(uri)` هر سه sentinel و رشته‌ی خالی را نامعتبر می‌داند.
   `f(list)` علاوه بر آن `PolarisFileWrapper(path).exists()` را چک می‌کند.
4. اگر آماده نبود: نام مدل در `downloadingModels` علامت می‌خورد و دانلود با
   `DownloadableModelSupport.getInstance().fetchResourcesByRequirementsAndModelNames(
   requirements, mapOf(model to listOf(model)), listener)` یا
   `ResPoolRepository.i(models, requirements, onStart, onEnd, onError)` شروع می‌شود.
5. وضعیت‌های برگشتی: `success` / `no model` / `downloading` / `network error` /
   `not initialized` / `manager not init`.
6. لاگ‌ها: tag `AI_Matting` و `RecommendModelDownloader`، پیام
   `matting model is not ready! downloading:<name>`.
7. کلید کش محلی: `KvStorage("app.config")` با کلید `ai_recommend_model_md5`.
8. چهار نقطه‌ی ورود: `checkMattingModelReady` (`tt_matting_video`)،
   `checkVosMattingModelReady`، `checkInteractiveMattingModelReady`
   (`tt_interactive_matting`)، `downloadMattingModel` (`tt_matting`).

### 6.3 گیت‌های AB/سرور (مشاهده، رشته‌ها)

`lv_matting_photo_use_new_model`, `lv_matting_video_use_new_model`,
`SessionConfig_enabled_picture_object_matting_model`,
`enabled_video_sequence_model`, `matting_entry_enable`,
`MattingPostProcessingConfig(group="v1"|"v2")`,
`ABChromaMattingEdgeSmoothAndSpill`.

گیت‌های VIP در Lua بومی (مشاهده): `type == 24, ai matting in app`,
`using vip ai matting`, `using vip custom matting`, `using vip chroma matting`,
و gateهای نسخه‌ی درفت مثل `use matting expansion` (123.0.0)،
`use matting feather`، `use matting reverse`، `enable_matting_stroke` (129.0.0).
⇒ گسترش/نرمی/معکوس فقط از نسخه‌ی مشخصی از درفت به بعد معنا دارند (مهاجرت درفت).

---

## 7. گراف‌های الگوریتم (۹ عدد، همه در `matting_config.zip`)

| ماژول | اندازه | نود | پارامترها | مدل | کاربرد |
|---|---|---|---|---|---|
| `ai_matting` | 360×640 | `matting` | `modeltype 4`, `forwardtype 0`, `frashevery 15`, `edgemode 1`, `imagemode 0`, `proc_border 1` | (پیش‌فرض `tt_matting`) | حذف پس‌زمینه عکس/ویدیو تک‌فریم |
| `ai_matting_gru` | 360×640 | `matting` | همان + `forwardtype -1` | `tt_matting_video_gru` | **ویدیو با حافظه‌ی زمانی** |
| `ai_matting_video_object` | 360×640 | `general_seg` | — | `video_saliency_seg_bce` | آبجکت/سالیانسی ویدیو |
| `ai_matting_video_object_preview` | 360×640 | `general_seg` | — | `video_saliency_seg_bce_preview` | همان، نسخه‌ی پیش‌نمایش |
| `camera_matting` | — | `matting` | `frashevery 15`, `edgemode 3`, `modeltype 0`, `forwardtype 0`, `usegpu 0`, `proc_border 1` | (دوربین) | مَتینگ زنده‌ی دوربین |
| `custom_matting` | — | `mobile_vos` + ۲ `script` | `mobile_vos_input_mask_type 1` | `mobilevos_packed` | براش/VOS |
| `interactive` | — | `interactive_matting` | `method 1`, `insidemem 1` | `tt_interactive_matting` | کلیک/براش تعاملی |
| `saliency_matting` | 640×640 | `script` | `return_mask 1`, `return_crop 0` | `saliency_script_for_cc` | سالیانسی |
| `tag_matting` | — | `script` | — | `ge_seg_lv_script_v1.0.model` | سگمنت بر اساس تگ |

همه با `"mode": 2` (استنباط: حالت GPU/گراف) و یک `texture_blit`/`textureBlitter`
برای ورودی. نام گراف `AlgorithmGraph_9bpck63bYbqbZAcfdUcmcAbNdWcf` (برای
`ai_matting`, `ai_matting_gru`, `saliency_matting`) و
`…AbcUcjcAbNdWcf` (برای video_object) — یعنی **همان گراف پایه با پارامتر
متفاوت**، که با «مدل دانلودی + config کوچک» سازگار است.

نکات کلیدی برای هم‌ترازی:

* ورودی مدل ویدیویی **360×640** است (نه 1080p): ماسک در رزولوشن پایین محاسبه و
  بعد بالا/پایین‌برده می‌شود. استنباط: کیفیت لبه از پس‌پردازش GPU می‌آید، نه از
  رزولوشن مدل.
* `matting_mp_frashevery = 15` + وجود گراف `_gru` و مدل `tt_matting_video_gru`
  ⇒ استنباط: مدل ویدیویی هر ۱۵ فریم «رفرش» می‌شود و بین آن‌ها حافظه‌ی
  بازگشتی (GRU) ماسک را پیش می‌برد. **این دقیقاً چیزی است که ما نداریم.**
* `usegpu 0` فقط برای دوربین؛ یعنی مسیر اصلی روی GPU است (استنباط).

---

## 8. پس‌پردازش ماسک روی GPU (مهم‌ترین بخش برای کیفیت)

افکت `assets/zip/MattingBlendV2.zip` → `AmazingFeature_DilateErode`
(مشاهده‌ی `DilateErode.lua` + شیدرهای `shaderLib/shaderGLES/*.frag`).

زنجیره (به ترتیب اجرا):

1. **نصف‌رزولوشن:** بافت میانی در `0.5 ×` ابعاد خروجی (و اگر `blur < 20`
   کوچک‌تر: `1 - 0.5*blur/20`). (مشاهده)
2. **ماکس/مین افقی:** ۴۳ نمونه؟ نه — **۱۵ نمونه در هر طرف** (۳۱ تپ) روی
   کانال ماسک؛ خروجی سه‌تایی `(max, min, center)` در RGB. (مشاهده)
   ```
   r = |k|/15 / ((screen.xy / min(screen.xy)) * normSize)   // normSize = 240
   ```
3. **ماکس/مین عمودی** + تصمیم:
   * `k >= 0` → `out = max` ⇒ **گسترش (dilate)**
   * `k < 0` → `out = min` و سپس **eroزیون نرم**:
     ```
     out = pow(clamp(min - (max - center) * (|k|/5), 0, 1), 1 + |k|/4)
     ```
   (مشاهده؛ فرمول عیناً از `convolutionV` شیدر)
4. **مسیر جایگزین دیسکی** وقتی `kernelType > 0.5`: نمونه‌برداری مارپیچی
   (Vogel-like) با ۶۰ تکرار، زاویه‌ی طلایی `2.399963` رادیان و رشد شعاع
   `t ← t + 1/t`، شعاع پایه `radius = 11.125 - 1`. (مشاهده)
   ⇒ برای شعاع‌های بزرگ، به‌جای تپ‌های خطی از دیسک نمونه‌برداری می‌کند
   (کیفیت بهتر، هزینه‌ی ثابت ۶۰ تپ).
5. **نرم‌کردن لبه (feather):** دو پاس گاوسی جداشدنی، ۴۳ تپ (۲۱ در هر طرف)،
   `sigma = 4` روی فاصله‌ی نرمال‌شده ×۱۵، با `gamma = 1.4` قبل و `1/1.4` بعد.
   اگر `edgeSmooth < 0.2` فقط تپ‌های ۱۹..۲۳ با گام ۸× (کرنل ۵ تپ) استفاده
   می‌شود. (مشاهده)
6. **Despill اختیاری:** `inputSpill = 0.10` در مسیر مَتینگ (هاردکد در Lua)،
   با منطق HSV/YUV و clamp کانال سبز (`fixColor.g = mix(g, min(g, …))`).
   برای حذف پس‌زمینه معمولاً بی‌اثر است چون پس‌زمینه‌ی مرجع مشکی است.
   (مشاهده)
7. **معکوس‌سازی:** اگر `enable_reverse > 0.5` ⇒ `mask = 1 - mask`. (مشاهده)
8. **انتشار ماسک:** `Amaz.BuiltinObject.addUserTexture("#MattingMask", OutputTex)`
   ⇒ ماسک تک‌کاناله در کانال R به گراف رندر تزریق می‌شود تا فیلترهای بعدی
   (blur پس‌زمینه، جایگزینی، …) آن را با نام `#MattingMask` بخوانند. (مشاهده)

نگاشت اسلایدرها (مشاهده، `DilateErode.lua`):

| ورودی UI | تبدیل | اثر |
|---|---|---|
| `erode_dilate_kernel_size` | `kernelSize = value / 8.0` | مثبت = گسترش، منفی = جمع‌شدگی، صفر = بدون تغییر |
| `blur_kernel_size` | `inputEdgeSmooth = value / 100.0` | ۰..۱ نرمی لبه |
| `channel` | ۰ = کانال آلفا، ۱ = لومینانس | منبع ماسک |
| `enable_reverse` | ۰/۱ | معکوس |
| `normSize` | ۲۴۰ (حالت عادی) | مقیاس کرنل |

**این جدول مستقیماً قابل ترجمه به پارامترهای UI ماست** (بخش 10).

---

## 9. مسیر عکس (`ImageEditRemoveBGComponent`)

مشاهده‌ی bytecode:

| متد | کار |
|---|---|
| `generateAlgorithmInput()` | ساخت پس‌زمینه‌ی شبکه‌ای (`ImageGridCanvasEXKt.h`) + `ImageEditorCommonInput.b(...)` |
| `exportMattingFrame(w, h, segmentId)` | `ExportCurrentMattingFrameReqStruct(width, height, segmentId, output_path, enable_auto_position=true)` → `VideoClient.w(...)` → فایل `<uuid>.png` در `SessionResourceExKt.b(session)` + `/image_editor_matting/` |
| `waitMatting(segmentId)` | poll با `GetNotDoneSegmentIDsReqStruct` داخل `withTimeoutOrNull`؛ اگر سگمنت در لیست نبود ⇒ آماده |
| `H0(...)` + `G9(...)` | ساخت Req های اعمال: `AlgorithmUpdateReqStruct` + `VideoAlgorithmUpdateParam` |
| `N5(id)` | لغو: `DraftComboParams("image_editor_matting_cancel")` |

اعمال نتیجه (مشاهده):

```
VideoAlgorithmUpdateParam:
  name        = "image_editor_matting"
  path        = RemoveBGProduct.a          (فایل ماسک)
  path_on     = true
  algorithmId = UUID
  algorithmType = LVVELocalAlgorithmType.Y  ← «مَتینگ تصویر»
FinishAlgorithmTaskInputReqStruct:
  segmentId, algorithmId, algorithmType, outputPath, overlayUniqueId = RemoveBGProduct.d
UpdateCanvasBackgroundImageReqStruct / ...ColorReqStruct   ← پس‌زمینه‌ی جدید
DraftComboParams("image_editor_matting_apply")            ← undo/redo اتمیک
```

⇒ معماری CapCut برای عکس: **ماسک به‌عنوان یک algorithm/overlay روی سگمنت** ثبت
می‌شود، نه به‌صورت پیکسل پخته‌شده. یعنی می‌توان بعداً expansion/feather/reverse
را بدون اجرای مجدد مدل عوض کرد — چون پس‌پردازش ماسک روی GPU و در زمان رندر
انجام می‌شود. (استنباط قوی)

پس‌زمینه‌ی AI: `RemoveBGProduct$BackgroundSnapshot` =
`Image(uri, "photo_editor_gird_bg", bool)` یا `Color("#…")` یا `None`؛ رنگ
`#00000000` به‌عنوان «شفاف» رد می‌شود. (مشاهده)

---

## 10. شکاف نسبت به موتور فعلی ما

موتور ما: `app/src/main/java/com/aicreatorstudio/app/ai/engines/MediaPipeMattingEngine.kt`
(MediaPipe Selfie Multiclass 256²، ماسک در post-pass ۱۰fps، sidecar PNG،
ترکیب در export).

| قابلیت CapCut | شاهد | وضعیت ما | شکاف |
|---|---|---|---|
| گسترش/جمع‌شدگی ماسک (expansion) | `Matting.getExpansion` + dilate/erode شیدر | ❌ نداریم | **P0** — ارزان، اثر بصری زیاد |
| نرمی لبه‌ی نرمال‌شده (feather 0..100) | `blur_kernel_size/100` + گاوسی ۴۳ تپ | ⚠️ `featherPx` 0..10 پیکسلی (تصویر-محور) | **P0** — نرمال‌سازی + gamma |
| معکوس‌کردن انتخاب (reverse) | `enable_reverse` / `getReverse` | ❌ نداریم | **P0** — یک خط |
| ماسک به‌عنوان texture در گراف رندر | `#MattingMask` + `path_on` | ⚠️ PNG sidecar + ترکیب در export | P1 (معماری متفاوت، نتیجه مشابه) |
| مدل زمانی ویدیو (GRU) | `ai_matting_gru` + `frashevery 15` | ⚠️ EMA ساده (α=0.65) | **P1** — بزرگ‌ترین شکاف کیفیت |
| براش/پاک‌کن تعاملی (VOS) | `custom_matting` + `VVEMattingPenType` | ❌ نداریم | P2 (نیاز به مدل VOS) |
| حالت‌های سالیانسی/آبجکت/سر/حیوان/تگ | ۵ گراف + `MATTING_TYPE_*` | ❌ فقط شخص | P2 |
| تولید پس‌زمینه با AI | `com.vega.edit.matting.aibackground` (864 متد) | ❌ فقط رنگ/تصویر/ویدیو/بلور | P2 (نیاز به مدل generative) |

> **این جدول یک عکس لحظه‌ای از 2026-10-25 است، نه وضعیت فعلی.** از آن
> تاریخ به بعد: expansion/feather/reverse و گاما همه پیاده شده‌اند
> (بند ۱۵)، و EMA با `MattingTemporalFilter` (میانه‌ی سه فریم + ریست
> برش صحنه) جایگزین شده. وضعیت جاری را از
> `AIerix-Cut-Lead-Engineer-Agent/skills/aierix-cut-project/references/open-gaps.md`
> بخوانید.
| ماسک ویدیویی / محصول ابری | `mask_video_path`, `is_cloud`, `cloud_product_fps` | ❌ | P2 (سرور) |
| گیت/دانلود مدل با md5 و sentinel | `EffectAiModelDownloader` | ✅ داریم (`AiModelInstaller` + lockfile) | هم‌تراز |
| undo/redo اتمیک برای اعمال | `DraftComboParams("…_apply")` | ⚠️ تاریخچه دارد، ولی combo اتمیک ماسک+پس‌زمینه ندارد | P1 |
| تشخیص چهره برای انتخاب سوژه | `face_detect_interval/limit/threshold` | ❌ | P2 |

### 10.1 نکته‌ی مهم دربارهٔ کیفیت

CapCut مدل را در **360×640** اجرا می‌کند و لبه را با یک زنجیره‌ی GPU
(dilate/erode + گاوسی ۴۳ تپ + gamma) بازسازی می‌کند. ما مدل را در 256² اجرا
می‌کنیم و ماسک را در 720 نگه می‌داریم، ولی **پس‌پردازش ما فقط یک بلور
پیکسلی است**. بنابراین بخش زیادی از اختلاف بصری از **پس‌پردازش** می‌آید، نه از
مدل. این خبر خوبی است: P0 ها بدون مدل جدید قابل انجام‌اند.

---

## 11. آنچه تأیید نشد (صریح)

1. **محدوده‌ی دقیق اسلایدرهای expansion/feather** در UI پیدا نشد؛ فقط تبدیل
   داخلی (`/8`, `/100`) مشاهده شد. عدد صفر نقطه‌ی بی‌اثر است، دامنه‌ی منفی برای
   expansion مسلم است (شیدر دو شاخه دارد) ولی حد پایین/بالا نامعلوم.
2. **مقدار عددی `matting_type`** برای هر نام `MATTING_TYPE_*` پیدا نشد.
3. **معنای دقیق `matting_mp_frashevery=15`** (به‌ازای فریم؟ میلی‌ثانیه؟) تأیید
   نشد؛ «هر ۱۵ فریم یک‌بار forward کامل» استنباط است، نه مشاهده.
4. **فرمت فایل مدل‌ها** (`tt_matting*`) نامعلوم است — وزن‌ها در APK نیستند و
   بدون دستگاه/شبکه قابل دریافت نیستند.
5. **مصرف `#MattingMask`** با نام در فیلترهای بعدی دیده شد، ولی قرارداد دقیق
   (کدام افکت، با چه parame) فقط تا حد رشته‌ی افزودن بافت تأیید شد.
6. **رفتار زمان اجرا** (FPS، مصرف حافظه، کیفیت واقعی) اندازه‌گیری نشد: CapCut
   روی دستگاه نصب نیست و نصب نسخه‌ی debug ما روی MIUI بلاک است
   (`INSTALL_FAILED_USER_RESTRICTED`). هیچ ادعای عملکردی در این سند وجود ندارد.
7. **مرز VIP/DRM**: رشته‌ها دیده شد، منطق کامل Lua خوانده نشد (کپی نمی‌کنیم).

---

## 12. چه چیزی از REA در دسترس بود

| ابزار | نتیجه روی این ماشین |
|---|---|
| `open_binary` روی APK | ✅ کار می‌کند (هویت/هش) |
| `inspect_android_package` | ❌ `capability_unavailable` (نیاز به `REA_JADX_MCP_JAR`) |
| `decode_android_resources` | ❌ نیاز به `REA_APKTOOL_COMMAND` |
| `open_binary` روی ELF | ❌ provider برای ELF نیست (Ghidra/IDA/Hopper پیکربندی نشده) |
| `extract_artifact` | ❌ برخورد نام `res/3z.png` vs `res/3Z.png` |
| `inspect_keyed_archive` | ❌ برای APK پشتیبانی نمی‌شود |
| ADB / runtime | ❌ دستگاه قطع است؛ CapCut نصب نیست |

بنابراین تحلیل با زنجیره‌ی محلی انجام شد: `apkanalyzer dex code` (bytecode کامل
کلاس‌ها)، `apkanalyzer dex packages`، `aapt2 dump badging`،
`llvm-strings` روی `libmiddle-bridge.so`، و `tar`/`ZipFile` برای استخراج
asset ها. هیچ شاهدی توسط REA «ساخته» نشد.

---

## 13. فایل‌های شاهد (محلی، commit نمی‌شوند)

```
build\capcut-re\v19.9.0\
  summary.md, inventory.csv, packages.txt, libs.txt, assets.txt
  analysis\pkgs-focus.txt              (93 پکیج مَتینگ/سگمنت)
  analysis\classes-matting.txt          (1,429 کلاس)
  analysis\methods-matting.txt          (6,206 امضای متد)
  analysis\str-libmiddle-bridge.so.txt  (126,849 رشته)
  analysis\token-hits-specific.txt
  analysis\bundled\matting-config\matting_config\<9>\algorithmConfig.json
  analysis\bundled\blend-v2\AmazingFeature_DilateErode\…  (Lua + شیدرها)
  analysis\dexcode-model\
      EffectAiModelDownloader.txt / .compact.txt
      MattingModelDataKt.txt
      MattingService.smali.txt / .compact.txt
      ImageEditRemoveBGComponent.smali.txt / .compact.txt
      Matting.swig.txt, MattingStroke.swig.txt,
      LVVEMattingPenType.swig.txt, LVVEMattingFlag.swig.txt,
      LVVEMattingBlendMode.swig.txt
      AIMattingVideoObjectConfig.swigtwo.txt,
      MattingService_FaceDetectConfig.swigtwo.txt
      InnerResourceHelper.txt, InnerResource.txt
```

اسکریپت بازتولید: `scripts\capcut_re_inventory.ps1 -Apk <path> -OutDir <dir>`
(APK تکی، bundle مانند `.apkm/.xapk/.apks`، یا پوشه‌ی پکیج pull شده).

---

## 14. ترتیب پیشنهادی کار (بر پایه‌ی شاهد)

**P0 — بدون مدل جدید، اثر بصری زیاد (۱ تا ۲ روز):**

1. یک تابع مشترک `MaskPostProcess` بساز که روی ماسک بایت‌مپ کار کند:
   `dilate/erode(radiusNormalized) → gaussianFeather(featherNormalized, gamma 1.4) → reverse`.
   همین تابع هم در engine (هنگام نوشتن sidecar) و هم در export/preview صدا زده شود
   تا هر سه یک نتیجه بدهند.
2. `MattingSettings` را با `expansion` (منفی..مثبت) و `reverse` گسترش بده؛
   `featherPx` را به `feather` نرمال‌شده (۰..۱) تبدیل کن و مقدار قدیمی را در
   مهاجرت JSON نگه دار.
3. اسلایدرها را با همان نگاشت مشاهده‌شده بگذار: expansion داخلی `/8`، feather `/100`
   (با برچسب فارسی و مقدار پیش‌فرض صفر).
4. تست واحد برای فرمول‌ها (dilate/erode نرم + reverse) و یک تست تصویری روی یک
   فریم نمونه با ماسک مصنوعی.

**P1 — کیفیت زمانی:**

5. جای EMA ساده، یک فیلتر زمانی دو-پاس (forward/backward) روی ماسک‌های sidecar،
   یا افزایش نرخ نمونه به ۱۲–۱۵fps با میان‌یابی وزنی بر اساس اختلاف فریم.
   هدف: حذف لرزش بدون تأخیر؛ اندازه‌گیری روی همان کلیپ ۳۶ ثانیه‌ای.
6. اتمیک‌کردن اعمال ماسک+پس‌زمینه در یک گام undo (تا Ctrl+Z هر دو را با هم برگرداند).

**P2 — قابلیت‌های جدید (نیاز به مدل/سرور):**

7. براش تعاملی (VOS) — نیاز به مدل جداگانه؛ تا آن زمان دکمه نگذار.
8. حالت آبجکت/سالیانسی — کاندید: یک مدل segmentation عمومی (مثلاً YOLO-seg یا
   MediaPipe Interactive Segmenter)؛ باید مستقل انتخاب و pin شود.
9. پس‌زمینه‌ی تولیدی AI — نیاز به سرویس/مدل generative؛ خارج از دامنه‌ی محلی.

هیچ‌کدام از این‌ها وزن یا کد CapCut مصرف نمی‌کند؛ فقط پارامترها و ترتیب
مراحل از شاهد الهام گرفته شده است.

---

## 15. یادداشت پیاده‌سازی P0 (2026-10-26) — چه رفت، چه ماند

**اعمال‌شده:** کل زنجیره
`expansion → feather → reverse` دقیقاً به همین ترتیب، روی ماسک خامِ
sidecar، در زمان ترکیب (export) با `MaskPostProcess`/`MaskBitmaps` اجرا
می‌شود.

**گاما (تصحیح 2026-10-30):** در یادداشت اولیه نوشته شد که gamma پیش/پسِ
بلور پیاده نشده چون «جهت آن تأیید نشد». این درست نبود: بند ۸.۵ همین سند
ترتیب را با علامت **(مشاهده)** ثبت کرده (`gamma = 1.4` قبل و `1/1.4`
بعد) و بند ۱۱ — فهرست صریح «آنچه تأیید نشد» — آن را در بر نمی‌گیرد.
حدسِ حذف‌شده بود، نه ابهامِ سند. حالا پیاده شده:

```
out = blur(mask ^ 1.4) ^ (1 / 1.4)      // FEATHER_GAMMA = 1.4f
```

گاما **یک‌بار دور مجموع سه پاس box** می‌پیچد، نه دور هر پاس (وگرنه به
`γ³` تبدیل می‌شد). دو خاصیت آن را بی‌خطر می‌کند: دو توان دقیقاً معکوس
یکدیگرند، پس هر میدان ثابت دست‌نخورده می‌ماند (پیش‌زمینه‌ی یکنواخت ۱ و
پس‌زمینه‌ی یکنواخت ۰)، و وقتی بلور اجرا نشود گاما عیناً همانی است — پس
مسیر سریع `isNoOp` را هم نمی‌شکند. تنها اثرش روی شیب گذار است: در یک
لبه‌ی پله‌ای نتیجه `Φ^(1/1.4)` است، یعنی کانتور ۵۰٪ به سمت **بیرون**
می‌رود (≈ ۰.۶۱) با همان دامنه‌ی کلی — حدود ۲۰٪ بایاس بیرونی در
`feather = 1`.

تنها ابهام باقی‌مانده این است که کدام توان اول اعمال می‌شود؛ خوانش
دیگر (`blur(m^(1/1.4))^1.4`) تصویر آینه‌ای آن است (بایاس **درونی** با
همان اندازه) و با معکوس‌کردن `FEATHER_GAMMA` به دست می‌آید. اگر روزی
مقایسه‌ی دستگاهی خلافش را نشان داد، تغییر همان یک ثابت است و
`MaskPostProcessTest` تستِ «کانتور به بیرون می‌رود» را دارد که
قرمز می‌شود.

میان‌بُر `edgeSmooth < 0.2 → ۵ تپ با گام ۸×` عمداً کپی نشد: مسیر سه
box-blur از قبل نسبت به شعاع O(N) است، پس آن میان‌بُر فقط یک ضریب ثابت
می‌خرید و دقت لبه را در نرمی‌های کم هزینه می‌کرد.

هزینه‌ی گاما دو sweep کامل `pow` روی هر ماسک است (چند میلی‌ثانیه در
رزولوشن sidecar) و فقط وقتی بلور اجرا شود پرداخت می‌شود؛ اگر پروفایلِ
export بعداً آن را دید، یک LUT ۲۵۶تایی آن را رایگان می‌کند.

**واحدهای نهایی:** `expansion ∈ −1..+1` (در `±1` → `shortSide/20` px)،
`feather ∈ 0..1` (`1` → sigma ی `shortSide/60` px). هر دو از
`normSize = 240` و `kernelSize = ui/8` شیدر مشتق شدند؛ محدوده‌ی دقیق
اسلایدر CapCut هرگز مشاهده نشد (همان عدم‌قطعیت بند 11).

**یک تفاوت معماری با پلان بند 14:** به‌جای اینکه این تابع هم در engine
هم در export صدا زده شود، ماسک‌های sidecar *خام* نوشته می‌شوند و
پست‌پروسه فقط در زمان ترکیب اعمال می‌شود — این همان خواص CapCut (کش
اسلایدر بدون اجرای دوباره‌ی مدل) را می‌دهد و از دودبل-فدر در مسیر
ذخیره/بازیابی جلوگیری می‌کند؛ پِروژه‌های قدیمی که فدر در PNG آنها پخته
شده با `feather = 0` لود می‌شوند.

**همه‌ی پاس‌ها O(N)** هستند (deque مونوتونیک و box-blur سه‌گذر) چون
زنجیره داخل حلقه‌ی export اجرا می‌شود؛ معنای مرزی «تکثیر شبه‌نمونه» است
(`proc_border 1`) و حسابِ مرزی در برابر oracleهای بی‌قاعده‌ی
`MaskPostProcessTest` تأیید شده است.

## 16. حالت پیش‌فرض «حذف کامل پس‌زمینه» (2026-10-27)

درخواست کاربر: «وقتی دکمه‌ی حذف پس‌زمینه رو می‌زنم، سوژه باقی بمونه و
پس‌زمینه کلا حذف بشه.» در CapCut این دقیقاً خروجیِ ابزار است؛ در پیاده‌سازی
ما تا این تاریخ `ORIGINAL` (pass-through) پیش‌فرض بود — یعنی دکمه به‌وضوح
هیچ‌چیز عوض نمی‌کرد و `TRANSPARENT` هم در export پرتاب خطا می‌شد.

**تصمیم (معادل صادقانه در MP4):** MP4/HEVC کانال آلفا ندارد و muxer
آفلاینی برای آلفا نداریم، پس `TRANSPARENT` به «سوژه روی رنگ بومِ پروژه»
بازتعریف شد: فُرم حذف‌شده با `CanvasLetterbox.fadeColorArgb(canvasBackground)`
پر می‌شود — همان رنگی که میله‌های لتر‌باکس سفارشی می‌شوند (تک‌منبع
رنگ؛ پازیراه آلفا ساخته نمی‌شود). بوم پیش‌فرض: مشکی؛ بوم رنگ‌ی: همان
سواچ کاربر؛ بوم «تار»: مشکی (همان حفره‌ی مستند CanvasBackgroundOverlay).

**اجرا (`runDefaultBackgroundMode`):** در merge پس از اجرای تازه
(`EditorViewModel.onAiToolSuccess`)، اگر حالت پس‌زمینه هنوز پیش‌فرضِ
دست‌نخوردهٔ `ORIGINAL` باشد، دیگر `TRANSPARENT` ذخیره می‌شود؛ حالت‌های
دلخواهِ کاربر (تار/رنگ/عکس/ویدیو — و انتخاب صریح «اصلی») دست‌نخورده
می‌مانند… پنل هم چیپ «حذف» را ردیف اول گذاشت و هشدار صادقانه دارد که
پیش‌نمایشِ زنده (ExoPlayer) حذف را نشان نمی‌دهد و نتیجه در خروجی دیده
می‌شود.

**مهاجرت:** پروژه‌های قدیمی با `mattingBgMode=ORIGINAL` ساده بارگذاری
می‌شوند و export مشابه قبل pass-through است؛ با هر اجرای مجدد دکمه،
حالت به `TRANSPARENT` مبادله می‌شود. `MattingSettingsTest`
(`runDefaultBackgroundMode`, `fadeColorArgb`) و
`AiToolsTrajectoryRoundtripTest` (پین`TRANSPARENT`) + تست ساختاری
`AiToolStateWiringTest` گارانتی‌اند.
