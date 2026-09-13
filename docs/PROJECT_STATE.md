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
- minSdk 26, targetSdk 35, compileSdk 35
- Final CI/CodeQL IDs and APK hash must be filled from the successful final branch head before delivery.

## 1.0.10 Readest annotation merge rule
Real-device feedback showed that 1.0.9 could make Moon+ annotations disappear completely. The cause was merge order: existing Moon Exporter note IDs were removed before an exact replacement range had actually been resolved.

Binding 1.0.10 behavior:
- Existing Readest `booknotes` are preserved by stable ID.
- A Moon Exporter note is replaced only when a new exact EPUB range CFI has successfully been resolved.
- Native/unrelated Readest notes are never removed.
- On a clean direct export, when an annotation cannot be resolved to an exact text range but its Moon chapter/spine can be mapped, a Readest chapter-fallback note is still written so the annotation does not disappear.
- Exact range CFI continues to take precedence over fallback when resolution succeeds.
- Original annotation data remains additionally preserved in `moon-export.mrexpt`.
- The export report distinguishes exact mappings from retained/fallback mappings.

## 1.0.10 finished reading status
Readest uses an explicit library status in addition to progress.

Binding behavior:
- Moon+ progress at 100% writes `progress: [100,100]` and `readingStatus: "finished"` to the Readest `library.json` book row.
- `readingStatusUpdatedAt` is set when the finished status is written.
- Moon+ progress below 100% does not forcibly clear an existing Readest reading status.
- Do not rely on `[100,100]` alone for the Readest "Finished" badge.

## Retained 1.0.9 Readest export performance
- A book embedded in `.mrpro` is prepared from the source only once per export. Hashing, EPUB metadata inspection, highlight resolution and target copying reuse the same temporary local book file.
- Temporary ebook files are deleted immediately in `finally`; no durable ebook cache is introduced.
- Book copy uses larger buffered I/O.
- Highlight resolution reuses one open EPUB ZIP per book.

## Retained export progress UI
- Local export progress is shown in a dedicated bottom section `4. Exportfortschritt`.
- Direct Readest export reports seven stages per book: prepare source, calculate Readest ID, inspect EPUB, resolve highlights, copy ebook, write config, update library.
- The A-Z rail down arrow reaches the final list item including the progress section.

## Exact Readest highlights
- `ReadestCfiResolver` resolves `AnnotationRecord.original` against actual EPUB XHTML.
- Moon+ chapter data chooses the preferred EPUB spine section.
- Readest/Foliate child-node indexing semantics are reproduced for text chunks, virtual nodes, `cfi-inert`, `cfi-skip`, and text offsets.
- A successful match produces a real EPUB CFI range with start/end offsets.
- If identical text occurs multiple times, Moon+ source position is used to prefer the closest occurrence.
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
1. Real-device test 1.0.10 against a Readest folder previously affected by 1.0.9, checking that annotations no longer disappear.
2. Verify how many real annotations resolve to exact visible ranges versus chapter fallback and improve tolerant EPUB text matching where needed.
3. Verify 100% Moon+ books render as Readest `Finished`, not just `100%`.
4. Improve exact Readest reading-resume translation beyond percentage/chapter fallback.
5. Add explicit position-quality model `EXACT`, `FALLBACK`, `UNRESOLVED` in data model/UI.
6. Harden CWA/BookLore/generic KOSync real error handling and per-book results.
7. Complete localization/responsive UI and final release/signing/security audit.
8. WebDAV only after the above is stable.

## Development rule
Future sessions must read this file, `CHANGELOG.md` and the private handoff before changing scope. Do not merge open revision PRs without explicit user approval.
