# HaP — bounded retry of the same upstream

The proxy used to close every client the moment the upstream reader ended, whatever the reason. A
live channel that went back to `prebuf` and timed out, or whose HTTP session dropped, killed the
playback it was serving. This opens the same source again a bounded number of times, with backoff,
before giving up — behind a switch that ships off.

## Specs

S1 — The recovery sequence, verbatim from the brief: «1. Confirmar que existe un fallo real. 2.
Permitir una recuperación breve sin intervención. 3. Intentar recuperar la sesión actual. 4.
Reconectar usando el mismo motor.» This slice covers 1, 2 and 4 for the same source; 3 and source or
engine switching are still out of scope.

S2 — The flag, verbatim: «Mantén la recuperación avanzada tras una feature flag hasta demostrar
resultados.» The switch is in Ajustes, persists, defaults to off, and is read when the native proxy
starts, so it can be compared and reverted without a rebuild.

S3 — Honest limits, verbatim: «No prometas ... cero cortes ... sin evidencias» and «Comprueba si un
cambio de stream altera códecs, pistas, PTS/PCR o posición temporal. No prometas failover invisible
cuando un reproductor pueda necesitar reabrir el contenido.» A retry is a new session: PIDs, continuity
counters and PCR start over, so the player may reload. The panel says so.

S4 — Bounded and interruptible: at most 3 retries, exponential backoff with deterministic jitter
capped at 8 s, and every wait notices a stop or the last client leaving within 100 ms. No retry loops
without an end.

S5 — The trigger is a fact, not a guess: the reader ended while the broadcast was still wanted
(`running_`) and clients were still connected. A normal end, an explicit stop or the last client
leaving never retries.

S6 — Observability: each broadcast counts its recoveries and `/status`/`/stat?action=get_clients`
expose them as `recoveries`, so a comparative run does not depend on log scraping.

S8 — A session that the engine accepts, that reports no peers and shows no progress for 20 s is
ended so the retry can run; a session with peers is left to the engine's own window, because retrying
a slow swarm restarts its prebuffer. Both live behind the recovery switch, so nothing changes with it
off.

S7 — A session that produces nothing must not hold the retry behind the full configured timeout: with
recovery on, the upstream read timeout is the smaller of the configured value and 15 s, and it is never
raised. Measured on the box, this does not shorten the *silent channel* case, because the engine ends
that session itself at ~30 s; it covers a socket that stalls without the engine noticing.

## Tasks

| ID | Task | Specs | Route | Commit |
| --- | --- | --- | --- | --- |
| R1 | `RecoveryPolicy` (attempts, backoff, jitter) and 7 host tests | S2, S4 | parent | this commit |
| R2 | Retry loop in `Broadcast`, interruptible wait, probe reset per session, `recoveries` in the JSON | S1, S4, S5, S6 | parent | this commit |
| R3 | Persisted switch, native config plumbing and the Ajustes panel | S2, S3 | parent | this commit |
| R4 | Box run with the flag on: cold channel recovers, live channel does not falsely retry | S1, S5, S6 | parent | this commit |
| R5 | Read timeout shortened while recovery is on, with four host tests | S7 | parent | this commit |
| R6 | Peerless no-progress watchdog in the engine reader, with ten host tests | S8 | parent | this commit |

## Log

L1 — Originating request, verbatim: «continua» after «ya esta la tv box conectada por usb», plus the
recovery section of the brief quoted in S1-S3. The full brief is the message that opened the session.

L2 — Design decision: the trigger cannot be `HttpClient::stream`'s return value. Its non-chunked path
treats an EOF or read failure as a normal finish (`ok` stays true), and a user stop and a dead upstream
are both "the read loop is over". The loop therefore asks the broadcast, not the transport:
`running_ && client_count() > 0` means nobody closed it on purpose.

L3 — Measured on the box, 2026-10-26, flag on, channel `ALTITUDE SPORTS *` cold:
`recoveries` reached **2**; logcat showed
`WARNING [841fb228] upstream ended with 2 client(s) waiting; retry 2/3 in 1140 ms`, a new
`stream URL` 4 s later, and the stream then delivered **368,083 packets** over the next ~100 s. The
first session had produced zero packets (stuck in `prebuf`); the retry is what made it play. Continuity
stayed 0 after the recovery, and the probe was reset per session so the new PIDs could not be counted
as errors.

L4 — A dead content id does **not** reach the retry path: the proxy's availability check rejects it
first (`ERROR Engine response timeout waiting for LOADRESP`, HTTP 500 to the client) and no broadcast
is created. So the positive path needs a channel the engine accepts but whose stream then ends - which
is what the cold channel above did after its 30 s read timeout. Recovery does not make an unavailable
channel available.

L5 — Flag off is the shipped state: one pass through the loop, `retry_backoff_ms` returns 0, no
`recoveries`, no new behaviour. Verified on the box after the run.

L6 — Checks: native **81/81** (7 policy tests), JVM suite green with the panel mapping updated,
`:app:assembleUniversalDebug` and `:app:lintArmv7Debug` green, signed release installed on the box.

L10 — Watchdog on the box, 2026-10-27, flag on. Dead channel (`DAZN Baloncesto 1080p **`, 0 peers):
`WARNING [Broadcast_d0924804] no engine progress for 20 s; ending the session so the retry can run`
**23 s after the request** (the session itself started ~3 s in), then retries 1-3 and give-up 6 s
later, with the client closed at **30.5 s** instead of the 80 s it used to wait. Healthy channel
(`ALTITUDE SPORTS *`) in the same run: first byte 1.77 s, 25 MB in 26 s, **recoveries 0**, no watchdog
line. Two things to know: the retries after a forced stop end in under a second each (the engine is
still releasing the stopped session), so they burn in about six seconds; and a peerless channel that
needs more than 20 s to find peers would be retried instead of waited for. Both are why the flag
still ships off.

L8 — Silent channel on the box, 2026-10-27, flag on: `DAZN Baloncesto 1080p **` returned headers in
1.59 s and then sent **zero bytes**; the retry fired at **+30 s**, not +15 s, because the engine ends the
session on its own prebuffer window first (`prebuf;0;0;…` once a second for 30 s). The second session
waited the same. So S7 covers a stalled socket, and a faster recovery for this case needs a watchdog on
*zero progress*, not on bytes.

L9 — While reading that log: with the flag off, every unexpected end logged
`ERROR giving up after 0 retries`, which blames a policy that was never asked to do anything. The line
now only appears when attempts were configured.

L7 — Not covered, and the reasons: a comparative A/B on a cold channel is not reproducible on demand
(the swarm warms up); no player was involved, so "the player reloads or not after a retry" is untested;
and the 30 s read timeout means a client can wait 30 s before the first retry starts. A shorter
no-payload watchdog is the next candidate, but its threshold has to be measured before it becomes a
default.
