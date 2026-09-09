# Moon Exporter

Moon Exporter is a small Android app for migrating Moon+ Reader reading data to modern reader stacks.

It scans a Moon+ Reader folder or a WebDAV save folder, shows every detected book, lets you correct names and match EPUB files, then writes a clean export bundle for Readest, KOReader-compatible sync servers, Calibre-Web Automated and BookLore-style workflows.

The app is intentionally local-only. It has no network permission and never uploads books or reading data.

## What it reads

Point the app at the folder that contains Moon+ Reader state files. Common sources are:

- `/Books/.Moon+/...` on Android devices where Moon+ can expose it through the system picker
- a WebDAV sync folder copied from your server
- a folder restored from a Moon+ backup

Moon Exporter looks for:

- `.po` files, Moon+ reading positions
- `.an` files, Moon+ compressed annotations
- `_names.list` and `recent.list`, optional title hints

Moon+ `.po` positions usually look like this:

```text
1703297605115*21@0#4826:11.1%
```

Moon Exporter preserves the timestamp, chapter or page, section, character offset and percentage.

Moon+ `.an` files are decompressed into `.mrexpt` text files. Readest can import these directly.

## What the app shows

The main screen lists all detected books with:

- cover image, when a matching EPUB is selected
- current name
- reading progress percentage
- annotation count
- hash-file warning for cryptic imported names such as `1f328bd7cdae70336ea4f03ca7b673e5.epub`
- EPUB match status

You can edit the displayed title before exporting. This does not modify the source folder.

## Matching EPUB files

Use **EPUBs matchen** to select one or more EPUB files.

Moon Exporter extracts:

- EPUB title from the OPF package document
- cover image when present
- KOReader `partialMD5` document id

The match is used for covers and for KOReader/CWA/BookLore-compatible progress exports.

## Export bundle

The app writes one ZIP file containing:

```text
README.txt
all_reading_positions.csv
cryptic_books_manual_match.csv
migration_manifest.json
kosync_progress.json
mrexpt/<book>.mrexpt
readest-progress/<book>.readest-annotations.json
recovered-hash-books/<book>.mrexpt
recovered-hash-books/<book>.epub.po
```

### Readest annotation import

For each book:

1. Open the EPUB in Readest.
2. Open the annotation import dialog.
3. Choose **Moon+ Reader**.
4. Select the matching file from `mrexpt/`.

Readest resolves Moon+ highlights and notes against the book text and stores them as native Readest annotations.

### Readest progress import

`readest-progress/` contains Readest annotation JSON files with a bookmark and location candidate derived from Moon+ `.po` data.

For EPUB files this is conservative. The app writes a chapter-start EPUB CFI, not a fabricated exact text offset. This avoids silently creating wrong positions when the original EPUB edition differs.

If exact Moon+ offset to Readest CFI migration is required, use the same source EPUB and a DOM-aware converter. Moon Exporter keeps all raw `.po` fields so that exact conversion can be added later.

### KOReader, CWA and BookLore-compatible export

`kosync_progress.json` contains percentage-based progress rows:

```json
{
  "document": "KOReader partialMD5",
  "title": "Book title",
  "percentage": 0.421,
  "progress": "42.10%",
  "device": "Moon Exporter",
  "timestamp": 1703297605
}
```

`document` is available only after the matching EPUB has been selected. It is the same identifier KOReader and Calibre-Web Automated use for KOSync.

This file is meant for import tools, server-side scripts or future native importers. It does not contain ebook files.

## Security model

Moon Exporter is designed as an offline migration tool.

- no `INTERNET` permission
- no analytics
- no background services
- no exported file-provider or content-provider
- no ebook copies in the export ZIP
- Android Storage Access Framework only
- read-only persisted URI permissions
- bounded file reads for `.po`, `.an` and EPUB metadata
- ZIP entry names are sanitized before export
- `../` paths inside EPUBs are ignored during cover/title extraction
- app backups are disabled
- release builds enable shrinking and resource shrinking

The app treats all Moon+ and EPUB metadata as untrusted input.

## Development

Build locally:

```bash
gradle :app:assembleDebug
```

Run static checks:

```bash
gradle :app:lintDebug
```

The repository includes GitHub Actions for Android build/lint, CodeQL and dependency updates.

## Scope

Moon Exporter migrates reading data. It is not a reader app and it is not a sync server.

For live sync between Moon+ Reader, Calibre-Web, BookLore, KOReader and Readest-style workflows, use a sync bridge such as MoonDav or a native KOReader/CWA setup.
