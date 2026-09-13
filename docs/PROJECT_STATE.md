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
- Branch: `revision/1.0.9-readest-speed-annotations`
- PR: #21, open and not merged
- Version: `1.0.9`, versionCode 11
- Tested app-code head: `a2c0e95c83a18b23ce15bd9a3f555761e47c49fe`
- minSdk 26, targetSdk 35, compileSdk 35
- Android CI run `34763052900`: success
- CodeQL run `34763052880`: success
- Debug artifact: `MoonExporter-1.0.9-debug`, artifact ID `10319672483`
- Verified APK SHA256: `1ef495695eac655e89a55a4ef75d4d79601320e0854716c8266cff9fa260fc79`
- Documentation commits after the tested head do not change app code.

## 1.0.9 Readest export performance
- A book embedded in `.mrpro` is prepared from the source only once per export. Hashing, EPUB metadata inspection, highlight resolution and target copying reuse the same temporary local book file.
- This removes the 1.0.8 behavior that could rescan a large `.mrpro` from the beginning multiple times for one book.
- Temporary ebook files are deleted immediately in `finally`; no durable ebook cache is introduced.
- Book copy uses larger buffered I/O.
- Highlight resolution reuses one open EPUB ZIP per book.

## 1.0.9 export progress UI
- The local export progress bar is no longer shown in the backup-analysis card.
- A dedicated bottom section `4. Exportfortschritt` shows a determinate bar and current detailed phase.
- Direct Readest export reports seven stages per book: prepare source, calculate Readest ID, inspect EPUB, resolve highlights, copy ebook, write config, update library.
- The A-Z rail down arrow reaches the actual final list item including the progress section.

## 1.0.9 exact Readest highlights
1.0.8 wrote only chapter-start CFIs for direct annotations. Readest could list these notes but could not paint the selected text and clicking the note navigated only to the chapter start.

1.0.9 behavior:
- `ReadestCfiResolver` resolves `AnnotationRecord.original` against actual EPUB XHTML.
- Moon+ chapter data chooses the preferred EPUB spine section.
- Readest/Foliate child-node indexing semantics are reproduced for text chunks, virtual nodes, `cfi-inert`, `cfi-skip`, and text offsets.
- A successful match produces a real EPUB CFI range with start/end offsets.
- If identical text occurs multiple times, Moon+ source position is used to prefer the closest occurrence.
- No direct Readest note is generated if the text cannot be resolved safely. The annotation remains losslessly in `moon-export.mrexpt`.
- Old Moon Exporter-generated notes with the same stable IDs are replaced on re-export so incorrect 1.0.8 chapter-start entries do not remain alongside corrected notes.
- Native/unrelated Readest notes are preserved.
- XHTML parsing is bounded and external XML entities/DTDs are disabled.
- A synthetic EPUB unit test verifies an exact range CFI rather than chapter-only CFI.

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

## UI retained behavior
- Whole book cards toggle selection.
- Missing target ebook can be assigned manually through SAF without losing recovered Moon+ progress.
- A-Z fast rail is tappable/draggable and has top/bottom arrows.
- Filters include `Ohne Buchdatei`.
- Analysis runs as a non-exported foreground `dataSync` service with notification progress.
- System bars remain inset-safe and readable.

## Security and privacy
- No telemetry, analytics, ads or cloud crash reporting.
- No real user backup, Readest or ebook data in source, tests, logs or CI artifacts.
- SAF only for user files. No broad storage permissions.
- `android:allowBackup="false"`.
- No credential/Auth-header logging.
- Temporary export ebooks must be removed after use.

## Still incomplete / next priorities
1. Real-device test 1.0.9 against the same book used for 1.0.8, measuring export time and checking visible/clickable highlights.
2. Count and surface unresolved real annotations when XHTML/text differs from Moon+ source data; never invent a position.
3. Improve exact Readest reading-resume translation beyond percentage/chapter fallback.
4. Add explicit position-quality model `EXACT`, `FALLBACK`, `UNRESOLVED` in data model/UI.
5. Harden CWA/BookLore/generic KOSync real error handling and per-book results.
6. Complete localization/responsive UI and final release/signing/security audit.
7. WebDAV only after the above is stable.

## Development rule
Future sessions must read this file, `CHANGELOG.md` and the private handoff before changing scope. Do not merge open revision PRs without explicit user approval.
