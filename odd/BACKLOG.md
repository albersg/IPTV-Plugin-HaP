# HaP — consolidated backlog

Everything still open, each line naming its evidence and what closes it. Resolved items are kept
briefly at the end so a later reader does not reopen them.

`docs/architecture/BASELINE.md` is the verified state of each capability. When the two disagree, the
baseline wins.

## 1. Verification owed on the device

**V1 — Play a real channel. Done on the box**, with the signed release carrying this branch's code:
the proxy served `ALTITUDE SPORTS *` for 13 minutes to a client through `adb forward`, 1,271,559 TS
packets, 0 continuity errors, 0 PCR discontinuities, one 164-byte start alignment. Two clients, one of
them limited to 100 KB/s, shared one upstream (56 MB and 7 MB served). Numbers in `docs/Measurements.md`.
Not exercised: a player rendering it on screen (Kodi is installed but has no AceStream source).

**V2 — Transport health on screen. Done.** On the box with the real stream the panel read
`ALTITUDE SPORTS * · 8,4 Mb/s · 0 errores de continuidad · datos hace 0 s`; on the emulator the host
fixture exercised the other two wordings (`no MPEG-TS recognised`, `no data for 120 s`). The block
sits above the Refresh button because below the client rows it fell past the last focusable control.

**V3 — BACK and focus memory, section by section.** BACK returning to Inicio is confirmed on the
device, again in this walk. The remaining half is the round trip: enter a section, move three rows,
leave with BACK, come back, and land where you left. Activating a rail entry leaves the focus on the
rail by design, so the walk has to press RIGHT first; the next attempt must do that and then compare
the focused control before and after, not the rail label.

**V4 — D-pad and focus inside a section. Done on the box.** `tools/tv-audit.sh` enters every section
from a cold start (DOWN ×N + OK + RIGHT, all inside one `adb shell`) and records the focused control
at each step. The traces: Inicio server switch → how-to card → lists card (clamped); Canales status →
load lists → search field (clamped); Eventos stays on `Todos`, whose next neighbour is to the right;
Fuentes two buttons (clamped); Conexiones Copy → LAN switch → refresh → restart (clamped); Ajustes
IPFS → Copy → Advanced → Log → wipe → recovery → check now → update settings (clamped). One real
finding: RIGHT is required after OK, because activating a rail entry leaves the focus on the rail.

**V5 — RecyclerView rows at full length. Done, and it found a defect.** The first walk showed the
Canales section saying "Carga una lista para ver sus canales" with two playlists on disk and the
server stopped: the catalogue was only filled by the proxy or by pressing "Cargar listas". The
section now fills itself from the playlist files when it opens with nothing rendered, and the box
then showed `lista-unificada.m3u (388)`. Walking the list reached the last row (`UFC FIGHT PASS *`),
where forty more DOWN presses changed nothing: rows recycle, focus follows and the app stays up.

**V6 — Stress evidence.** A connection closed mid-stream, several clients with one deliberately slow,
a prolonged stream, channel testing while playing, and a check that no thread or socket is left
behind. `tools/device-check.sh leak-check` measures the last part.

**V7 — Updater negative paths.** Rejection of an APK signed with a different key, an interrupted
download, low free space, a corrupted APK, and GitHub rate limiting. The happy path was validated
end to end for `v1.4.1`.

**V8 — First-run assistant exercised.** The card ("Set up HaP in 3 steps") was seen on the emulator
after `pm clear`, below the primary action, and is reachable with the D-pad. Its steps have not been
walked: saving a list, reaching step 3, finishing, and confirming it never returns. Also confirm BACK
leaves without recording a decision.

**V9 — The how-to-watch screen's details.** What OK does on the selectable address, the horizontal
scroll of a long LAN URL, and legibility at three metres.

