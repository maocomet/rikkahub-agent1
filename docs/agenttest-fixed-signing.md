# Fixed signing for the `.agenttest` test builds

The debug/test package (`applicationId me.rerere.rikkahub.agenttest`) is built by
GitHub Actions and sideloaded by friends. Historically CI ran `assembleDebug` with no
keystore, so every fresh runner auto-generated its own `~/.android/debug.keystore`. That
means **each build had a different signing certificate**, and Android refuses to install a
new build over one signed by another certificate (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) —
testers had to uninstall the old version (losing its data) before every update.

This change gives the `.agenttest` package one **fixed** signing identity. From the first
build signed with this key onward, newer builds overwrite-install older ones and keep app
data. The official package (`me.rerere.rikkahub`) is **not** affected — its release signing
still comes from `local.properties` (`storeFile`/`storePassword`/`keyAlias`/`keyPassword`).

Nothing secret is committed. All signing material lives in GitHub Actions **Secrets**.

## How it works

* `app/build.gradle.kts` defines an `agentTest` signing config. It is created only when all
  four inputs are present together — either as `local.properties` keys (`agentTestStoreFile`,
  `agentTestStorePassword`, `agentTestKeyAlias`, `agentTestKeyPassword`) or as the matching
  `RIKKAHUB_AGENTTEST_*` environment variables. A partial config **fails the build** instead
  of silently falling back to a throwaway key.
* The `debug` build type (which produces the `.agenttest` APKs) prefers `agentTest`, then the
  maintainer's own `legacyDebug` key, then AGP's throwaway debug key. The two CI workflows
  that distribute `.agenttest` builds export `RIKKAHUB_AGENTTEST_REQUIRED=true`, which makes
  Gradle **throw** if the fixed config is somehow missing — CI can never quietly ship a build
  signed with a random debug key.
* The workflows decode the keystore from a base64 secret on every run and, after the build,
  verify each produced APK's certificate SHA-256 equals the keystore's certificate SHA-256,
  then print it. Same keystore every run ⇒ identical fingerprint.

## One-time setup

Run once on your workstation, keep the keystore file and passwords safe, and record the
fingerprint (give it to testers so they can verify what they install).

```bash
# 1) Create the keystore (choose a strong password; store & key password may be the same)
keytool -genkeypair -v \
  -keystore rikkahub-agenttest.keystore \
  -alias rikkahubagenttest \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=RikkaHub Agent Test, OU=Test, O=rikkahub, L=, ST=, C="

# 2) Print the SHA-256 fingerprint you will share/verify later
keytool -list -v -keystore rikkahub-agenttest.keystore -storepass <store-password> -alias rikkahubagenttest
```

## GitHub Secrets to create

In **Settings → Secrets and variables → Actions** of the repo, add four repository secrets:

| Secret | Value |
| --- | --- |
| `RIKKAHUB_AGENTTEST_KEYSTORE_BASE64` | base64 of the keystore file (single line) |
| `RIKKAHUB_AGENTTEST_STORE_PASSWORD` | keystore (store) password |
| `RIKKAHUB_AGENTTEST_KEY_ALIAS` | alias, e.g. `rikkahubagenttest` |
| `RIKKAHUB_AGENTTEST_KEY_PASSWORD` | private-key password |

Producing the base64 (single line):

* PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("rikkahub-agenttest.keystore"))`
* macOS/Linux: `base64 -w0 rikkahub-agenttest.keystore`

These names are deliberately distinct from the official release secrets used by
`release.yml` (`KEY_BASE64`, `SIGNING_CONFIG`, `GOOGLE_SERVICES_JSON`).

If any secret is missing, the next `.agenttest` CI run fails loudly in the
"Materialize fixed .agenttest signing keystore" step (and Gradle also refuses via
`RIKKAHUB_AGENTTEST_REQUIRED=true`). It never falls back to a random debug signature.

## Local builds with the same signature (optional)

CI is the primary source of builds, but you can also sign local debug builds with the same
fixed key so they behave identically on a device. Either:

**A) Per-invocation environment variables**

```powershell
# PowerShell
$env:RIKKAHUB_AGENTTEST_KEYSTORE    = "C:\path\to\rikkahub-agenttest.keystore"
$env:RIKKAHUB_AGENTTEST_STORE_PASSWORD = "<store-password>"
$env:RIKKAHUB_AGENTTEST_KEY_ALIAS   = "rikkahubagenttest"
$env:RIKKAHUB_AGENTTEST_KEY_PASSWORD   = "<key-password>"
.\gradlew.bat :app:assembleDebug
```

**B) `local.properties` keys** (repo root; the file is gitignored — never commit it).
`local.properties` takes precedence over the env vars above.

```properties
agentTestStoreFile=C:/path/to/rikkahub-agenttest.keystore
agentTestStorePassword=<store-password>
agentTestKeyAlias=rikkahubagenttest
agentTestKeyPassword=<key-password>
```

With either setup the local `debug`/`.agenttest` APK is signed with the same certificate CI
uses. Without any setup, local builds keep their old behavior (your personal `debug.keystore`,
then AGP's throwaway one) so a clean workstation can still run `assembleDebug`.

> If your phone currently has a build signed with your personal `debug.keystore`, the first
> build signed with the fixed key will not install over it — uninstall once (see below).

## Verifying a stable fingerprint

After the secrets are set, run the workflow (push to `master`, or **Run workflow** /
`workflow_dispatch`) twice. In each run the **"Verify .agenttest APKs use the fixed signing
key"** step prints lines like:

```
app-universal-debug.apk -> fixed agent-test key sha256: <hex>
```

An identical `<hex>` on two independent runs (different commits) proves the signature is now
stable. You can also check an APK directly:

```bash
apksigner verify --print-certs app-universal-debug.apk   # 'Signer #1 certificate SHA-256 digest'
keytool -list -v -keystore rikkahub-agenttest.keystore -storepass <store-password> -alias rikkahubagenttest   # 'SHA256:'
```

## Migration note for existing testers

Old test builds were signed with throwaway keys, so each one already required an uninstall.
The transition to the fixed key is the **last** such break:

1. When the first fixed-signed build is distributed, every tester **uninstalls the previous
   test version once** (that also removes that install's local data) and installs the new one.
2. From then on, each newer fixed-signed build (with a higher `versionCode`) **overwrites the
   previous one and keeps app data** — no more uninstalls.

Suggested wording for the announcement of the first fixed-signed build:

> This test build switches to a stable signing key, so please **uninstall the previous test
> version once**, then install this one. Future updates will install directly on top and keep
> your data. Certificate SHA-256: `<hex>`.

The `.agenttest` build remains side-by-side with the official RikkaHub app and never touches
its signing or data.

## Recorded fingerprint (verified 2026-09-05)

Confirmed identical across two independent CI builds — push of `5f56ee8b`
(run `33943723632`) and a `workflow_dispatch` run (`33944372412`) — for all
three `.agenttest` APK variants:

```
sha256 2f1965cf7447301f857ec222fb1996ac179b07c771d9b3a636bd0116fefffcc3
```

Use this value in the migration announcement above and to sanity-check what
testers install: `keytool -list -v` on the same keystore (and
`apksigner verify --print-certs` on any built APK) must print the same
SHA-256. A newer build signed by this key will overwrite-install over an
older one and keep app data; a build with a different fingerprint will not.
