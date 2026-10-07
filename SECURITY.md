# Security Policy

## Supported versions

This is a client for a self-hosted server. Security fixes are applied to the
latest release only.

| Version                        | Supported |
| ------------------------------ | --------- |
| latest `main` / latest release | ✅        |
| older releases                 | ❌        |

## Reporting a vulnerability

**Please do not report security problems in a public issue.**

Report privately via GitHub's
[private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities)
("Security" → "Report a vulnerability") on this repository, or by contacting a
maintainer directly.

Please include:

- what the issue is and its impact,
- steps to reproduce (a minimal scenario is ideal),
- the app version, Android version, and server version,
- any logs, **with secrets, tokens and private server URLs redacted**.

You can expect an acknowledgement within a few days. Please give us a
reasonable window to release a fix before disclosing publicly.

## Threat model & scope

This app is a **client**. The server you point it at is trusted to the same
degree you trust that machine.

In scope:

- credential handling in the app (storage, backup, logging),
- the network layer (TLS/cleartext decisions, redirects, token leakage),
- data exposure through logs, notifications, the widget, or the clipboard,
- injection across the app's own boundaries (deep links, share intents, intents
  to exported components).

Out of scope:

- vulnerabilities in the OpenCode server itself — report those upstream,
- a malicious server the user deliberately connected to,
- attacks that require a rooted device or a compromised OS,
- physical access to an unlocked device.

## Security design notes

- **Credentials** are encrypted with the Android Keystore and are excluded from
  cloud backup and device transfer
  (`app/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml`).
- **Cached conversation text** and **crash reports** are also excluded from
  backups, because they can quote tool output, file contents or secrets.
- **Cleartext HTTP** is permitted deliberately: the OpenCode server speaks plain
  HTTP on the user's own network. The decision is explicit in
  `app/src/main/res/xml/network_security_config.xml`, which trusts only system
  CAs and never user-installed CAs.
- **No telemetry** leaves the device except a short, single-line, sanitized
  error summary sent to the user's _own_ server `/log` endpoint (best-effort,
  rate-limited).
- **Fine-grained permissions only**; the app requests no contacts, location or
  storage permission beyond what the feature needs.

## Secrets hygiene for contributors

Never commit `keystore.properties`, `*.jks` / `*.keystore`, `local.properties`,
or `.env` files. They are git-ignored. If you ever commit one by accident,
treat the value as compromised: remove it from the working tree, rotate it, and
have the history rewritten before publishing.
