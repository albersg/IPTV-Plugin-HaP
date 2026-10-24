# Changelog

All notable changes to HaP will be documented here.

## [1.4.0] - 2026-10-24

Performance and stability work targeting the Xiaomi TV Box S 3rd Gen
(`MiTV-AFMU0`, Android 14, Amlogic S905X5M, **armeabi-v7a only**, 1.96 GB RAM).

### Fixed

- **Slow clients no longer stall the stream for everyone.** `broadcast_chunk`
  pushed each chunk to every client with a blocking wait, one client after
  another, inside the single reader thread; one stalled client therefore stalled
  the upstream reader and everything else on that broadcast. The producer no
  longer blocks: overflow drops the oldest chunk for the client that fell
  behind, and the existing "client too slow" detection still closes a client
  that stays stuck.
- **The live cache is now what it claims to be.** `AceServeRuntime` passed
  `--live-mem-cache-size 104857600` (100 MiB), but the AceServe bootstrap in
  `main_android.py` overrode the player config with `live_cache_size =
  1048570000` — ten times larger, ~1000 MiB — and forced the cache type to disk,
  including through `adjustCacheSettings`. Both now use a single
  `LIVE_CACHE_SIZE_BYTES = 104857600` constant.
- **The configuration screen no longer rebuilds itself every second.** The 1 Hz
  refresh called `HapBridge.status()` on the main thread, called
  `removeAllViews()` and re-inflated the status pills, and allocated a new
  background `Drawable` per pill on every tick. It also created a new `Button`
  per endpoint per tick, which accumulated in `busyControls` forever. The status
  read now runs off the main thread, the view hierarchy is built once, and a
  property is written only when its value actually changed.
- **Channel testing no longer freezes the screen.** Every background action
  shared one single-thread executor, so a list test — up to ~8 s per channel —
  queued every refresh, source change and playlist load behind it. Tests now run
  on their own executor, and stopping a test closes the in-flight request's
  socket so the local proxy observes the disconnect, which lets the native peer
  session be torn down instead of running to `max_wait`.
- **Native HTTP server hardening:** a bound on concurrent connections, a receive
  timeout on accepted sockets (a client that connected and sent nothing used to
  hold a thread indefinitely), backoff instead of a busy `accept()` loop on
  `EMFILE`/`EBADF`, `EINTR` no longer treated as a fatal send error, and a
  shutdown that waits for in-flight connections instead of letting detached
  threads outlive the handler they capture.
- **Channel switching no longer serializes on engine authentication:** the
  broadcast manager held its mutex across a blocking `AceEngine` authenticate.
- `check_channel_peers` polled `ace.status(1)` in a tight loop with no delay and
  no overall deadline.

### Added

- Host-side GoogleTest suite (`native-tests/`) covering URL parsing and
  `path_is_safe_relative`, the guard that keeps `/stat/<path>` from serving files
  outside the bundled `http/` directory. 26 cases, run by `ctest`.
- `tools/device-check.sh`: reproducible on-device measurements (hardware facts,
  per-process RSS, thread and socket counts, start/stop leak checks).
- A debug-only control activity (`app/src/debug/`) so the runtime can be started,
  stopped and inspected over ADB instead of the D-pad. Release builds contain
  neither its manifest entry nor an exported control surface.
- CI workflow `native-tests.yml`: native unit tests, Android lint, and an APK
  size report per flavor.
- `docs/Fork-and-Releases.md`: how to publish signed releases from a fork, and
  why an APK signed with a different key cannot replace an existing install
  without losing app data.

### Measured on the reference device

Before/after, same device, `:app:assembleArmv7Debug`, no sources configured:

| Metric | Before | After |
| --- | --- | --- |
| Cold start (`am start -W`) | 2286 ms | 2084 ms |
| Idle RSS while the screen is open | 130 MB, climbing to 247 MB | 116 MB, flat/declining to 107 MB over 60 s |
| App threads while idle | 24 | 23, stable over 60 s |
| `uiautomator dump` | never reaches idle state | still never reaches idle state |

