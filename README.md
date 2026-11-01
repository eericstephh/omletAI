# املت AI — استودیو ویرایش ویدیو با هوش مصنوعی

<div dir="rtl">

**املت AI** یک ویرایشگر ویدیوی کامل برای اندروید است که ابزارهای هوش مصنوعی را در کنار یک تایم‌لاین حرفه‌ای می‌گذارد تا ویرایش، زیرنویس و بهبود ویدیو سریع و ساده شود. رابط کاربری کاملاً فارسی و راست‌به‌چپ است و بیشتر قابلیت‌های هوش مصنوعی آن **آفلاین و روی خود گوشی** اجرا می‌شوند.

> این پروژه با کمک هوش مصنوعی توسعه یافته است.

## دانلود

آخرین نسخه (v1.1) را از بخش [Releases](https://github.com/eericstephh/omletAI/releases) دریافت کنید:

| | |
|---|---|
| فایل | `AICreatorStudio-v1.1-release.apk` |
| نسخه اندروید | Android 7.0 به بالا (API 24+) |
| حجم | حدود ۹۵ مگابایت |

نصب: فایل را باز کنید؛ اگر پیام «نصب از منابع ناشناس» آمد، اجازه نصب را به مرورگر یا فایل‌منیجر خود بدهید.

## قابلیت‌های هوش مصنوعی

| ابزار | کار |
|---|---|
| زیرنویس هوشمند | تبدیل گفتار به زیرنویس فارسی، به‌صورت آفلاین (Whisper) یا ابری |
| اصلاح هوشمند زیرنویس | اصلاح خودکار غلط‌های نگارشی و نگارش فارسی زیرنویس‌ها با هوش مصنوعی |
| متن‌یابی هوشمند | استخراج متن از داخل تصویر ویدیو (OCR فارسی، عربی، انگلیسی) |
| هایلایت هوشمند | ساخت کلیپ کوتاه از بهترین لحظات ویدیو |
| حذف پس‌زمینه | جداکردن سوژه از پس‌زمینه بدون گرین‌اسکرین |
| قاب‌بندی خودکار | برش خودکار و هوشمند کادر ویدیو |
| ری‌فریم هوشمند | تبدیل افقی به عمودی (و برعکس) با حفظ سوژه در کادر |
| ردیابی حرکت | دنبال‌کردن سوژه و متصل‌کردن افکت به مسیر حرکت |
| افزایش کیفیت | بهبود رزولوشن تا ۴ برابر (ESRGAN) |
| پاک‌سازی صدا | حذف نویز پس‌زمینه از گفتار |
| بهبود صدا | واضح‌سازی و تعادل صدای گفتار |
| برش سکوت | حذف خودکار بخش‌های بی‌صدا |
| سینک ضرب | هم‌زمان‌سازی برش‌ها با ریتم موسیقی |

## امکانات ویرایشگر

- تایم‌لاین چندلایه با پیش‌نمایش زنده
- متن و تیتراژ با فونت دلخواه، فیلتر و افکت رنگی
- ترنزیشن، تغییر سرعت، تریم و چرخش
- لِین صدا مستقل، افکت صوتی و تغییر پیچ/سرعت گفتار
- خروجی از 480p تا 4K (2160p) با شتاب‌دهی سخت‌افزاری
- سه تم رنگی: شب بنفش، اقیانوس، غروب سرخ
- آموزش تعاملی شروع به کار + پنل اعلان نسخه جدید

## هوش مصنوعی آفلاین و ابری

- **آفلاین (پیش‌فرض):** مدل‌های Whisper، MediaPipe، ESRGAN و Tesseract داخل خود اپلیکیشن دانلود و اجرا می‌شوند؛ هیچ ویدیویی برای پردازش هوش مصنوعی از گوشی خارج نمی‌شود.
- **ابری (اختیاری):** با وارد‌کردن کلید API شخصی Groq در تنظیمات، زیرنویس‌گذاری و اصلاح زیرنویس ابری فعال می‌شود. کلید فقط به‌صورت رمزنگاری‌شده (Android Keystore) روی همان دستگاه ذخیره می‌شود. راهنمای گام‌به‌گام دریافت کلید داخل خود اپلیکیشن قرار دارد.

## ساخت از سورس

```bash
git clone https://github.com/eericstephh/omletAI.git
cd omletAI
./gradlew :app:assembleRelease
```

- Android Studio (نسخه جدید) + JDK 17
- compileSdk 35 / minSdk 24
- Kotlin + Jetpack Compose + Hilt + Media3

تست‌ها:

```bash
./gradlew :app:testDebugUnitTest
```

## مجوز

[MIT](LICENSE)

</div>

---

# Omelet AI — AI Video Editing Studio (English)

**Omelet AI (املت AI)** is a full video editor for Android with on-device AI tools built around a professional timeline. Fully Persian, right-to-left interface; most AI features run **offline on the device** — no video ever leaves your phone.

- **12 AI tools:** smart Persian subtitles (offline Whisper or cloud), AI subtitle correction, in-video OCR (fa/ar/en), smart highlights, background removal, auto-framing & reframe, motion tracking, 4× AI upscale (ESRGAN), voice clean & enhance, silence cutting, beat-sync editing
- **Full editor:** multi-track timeline, text overlays, filters, transitions, speed control, independent audio lanes, export from 480p up to 4K with hardware acceleration
- **Optional cloud:** bring your own Groq API key (stored encrypted in the Android Keystore) for cloud transcription — a step-by-step guide is built into the app
- **Three dark themes**, interactive onboarding, in-app update notifications

**Download:** grab the latest APK from [Releases](https://github.com/eericstephh/omletAI/releases) (requires Android 7.0+).

**Build:** Android Studio, JDK 17, `./gradlew :app:assembleRelease` — Kotlin · Jetpack Compose · Hilt · Media3.

License: [MIT](LICENSE)
