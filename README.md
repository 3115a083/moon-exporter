# Moon Exporter

Moon Exporter is a standalone Android migration tool for Moon+ Reader data.

## Main capabilities

- Read Moon+ folders containing `.po`, `.an`, `_names.list` and related state files.
- Read `.mrpro` backup containers and recover database-backed book/annotation metadata.
- Match EPUB files, extract title/author/ISBN/cover metadata and calculate KOReader-compatible `partialMD5` identifiers.
- Filter and select books in a large list with a draggable fast scroller.
- Export Readest-compatible `.mrexpt` annotations.
- Export either full book files plus markings or markings only.
- Optionally send reading progress to a standard KOSync server or Calibre-Web Automated.

## KOSync / Calibre-Web Automated

Moon Exporter never uploads an ebook through the KOSync/CWA progress API. It identifies an already existing server-side book using KOReader `partialMD5` and sends only progress JSON.

Calibre-Web Automated uses HTTP Basic Auth and `/kosync` endpoints. Moon Exporter normalizes the base URL to avoid `/kosync/kosync`.

## Security

- no analytics, ads or telemetry
- app backups disabled
- cleartext network traffic disabled
- only `INTERNET` permission is requested
- Storage Access Framework for user-selected input/output
- no credential, authorization-header, book-content or annotation logging
- bounded ZIP/XML processing and path traversal checks
- no real user backup data in source, tests or build artifacts

## Build

GitHub Actions runs unit tests, lint, debug APK build, manifest checks and repository privacy checks.

Local commands:

```bash
gradle --no-daemon :app:testDebugUnitTest
gradle --no-daemon :app:lintDebug
gradle --no-daemon :app:assembleDebug
```
