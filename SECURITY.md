# Security Policy

Moon Exporter handles potentially sensitive reading data. Real user backups and derived personal data must never be committed, added to tests, logged or included in build artifacts.

## Application rules

- No analytics, telemetry or advertising.
- Android app backups are disabled.
- Cleartext traffic is disabled. Network sync requires HTTPS.
- The app requests only permissions required for its operation. Current network sync requires `android.permission.INTERNET`.
- Credentials and authorization headers must not be logged.
- Book contents, annotations and personal filenames must not be logged.
- ZIP paths are validated against traversal patterns and large inputs are processed with explicit limits or streaming.
- XML input is treated as untrusted and DOCTYPE/ENTITY declarations are rejected.
- Export cleanup may delete only files created by the current failed/cancelled export.
- Existing user files are never silently overwritten.

## KOSync/CWA guarantee

The KOSync/CWA workflow sends only authentication data, KOReader-compatible `partialMD5` document identifiers and reading-progress JSON. It does not upload ebook files.

## Tests

Use synthetic fixtures only, such as `Example Book`, `Synthetic Author` and invented positions/ISBNs.
