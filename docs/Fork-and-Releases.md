# Forking HaP and publishing your own releases

This guide covers everything needed to build and publish your own signed HaP
APKs from a fork, and the one consequence of forking that cannot be avoided.

## The signature problem — read this first

Android identifies an app by its package name **and** its signing certificate.
Two APKs with the same package name (`com.streamvault.plugin.hap`) but different
signing keys are *different apps* as far as the installer is concerned.

Consequences for your fork:

- An APK signed with **your** keystore cannot be installed over an existing
  install signed with jopsis' key. The install fails with
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
- The only way to switch an already-installed HaP to your fork's APK is to
  uninstall the existing one first. Uninstalling **deletes the app's data**:
  M3U sources, custom channels, IPFS settings, and preferences.
- You cannot reuse jopsis' key unless you already hold it. Do not try: a
  different key with the same alias is still a different key.

This is unavoidable and is exactly why the update system must verify the
signature of a downloaded APK before handing it to the installer: an APK from a
different key would otherwise look like a valid upgrade to a naive version
check, and the install would fail (or, worse, silently require a data wipe).

Once you are on your own key, every subsequent release of your fork **must** be
signed with the same key, or you will repeat the data loss on every update.

## One-time keystore setup

Generate a keystore once and keep it safe and backed up. Losing it means losing
the ability to update your own users in place.

```sh
keytool -genkeypair -v \
  -keystore release.jks \
  -alias hap \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype JKS
```

Add these four repository secrets to your fork
(Settings → Secrets and variables → Actions → New repository secret):

| Secret | Value |
| --- | --- |
| `ANDROID_SIGNING_KEYSTORE_BASE64` | `base64 -w0 release.jks` output |
| `ANDROID_SIGNING_STORE_PASSWORD` | keystore password |
| `ANDROID_SIGNING_KEY_ALIAS` | `hap` (the alias above) |
| `ANDROID_SIGNING_KEY_PASSWORD` | key password |

Note the workflow reads the keystore from
`ANDROID_SIGNING_KEYSTORE_BASE64`. The Gradle build reads
`SIGNING_STORE_FILE`/`SIGNING_STORE_PASSWORD`/`SIGNING_KEY_ALIAS`/
`SIGNING_KEY_PASSWORD` from the environment; the workflow wires the former into
the latter, so a local unsigned build still works with none of them set.

```sh
base64 -w0 release.jks   # copy this into ANDROID_SIGNING_KEYSTORE_BASE64
```

Never commit `release.jks`. It is covered by `.gitignore` (`*.jks`, `*.keystore`).

## Version rules

The workflow enforces consistency, so a release cannot drift from the code:

- `versionName` and `versionCode` live in `app/build.gradle.kts`.
- `AndroidManifest.xml` mirrors `versionName`/`versionCode` in its
  `com.streamvault.plugin.*` metadata.
- `README.md` and `docs/Changelog.md` document the same version.
- The release tag must equal `versionName`, with an optional leading `v`
  (`1.3.4` and `v1.3.4` are both accepted). A tag like `v1.3.4` will not build
  if `versionName` is still `1.3.3`.

To cut a release, bump both version fields first, then either push a matching
tag or run the workflow manually (`workflow_dispatch`); the manual path creates
the tag for you and refuses if it already points at another commit.

## Published artifacts

The workflow publishes four APKs per release:

| Asset | Contents |
| --- | --- |
| `IPTV-Plugin-HaP.apk` | universal (both ABIs), stable download URL |
| `IPTV-Plugin-HaP-<version>.apk` | same universal APK, versioned name |
| `IPTV-Plugin-HaP-<version>-arm64-v8a.apk` | 64-bit ARM only |
| `IPTV-Plugin-HaP-<version>-armeabi-v7a.apk` | 32-bit ARM only |

Per-ABI assets matter: the universal APK carries both ABIs' kubo binary and
AceServe zip (~139 MB), while an ABI-specific APK is roughly half that
(~70 MB for `armeabi-v7a`). On a device with limited storage the smaller APK is
the difference between a clean install and a generic "app not installed".

The Xiaomi TV Box S 3rd Gen (`MiTV-AFMU0`) reports
`ro.product.cpu.abilist=armeabi-v7a,armeabi`, so its correct asset is the
`armeabi-v7a` one. Any ABI-aware updater should select by the device's **real**
ABI list, not by a guessed or modern-looking default.

## Pointing the updater at your fork

The in-app updater talks to the GitHub Releases API. For a fork, the repository
it queries must be your fork, not `jopsis/IPTV-Plugin-HaP`; otherwise the app
would offer releases signed with a key it cannot install over itself.

Until the updater lands, the practical flow is: publish a release from your
fork, download the correct per-ABI asset on the device, and install it
manually.
