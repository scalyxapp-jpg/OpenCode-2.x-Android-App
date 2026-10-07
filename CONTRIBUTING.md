# Contributing

Thanks for wanting to help! This document explains how to build, test and
propose changes.

## Code of conduct

By participating you agree to our [Code of Conduct](CODE_OF_CONDUCT.md).

## Getting set up

1. Install **JDK 17** and the **Android SDK** (platform 36, build-tools).
2. Clone the repository and build:

    ```bash
    ./gradlew :app:assembleDebug
    ```

3. Run the unit tests before you start changing code, so you have a green
   baseline:

    ```bash
    ./gradlew :app:testDebugUnitTest
    ```

## Project layout

| Path                                                 | What lives here                                                                                          |
| ---------------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| `app/src/main/java/com/opencode/android/ui/session/` | Pure stream/conversation core (reducer, streamer, command surface). Start here for streaming/state bugs. |
| `app/src/main/java/com/opencode/android/ui/`         | Compose screens, ViewModels, widgets, theming.                                                           |
| `app/src/main/java/com/opencode/android/data/`       | Repository, API surface, caches, stores, the backend session.                                            |
| `app/src/main/java/com/opencode/android/domain/`     | Wire/domain models.                                                                                      |
| `app/src/test/`                                      | JVM unit tests — fast, no device.                                                                        |
| `app/src/androidTest/`                               | On-device tests (optional, skip without a server).                                                       |

`CONTEXT.md` is the domain glossary. Read it before a non-trivial change; it
names the concepts (SessionStreamer, StreamReducer, ConversationState, …) so
you can find the code.

## Making a change

1. **Branch** from `main`: `git checkout -b fix/short-description`.
2. **Keep it focused.** One logical change per pull request.
3. **Write tests** for behaviour changes. The streaming/state code is pure and
   unit-testable without a device — use that.
4. **Keep the build green**:
    ```bash
    ./gradlew :app:testDebugUnitTest :app:assembleDebug
    ```
5. **Commit** with a clear conventional message, e.g.
   `fix(chat): keep the stop button running across steps`.
6. **Open a pull request** and fill in the template.

## Coding conventions

- Kotlin, 4-space indent, `const` over `let`, no `var` where avoidable.
- Prefer small pure functions with unit tests over logic buried in a ViewModel.
- Public functions get explicit return types.
- No secrets, personal hosts, tokens or machine-specific paths in code, tests,
  fixtures or docs. Use placeholders and environment/instrumentation args.
- Never commit `keystore.properties`, `*.jks`, `local.properties` or `.env`
  files — they are git-ignored; keep it that way.

## Reporting bugs

Use the [bug report template](.github/ISSUE_TEMPLATE/bug_report.yml). Include app
version, Android version, the server version, and the exact steps to reproduce.
**Never paste credentials, tokens or private server URLs** — redact them.

## Security issues

Do **not** open a public issue for a security problem — follow
[SECURITY.md](SECURITY.md).

## License

By contributing you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE).
