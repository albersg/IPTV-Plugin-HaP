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
| F5 | `check_peers` opens real P2P sessions outside the concurrency counters | MEDIUM | `httpaceproxycpp/src/plugins.cpp` | **done**, capped at two concurrent checks |
| F6 | Unbounded `strtoull` chunk size; `chunk_size + 2` wraps | MEDIUM | `app/src/main/cpp/android_http_client.cpp` | **done and tested**, `2fa795a` |
| F7 | `Access-Control-Allow-Origin: *` on unauthenticated playlist endpoints that carry credentials | MEDIUM | `httpaceproxycpp/src/plugins.cpp` | **done**, wildcard removed |
| F8 | `--bind-all` may expose the engine's own ports regardless of LAN mode | **REFUTED by measurement** | `AceServeRuntime.java` | T7 (closed) |
| F9 | Request targets, stream URLs and provider URLs logged at INFO | LOW/MEDIUM | `proxy.cpp`, `broadcast.cpp`, `StreamVaultHapPluginService.java` | **done** (native in `5325288`, Java with D1) |
| F10 | Failed-source refresh amplification without a single-flight lock | LOW | `httpaceproxycpp/src/plugins.cpp` | **done**, mutex plus 30 s failure backoff |
| F11 | EOL jQuery 3.3.1 / Bootstrap 4.1.1 served from `/stat`; no third-party notice file | LOW | `assets/httpaceproxy/http/` | **notice written**; upgrade deferred with a trigger |

## Tasks

| ID | Spec | Task | Verification |
| --- | --- | --- | --- |
| T1 | S1 | Require a caller check on the exported service; stop returning source URLs from `MSG_GET_CONFIGURATION_VALUES` unless the caller is trusted | BLOCKED: the host package is unknown (see L5) |
| T2 | S1, S3 | Use the real peer address for both the firewall decision and the client record; ignore client-supplied `X-Forwarded-For` except from a trusted proxy | **done and tested**, `f9e0612` |
| T3 | S1, S3 | Reject CR, LF and control bytes for every value concatenated into an engine command | **done and tested**, `f9e0612` |
| T4 | S1 | Make `check_peers` sessions count against the concurrency limits | **done**, capped at two |
| T5 | S1 | Bound the chunk size; reject sizes beyond a sane maximum before the `+2` | **done and tested**, `2fa795a` |
| T6 | S1 | Restrict CORS on playlist endpoints carrying credentials | **done**, syntax-checked only |
| T6b | S2 | Do not add proxy authentication (breaks Cast and header-less players) without a device test | decision recorded |
| T7 | S2 | Resolve `--bind-all` by inspecting the engine's listening sockets on hardware; do not change blind | **done**: the flag is not the cause |
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

**L5 — why F1 is not fixed yet, and the exact way in.** The critical finding is the exported plugin
service: any app can bind `com.streamvault.plugin.API` and drive the runtime, and
`MSG_GET_CONFIGURATION_VALUES` hands back the stored provider URLs. Three fixes were considered and
only one is safe, and it needs one fact this repository does not contain.

A signature-level permission is wrong here. This fork is signed with the human's own key
(`~/hap-signing/release.jks`), while StreamVault is signed with jopsis's, so a signature check would
refuse the very caller the plugin exists to serve. A manifest `android:permission` is also wrong for
the same reason: the client would have to declare `uses-permission` in *its* manifest, which is not
ours to change.

The workable design is a caller allowlist by package name, because an installed package name cannot
be taken over by another app on the same device. That requires the host's package name, and the
repository does not record it: `StreamVaultHapPluginService` learns nothing about its caller, and the
manifest describes the plugin's own identity (`com.streamvault.plugins.hap`), not the host's.
Guessing a prefix and shipping it would break the integration silently.

The morning action is one command, then the fix is mechanical:

```bash
ADB=/mnt/c/Users/alber/hap-tools/platform-tools/adb.exe
$ADB shell pm list packages | grep -i streamvault
```

That yields the host package(s). With them, `Binder.getCallingUid()` plus
`getPackageManager().getNameForUid` gives a caller identity, and the service can allow the host and
its own UID while redacting source URLs and refusing LAN enablement for everyone else. Until then
F1 remains open, and it is the highest-severity item on this branch.

**L6 — F8 is refuted, measured on the Xiaomi box.** With the server running and LAN mode off, the
app's sockets were read directly:

```
127.0.0.1:8888    the HaP proxy: loopback only, exactly as LAN mode off promises
0.0.0.0:6878      AceStream stream/HTTP
0.0.0.0:62062     AceStream control API
0.0.0.0:8621      AceStream
0.0.0.0:45xxx     the peer listener
```

The hypothesis behind privacy item P1 was that `--bind-all` in `AceServeRuntime` caused this, so
removing it would close the exposure. The flag was removed, the APK rebuilt, reinstalled and the
server started again: **the same four ports still listened on 0.0.0.0**. The flag is therefore not
the cause, and it was restored rather than left out on a theory the measurement contradicts.

What this means, stated plainly:

- The engine binds every interface by default, and it must accept inbound peers to work at all, so
  at least the peer port is public by design. The stream port and the control API are public too,
  and HaP has no lever over that: the runtime is a prebuilt binary and the app runs unprivileged.
- The proxy's LAN firewall therefore protects less than it appears to. A peer that can reach the
  network can talk to the engine directly, without passing the proxy's allow list. `--bind-all` was
  a red herring; the real gate would have to be the engine's own configuration or a firewall
  outside the app.
- Closing this needs either documented AceServe options for a per-port bind address, or a VPN
  interface that keeps the peer port reachable only through the tunnel. Neither is a HaP code change,
  and neither should be claimed as done.

Nothing about the P2P design changed, so the earlier statement stands: an app inside HaP cannot hide
the user's address from the swarm.

**L7 — the audit's tail closed.** F5, F9, F10 and F11 landed in `5325288`; F2 and F3 gained real
tests in `f9e0612`, each checked against a deliberately broken build so they are not decorative. F7
has no logic to pin: removing four headers is a review matter. F6 was pinned earlier. What remains
open is F1, and it waits on a human decision rather than on missing evidence.