**V10 — Overscan and TalkBack.** At 1280x720 / 213dpi the emulator renders the same 960x540dp
layout with no edge clipping (the section scrolls under the footer as designed). Accessibility now
has evidence: six switches had no label of their own and were announced as "switch, off" with no
subject; each one carries its row label now, `ControlLabelsTest` pins it, and `tools/tv-audit.sh`
walked all six sections with eleven D-pad positions each (and four on the final build), every position reporting zero focusable nodes without a name
(`tools/a11y-audit.py`, with a negative control). Not done: hearing it through TalkBack itself.

## 2. Measurements still missing

`docs/Measurements.md` now holds the big ones: cold start, idle CPU, the guide chain, the filter on
real data, the transport probe on the host, and - new - a real 13-minute stream with CPU, RSS,
threads and sockets, plus IPFS on versus off. Still missing: the cost of parsing M3U and EPG, HTTP
latency and time to first byte, `dumpsys gfxinfo` frame timings, and RSS stability over a full minute
at rest.

## 3. Code work still open

| ID | Item | Why it is open |
| --- | --- | --- |
| ~~P4~~ | ~~Make the automatic update check optional (manual-only)~~ | Resolved: it was already implemented. `maybeRunAutomaticUpdateCheck` asks `UpdateSettings.isAutomaticCheckDue`, which starts with `isAutomaticCheckEnabled`, and the switch in the panel turns it off; with it off, only "Comprobar ahora" checks. The switch was missing its TalkBack label, not its behaviour |
| — | Resolve the OkHttp/Okio thread mystery | 22 threads run while `dependencyInsight` finds no OkHttp and no marker appears in the dex, yet OkHttp-style threads were observed earlier. Resolve by sampling stack traces (`debuggerd -b <tid>` or Perfetto), not by guessing |
| — | Visual review of `Canales`, `Fuentes`, `Ajustes` | Only `Inicio` and `Conexiones` have been reviewed visually; the `Fuentes` empty state is two buttons and a lot of nothing |
| — | Confirm the folded panels in `Ajustes` | `c74a73a` changed the collapsed padding and was verified for `Canales`; the other folded panels were not walked after it |
| — | D-pad row order outside `Conexiones` | The fix is global (`FocusRows`), verified on `Conexiones`, `Inicio` and the rail; no other section had a two-column composition to exercise it, so a walk of `Fuentes` and `Ajustes` on the box is still owed |
| ~~Prebuffer watchdog~~ | Implemented behind the recovery switch: 20 s of no progress **and no peers** in a prebuffer state ends the session so the retry can run. Measured: fires 20 s after the session starts, leaves a healthy channel alone (`recoveries 0`), and closes a dead channel's client at 30 s instead of 80 s. Limits written in `odd/tasks/hap-stream-recovery.md`: a peerless channel slower than 20 s would be retried instead of waited for, and the retries after a forced stop burn in ~6 s |
| — | Vendor AceStream app on the same box | Measured: with `org.acestream.node.web` running, HaP's server start fails ("El servidor tiene un problema") because the vendor app holds the engine; force-stopping it made the same build start. A real coexistence note for the UI or the docs, not yet handled |
| — | IPFS is on but not running | Observed while restoring the box: after a cold start with the server stopped, the IPFS switch read on while the panel said `Parado` and no `libipfs.so` ran; toggling it off and on started it. Either it should start with the preference or the switch should follow the process, but the two must not disagree |
| — | Rail escape from a right-edge control | On the box, LEFT from a switch at the right edge does not reach the rail and BACK from Inicio content leaves the activity, so the reliable way back to the rail is a cold start. Not necessarily wrong (BACK from other sections goes home), but the walk should not assume LEFT escapes |
| — | Branch hygiene | The branch carries roughly 14,000 authored lines across 38 commits and long passed the ~400-line review slice. History will not be rewritten this late; a proper review needs chained PRs by area |

## 4. Stream recovery

A bounded retry of the same upstream now exists behind a switch that ships off
(`odd/tasks/hap-stream-recovery.md`): when the reader ends while clients are still waiting, the source
is reopened at most 3 times with exponential backoff, and each broadcast counts its `recoveries` in
`/status`. It recovered a real cold channel on the box after two attempts (368,083 packets after the
retry) and does not touch a healthy stream.

