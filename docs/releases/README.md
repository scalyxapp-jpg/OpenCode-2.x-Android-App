# Release notes

One Markdown file per release, named after the tag:

```
docs/releases/v1.0.0.md
docs/releases/v1.1.0.md
```

`docs/releases/vX.Y.Z.md` is the **source of truth for that release's notes**.
When a tag `vX.Y.Z` is pushed, the release workflow uses this file as the GitHub
release body and appends a _Build artifacts_ section with the SHA-256 of every
attached APK, computed at build time.

Conventions:

- Write the notes **before** tagging, so they are part of the tagged commit.
- Do **not** hard-code the APK checksum — it changes per build; CI appends it.
- The signing certificate digest is stable (same keystore) and may be listed.
- Keep the file self-contained: it is rendered on the release page, so links must
  be absolute GitHub URLs, not relative paths.

See [RELEASING.md](../RELEASING.md) for the full release checklist.
