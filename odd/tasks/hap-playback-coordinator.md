# HaP — playback state: name it before deciding on it

The recovery work left the proxy deciding with scattered facts: `running_`, the client count, the
probe's `stalled`, the engine's prebuffer state, the retry counter. This slice gives those facts one
name per upstream and publishes it, and deliberately decides nothing with it yet.

The brief's exact wording for this phase is not reproduced here; what follows is the requirement as
this repository reads it, so a later reader can check the implementation against something written.

## Specs

S1 — The proxy may only claim what it can prove. "Playing" means payload is flowing through the
transport; it never means a player is rendering it. A session that never delivered is not "degraded",
because it never had a quality to degrade.

S2 — One state per upstream, from a closed set, published as a stable lowercase string in `/status` and
`/stat?action=get_clients`: `starting`, `prebuffering`, `playing`, `degraded`, `recovering`, `failed`.

S3 — Every session has a generation: 1 for the first, plus one per retry. A state without its
generation cannot say whether the stream is the one the client opened.

S4 — Observation only. No decision reads the state in this slice: the retry, the watchdog and the
timeouts keep deciding exactly as they were measured to. Moving a decision onto the state needs a
comparative run, like every other change in the recovery work.

S5 — Giving up wins over every other fact: `failed` is terminal for the broadcast, and no later status
may talk it back into `playing`.

## Tasks

| ID | Task | Specs | Route | Commit |
| --- | --- | --- | --- | --- |
| P1 | `classify_playback_state` as a pure function with nine host tests, including the name contract | S1, S2, S5 | parent | this commit |
| P2 | Broadcast exposes `state()`/`generation()`; `streams[]` carries both; the app parses them and tolerates their absence | S2, S3 | parent | this commit |
| P3 | Box run: live channel and dead channel sampled every 10 s | S1, S3 | parent | this commit |

## Log

L1 — Box evidence, 2026-10-27, flag on. Live channel (`ALTITUDE SPORTS *`):
`state=playing, generation=1, recoveries=0, packets=78077`. Dead channel (`DAZN Baloncesto 1080p **`),
sampled every ten seconds: `prebuffering` (generation 1) for the first 20 s, then `recovering` with the
generation climbing 2, 3, 4, 5 as each retry opened a session, `recoveries` 1 to 4. The sampling stopped
before the give-up; `failed` is pinned by a unit test and not yet seen on the box.

L2 — Why the states are what they are. `starting` and `prebuffering` are separated because the engine
says which one it is; the proxy does not guess. `recovering` beats the engine's `prebuffering` for a
retry, because "the engine is prebuffering the session a retry just opened" is not the same news as "the
stream you opened is prebuffering". `degraded` requires payload to have flowed.

L3 — What is deliberately absent: per-client state (a slow client and a fast one see the same upstream),
event streams/SSE, budgets, and any action. Those belong to the deciding half, and the deciding half
starts when there is a comparative run to justify it.
