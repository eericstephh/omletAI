# WorkBuddy Clone — AICreatorStudio (.opencode)

کلون کامل اسکیل‌ها، ایجنت‌ها و ابزارهای `.opencode` پروژهٔ AICreatorStudio به **فرمت WorkBuddy**.

منبع: `G:\projects\edit\New folder (2)\AICreatorStudio\.opencode`
تاریخ کلون: ۲۰۲۶-۱۰-۲۸

## چی کلون شد؟

| نوع | تعداد | مقصد نصب (WorkBuddy) |
|---|---|---|
| Skills (اسکیل‌های اصلی) | ۵۷ | `C:\Users\eric steph\.workbuddy-ai\skills\<name>\SKILL.md` |
| Commands → Skills (کامندها به‌صورت اسکیل) | ۱۱ | `C:\Users\eric steph\.workbuddy-ai\skills\<command-name>\SKILL.md` |
| Agents (ایجنت‌ها) | ۱۴ | `C:\Users\eric steph\.workbuddy-ai\agents\<name>.md` |
| Tools (پلاگین/داشبورد/state) | ۳ پوشه | فقط در همین بسته — `tools/` (مخصوص OpenCode است و در WorkBuddy اجرا نمی‌شود) |

## تغییرات اعمال‌شده در تبدیل

### Skills (۵۷ + ۱۱ کامند = ۶۸ اسکیل)
- فرمت `SKILL.md` با frontmatter `name` + `description` — دقیقاً همان استاندارد WorkBuddy.
- فیلد `compatibility: opencode` حذف شد.
- کامندهای OpenCode (پوشهٔ `commands/`) به اسکیل تبدیل شدند چون WorkBuddy اسکیل را با Skill tool فراخوانی می‌کند؛ داخل هرکدام یک یادداشت «WorkBuddy note» اضافه شده.

### Agents (۱۴)
- فرمت: markdown با frontmatter `name`، `description` و در صورت نیاز `tools: [...]`.
- فیلدهای مخصوص OpenCode حذف شدند: `mode` (primary/subagent)، `model: opencode/deepseek-...`، `temperature` — ایجنت در WorkBuddy از مدل پیش‌فرض استفاده می‌کند.
- محدودیت‌های ابزار به فرمت whitelist تبدیل شد:
  - `orchestrator` (bash:false) → بدون Bash
  - `planner` (bash+edit:false) → فقط خواندن + Write
  - `reviewer` (bash+edit+write:false) → فقط‌خواندنی (Read/Glob/Grep/WebFetch/WebSearch)
  - `tester` (edit+write:false) → بدون Edit/Write
  - بقیهٔ ۱۰ ایجنت → دسترسی کامل
- **نکته:** اگر WorkBuddy پوشهٔ `~/.workbuddy-ai/agents/` را به‌عنوان ایجنت کاربر تشخیص نداد، همین فایل‌ها را داخل `<پروژه>/.codebuddy/agents/` هر پروژه کپی کنید (مسیر رسمی custom agents).

## لیست اسکیل‌های نصب‌شده

**طراحی و UX (۲۹):** accessibility · color-systems · component-design · content-design · dark-mode · dashboard-data-density · data-visualization · design-critique · design-evaluation · design-handoff · design-systems · design-tokens · ecommerce-conversion · empty-loading-error · forms-data-tables · forms-validation · icons-imagery · information-architecture · interaction-design · journey-mapping · landing-pages · layout-grid-spacing · microinteractions-motion · mobile-editor-workflows · mobile-ux · navigation-patterns · notifications-feedback · onboarding · premium-product-design ...

**فارسی و RTL (۸):** persian-accessibility · persian-localization · persian-rtl-android · persian-rtl-layout · persian-typography · persian-ui-quality · persian-ux-writing · rtl-localization

**تحقیق کاربر (۶):** ux-research · usability-heuristics · usability-testing · user-goals-jtbd · task-flows · product-framing

**پیاده‌سازی و کیفیت (۱۴):** android-build · frontend-implementation · performance-aware-ui · responsive-design · visual-hierarchy · visual-regression · web-saas · game-ui · wireframing · pricing-subscription-ux · search-filter-sort · trust-privacy-security-ux · bidirectional-content · typography

**کامندها (۱۱):** component · component-library · critique · design · design-system · persian-ui-audit · redesign · responsive-review · ui-audit · verify · visual-qa

## لیست ایجنت‌های نصب‌شده (۱۴)

Persian-UI-Specialist · coder · design-system-architect · fixer · frontend-engineer · orchestrator · planner · reviewer · tester · ui-reviewer · ui-ux · ux-researcher · visual-designer · visual-qa

## tools/ (کپی امن — مخصوص OpenCode)

- `plugin/progress.ts` — پلاگین نمایش پیشرفت OpenCode (در WorkBuddy معادل ندارد؛ منطقش را می‌توان به hook تبدیل کرد)
- `dashboard/` — داشبورد محلی (`serve.mjs` + `index.html`) — مستقل از OpenCode است، با `node serve.mjs` اجرا می‌شود
- `state/progress.json` — وضعیت pipeline

## نحوهٔ استفاده

1. WorkBuddy را ری‌استارت کنید (یا session جدید باز کنید) تا اسکیل‌های جدید در لیست ظاهر شوند.
2. اسکیل‌ها به‌صورت خودکار وقتی موضوع مرتبط باشد فراخوانی می‌شوند؛ یا صریح بگویید «از اسکیل persian-rtl-layout استفاده کن».
3. ایجنت‌ها (اگر مسیر agents پشتیبانی شود) با Agent tool قابل انتخاب‌اند؛ در غیر این صورت فایل را به `.codebuddy/agents/` پروژه کپی کنید.
