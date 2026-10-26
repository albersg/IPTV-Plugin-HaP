# HaP — consolidated backlog

Everything still open, each line naming its evidence and what closes it. Resolved items are kept
briefly at the end so a later reader does not reopen them.

`docs/architecture/BASELINE.md` is the verified state of each capability. When the two disagree, the
baseline wins.

## 1. Verification owed on the device

**V1 — Play a real channel.** The proxy answers `/aio` with 200 and a valid `#EXTM3U`, but no M3U
source is configured on the box, so the streaming path has not been exercised since the shell was
rewritten. This is the most important open verification, and it is also V2/V3's prerequisite.

**V2 — Transport health on screen.** The new `Conexiones` block reads `streams` from the same
`/stat?action=get_clients` answer the client list uses. Parsing is pinned by `TransportStatusTest`;
what has never been seen is one line per upstream on the TV while something plays, including the
stalled wording. Closes with a screenshot of a live stream and one of a stopped upstream.

**V3 — BACK and focus memory, section by section.** BACK returning to Inicio is confirmed on the
device. The per-section anchor was attempted and the test was inconclusive: activating a rail entry
leaves focus on the rail by design, so the check has to move focus into the section first, then leave
and come back. Both behaviours are covered by units; the round trip per section has not been walked.

**V4 — D-pad and focus inside a section, on the box.** The reported defects (a press swallowed when
keys arrive faster than ~1 Hz, DOWN from the Privacy switch escaping into the rail) were addressed in
`b6196d2` and `02f6d09`: `FocusNavigator` consumes every vertical press it resolves, and the single
hand-off is bounded by `ScrollHandoff` to a recycler row that has a row beyond it. Units pin the
arithmetic; the box has not seen it since. Closes when four fast DOWN presses reach `Ajustes` and DOWN
from the last privacy control stays in the section.

**V5 — RecyclerView rows at full length.** The list is installed and has not crashed, but Google
TV's ambient screensaver took the screen during the check, so the list has never been looked at with
rows in it. Closes with the list scrolled to both ends and focus following.

**V6 — Stress evidence.** A connection closed mid-stream, several clients with one deliberately slow,
a prolonged stream, channel testing while playing, and a check that no thread or socket is left
behind. `tools/device-check.sh leak-check` measures the last part.

**V7 — Updater negative paths.** Rejection of an APK signed with a different key, an interrupted
download, low free space, a corrupted APK, and GitHub rate limiting. The happy path was validated
end to end for `v1.4.1`.

**V8 — First-run assistant exercised.** It shows because nothing is configured; its steps have not
been walked: saving a list, reaching step 3, finishing, and confirming it never returns. Also confirm
BACK leaves without recording a decision.

**V9 — The how-to-watch screen's details.** What OK does on the selectable address, the horizontal
scroll of a long LAN URL, and legibility at three metres.

**V10 — Overscan and TalkBack.** No section has been checked at 720p, and no content description has
been heard through a screen reader.

## 2. Measurements still missing

`docs/Measurements.md` holds what exists (cold start 358 ms, PSS 54,857 KB, 22 threads, idle CPU,
the guide chain, the filter on real data) and the transport probe's host throughput. Still missing:
CPU percentage while serving, consumption with IPFS on versus off, the cost of parsing M3U and EPG,
HTTP latency and time to first byte, `dumpsys gfxinfo` frame timings, and RSS stability over a full
minute. One run of `tools/device-check.sh scenario` closes most of them.

## 3. Code work still open

| ID | Item | Why it is open |
| --- | --- | --- |
| P4 | Make the automatic update check optional (manual-only) | `UpdateSettings` stores an interval and an on/off flag, but the screen offers no manual-only mode; today a check runs when the screen opens, gated by the stored interval |
| — | Resolve the OkHttp/Okio thread mystery | 22 threads run while `dependencyInsight` finds no OkHttp and no marker appears in the dex, yet OkHttp-style threads were observed earlier. Resolve by sampling stack traces (`debuggerd -b <tid>` or Perfetto), not by guessing |
| — | Visual review of `Canales`, `Fuentes`, `Ajustes` | Only `Inicio` and `Conexiones` have been reviewed visually; the `Fuentes` empty state is two buttons and a lot of nothing |
| — | Confirm the folded panels in `Ajustes` | `c74a73a` changed the collapsed padding and was verified for `Canales`; the other folded panels were not walked after it |
| — | Branch hygiene | The branch carries roughly 14,000 authored lines across 38 commits and long passed the ~400-line review slice. History will not be rewritten this late; a proper review needs chained PRs by area |

## 4. Next engineering step

The transport probe is the first input of the recovery work. The next unit is a
`PlaybackCoordinator` with an explicit state machine and session generations, consuming these counters
instead of guessing from an open socket. It must stay behind a switch until a comparative run against
today's behaviour exists, and it must not restart a stream that is merely slow: a drop in peers or a
short bitrate dip is not a failure.

## 5. Decisions recorded, with their revisit triggers

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

## 6. Resolved, kept so they are not reopened

- **D1 — exported StreamVault service.** Closed: `StreamVaultHapPluginService` is
  `android:exported="false"`. The manifest keeps the exact one-line reopening instructions for when
  StreamVault is installed and a real caller check exists.
- **D3 — RecyclerView.** Delivered in `02f6d09`; the device walk is V5 above.
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
