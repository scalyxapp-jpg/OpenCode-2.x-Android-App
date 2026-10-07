# Releasing & first-time publishing checklist

This is the maintainer checklist for taking a clone of this repository public and
shipping releases.

## 1. Repository links

This repository is configured for **`scalyxapp-jpg/OpenCode-2.x-Android-App`**.
If you fork it, update the hard-coded URLs in:

- `README.md` (CI badge, clone URL)
- `CHANGELOG.md` (compare link)
- `.github/ISSUE_TEMPLATE/config.yml` (security + discussions links)

Still to fill in before publishing:

- [ ] Replace the Code of Conduct contact placeholder
      (`<INSERT CONTACT METHOD>`) in `CODE_OF_CONDUCT.md`.
- [ ] Review `NOTICE` and set the copyright holder if you want your own name.
- [ ] Confirm no local-only files are tracked:
    ```bash
    git status --ignored --short
    git ls-files | grep -iE 'keystore|\.jks|local\.properties|\.env$'   # must be empty
    ```
- [ ] Confirm the history is clean:
    ```bash
    git log --all --pretty=format: --name-only -- keystore.properties opencode-release.jks | sort -u
    git grep -I "PRIVATE KEY" $(git rev-list --all) || echo clean
    ```

## 2. Repository settings on GitHub

- [ ] Default branch `main` (rename `master` if needed).
- [ ] Enable **branch protection** on `main`: require the `Build & test` CI
      check, require a pull request, disallow force pushes.
- [ ] Enable **private vulnerability reporting** (Settings → Security).
- [ ] Enable **Dependabot alerts** and security updates.
- [ ] Add repository topics and a description (unofficial Android client).

## 3. Signing for releases

Release CI needs four repository secrets
(Settings → Secrets and variables → Actions):

| Secret                    | Value                         |
| ------------------------- | ----------------------------- |
| `SIGNING_KEYSTORE_BASE64` | `base64 -w0 your-release.jks` |
| `SIGNING_STORE_PASSWORD`  | keystore password             |
| `SIGNING_KEY_ALIAS`       | key alias                     |
| `SIGNING_KEY_PASSWORD`    | key password                  |

Generate a keystore if you do not have one:

```bash
keytool -genkeypair -v -keystore release.jks -alias opencode \
  -keyalg RSA -keysize 4096 -validity 10000
```

> **Losing this keystore means you can never update an installed app again.**
> Back it up somewhere safe and offline. Never commit it.

## 4. Cutting a release

1. Update `versionName` / `versionCode` in `app/build.gradle.kts`.
2. Move the `Unreleased` entries in `CHANGELOG.md` under the new version and add
   the compare link.
3. Commit, then tag and push:
    ```bash
    git tag -a v1.0.0 -m "v1.0.0"
    git push origin main --tags
    ```
4. The **Release** workflow builds a signed APK and attaches it to a GitHub
   Release. Without the signing secrets the job skips and warns, so forks are
   unaffected.

## 5. Optional hardening

- [ ] Add a `CODEOWNERS` file if you want review routing.
- [ ] Add a reproducible-build / SBOM step if you need supply-chain evidence.
- [ ] Consider an F-Droid metadata file if you want the app distributed there.
