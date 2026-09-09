# Security Policy

Moon Exporter is an offline migration tool for local reading-progress data.

## Supported versions

Security fixes target the default branch until the first tagged release exists.

## Threat model

The app treats the selected Moon+ folder, WebDAV save folder, `.po`, `.an`, `_names.list`, `recent.list` and EPUB metadata as untrusted input.

## Security properties

- The Android manifest does not request `INTERNET`.
- The app uses Android's Storage Access Framework instead of broad storage permissions.
- It asks only for read access to source folders and files.
- It writes only to a user-selected export document.
- It does not export ebook files.
- It does not follow filesystem paths from input data.
- Export ZIP entry names are sanitized.
- EPUB metadata extraction rejects traversal-like paths.
- File reads are bounded to avoid memory exhaustion on unexpected input.
- Android backup and data extraction are disabled.

## Reporting a vulnerability

Open a private security advisory on GitHub or contact the repository owner. Include:

- affected version or commit
- reproduction steps
- sample input if it can be shared safely
- expected and actual behavior

Do not publish exploit details before a fix is available.
