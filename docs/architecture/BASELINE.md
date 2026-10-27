# HaP — verified baseline

What the code in this repository does **now**, and how each claim is known. This is not an audit
history: when a line here disagrees with `odd/BACKLOG.md`, this file wins, because every entry names
the code or the command that proves it.

- Verified on: 2026-10-26.
- Branch: `main` at `02dd869`, plus the transport-health slice described below.
- App version: `1.4.1` (`versionCode` 16).
- Target hardware: Xiaomi TV Box S 3rd Gen (`MiTV-AFMU0` / `twilight`), Android 14 / SDK 34,
  Amlogic S905X5M, `armeabi-v7a` only, 4096-byte pages, 960x540dp layout surface.

## How the state was verified

| What | Command or file | Result |
| --- | --- | --- |
| JVM + Robolectric suite | `./gradlew :app:testUniversalDebugUnitTest` | **254 tests, 0 failures**, 29 files |
| Native suite | `cmake -S native-tests -B build/native-tests && ctest --test-dir build/native-tests` | **109 tests, 0 failures** |
| APK | `./gradlew :app:assembleUniversalDebug` | green, both ABIs |
| Lint | `./gradlew :app:lintArmv7Debug` | green, 0 errors (133 warnings, none new-blocking) |
| CI | `.github/workflows/native-tests.yml`, `build-apk.yml` | ctest + lint + release build; release requires the signing secrets |

`AGENTS.md` is the build/verify contract. The `graphify-out/` map it used to name is not present in
this repository; the code areas it lists are still accurate.

## Capability classification

### Implemented and tested

| Capability | Evidence |
| --- | --- |
| Native proxy core: one upstream per infohash, non-blocking fan-out, shared chunk buffers, bounded connection cap, cancellable channel tests | `broadcast.cpp`, `http_server.cpp`, `ChannelTestBatchTest`, native suite |
| 100 MiB AceServe cache (product constraint, not a knob) | `config.cpp`, `docs/Measurements.md` |
| M3U catalogue: parsing, validation, custom channels, local `localfile:` sources | `M3uParserTest`, `LocalSourceFileTest`, `LocalListFilesTest`, `test_local_source.cpp` |
| Guide: disk cache, gzip, incremental XMLTV window, 30k-programme test, event extraction, sport filter | `GuideCacheTest`, `EpgWindowTest`, `EventCatalogTest`, `EventSportTest`, `ChannelGuideMatcherTest`; emulator run recorded in `odd/tasks/hap-events-guide.md` |
| TV shell (five sections) and phone shell (bottom bar) | `activity_hap.xml`, `layout/`, `layout-w600dp/`, `HapSectionsTest`, `RailFocusTest` |
| D-pad graph: clamped sections, wrapped rail, bounded RecyclerView hand-off, focus memory | `FocusGridTest`, `ScrollHandoffTest`, `FocusRefreshPurityTest` |
| Channel list on `RecyclerView` with `DiffUtil` | `ChannelRecyclerTest`, `ChannelDiffTest` |
| Updater: GitHub Releases, signature check, install receiver | `UpdateCoreTest`; v1.4.1 validated end to end on the box |
| Privacy: explicit-stop wipe, log redaction, capped engine log | `StopWipeTest`, `LogRedactionTest` |
| Security: peer checks capped at 2, chunk-size bound, control-byte rejection, CORS on credentialed endpoints, real peer address | `test_util.cpp`, `test_chunk_parser.cpp`, `proxy.cpp` |
| Playback state per upstream (`starting`…`failed`) and session generation | `playback_state.cpp`, 9 host tests, `TransportStatusTest`; box: live `playing`/gen 1, dead `prebuffering`→`recovering`/gen 2-5 |
| Transport health: per-upstream continuity, PCR, resync, arrival rate, stall | `ts_probe.cpp`, 16 `TsProbe` tests, `TransportStatusTest`; measured below |
| IPFS: bundled kubo on its own supervisor thread, LAN switch tied to `ProxyExposure` | `IpfsRuntime`, `IpfsSettings`, `HapServiceStatesTest` |
| StreamVault companion service: **closed to other apps** | `AndroidManifest.xml` (`android:exported="false"`), reopening comment in place |
| TalkBack can name every control | `ControlLabelsTest` (every `Switch` carries its row label); `tools/a11y-audit.py` over `tools/tv-audit.sh` on the box: 6 sections × 11 D-pad positions, then 6 × 4 on the final build, **0 focusable nodes without a name** each time, with a negative control |
| Channel list without the server | Canales fills itself from the playlist files when it opens with nothing rendered; verified on the box (`lista-unificada.m3u (388)` with the server stopped) |

### Implemented, pending validation

These exist in the app and have automated coverage where the logic is testable, but the behaviour on
the box has not been observed, or was observed before the current shell.

