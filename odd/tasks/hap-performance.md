# HaP performance & stability — target Xiaomi TV Box S 3rd Gen

Device baseline: MiTV-AFMU0 (`twilight`), Android 14 / SDK 34, Amlogic S905X5M,
**armeabi-v7a only**, 4 cores, 1.96 GB RAM (~850 MB available), 648 MB ZRAM swap,
page size 4096. Reference build: `:app:assembleArmv7Debug` = 70 MB
(`universal` = 139 MB; its arm64 half is unusable on this box).

## Specs

S1. "Restricción obligatoria: mantener la caché de streaming de AceServe en 100 MiB
(104857600 bytes). No reducirla."

S2. "Prioriza la continuidad del stream sobre una reducción marginal de latencia o memoria."

S3. "Una reproducción activa debe tener prioridad absoluta sobre el testeo masivo."

S4. "No presupongas que las coroutines cancelan automáticamente una operación nativa
bloqueante."

S5. "No sustituyas el servidor ni libcurl por defecto: demuestra que la alternativa
funciona mejor mediante benchmarks y pruebas prolongadas."

S6. "No inventes mediciones ni afirmes que algo está validado sin haberlo probado."

S7. Pulsar Home must not mean a full stop; an explicit stop must leave no AceServe,
HTTPAceProxy, IPFS, test, socket, thread or timer behind.

S8. Behaviour is unchanged for StreamVault, M3U/EPG, Cast/LAN, IPFS, custom channels,
preferences and external player compatibility (AGENTS.md Change Rules).

## Tasks

| ID | Spec | Route | Change |
|----|------|-------|--------|
| T1 | S1 | inline | Unify AceServe live cache at 104857600 (remove the ~1000 MiB disk override in `main_android.py`) |
| T2 | S2 | worker A | Non-blocking fan-out: producer never waits on a slow client |
| T3 | S2, S5 | worker A | Share one immutable chunk buffer across clients (remove per-client copies) |
| T4 | S2, S7 | worker A | HTTP server: connection cap, recv timeout, accept backoff, EINTR-safe send, safe shutdown |
| T5 | S3, S4 | worker A | Cancellable native channel tests; no hot poll loop in `check_channel_peers` |
| T6 | S3 | worker B | Stop serializing channel tests on the shared single-thread executor |
| T7 | S2 | worker B | Incremental runtime refresh instead of 1 Hz full view rebuild |
| T8 | S6 | inline | JVM unit tests (first test sources in the repo) + native unit tests |
| T9 | S6 | inline | On-device before/after measurements; docs + Changelog |

## Log

L1. User request (verbatim, decisive clauses): "Restricción obligatoria: mantener la caché
de streaming de AceServe en 100 MiB (104857600 bytes). No reducirla." /
"No te limites a elaborar informes: implementa y valida mejoras reales." /
"Prioriza la continuidad del stream sobre una reducción marginal de latencia o memoria." /
"Una reproducción activa debe tener prioridad absoluta sobre el testeo masivo." /
"No presupongas que las coroutines cancelan automáticamente una operación nativa
bloqueante." / "No inventes mediciones ni afirmes que algo está validado sin haberlo
probado." / "Mantén los 100 MiB de caché AceServe." (Full brief: 12 numbered sections,
audit → streaming/proxy → channel testing → UI/D-pad → resource lifecycle → global
performance → GitHub Releases updates → security → tooling → method → final instruction.)

L2. Environment finding: WSL2 has no USB sysfs; the box is reachable only through
Windows-side `adb.exe` interop. It enumerates as PnP class `USBDevice`,
`USB\VID_18D1&PID_4EE7`, not class `USB` — friendly-name/USB-class filtering misses it.

L3. Device measurement: `ro.product.cpu.abilist` = `armeabi-v7a,armeabi`. The arm64-v8a
flavor cannot install; `universal` doubles APK size for no benefit on this hardware.

L4. Decision (user): unify the AceServe live cache at 100 MiB (104857600). The Python
bootstrap's `live_cache_size = 1048570000` (~1000 MiB, disk type) contradicts both the
Java `--live-mem-cache-size 104857600` and S1.

L5. Confirmed code evidence (audit, not hypothesis):
- `http_server.cpp`: `std::thread(...).detach()` per connection, no cap, no `SO_RCVTIMEO`;
  `accept()` error path `continue`s with no backoff; `send_fd_all` treats `EINTR` as failure.
- `broadcast.cpp`: `broadcast_chunk` pushes to each client with a blocking `wait`
  (write_timeout/4) **sequentially in the single producer thread**, so one slow client
  stalls the shared reader for every client; each push copies the chunk per client.
- `BroadcastManager::get_or_create` holds the manager mutex across a blocking
  `ace_->authenticate()`.
- `proxy.cpp check_channel_peers`: polls `ace.status(1)` with no sleep for up to max_wait,
  and never observes client disconnect, so aborted tests keep a live P2P session.
- `HapConfigActivity`: one `newSingleThreadExecutor()` shared by all background work,
  including the whole list test; a 1 Hz `Handler` loop rebuilds view containers and
  allocates new Drawables each tick; `uiautomator` cannot reach idle state.

L6. Commit slices on `perf/xiaomi-streaming-core` (native work is the larger
unit; it is kept whole because the fan-out, chunk lifetime and server shutdown
changes are one interlocking subsystem, and splitting them would produce commits
that do not build independently):

- a457a24 perf(native) — non-blocking fan-out, zero-copy chunks, HTTP server
  hardening, cancellable peer tests. 450 authored lines: this is the slice
  boundary. Risk: item 4 (concurrency/shutdown lifetimes).

Remaining slices, each its own commit: AceServe cache unification (Python),
UI refresh + channel-test isolation (Java), debug/ADB tooling, native test
suite, CI, docs, and the updater feature unit.

L7. Verification performed on the reference device (MiTV-AFMU0) with
`:app:assembleArmv7Debug` built from both workers' changes:
- cold start 2286 ms -> 2084 ms
- idle RSS 116 MB flat/declining to 107 MB over 60 s (previously climbed to 247 MB)
- threads 23, stable over 60 s; no HaP-owned thread leak across 3 start/stop cycles
- explicit stop leaves no AceServe process, no sockets, phase=Stopped
- `GET /aio` -> 200 with a valid M3U body; `GET /stat?action=get_status` -> 200 JSON
- `uiautomator dump` still cannot reach idle (before and after); not claimed fixed

L8. Delivery shape. The branch is deliberately larger than the ~400-line review
slice: 5411 authored lines across 9 commits. Splitting further was rejected
because the remaining units do not build independently (HapConfigActivity holds
the T7 refresh and the updater UI in one file, and the updater's support classes
must land before the activity that calls them). Review this branch **per commit**,
not as one diff; the ordering above is the intended reading order, and every
commit builds on its own except the noted activity/support-class pair.

T8 outcome: native host suite (26 cases) and JVM updater-core suite (23 cases,
`app/src/test`), the repo's first tests of either kind. JVM tests for the older
Android-coupled classes (SourceValidator, PlaylistCatalog) were NOT added: they
need Robolectric or a pure-logic extraction, which is a separate refactor.

T10 verification on device (1.4.0 / versionCode 15 installed): the Updates panel
renders in Spanish, is reachable and operable with the D-pad, and "Comprobar
ahora" reaches the GitHub API and correctly reports no published releases for
albersg/IPTV-Plugin-HaP. Still unverified on hardware: download progress and
cancellation, certificate mismatch rejection, the PackageInstaller confirmation
and its status broadcast, and the rate-limit path. Those need a published release
signed with the fork's own key.
