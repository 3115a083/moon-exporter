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
- Branch: `revision/1.0.10-readest-highlights-finished`
- PR: #23, open and not merged
- Version: `1.0.10`, versionCode 12
- Tested app-code head: `05211c62d5600cbdd6d87b078b87787264a713df`
- minSdk 26, targetSdk 35, compileSdk 35
- Android CI run `34765408400`: success
- CodeQL run `34765408386`: success
- Debug artifact: `MoonExporter-1.0.10-debug`, artifact ID `10320725510`
- Verified APK SHA256: `85f5119185ba07e6ec031d67dd286912c617caffcfd23eecc38907a4f30ea5fc`
- Documentation commits after the tested app-code head do not change app behavior.

## 1.0.10 Readest highlight fix
1.0.9 generated no direct Readest annotations on the user's real test book. The likely failure path was strict XML parsing of EPUB XHTML. Real EPUB content can contain browser-tolerated entities or markup that Readest accepts but a strict Java XML DOM parser rejects.

1.0.10 behavior:
- EPUB content documents are parsed with bounded tolerant Jsoup XHTML/XML parsing with HTML fallback.
- Readest/Foliate CFI child-node indexing is still reproduced for exact range CFIs.
- Highlight text matching normalizes non-breaking spaces, smart quotes, dash variants and soft hyphens while final CFI offsets still refer to actual source DOM text nodes.
- Moon+ chapter data remains the preferred spine hint.
- If a text cannot be resolved safely, no direct Readest annotation position is invented. The original annotation remains in `moon-export.mrexpt`.
- Native/unrelated Readest notes remain preserved.
- Unit coverage includes real-world-like XHTML using `&nbsp;` plus inline markup inside a highlight.
- Readest annotation `cfi` is the required anchor; `xpointer0`/`xpointer1` remain optional and are not fabricated.

## 1.0.10 finished status
Readest has a separate library reading status. A progress tuple of `[100,100]` alone is not equivalent to a finished book.

1.0.10 behavior:
- An explicit Moon+ reading percentage of 100% maps to Readest `readingStatus: "finished"`.
- `readingStatusUpdatedAt` is written using the Moon timestamp when available, otherwise the export time.
- Matching against the just-written Readest library uses ISBN first, then unique normalized title/author.
- Ambiguous matches are not guessed and are reported in the final export status.
- This status pass does not reopen or rehash the ebook, preserving the 1.0.9 performance improvement.

## 1.0.9 retained export performance and progress UI
- A book embedded in `.mrpro` is prepared from the source only once per export.
- Hashing, EPUB metadata inspection, highlight resolution and target copying reuse one temporary local book file.
- Temporary ebook files are deleted immediately after each book.
- Book copying uses buffered I/O and one open EPUB ZIP is reused during highlight resolution.
- The dedicated bottom section `4. Exportfortschritt` shows determinate progress and named phases for every book.
- The A-Z rail down arrow reaches the bottom progress section.

## Direct Readest structure
- `Readest/Books/library.json` is the local library index.
- Managed books live under `Readest/Books/<Readest bookHash>/`.
- Moon Exporter may write EPUB/PDF, `cover.png`, `config.json`, and `moon-export.mrexpt` fallback.
- `nav.json` is a derived Readest cache and is not fabricated.
- Readest `bookHash` uses Readest's own partialMD5 sampling and is distinct from KOReader/KOSync partialMD5.
- Existing `library.json` and per-book `config.json` are merged and backed up before the first Moon Exporter modification.
- Existing book files with the same Readest hash are not duplicated.
- Percentage progress is a transparent fallback, not a real Readest page count.
- Do not fabricate Readest XPointer fields. Add them only if they can be generated exactly according to Readest's transformed document model.

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

## Still incomplete / next priorities
1. Real-device test 1.0.10 against the same book: verify visible highlights, exact click navigation and `finished` status for 100%.
2. If real highlights still fail, surface safe per-book resolved/unresolved annotation counts without logging book text or paths.
3. Improve exact Readest reading-resume translation beyond percentage/chapter fallback.
4. Add explicit position-quality model `EXACT`, `FALLBACK`, `UNRESOLVED` in data model/UI.
5. Harden CWA/BookLore/generic KOSync real error handling and per-book results.
6. Complete localization/responsive UI and final release/signing/security audit.
7. WebDAV only after the above is stable.

## Development rule
Future sessions must read this file, `CHANGELOG.md` and the private handoff before changing scope. Do not merge open revision PRs without explicit user approval.
