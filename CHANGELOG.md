# Changelog

All notable Moon Exporter revisions are recorded here so future development can start from the documented state instead of re-auditing the full codebase.

## 1.0.1 - 2026-09-12

### Changed
- Established the repository itself as the authoritative handoff source for future coding sessions.
- Added explicit project scope and development-state documentation under `docs/`.
- Extended the CI privacy scan to the project documentation and changelog.
- Versioned the debug APK artifact as `MoonExporter-1.0.1-debug`.

### Verified existing state
- Standalone package and namespace are `de.moonexporter.app`.
- `.mrpro` and Moon+ folder import paths exist.
- Readest marking export exists.
- KOSync and Calibre-Web Automated progress transfer exists and must never upload ebook files.
- CI performs unit tests, Android lint, APK build, manifest checks and repository privacy checks.

### Next engineering priorities
1. Harden and test large `.mrpro` imports, cancellation and bounded ZIP processing.
2. Add focused synthetic tests for malformed XML/ZIP input and Moon+ format edge cases.
3. Validate Readest `.mrexpt` compatibility against synthetic fixtures.
4. Expand KOSync/CWA protocol tests and HTTP error handling.

## 1.0.0 - 2026-09-11

- Reworked Moon Exporter as a standalone Android migration app.
- Removed legacy MoonDav package references.
- Added `.mrpro` processing, book metadata handling, Readest export and KOSync/CWA functionality.
- Restored strict Android CI and security checks.
