# Decision record: complementary technologies

Scope: the technologies the brief lists for evaluation (Kotlin, Coroutines,
Compose for TV, libcurl Multi, Asio/libuv, OkHttp/HttpEngine, Room, DataStore,
Media3, modern Android task mechanisms). Each entry states whether it solves a
need that actually exists in **this** codebase on **this** device, or is
rejected/deferred.

Reference device: `MiTV-AFMU0`, Android 14 (SDK 34), Amlogic S905X5M, 4 cores,
1.96 GB RAM (~700–850 MB free), **armeabi-v7a only**, page size 4096.

Measured baseline after the 1.4.x work: cold start 2084 ms, idle RSS 107 MB
(flat), 23 threads, armv7 APK 66.9 MB of which the kubo binary is ~62 MB.

## Ground truth about the current build

- `app/build.gradle.kts` declares **one** runtime dependency: `core-ktx`
  (plus JUnit for tests). No Kotlin plugin is applied; there is no Compose
  compiler, no KSP, no Hilt.
- `gradle/libs.versions.toml` declares Compose, Media3, Room, Hilt, Retrofit,
  Coroutines, Coil, WorkManager, LeakCanary and more. **None are used.** The
  catalog describes a planned application, not this one.
- `app/src/main/cpp/CMakeLists.txt` links `android`, `log`, `z` — **no curl**,
  despite the file `android_http_client.cpp`. libcurl is not in the build.
- There is no video decoding in HaP. It is a server and proxy; playback happens
  in TiviMate, OTT, StreamVault or a Cast receiver.
- Persistence is SharedPreferences plus files (`PlaylistCatalog.PREFS_NAME`, the
  custom M3U file). No database.
- Observed but unexplained: the running process carries `OkHttp Connecti…` and
  `Okio Watchdog` threads, so OkHttp/Okio are on the runtime classpath
  transitively from something. Nothing in HaP's source uses them. Worth
  identifying before anyone claims the app "does not ship OkHttp".

## Verdicts

### Kotlin — reject (for now)

No need it solves. The Java code is small (23 files), builds clean, and the two
defects that mattered were concurrency defects, not language defects. A Kotlin
migration is a whole-file rewrite of the UI and runtime for zero behavioural
gain, and it invalidates the just-added test suite and the 26 native tests'
counterparts. **Trigger to revisit:** if a genuinely new, large surface were
added (see Compose), or if the team standardises on Kotlin elsewhere.

### Coroutines / Flow / StateFlow — reject

This is the one worth being precise about, because the brief itself warns against
it. Channel-test cancellation was **not** failing because of thread-management
style; it was failing because `Future.cancel(true)` cannot interrupt a blocking
socket read. The fix was to keep the live `HttpURLConnection` and close it. That
fix is in Java and is proven by code reading; coroutines would have inherited
exactly the same problem and needed exactly the same socket-close fix.

For the new parallel batch, structured concurrency is genuinely a good fit — but
it is achievable in Java with a bounded `ExecutorService`, a generation token and
per-task cancellation handles, which is what the implementation does.

**Trigger to revisit:** adopting Kotlin for another reason first. Adopting
coroutines *alone* would mean a Kotlin migration to obtain a nicety.

### Compose for TV — reject

Decided separately in `docs/Decisions-UI-Toolkit.md`, with the reasons: the
screen's defect was the 1 Hz full-view rebuild (fixed in place), `tv-foundation`
is still `1.0.0-beta01` while stable APIs were requested, a single configuration
screen does not match focus-driven browse surfaces, and a 32-bit 1.96 GB device
gains nothing from a composition runtime here. That document also records its own
limitation: no A/B benchmark was run.

### libcurl Multi — **the one with a real functional gap**; targeted fix, not a rewrite

Verified in code: `HttpClient::stream` in `android_http_client.cpp` is a
hand-rolled POSIX-socket HTTP/1.1 client. It:

- throws for any scheme other than `http` (`native streaming currently supports
  only http URLs`), so **an HLS playlist whose segments are `https://` cannot be
  streamed**;
