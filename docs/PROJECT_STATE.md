# Moon Exporter project state

Updated: 2026-09-13

## Product scope
Moon Exporter is a local Android migration tool for Moon+ Reader data.

Binding flow:
1. Analyze one Moon+ Reader backup, especially `.mrpro`.
2. Review analyzed books, reading progress and markings, then select individual books.
3. Choose Readest, generic KOSync, Calibre-Web Automated or BookLore as destination.
4. Export/transfer the selected books and show transfer progress at the bottom.

WebDAV remains deferred until this local flow is reliable.

## Current revision
- Repository: `3115a083/moon-exporter`
- Branch: `revision/1.0.10-readest-marks-finished`
- PR: #22, open and not merged
- Version: `1.0.10`, versionCode 12
- Tested app-code head: `50dbf63c56277a8ebe0f75d28b536dcb9037cbdf`
- minSdk 26, targetSdk 35, compileSdk 35
- Android CI run `34765812351`: success
- CodeQL run `34765812355`: success
- Debug artifact: `MoonExporter-1.0.10-debug`, artifact ID `10320251587`
- Artifact ZIP digest: `sha256:d58c48de33f1743399346da31dcac5515692d4223bdae4787567843055023d94`
- Verified APK SHA256: `e784a81febfe282be623c290b6d436d898dba3c45949ff29a6384c2f94503078`
- Documentation-only commits after this tested app-code head do not change app behavior.

## 1.0.10 Readest annotation fixes
Real-device feedback after 1.0.9 showed that Moon+ annotations could disappear completely.

Two failure modes are addressed together:
- Existing Moon Exporter Readest notes are no longer deleted before an exact replacement exists.
- Real-world EPUB XHTML is parsed tolerantly with bounded Jsoup XHTML/XML parsing and HTML fallback instead of the previous strict Java XML DOM parser.

Binding behavior:
- Existing Readest `booknotes` are preserved by stable ID.
- A Moon Exporter note is replaced only when a new exact EPUB range CFI has successfully been resolved.
- Native/unrelated Readest notes are never removed.
- On a clean direct export, when exact text-range resolution fails but the Moon chapter/spine is safe, a chapter-fallback Readest note is retained rather than dropping the annotation entirely.
- Exact range CFI always takes precedence when resolution succeeds.
- Original annotation data remains additionally preserved in `moon-export.mrexpt`.
- Text matching normalizes non-breaking spaces, smart quotes, dash variants and soft hyphens while final CFI offsets remain based on the actual EPUB DOM.
- Unit coverage includes a real-world-like XHTML case with `&nbsp;` and inline markup inside a highlight.
- Do not invent Readest XPointer fields. Current Readest annotation data can use the CFI as the authoritative range anchor.

## 1.0.10 finished reading status
Readest uses an explicit library status in addition to progress.

Binding behavior:
- Moon+ progress >= 99.95% writes `progress: [100,100]` and `readingStatus: "finished"` to the already identified Readest library row.
- `readingStatusUpdatedAt` is set when finished status is written.
- Moon+ progress below the finish threshold does not forcibly clear an existing Readest reading status.
- Do not rely on `[100,100]` alone for Readest's Finished/Beendet state.

## Retained Readest export performance and UI
- An ebook embedded in `.mrpro` is prepared only once per export. Hashing, EPUB metadata inspection, highlight resolution and target copying reuse one temporary local book file.
- Temporary ebook files are deleted immediately in `finally`; no durable ebook cache is introduced.
- Highlight resolution reuses one open EPUB ZIP per book.
- Local export progress is shown in the dedicated bottom section `4. Exportfortschritt`.
- Direct Readest export reports named per-book phases and determinate progress.
- The A-Z rail down arrow reaches the final progress section.

## Direct Readest structure
- `Readest/Books/library.json` is the local library index.
- Managed books live under `Readest/Books/<Readest bookHash>/`.
- Moon Exporter may write EPUB/PDF, `cover.png`, `config.json`, and `moon-export.mrexpt` fallback.
- `nav.json` is a derived Readest cache and is not fabricated.
- Readest `bookHash` uses Readest's own partialMD5 sampling and is distinct from KOReader/KOSync partialMD5.
- Existing `library.json` and per-book `config.json` are merged and backed up before the first Moon Exporter modification.
- Existing book files with the same Readest hash are not duplicated.
- Percentage progress is a transparent fallback, not a real Readest page count.

## Cryptic Moon+ filenames
- Numeric/hash-like Moon+ filenames are technical identifiers, not trustworthy titles.
- Readest direct export offers optional normalized output filenames reconstructed from EPUB metadata first, then recovered Moon+ database/backup metadata.
- Original Moon+ backup and ebook files are never renamed or modified.

## Moon+ reading progress
- Full `.mrpro` backups recover progress primarily from `shared_prefs/positions10.xml` SharedPreferences entries.
- EPUB positions commonly use `chapter@section#characterOffset:percent%`; PDF uses `page:percent%`.
- Timestamped `.po` remains a fallback for other Moon+ variants.
- Original raw positions are always preserved.

## KOSync/CWA/BookLore
- Transfer authentication, document identity and reading progress only. Ebook files are never uploaded through these APIs.
- KOReader-compatible `partialMD5` identifies a book already present at the target.
- Moon+ percent `0..100` is normalized to KOSync `percentage` `0..1`.
- HTTP is allowed only for private/local home-network targets; public cleartext HTTP remains blocked.
- Never invent KOReader XPointer values.

## Security and privacy
- No telemetry, analytics, ads or cloud crash reporting.
- No real user backup, Readest or ebook data in source, tests, logs or CI artifacts.
- SAF only for user files. No broad storage permissions.
- `android:allowBackup="false"`.
- No credential/Auth-header logging.
- Temporary export ebooks must be removed after use.

## Next real-device checks
1. Re-export the same Readest book that lost annotations in 1.0.9.
2. Verify annotations appear in Readest and exact-range annotations are visibly highlighted and navigate to their real text.
3. Verify a Moon+ 100% book appears as Readest Finished/Beendet rather than only 100%.
4. If exact matching still fails for some annotations, surface safe exact/fallback/unresolved counts without logging annotation text or private paths.
5. Continue structural reading-resume conversion and KOSync/CWA/BookLore hardening after these Readest paths are stable.

## Development rule
Future sessions must read this file, `CHANGELOG.md` and the private handoff before changing scope. PR #22 is authoritative for 1.0.10. PR #23 was a duplicate and is closed unmerged. Do not merge revision PRs without explicit user approval.
