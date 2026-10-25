# Third-party notices

HaP ships or links the following third-party components. Versions are those actually bundled in this
repository at the time of writing; update this file whenever one of them changes.

## Bundled web assets, served from `/stat`

These are served by the local proxy dashboard in `app/src/main/assets/httpaceproxy/http/`.

| Component | Version | License | Notes |
| --- | --- | --- | --- |
| jQuery | 3.3.1 | MIT | **End of life.** Ships before the fixes for CVE-2020-11022 and CVE-2020-11023, both cross-site scripting through HTML containing `<option>` elements. |
| Bootstrap | 4.1.1 | MIT | **End of life.** Predates the fix for CVE-2019-8331, XSS in tooltip and popover data attributes. |
| Popper.js | 1.14.6 | MIT | Dependency of Bootstrap 4.1.1. |

Why they are still here, stated plainly: replacing them means re-testing a dashboard that no one has
opened in a browser during this work, and the version numbers are the only reason a reviewer would
reach for an upgrade. The script-execution sink that mattered in practice was the dashboard's own
string-concatenated table rows; that was fixed independently by escaping every interpolated value and
restricting icon URLs to `http(s)` and relative paths. The residual risk is a malicious page that can
already reach the LAN dashboard, which the same fix does not cover.

Revisit trigger: the first time the dashboard is changed for any other reason, upgrade jQuery and
Bootstrap together and re-test `/stat` in a browser. Do not upgrade them blind.

## Native and runtime components

| Component | Version | License | Notes |
| --- | --- | --- | --- |
| Kubo (IPFS) | v0.43.0 | Apache-2.0 / MIT | Built from source by `tools/fetch-kubo.sh` into `libipfs.so`. |
| AceStream engine (AceServe) | as bundled | Proprietary | Prebuilt ARM blob inside `ace-*.zip`. Not open source, redistributed as the app's streaming core. |
| GoogleTest | v1.15.2 | BSD-3-Clause | Test-only, fetched by `native-tests/CMakeLists.txt`. Never packaged. |

## AndroidX

The application depends on `androidx.core:core-ktx` (Apache-2.0). Anything else declared in
`gradle/libs.versions.toml` is limited to what the build actually references, by design.

## Missing from this file

There is no software bill of materials and no automated license scan. For a repository that
redistributes a proprietary binary, that is a gap worth closing before any public release; it is
recorded here rather than silently ignored.