Not measured, and therefore not claimed: multi-client streaming throughput,
`check_channel_peers` cancellation latency against a real broadcast, and
long-session memory behaviour. The 100 MiB cache value is applied by code but
its effect on playback has not been profiled on hardware.

The configuration screen still never reports an idle state to `uiautomator`,
before and after these changes. The 1 Hz refresh is deliberate, so screens driven
by that tool remain unreliable; the debug-only control activity exists precisely
so ADB-driven testing does not depend on it.

## [1.3.3] - 2026-09-09

### Fixed

- The 1.3.2 per-ABI APKs only dropped the other ABI's unused *native
  libraries* (~20MB); the AceServe engine's per-ABI zip
  (`assets/aceserve/<abi>/ace-<abi>.zip`, ~40-45MB) still shipped in full in
  every APK, including the per-ABI ones, because `android.splits.abi` only
  filters `lib/<abi>/`, not `assets/`. Per-ABI product flavors now also give
  each ABI its own asset source set, dropping the other ABI's AceServe zip
  too. The `armeabi-v7a`/`arm64-v8a` APKs are now ~65-70MB (down from
  ~105-110MB in 1.3.2), instead of the universal APK's ~125MB.

## [1.3.2] - 2026-09-09

### Fixed

- The universal APK (both ABIs bundled) is ~125MB because of the kubo (IPFS)
  binary; on storage-constrained 32-bit Android TV boxes this could make a
  clean install fail with a generic "app not installed" error even though
  the armeabi-v7a native library itself was present and valid. The release
  now also publishes per-ABI APKs
  (`IPTV-Plugin-HaP-<version>-armeabi-v7a.apk`, `-arm64-v8a.apk`), each about
  20MB smaller by dropping the other ABI's unused native libraries, alongside
  the existing universal APK.

## [1.3.1] - 2026-09-08

### Fixed

- Enabling the external player server no longer sends a proxy restart and a
  service start at the same time. With the service already running, the
  finished supervisor thread let `ACTION_START` spawn a new one alongside the
  `proxy-restart` thread, so HTTPAceProxy was stopped and started twice at
  once; when the readiness check lost the race, the supervisor tore down
  AceServe and the proxy and left the service in the `Failed` phase.
  `HapBridge` now picks a single service action per configuration change: a
  stopped service starts (it reads the listen host on start), a running one
  only restarts the proxy, and only when the listen host actually changed.
- `cast.rewriteUrl` in `StreamVaultHapPluginService` used the same
  server-mode-then-start pair and had the same double restart; it now goes
  through the shared `HapBridge.enableServerModeAndStart()` helper.

## [1.3.0] - 2026-09-02

### Added

- IPFS panel with a master switch that prepares and runs a bundled kubo
  (IPFS) daemon per device ABI (`arm64-v8a`, `armeabi-v7a`), independent from
  the AceServe/HTTPAceProxy runtime supervisor.
- Local IPFS gateway (default `http://127.0.0.1:8080`) that binds to the LAN
  when the existing "LAN server" switch is on, mirroring HTTPAceProxy
  exposure.
- Advanced IPFS settings: external node mode (host/port of an already-running
  IPFS node instead of the bundled one), gateway port, and disk storage
  quota.
- `tools/fetch-kubo.sh` to build the `libipfs.so` binaries locally (arm64-v8a
  needs only Go; armeabi-v7a requires the Android NDK for cgo linking); CI
  builds and caches them before assembling the release APK.
- `:app:preBuild` Gradle check that fails with a clear message if the IPFS
  binaries are missing from `jniLibs`.

## [1.2.2] - 2026-06-28

### Added