- parses chunked transfer encoding by hand;
- has no connection reuse and no redirect handling on the streaming path.

That is a real limitation, but it is a **capability** gap on an edge path, not the
cause of any measured instability — AceStream serves the live stream over plain
HTTP on the loopback AceServe port, which is the hot path.

**Recommendation:** do not add libcurl for performance; that was never
demonstrated and the current path handles the hot case. If HLS-over-HTTPS
upstream is a supported scenario, the cheapest correct fix is to route those
segments through the existing Java bridge (which already does HTTPS, redirects
and `ETag`), or to add libcurl to that one path — decided by measuring, not by
preference. **Trigger:** a reproducible case of a channel that only works with
HTTPS segments.

### Asio / libuv / event-loop rewrite — reject

The defects that mattered were (a) the producer blocking on a slow client and
(b) detached threads outliving the handler. Both were fixed in ~450 lines:
non-blocking fan-out with drop-oldest, shared chunk buffers, a connection cap,
receive timeouts, accept backoff and a `ConnectionTracker` that outlives the
server. Measured effect: a full-queue push went from 3750 ms blocked to <20 ms.

An event loop would not improve stream continuity on 4 cores with 8 streaming
clients and a capped HTTP connection count. It would trade a working, tested
thread model for a large dependency and a rewrite of the code path with the
highest blast radius. **Trigger:** connection concurrency orders of magnitude
above today's caps, which this device cannot sustain anyway.

### OkHttp / HttpEngine — reject

REST calls already go through `HttpURLConnection` via the JNI bridge, with
`ETag`/`If-None-Match`, 304 handling, timeouts and redirects — everything the
updater needs. OkHttp would add ~1 MB and a dispatcher, for features not used.
**Trigger:** real connection-pool contention or HTTP/2 requirements, neither
present at these request volumes.

### Room — reject

No database-shaped need. Data is a handful of preferences and one M3U file.
Room brings schema, migrations and codegen to model a key-value store. **Trigger:**
EPG persistence or a channel catalogue large enough to need indexed queries.

### DataStore — reject

Same need as above, and DataStore is coroutine-first, which reintroduces the
Kotlin question. `SharedPreferences` here is read on a supervisor thread and
cached in memory; there is no measured contention. **Trigger:** Kotlin adoption
plus a concrete consistency problem.

### Media3 — reject

HaP does not play media. Adding a player would change the product, not optimise
it, and the brief asks not to add players without demonstrated value.
**Trigger:** an explicit product decision to play inside HaP.

### Modern Android task mechanisms (WorkManager) — defer

The updater's automatic check runs when the configuration screen opens, gated by
a persisted timestamp and interval, with a manual "check now" always available.
That satisfies "ligera, sin servicio permanente" without a dependency or a
scheduler. WorkManager would only add value for periodic checks **while the app
is closed**, which the product does not require today. **Trigger:** a requirement
for daily background update checks without opening the app.

## Summary

| Technology | Verdict | Reason in one line |
| --- | --- | --- |
| Kotlin | Reject | Two real defects were concurrency, not language |
| Coroutines/Flow | Reject | Cancellation failed on a blocking read; socket close is the fix |
| Compose for TV | Reject | Defect was the refresh loop; foundation still beta |
| libcurl Multi | Targeted, not for perf | Only real gap is `https://` streaming on the HLS path |
| Asio/libuv | Reject | Backpressure and lifetime already fixed in ~450 lines |
| OkHttp | Reject | `HttpURLConnection` already covers ETag/304/redirects |
| Room | Reject | No database-shaped need |
| DataStore | Reject | No need, and coroutine-first |
| Media3 | Reject | HaP does not play media |
| WorkManager | Defer | Only needed for checks while the app is closed |

None of these were adopted "because modern". The two that remain genuinely on the
table are libcurl (for a real HTTPS-streaming gap, on the edge path) and
WorkManager (if background checks become a requirement) — both with an explicit
trigger and both to be decided by measurement.
