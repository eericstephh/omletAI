# persian-ui-ux-agent

A Persian-first UI/UX agent kit for [OpenCode](https://opencode.ai): one agent, one audit command and seven focused skills that help you build and review Persian (Farsi) and right-to-left interfaces with evidence instead of guesswork.

## معرفی به فارسی

**persian-ui-ux-agent** مجموعه‌ای از یک ایجنت، یک دستور و هفت مهارت برای [OpenCode](https://opencode.ai) است که کیفیت رابط‌های فارسی را بالا می‌برد: چیدمان راست‌به‌چپ، تایپوگرافی فارسی، نگارش تجربه کاربری، بومی‌سازی تاریخ و عدد، متن‌های ترکیبی فارسی و انگلیسی و دسترس‌پذیری.

- ایجنت `Persian-UI-Specialist` پروژه را بررسی می‌کند و بر پایه شواهد پیشنهاد می‌دهد؛ به‌صورت پیش‌فرض چیزی را تغییر نمی‌دهد.
- دستور `/persian-ui-audit` یک ممیزی فقط‌خواندنی با امتیاز ۰ تا ۵ و گزارش «موفق / ناموفق / اجرا نشده» تولید می‌کند.
- هفت مهارت جداگانه برای هر حوزه در `.opencode/skills/` قرار دارند.

شروع سریع: پوشه `.opencode/` را طبق [راهنمای نصب](docs/INSTALLATION.md) در پروژه خود ادغام کنید، OpenCode را اجرا کنید و بنویسید: `/persian-ui-audit`.

> وضعیت راستی‌آزمایی: کشف شدن ایجنت، دستور و مهارت‌ها در OpenCode نسخه 1.18.35 آزموده شده است، اما اجرای واقعی با یک مدل هنوز آزموده نشده است. جزئیات در [COMPATIBILITY.md](docs/COMPATIBILITY.md).

## What it provides

- **Agent `Persian-UI-Specialist`** - a senior product-designer/UI-engineer persona. It inspects the project first, respects existing architecture, ranks issues by severity, separates **Verified** findings from **Hypotheses**, proposes a plan before large changes, and reports exactly what it ran. Edits require approval (`edit: ask`).
- **Command `/persian-ui-audit`** - a read-only audit with 0-5 scores (RTL layout, typography, writing quality, localization formatting, mixed-direction text, accessibility, test coverage), severity-grouped findings with file paths, top-five improvements, a staged remediation plan, and a check log split into Passed / Failed / Not run.
- **Seven skills** (loaded on demand through OpenCode's `skill` tool):

| Skill | Covers |
|---|---|
| `persian-rtl-layout` | `lang`/`dir`, logical CSS, flex/grid, icon mirroring, focus order, scrolling, mobile |
| `persian-typography` | glyph coverage, font licensing, fallbacks, line-height, no letter-spacing, clipping, font performance |
| `persian-ux-writing` | ی/ک hygiene, نیم‌فاصله, tone, terminology, errors, empty states, plurals |
| `persian-localization` | Jalali/Gregorian, digits, Intl, rial vs toman, week/time zone, input parsing, canonical data |
| `bidirectional-content` | URLs, emails, phones, IDs, versions, `dir`/`<bdi>`/isolates, safe handling of bidi controls |
| `persian-ui-quality` | tokens, hierarchy, state matrix, responsive, performance, restrained glassmorphism |
| `persian-accessibility` | names, forms/errors, RTL keyboard behavior, contrast, targets, zoom, reduced motion |

## Repository structure

```text
persian-ui-ux-agent/
├── .opencode/
│   ├── agents/Persian-UI-Specialist.md
│   ├── commands/persian-ui-audit.md
│   └── skills/<seven skills>/SKILL.md
├── docs/            INSTALLATION, COMPATIBILITY, TROUBLESHOOTING, DESIGN-CHECKLIST, CONTRIBUTING, RELEASE
├── scripts/         validate_repo.py, check_opencode_discovery.py
├── tests/           test_repo_structure.py
├── .github/workflows/validate.yml
└── README.md  CHANGELOG.md  LICENSE  SECURITY.md  .gitignore
```

The `.opencode/` directory is what you copy into your own project. If your project does not have one, create it while merging; it is not required to exist beforehand.

## Requirements

- OpenCode (verified with **1.18.35**; see [COMPATIBILITY.md](docs/COMPATIBILITY.md)).
- To run the repository's own checks: Python 3 (tested with 3.12; CI uses 3.12). No packages needed.

## Installation

Short version (details, Windows PowerShell, global install and conflict handling in [docs/INSTALLATION.md](docs/INSTALLATION.md)):

```bash
SRC="/path/to/persian-ui-ux-agent/.opencode"
DST="/path/to/your-app/.opencode"
mkdir -p "$DST"
( cd "$SRC" && find . -type f | while IFS= read -r f; do
    if [ -e "$DST/$f" ]; then echo "skip (exists): $f"
    else mkdir -p "$DST/$(dirname "$f")" && cp "$f" "$DST/$f"; fi
  done )
```

This never overwrites existing files. Then confirm OpenCode sees the assets:

```bash
opencode agent list          # Persian-UI-Specialist
opencode debug skill         # seven skills
opencode debug config        # command: persian-ui-audit
```

## Usage

- Switch agent with <kbd>Tab</kbd> or mention `@Persian-UI-Specialist`.
- Run `/persian-ui-audit` for the whole project, or give a focus: `/persian-ui-audit checkout flow`.
- Implementation happens only after you ask for it, e.g. "fix the High findings in the settings page".

### Example prompts

English:

- `/persian-ui-audit`
- "Review the signup form for Persian/RTL problems and list them by severity. Don't change any files."
- "Our price column shows mixed digits and Latin separators. Propose a localization policy and the smallest code change."
- "Implement the RTL fixes for the navbar and drawer using logical properties; run the existing tests afterwards."

Persian:

- `/persian-ui-audit صفحه ثبت‌نام و تنظیمات`
- «فرم ورود را از نظر چیدمان راست‌به‌چپ و نگارش فارسی بررسی کن و فقط گزارش بده، چیزی را تغییر نده.»
- «تاریخ‌ها در جدول سفارش‌ها میلادی و با ارقام لاتین نمایش داده می‌شوند؛ سیاست محلی‌سازی مناسب را پیشنهاد بده و قبل از تغییر از من بپرس.»

## Validation

```bash
python scripts/validate_repo.py                 # static: structure, frontmatter shape, links, secrets heuristic
python -m unittest discover -s tests -v         # unit tests for the validator
python scripts/check_opencode_discovery.py      # runtime: asks a real OpenCode what it discovers (exit 2 = blocked)
```

The first two are **static** checks. They do not prove OpenCode discovers the files; the third does, for the OpenCode on your machine. None of them test model behavior.

## Compatibility status

| Area | Status |
|---|---|
| Discovery of agent, command, 7 skills (project install, OpenCode 1.18.35, Linux) | Verified |
| Discovery from user-level config (Linux, isolated HOME) | Verified |
| Executing the agent/command with a model | **Not verified** |
| Other OpenCode versions, Windows, macOS | **Not verified** |
| GitHub Actions workflow | **Not executed** |

Full evidence and commands: [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).

## Known limitations

- Skills give guidance; they cannot render a UI. Visual findings the agent cannot see are labeled as hypotheses and need a browser check.
- Screen-reader behavior with Persian voices depends on OS and assistive technology and is not covered by automated checks.
- No font is recommended or vetted; license checks are part of the typography skill's procedure.
- The validator is not a YAML parser.
- Read-only behavior of the audit is enforced by the prompt plus `edit: ask`, not by a hard deny; set `edit: deny` for a hard guarantee.

## Documentation

[Installation](docs/INSTALLATION.md) · [Compatibility](docs/COMPATIBILITY.md) · [Troubleshooting](docs/TROUBLESHOOTING.md) · [Design checklist](docs/DESIGN-CHECKLIST.md) · [Contributing](docs/CONTRIBUTING.md) · [Publishing](docs/RELEASE.md) · [Security](SECURITY.md) · [Changelog](CHANGELOG.md)

## Contributing

See [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md). Run the validator and tests before opening a pull request.

## License

[MIT](LICENSE)