- Local TS proxy (startTsfProxy) in `handlePreparePlayback` that strips AceStream 72-byte framing headers from streams before forwarding to ExoPlayer, using raw TCP sockets instead of HttpURLConnection
- Triple 0x47 sync-byte verification at 188-byte intervals to handle AceStream framing headers of any size
- Batch packet writes to reduce syscall overhead during TS streaming
- Automatic retry mechanism (up to 60 attempts, 2s interval) when the HaP proxy resets the connection — the ExoPlayer socket stays alive during retries
- HTTP redirect following (302) in the proxy thread
- `PeerResultInfo.errorResult()` public factory method for creating error results from outside the package
- Diagnostic logging with `HaP-TSF` tag for TS proxy debugging

### Fixed

- Channel list height now uses WRAP_CONTENT instead of dp(320), fixing scrolling on phone form factors where nested ScrollViews intercepted touch events
- Test errors now store a `PeerResultInfo` in `peerResults` map so each channel row displays the error (red pill + error text) instead of only showing it in the feedback area

## [1.2.1] - 2026-06-01

### Added

- Added transparent support for `api.acestream.me/all` and `api.acestream.me/search` sources, formatting AceStream API JSON results as M3U channels internally.

## [1.2.0] - 2026-06-01

### Added

- Added standalone launcher support for phones and Android TV/Leanback so HaP can be opened without StreamVault.
- Added an External player server mode that enables LAN exposure, starts HaP, and remembers the mode for automatic startup after boot or app update.
- Added local and LAN M3U/EPG URL rows in the configuration UI for players such as TiviMate, OTT, and other IPTV clients.
- Added the installed app version next to the HaP title in the configuration UI.

### Changed

- Updated app labeling and plugin metadata to describe HaP as both a StreamVault companion and an external IPTV player server.
- Refreshed the launcher icon and Android TV banner with HaP lettering and multimedia branding.
- Made Channel status collapsed by default.

## [1.1.5] - 2026-05-18

### Fixed

- Fixed a race condition on first start where the HTTP proxy could fail to open because AceServe's API port (62062) was not yet ready when the HTTP port (6878) responded first. The supervisor now requires both ports to be accepting connections before starting the proxy.

## [1.1.4] - 2026-05-17

### Added

- Transparent support for M3U sources protected by the TestCookie-Nginx JavaScript challenge. Sources that respond with a `slowAES`-based page now have the AES-CBC cookie puzzle solved locally using `javax.crypto` (no new dependencies) and are retried automatically with the resulting `__test` cookie. Solved cookies are cached per hostname for six hours, matching the server-side TTL. Both the runtime M3U downloader (`NativeHttpBridge`) and the source validator (`SourceValidator`) participate in challenge resolution.

## [1.1.3] - 2026-05-14

### Changed

- Advertise a canonical `x-tvg-url` in generated HaP playlists and serve `/aio/epg.xml` as a local XMLTV endpoint for StreamVault EPG sync.
- Preserve EPG URLs declared by user M3U sources and route StreamVault EPG requests to the first XMLTV source, falling back to a valid empty XMLTV document when no source EPG is available.

## [1.1.2] - 2026-05-14

### Changed

- Improved M3U source feedback so validation errors and successful additions are shown next to the form.
- Added direct gateway fallback for `dweb.link` IPFS/IPNS playlist URLs when Chromecast cannot connect to the gateway host.
- Made Channel status open by default, prepare HaP before loading lists, and show TV-focusable channel controls.

## [1.1.1] - 2026-05-12

### Changed

- Hide the plugin APK from Android and Android TV launchers while keeping StreamVault activity configuration available.

## [1.1.0] - 2026-05-12

### Added

- Initial public release of the StreamVault HaP Plugin APK.
- StreamVault plugin API service with support for M3U provider, playback preparation, Cast URL rewriting, and native activity configuration.
- Native Android/TV configuration activity for runtime controls, source management, connected clients, channel status testing, and logs.
- User-managed M3U sources with validation for AceStream-compatible content.
- Custom channel editor for manually adding AceStream IDs.
- Bundled AceServe and HTTPAceProxy runtime integration for local playback.
- Signed APK release automation through GitHub Actions.
- GitHub Release publishing with versioned and stable APK download assets.
