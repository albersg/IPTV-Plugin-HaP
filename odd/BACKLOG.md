# HaP — consolidated backlog

Everything still open across the optimisation brief, the UI redesign and the security audit. Each
line names its evidence and what closes it.

Shipped and no longer listed here: native streaming hardening, the 100 MiB cache unification, the
incremental 1 Hz UI refresh, bounded parallel channel testing, the in-app GitHub Releases updater
(validated end to end on the device, `v1.4.1`), the five-section TV shell with the plain-language
home, the how-to-watch screen, deterministic D-pad navigation, in-place list updates, the first-run
assistant, and four security fixes.

## 1. Needs a human decision (nothing else can proceed)

**D1 — The critical security finding: an exported service authorises nobody.** Any app on the
device can bind `com.streamvault.plugin.API`, start and stop the runtime, turn on LAN exposure, and
read back the stored provider URLs, credentials included. Two constraints collide: this fork is
signed with the human's key while StreamVault is signed with jopsis's, so a signature check refuses
the legitimate caller; and StreamVault is **not installed on the box**, so a package allowlist has
no host to name. Options: (a) close the service now and reopen it when StreamVault is installed,
(b) ship an allowlist the user can populate, (c) accept the exposure. Nothing has been changed.

**D2 — Publish a release or not.** The redesign is installed and runs on the device, but a real
channel has never been played with it. A tag published now would ship a UI validated only as far as
the shell, the states and the proxy.

**D3 — RecyclerView. Done**, `02f6d09`. The human approved the dependency; the channel list now runs on
a `ListAdapter` with a `DiffUtil` callback over the row value model, so an unchanged tick dispatches
nothing, and the other five lists stay in place where they are cheaper. The one exception to the
"never delegate a vertical press" rule is narrow and pinned by a pure unit: it fires only for a direct
recycler child that has a row beyond it, which is what lets the list scroll at all.

Still owed for D3: seeing it work on the box. The code is installed and has not crashed, and the
process stayed alive with nothing in logcat, but Google TV's ambient screensaver kept taking the
screen during the check, so the channel list itself has never been looked at with rows in it.

## 2. Verification owed on the device

**V1 — Play a real channel.** The proxy answers `/aio` with 200 and a valid `#EXTM3U`, but no M3U
source is configured on the box, so the streaming path has not been exercised since the shell was
rewritten. This is the most important open verification.

**V2 — BACK and focus memory, section by section.** BACK returning to Inicio is confirmed on the
device. The per-section anchor was attempted and the test was inconclusive: activating a rail entry
leaves focus on the rail by design, so the check has to move focus into the section first, then leave
and come back. Still owed. BACK from each section returns to Inicio and the
per-section focus anchor restores the last control. Both are covered by unit tests and by the
navigation seen on device, but the round trip per section has not been walked.

**V3 — Stress evidence.** A connection closed mid-stream, several clients with one deliberately slow,
a prolonged stream, channel testing while playing, and a check that no thread or socket is left
behind.

**V4 — Updater negative paths.** Rejection of an APK signed with a different key, an interrupted
download, low free space, a corrupted APK, and GitHub rate limiting.

**V5 — First-run assistant exercised.** It is showing on the box because nothing is configured. Its
steps have not been walked: saving a list, reaching step 3, finishing, and confirming it never
returns. Also confirm BACK leaves without recording a decision, which is the behaviour today.

**V6 — The how-to-watch screen's details.** What OK does on the selectable address, the horizontal
scroll of a long LAN URL, and legibility at three metres.

**V7 — Overscan and TalkBack.** No section has been checked at 720p, and no content description has
been heard through a screen reader.

## 3. Measurements still missing

The brief asks for numbers, and these were never taken: CPU percentage while serving, consumption
with IPFS on versus off, the cost of parsing M3U and EPG, HTTP latency and time to first byte.
Frame timings have not been captured with `dumpsys gfxinfo`, nor RSS stability over a full minute.
What exists: cold start **358 ms**, total PSS **54,857 KB**, **22 threads**, and focus proven stable
across the 1 Hz refresh by identical screencaps six seconds apart.

## 4. Code work pending on the security audit