What is still missing before it can become the default:

- A no-payload watchdog shorter than the 30 s read timeout. Today a client can wait the whole timeout
  before the first retry starts; the threshold has to be measured, not guessed.
- A player test: a retry is a new session (new PIDs, continuity and PCR), so whether the player resumes
  or has to reload is unknown.
- The prebuffer watchdog above: today the retry waits for the engine's own prebuffer window.
- A comparative run against the flag off, on the same channel and time window, to show it does not make
  things worse.
- Source failover and engine switching remain out of scope: they need this one to be proven first.

## 5. Fast start, measured and not tuned

Cold start per channel on the box, six channels, server freshly started: time to first byte **0.98,
1.21, 1.34, 1.39, 1.40 and 1.73 s**; `/aio` (388 channels, 42 KB) 8.6-17.6 ms and `/stat` 4.6-11.5 ms.
Two of the six were silent channels that sent zero bytes and were closed at ~31 s.

Consequences recorded rather than features added:

- No buffer or timeout tuning: the first byte arrives in about a second, so the knob with the most
  room is not the buffer.
- No keep-warm: holding a broadcast alive after the last client would save at most ~1.4 s on a return
  within the window, at the cost of keeping an engine session (39-50 % of a core, ~280 MB) alive for a
  channel nobody is watching. Not worth it; the source is the engine, not the proxy.

## 6. Decisions recorded, with their revisit triggers

- **Stay on Java and Android Views.** No Compose. Large lists now have device-adjacent proof
  (RecyclerView + units) but not a device walk; playback remains unverified, so the decision stays
  conditional in `docs/Decisions-UI-Toolkit.md`.
- **Rejected:** Kotlin, Coroutines/Flow, Asio/libuv, OkHttp, Room, DataStore, Media3. **Deferred:**
  WorkManager. **Targeted only:** libcurl, for a reproducible HTTPS/HLS channel, never for speed.
  Triggers for revisiting each are in `docs/Decisions-Technologies.md`.
- **The AceStream cache stays at exactly 104,857,600 bytes.** It is a product constraint, not a knob.
- **The engine binds every interface and HaP cannot prevent it.** Measured: with LAN mode off the
  proxy is loopback-only as intended, but the engine listens on `0.0.0.0` at 6878, 62062, 8621 and
  its peer port, with or without `--bind-all`.

## 7. Resolved, kept so they are not reopened

- **D1 — exported StreamVault service.** Closed: `StreamVaultHapPluginService` is
  `android:exported="false"`. The manifest keeps the exact one-line reopening instructions for when
  StreamVault is installed and a real caller check exists.
- **D3 — RecyclerView.** Delivered in `02f6d09`; the device walk is V5 above.
- **Focus lost to the rail while a service transition disables the focused control.** Fixed and
  verified on the box: the control is remembered for the duration of the transition and given focus
  back when it ends (`odd/tasks/hap-focus-during-transitions.md`). This was the real cause behind the
  old "DOWN from the Privacy switch escaped to the rail" report, not the navigator's row model.
- **F5** — peer checks are capped at two and wait with a deadline (`proxy.cpp`, `test_util.cpp`
  sibling coverage in `proxy` code).
- **F9** — request targets and resolved URLs are redacted; pinned by `LogRedactionTest` and
  `RedactRequestTarget`/`RedactUrl` in `test_util.cpp`.
- **F10** — playlist refresh has single-flight plus failure backoff (`plugins.cpp`,
  `refresh_if_needed`).
- **F11** — `THIRD-PARTY-NOTICES.md` documents the EOL jQuery/Bootstrap versions and why they remain.
- **F2, F3, F7** — pinned by `test_util.cpp` (`RedactUrl`, `ControlCharacters`,
  `EffectiveClientAddress`).
- **P3** — opt-in wipe on explicit stop, `StopWipe` + `StopWipeTest`.
- **P5** — capped engine log and no command lines in logcat.
