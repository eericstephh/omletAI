فاز ۱ (P0) — پایداری هسته املت، بدون feature جدید:

1. فیکس باگ splitClip در viewmodel/TimelineDelegate.kt (کلیپ راست از trimStartMs=0 پخش می‌شود؛ کلیپ چپ طول غلط؛ اختلاط محور source/timeline) + گسترش TimelineDelegateTest تا محتوای دو نیمه را verify کند.
2. رفع threading در db/RoomProjectRepository.kt: حذف runBlocking از مسیر UI (:223) و داخل کوروتین IO (:235,249)، جایگزینی CoroutineScopeهای بدون owner با scope تزریق‌شده.
3. حذف fallbackToDestructiveMigration در di/AppModule.kt و اطمینان از پوشش migrationهای صریح 4→7 (با تست مایگریشن).
4. انتقال keystore و پسوردهای ریلیز از app/build.gradle.kts به local.properties (با fallback روشن و پیام خطا).
5. پخش پیوسته چندکلیپی در preview + اعمال trim روی preview در EditorScreen.kt (playhead روی زمان ترکیب، سوییچ خودکار بین کلیپ‌ها با ExoPlayer playlist و ClippingConfiguration) — بزرگ‌ترین فاصله محصولی.
6. فیکس اندازه کلیپ speed-dar در EditorTimeline.kt (استفاده از rampedDurationMs).
7. حذف undo-spam در trim drag (coalesce snapshots) و فیکس بیت‌ریت export در VideoExportEngine.kt (مصرف ExportQuality.videoBitrateBps).
8. پاکسازی: فایل‌های زباله روت (EditorScreen.kt قدیمی، track*.ps1، node_modules، مدیاهای تستی)، کد مرده (ViralStylePanel، IOSTheme/IOSType)، قوانین Proguard مرده.

قوانین اجرا:
- هر تغییر با تست همراه (واحد حداقل؛ برای playback تغییرات، بیلد + نصب روی emulator با اسکریپت android-dev و تست دستی).
- هیچ mock/placeholder جدید؛ هیچ بازنویسی معماری (تقسیم God classes فقط در فاز بعد).
- اجرای تست‌های موجود قبل و بعد از هر تغییر برای جلوگیری از رگرسیون.
- وابستگی‌های خارجی (مدل whisper 488MB، Worker ابری، VPN) بدون تغییر.

فاز ۲ (P1، پس از تأیید فاز ۱): waveform واقعی، filmstrip thumbnails، یکسان‌سازی مسیر ابری Whisper، تصمیم RTL/لوکالیزیشن، بیلد و تست ریلیز کامل روی دستگاه.

متریک موفقیت فاز ۱: split صحیح روی پروژه چندکلیپی، preview پیوسته از کلیپ اول تا آخر، هیچ runBlocking روی مسیر UI، تست‌های موجود سبز، APK ریلیز بدون secret در VCS-tree.