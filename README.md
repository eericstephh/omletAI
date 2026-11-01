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

**Omelet AI (املت AI)** is a complete video editor for Android that puts AI tools right next to a professional timeline, so editing, subtitling and enhancing videos becomes fast and simple. The interface is fully Persian and right-to-left, and most of its AI features run **offline, entirely on the device**.

> This project was developed with the help of AI.

## Download

Get the latest release (v1.1) from the [Releases](https://github.com/eericstephh/omletAI/releases) page:

| | |
|---|---|
| File | `AICreatorStudio-v1.1-release.apk` |
| Android | 7.0 and up (API 24+) |
| Size | ~95 MB |

Install: open the APK; if you see an "install from unknown sources" prompt, allow installs for your browser or file manager.

## AI features

| Tool | What it does |
|---|---|
| Smart subtitles | Speech-to-text Persian subtitles — fully offline (Whisper) or cloud |
| AI subtitle correction | Automatically fixes spelling and Persian-writing mistakes in subtitles |
| Smart OCR | Extracts text shown inside the video frame (Persian, Arabic, English) |
| Smart highlights | Auto-builds a short clip from the best moments of a video |
| Background removal | Separates the subject from the background — no green screen needed |
| Auto framing | Smart, automatic cropping of the video frame |
| Smart reframe | Convert landscape to portrait (and back) while keeping the subject in frame |
| Motion tracking | Follows a subject and attaches effects to its motion path |
| AI upscale | Up to 4× resolution enhancement (ESRGAN) |
| Voice clean | Removes background noise from speech |
| Voice enhance | Clarifies and balances spoken audio |
| Silence cut | Automatically removes silent segments |
| Beat sync | Syncs cuts to the rhythm of the music |

## Editor features

- Multi-track timeline with live preview
- Text & title overlays with custom fonts, filters and color effects
- Transitions, speed control, trim and rotation
- Independent audio lane, voice effects, pitch/speed changes
- Export from 480p up to 4K (2160p) with hardware acceleration
- Three color themes: Violet Night, Ocean, Crimson Sunset
- Interactive onboarding tour + in-app update notifications

## Offline & cloud AI

- **Offline (default):** the Whisper, MediaPipe, ESRGAN and Tesseract models are downloaded and executed inside the app itself — no video is ever sent off the device for AI processing.
- **Cloud (optional):** enter your own Groq API key in Settings to enable cloud transcription and AI subtitle correction. The key is stored encrypted (Android Keystore) on that device only. A step-by-step key setup guide is built into the app.

## Build from source

```bash
git clone https://github.com/eericstephh/omletAI.git
cd omletAI
./gradlew :app:assembleRelease
```

- Android Studio (recent version) + JDK 17
- compileSdk 35 / minSdk 24
- Kotlin + Jetpack Compose + Hilt + Media3

Run the tests:

```bash
./gradlew :app:testDebugUnitTest
```

## License

[MIT](LICENSE)
