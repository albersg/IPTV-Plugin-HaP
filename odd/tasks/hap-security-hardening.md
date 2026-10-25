# HaP security hardening — findings from the branch audit

A read-only audit of `perf/xiaomi-streaming-core` produced eleven findings with `path:line`
evidence. This document turns them into reviewable work. It is deliberately ordered by blast
radius, not by severity label.

Two compatibility rules constrain every fix here. The exported plugin service must keep
`com.streamvault.plugin.API`, `com.streamvault.plugin.MANIFEST_JSON` and its `Messenger` ABI, or
StreamVault integration breaks; hardening means a permission or caller check, never
`exported="false"`. And external players and Cast cannot send custom headers or tokens, so proxy
authentication cannot be a header scheme without breaking playback.

## Specs

**S1 — Preserve integration.** Keep the StreamVault plugin surface and its Binder ABI. Keep
`acestream://`, `infohash://` and `/url/<encoded>` request shapes working. Keep `/stat` paths and
`#EXTINF` parsing unchanged.

**S2 — No silent breakage of playback.** Any change that could stop a stream on a real player is
out of scope for a code-only slice; it needs device validation first.

**S3 — Fix at the choke point.** Prefer one validated boundary over scattered checks.

## Findings and work

| ID | Finding | Severity | Surface | Task |
| --- | --- | --- | --- | --- |
| F1 | Exported plugin service authorises nobody; any app can start/stop the runtime, enable LAN exposure, and read back stored provider URLs including credentials | CRITICAL | `StreamVaultHapPluginService.java`, manifest | T1 |
| F2 | LAN mode has no authentication and its only gate trusts the spoofable `X-Forwarded-For` | HIGH | `httpaceproxycpp/src/proxy.cpp` | **done**, forwarded header trusted only from loopback |
| F3 | AceStream control-protocol injection: no CR/LF filtering before `write_line` | HIGH | `httpaceproxycpp/src/ace_client.cpp` | **done**, `write_line` refuses control bytes |
| F4 | Stored XSS in `/stat` dashboard | MEDIUM | `assets/httpaceproxy/http/js/index.js` | **done**, `8a80134` |
| F5 | `check_peers` opens real P2P sessions outside the concurrency counters | MEDIUM | `httpaceproxycpp/src/plugins.cpp` | T4 |
| F6 | Unbounded `strtoull` chunk size; `chunk_size + 2` wraps | MEDIUM | `app/src/main/cpp/android_http_client.cpp` | **done**, no regression test yet |
| F7 | `Access-Control-Allow-Origin: *` on unauthenticated playlist endpoints that carry credentials | MEDIUM | `httpaceproxycpp/src/plugins.cpp` | **done**, wildcard removed |
| F8 | `--bind-all` may expose the engine's own ports regardless of LAN mode | MEDIUM (hypothesis) | `AceServeRuntime.java` | T7 |
| F9 | Request targets, stream URLs and provider URLs logged at INFO | LOW/MEDIUM | `proxy.cpp`, `broadcast.cpp`, `StreamVaultHapPluginService.java` | T8 |
| F10 | Failed-source refresh amplification without a single-flight lock | LOW | `httpaceproxycpp/src/plugins.cpp` | T9 |
| F11 | EOL jQuery 3.3.1 / Bootstrap 4.1.1 served from `/stat`; no third-party notice file | LOW | `assets/httpaceproxy/http/` | T10 |

## Tasks

| ID | Spec | Task | Verification |
| --- | --- | --- | --- |
| T1 | S1 | Require a signature-level permission or caller-package allowlist on the exported service; stop returning source URLs from `MSG_GET_CONFIGURATION_VALUES` unless the caller is trusted | Compiles; integration ABI unchanged; needs device/StreamVault check |
| T2 | S1, S3 | Use the real peer address for both the firewall decision and the client record; ignore client-supplied `X-Forwarded-For` except from a trusted proxy | **done**, syntax-checked only |
| T3 | S1, S3 | Reject CR, LF and control bytes for every value concatenated into an engine command | ctest, including a `%0d%0a` case |
| T4 | S1 | Make `check_peers` sessions count against the concurrency limits | ctest |
| T5 | S1 | Bound the chunk size; reject sizes beyond a sane maximum before the `+2` | ctest |
| T6 | S1 | Restrict CORS on playlist endpoints carrying credentials | **done**, syntax-checked only |
| T6b | S2 | Do not add proxy authentication (breaks Cast and header-less players) without a device test | decision recorded |
| T7 | S2 | Resolve `--bind-all` by inspecting the engine's listening sockets on hardware; do not change blind | needs device |
| T8 | S2 | Stop logging request targets and provider URLs at INFO | review |
| T9 | S2 | Add single-flight to playlist refresh | ctest |
| T10 | S1 | Update or document the bundled jQuery/Bootstrap versions; add a third-party notices file | review |

## Log

**L1 — origin.** The human asked for maximum network privacy, maximum security and minimal
traceability for IPTV playback, and separately authorised the security audit §9 of the
optimisation brief. They also accepted that AceStream is P2P and that a HaP-side change cannot
hide their address from the swarm.

**L2 — evidence.** Findings F1-F11 come from the branch audit with `path:line` citations. F4 is
fixed in `8a80134`. F8 remains the open question behind privacy item P1: the bundled engine zip
exposes only three `.py` entries, so the flag's semantics could not be read offline.

**L4 — F3 and F6 fixed without a regression test.** Both are refused at the choke point and both compile under the NDK clang, but neither is pinned by a test: the native suite compiles only `util.cpp`, and `parse_chunk_size` lives in a translation unit that needs `jni.h`. Pinning F6 needs that function extracted into a unit the native suite can build. Recorded as debt, not as verified.

**L3 — constraint.** No device is connected during this work, so anything touching sockets,
binding or player compatibility is recorded as pending verification, not as done.
