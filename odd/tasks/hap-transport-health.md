# HaP — transport health: MPEG-TS continuity and stall detection

Goal: the proxy says, per active upstream, what the transport is actually doing — bytes received,
arrival bitrate, packet continuity, and whether payload stopped arriving — without adding a thread,
an unbounded map, or a claim about playback quality. This is the input the recovery work in phase 3
needs; nothing consumes it for decisions yet.

## Specs

S1 — The detection inputs of the brief, verbatim: «Combina, cuando estén disponibles: 1. Salud del
proceso y del motor. **2. Bytes realmente recibidos y entregados. 3. Velocidad de transferencia.**
4. Bitrate multimedia. 5. Peers. 6. Estado de colas y buffers. **7. Continuidad MPEG-TS. 8. Ausencia
de progreso.** 9. Errores HTTP. 10. Métricas del reproductor, solo si realmente son accesibles.»
Bytes received, arrival rate, continuity and absence of progress are the four this slice delivers;
bytes delivered already exist per client (`bytesSent`).

S2 — The laboratory scope, verbatim: «Utilízalo como herramienta de laboratorio y referencia para
desarrollar un analizador ligero y opcional: Continuidad de paquetes TS. Ausencia de datos
multimedia. Errores de estructura. … Bitrate y fluctuaciones.» and «No incorpores el toolkit
completo a la APK salvo justificación convincente.» No TSDuck, no dependency, no new binary.

S3 — Cost, verbatim from the brief's constraints: «No establezcas tamaños de búfer sin medir» and
«Eficiencia de RAM, CPU, almacenamiento, batería y red». The analyzer runs inside the single
upstream reader thread, is O(1) per 188-byte packet, allocates nothing per packet, keeps at most 64
tracked PIDs, and starts no thread. Its feed is the bytes the proxy already has.

S4 — Honesty of the name, verbatim: «No confundas buena continuidad del transporte con reproducción
correcta en pantalla: el reproductor externo puede fallar independientemente.» The counters are
transport counters (`continuityErrors`, `syncErrors`, `stalled`); no field may be called or
documented as playback quality, freeze detection in the player, or a success signal.

S5 — Exposure rule, verbatim from the security brief: «Registro seguro sin secretos» and «No abras
administración remota en Internet.» The new status fields add no URL, no token, no full infohash and
no client IP: each entry carries the channel name the existing `clients_data` already shows, the
client count, and transport counters. Existing `/status` keys and their shape stay unchanged.

S6 — Acceptance, verbatim from the brief: «Para componentes nuevos, define criterios de aceptación
comparativos respecto a la versión anterior». The previous behaviour is `/status` without a
`streams` array. Accepted when: (a) every existing key of `/status` is byte-identical for a proxy
with no active stream, except the added `streams` array; (b) the native suite passes with the new
tests; (c) the JVM suite and `:app:assembleUniversalDebug` still pass; (d) the added work per byte
is measured on the host and recorded in `## Log`.

## Tasks

| ID | Task | Specs | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | `TsProbe` pure component and its host GoogleTest suite | S1, S2, S3 | inline (writer scope unavailable) | `a4932fd` |
| T2 | Wire the probe into `Broadcast`, `BroadcastManager`, `/status` and `/stat?action=get_clients` | S1, S3, S5 | inline | `c25878f` |
| T3 | Show the transport line in the Conexiones section | S1, S5 | parent | `3e3310f` |
| T4 | Record the measurement and update `docs/architecture/BASELINE.md` + backlog | S3, S6 | parent | this commit |
| T5 | Full checks and work-unit commit | S6 | parent | this commit |

## Log

L1 — The brief that authorises this slice (originating request, verbatim excerpts): «Investiga e
implementa progresivamente un coordinador de reproducción mediante una máquina de estados
explícita» … «Detección de problemas: Combina, cuando estén disponibles: … 7. Continuidad MPEG-TS. 8.
Ausencia de progreso.» … «Quiero pruebas reproducibles de: Streaming real durante sesiones
prolongadas.» The full brief is the message that opened this session; this file quotes every part
that constrains the slice so a worker never needs it.

L2 — Decisions taken while implementing (T1, T2). `synchronized` and `syncErrors` are derived from
`bytes - packets*188 - held`, not accumulated: a byte held while resynchronising is an error until it
starts a frame, and the two views cannot disagree. The rate window is inclusive (`age <= 1000 ms`),
so a sample exactly one second old still counts and the next millisecond drops it to zero. PID state
uses 128 open-addressing slots capped at 64 entries, so the table cannot grow and a lookup is O(1)
without allocating. `Broadcast` gained a mutex only around `feed`/`snapshot`; the manager's lock is
taken before the probe's and never the reverse, so the reader thread cannot deadlock against a status
read.

L3 — Measured (T1, S3): 256 MiB of synthetic TS in 12 ms on the host, ~21 GiB/s, 0 continuity and 0
sync errors. The first bench reported 4,103 continuity errors because it rebuilt the same block
(restarting the continuity counter) once per feed — a defect in the bench, not in the probe; the
benchmark now advances the counter across feeds. Recorded in `docs/Measurements.md`.

L4 — Checks (S6): native suite **70/70** (16 new `TsProbe` tests); JVM suite **240/240** (5 new
`TransportStatusTest` cases); `:app:assembleUniversalDebug` green; `:app:lintArmv7Debug` green with 0
errors. `docs/architecture/BASELINE.md` created; `odd/BACKLOG.md` reconciled with the code (D1, F5,
F9, F10, F11, P3 and the pre-existing D-pad items were already resolved in code and are now recorded
as such).

L5 — Not verified (V2 in the backlog): the `Conexiones` line has never been seen on the TV with a
live stream. The parse is pinned in the JVM, the wiring compiles, and the `/status` shape is tested
only through the probe's counters, not through an HTTP request, because the host build has no
Android HTTP client.

## Slice boundaries

Three commits, because the component, the exposure and the screen are three reviewable questions:

| Commit | Added lines | What a reviewer is asked to believe |
| --- | --- | --- |
| `a4932fd` | 761 | the probe's arithmetic, and its tests, as one indivisible unit (the test is 356 of those lines; splitting a test from the code it pins was rejected on purpose) |
| `c25878f` | 135 | the wiring: one probe per upstream, two additive JSON keys, no changed key |
| `3e3310f` | 409 | the app reads one answer and paints one line; the parse contract is pinned |

The cumulative 1,305 lines are past the ~400-line review slice for the feature as a whole. The three
commits are the delivery strategy: each one reverts on its own, and the third can be dropped without
touching the server side.