| ID | Finding | Why it is open |
| --- | --- | --- |
| F5 | `check_peers` opens real P2P sessions outside the concurrency counters, so 32 requests from one LAN host keep 32 peer sessions alive | Needs a bounded counter on that path |
| F9 | Request targets, resolved stream URLs and provider URLs are logged at INFO | Credentials reach logcat and bug reports |
| F10 | A failed playlist refresh re-runs on every request; no single-flight | Needs a lock plus a failure backoff |
| F11 | jQuery 3.3.1 and Bootstrap 4.1.1 are EOL and served from `/stat`; no third-party notices file | Update or document; add a NOTICE |
| F2, F3, F7 | Fixed but unpinned by tests | The native suite compiles only `util.cpp`; each needs its code moved into a unit free of `jni.h`, as was done for `parse_chunk_size` |

## 5. Privacy items from the request

**P3 — Opt-in wipe of cache and logs on stop.** Would clear the streaming cache and the engine log on
an explicit stop, keeping `acestream.conf` and all user data. Not built.

**P4 — Automatic update checks optional.** Today a check runs when the screen opens, gated by a 24 h
interval. A setting to make it manual-only does not exist.

**P5 — Delivered** (the engine log is capped and command lines no longer reach logcat).

## 6. Defects observed on the device and not yet fixed

**The D-pad loses presses and lets focus escape the content.** Reported first because it is the
condition attached to keeping Android Views, and an earlier note in this file claimed navigation was
verified. That claim was too strong: what was verified is the rail on its own. Inside a section it
fails.

Observed, repeatedly, on `Ajustes`:

- Four DPAD_DOWN from Inicio at one-second spacing landed on `Conexiones` (index 3) instead of
  `Ajustes` (index 4). At 1.2 s the same four presses land correctly. So a press is swallowed when
  they arrive faster than roughly once per second.
- From the Privacy switch, one DPAD_DOWN moved focus out of the content and onto `Inicio` in the
  rail, and the following OK therefore did nothing to the switch it was meant to toggle. Focus
  leaving the content downwards is a navigation defect, not a layout one.

Working hypothesis to test rather than assume: the navigator builds one row model for the window and
treats the rail and the section as consecutive rows, so DOWN past the last control of a section wraps
into the rail instead of clamping, and a press arriving while the row model is being rebuilt against
a live tree is dropped. Both would be fixed in `FocusNavigator`, not in the panels.

Until this is fixed and re-verified, the decision to keep Android Views is **not** discharged: the
reviewer's condition was that navigation and focus demonstrably pass, and they do not yet.

- **Collapsed panels no longer hollow out their section.** Fixed in `c74a73a`: a folded panel now uses
half the card padding so it reads as a header row instead of an empty card, and the channel panel -
which is the whole of Canales - starts open. Verified on the box: Canales opens with its three actions
and its hint rather than an empty shell. The other folded panels in Ajustes were not walked after the
change.

- **`Canales`, `Fuentes` and `Ajustes` have not been reviewed visually**, only `Inicio` and
  `Conexiones`. The Fuentes empty state is two buttons and a lot of nothing.

## 7. Contradiction still unresolved

**The OkHttp/Okio thread mystery.** Twenty-two threads run while `dependencyInsight` finds no OkHttp,
no marker appears in the dex, and yet OkHttp-style threads were observed earlier. Resolve by sampling
stack traces (`debuggerd -b <tid>` or Perfetto) rather than guessing.

## 8. Branch hygiene

The branch carries roughly 14,000 authored lines across 38 commits and long ago passed the ~400-line
review slice. It is pushed and a release was cut from it, so history will not be rewritten. If this is
to be reviewed properly, it needs splitting into chained PRs by area: native hardening, privacy,
security fixes, updater, UI shell, UI focus, UI lists, first-run.

## 9. Decisions recorded, with their revisit triggers

- **Stay on Java and Android Views.** No Compose. The condition attached to this decision is device
  evidence for navigation, focus and large lists. Navigation and focus now have it; large lists and
  playback do not. `docs/Decisions-UI-Toolkit.md` keeps the decision conditional until they do.
- **Rejected:** Kotlin, Coroutines/Flow, Asio/libuv, OkHttp, Room, DataStore, Media3. **Deferred:**
  WorkManager. **Targeted only:** libcurl, for a reproducible HTTPS/HLS channel, never for speed.
  Triggers for revisiting each are in `docs/Decisions-Technologies.md`.
- **The AceStream cache stays at exactly 104,857,600 bytes.** It is a product constraint, not a knob.
- **The engine binds every interface and HaP cannot prevent it.** Measured: with LAN mode off the
  proxy is loopback-only as intended, but the engine listens on `0.0.0.0` at 6878, 62062, 8621 and
  its peer port, with or without `--bind-all`. Closing it needs documented AceServe bind options or
  a tunnel outside the app. No app-level fix exists and none should be claimed.