| Capability | What is missing |
| --- | --- |
| Transport line in `Conexiones` | seen only in JVM tests; never on a TV with a real stream |
| Real channel playback since the shell rewrite | no source configured on the box yet (V1 in the backlog) |
| D-pad round trip per section, taps vs. D-pad | units pin the graph; the per-section walk on the box is owed |
| RecyclerView rows at full length | code installed, rows never looked at with a real list |
| Stress: slow client, several clients, no leaked thread/socket | not run since the fan-out rewrite |
| Updater negative paths | wrong-key APK, interrupted download, low space, corrupted APK, rate limit |
| First-run assistant, how-to-watch screen, overscan, TalkBack | flows exist; not walked |
| Sustained CPU/RSS, IPFS on vs. off, TTFB | not measured (`docs/Measurements.md` lists the gap) |
| 16 KiB page devices | JNI link flag is set; the proprietary AceServe and kubo binaries are unverified |

### Experimental

| Capability | State |
| --- | --- |
| Bounded retry of the same upstream (`Ajustes → Recuperación de emisión`) | Implemented and **off by default**: up to 8 attempts inside a 90 s window per failure, exponential backoff with deterministic jitter capped at 8 s, `recoveries` in `/status`, a shortened upstream read timeout and a peerless no-progress watchdog while it is on. Recovered a real cold channel on the box after 2 attempts (368,083 packets afterwards); no false retry on a healthy stream. Measured: the watchdog ends a peerless silent session 20 s in, a healthy channel is untouched (`recoveries 0`), and a real Wi-Fi cut keeps the client connected for 67 s. A real 30 s Wi-Fi cut now recovers on the same client response (26.2 MB → 135.3 MB, `recoveries` 2, **41 s** of interruption) because every retry opens a fresh engine API connection and a retry session gets a shorter silence window. Limit: a peerless channel that needs more than 20 s to find peers would be retried instead of waited for. Not yet a default: no player has been tested across a retry, and a retry restarts the session's PIDs, continuity and PCR. See `odd/tasks/hap-stream-recovery.md`. |

`HapDebugControlActivity` exists only in debug builds (`app/src/debug/AndroidManifest.xml`) and is not
part of the product surface.

### Proposed — no code yet

Ordered by the sequence in which they reduce risk, not by novelty.

1. **PlaybackCoordinator**: the observational half is in place - one state per upstream
   (`starting`, `prebuffering`, `playing`, `degraded`, `recovering`, `failed`) plus a session
   generation, published in `/status` and pinned by `test_playback_state.cpp`, with box evidence in
   `odd/tasks/hap-playback-coordinator.md`. What remains is the deciding half: budgets, the
   `switching_source`/`switching_engine` states, and moving the retry and watchdog decisions onto it,
   which needs a comparative run first.
2. **Engine capability layer**: `AceServeEngineAdapter` first, a capability description (HLS, TS,
   seek, metrics, encryption) instead of forcing every engine to fake every method.
3. **Outpace adapter**: external engine on PC/NAS first. See the blocked note.
4. **Timeshift / catch-up / DVR**: only where the source or the engine holds history. Requires a real
   answer about native seekback in the installed AceServe version, judged on the device.
5. **Internal playback harness (Media3)**: a test-only path to observe decoding; never a replacement
   for external players.
6. **Secure remote administration** (phone → box pairing) and reopening StreamVault with real caller
   authentication.
7. **Persistent catalogue index (Room/SQLite)**: only if guide/catalogue growth outgrows the current
   in-memory window; the decision trigger is in `docs/Decisions-Technologies.md`.
8. **Supply chain**: SBOM, pinned hashes, signed provenance.

### Blocked

| Item | Why it is blocked |
| --- | --- |
| Reopening the StreamVault bound service | this fork is signed with a different key than StreamVault, and StreamVault is not installed on the target box; the manifest keeps the exact reopening instructions |
| Stopping the AceStream engine from binding `0.0.0.0` | measured on the box: the engine listens on all interfaces with or without `--bind-all`; no app-level fix was found |
| Shipping Outpace inside the APK | AGPL-3.0-or-later next to a proprietary engine is a distribution decision, not an engineering one; external use can be evaluated without shipping it |
| 16 KiB support for proprietary binaries | the vendor binaries are not ours to rebuild |
| Coexistence with the vendor AceStream app | measured: while `org.acestream.node.web` runs, HaP's server start fails because the vendor app holds the engine; force-stopping it made the same build start. Not handled in the UI yet |

## Transport health, measured

Taken on the build host (x86-64, g++ 14, `-O2`), synthetic 188-byte packets fed in 64 KiB chunks:

| Metric | Value |
| --- | --- |
| Bytes analysed | 268,500,096 (256 MiB) |
| Packets | 1,428,192 |
| Continuity / sync errors | 0 / 0 |
| Wall time | **12 ms** |
| Throughput | **~21 GiB/s**, single thread |

At the box's real 1.5 Mbps that is roughly 0.009 % of one core. The probe runs inside the existing
upstream reader, adds no thread, and keeps at most 64 PID entries in a fixed table.

## What this baseline deliberately does not claim

- Anonymity for AceStream traffic: it is P2P, and HaP cannot provide it on its own.
- Zero cuts, or a fixed latency improvement: no comparative run exists yet.
- Anything about the picture on screen from transport counters: a clean TS can still freeze in an
  external player.
- Hardware behaviour from emulator-only evidence.
