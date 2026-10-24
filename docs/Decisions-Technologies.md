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
- **Unresolved observation, and it contradicts itself.** The running app process
  shows threads named `OkHttp Connecti…` and `Okio Watchdog`, but the code does
  not support that reading:
  - `./gradlew :app:dependencyInsight --configuration armv7DebugRuntimeClasspath
    --dependency okhttp` → "No dependencies matching given input were found".
  - The release APK contains exactly one `classes.dex`, and it has no `okhttp3`,
    `okio/` or `OkHttpClient` markers.

  So OkHttp/Okio are **not** on this app's dependency graph. Either the thread
  names belong to something else loaded into that process, or the observation was
  misattributed (different PID at a different moment). No explanation is offered
  because none has been established. The next diagnostic is to correlate those
  thread ids with a stack sample (`debuggerd -b <tid>` or a Perfetto capture) on a
  fresh process, rather than to keep asserting either way.

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

### Compose for TV — reject, but not for the reason first given

**Correction.** An earlier version of this document justified the rejection partly
because "`tv-foundation` is still `1.0.0-beta01`". That was wrong. Checked against
Google Maven: `androidx.tv:tv-foundation` has a **stable 1.0.0**, and
`androidx.tv:tv-material` has a stable **1.1.0**. The `1.0.0-beta01` in
`libs.versions.toml` is a stale pin in this repository, not the state of the
library. That reason is withdrawn.

The rejection now rests only on the reasons that are still true: the screen's
measured defect was the 1 Hz full-view rebuild and was fixed in place; one
configuration screen does not match focus-driven browse surfaces; and on a 32-bit
1.96 GB device a composition runtime is a cost against no demonstrated gain for
this screen. See `docs/Decisions-UI-Toolkit.md`.

**Revised criterion (adopted from a reviewer, and correct):** keep Views only if
they pass the navigation, focus and large-list requirements the brief actually
asks for; reconsider Compose if it demonstrates a measurable improvement in user
experience or maintainability. The D-pad work is the test that decides this — if
Views cannot be made to navigate predictably, Compose becomes the better answer
and this decision is void. That has not been demonstrated either way yet.

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

**Correction to an earlier claim in this document.** It previously said the
cheapest fix was to "route those segments through the existing Java bridge (which
already does HTTPS, redirects and ETag)". That was asserted without reading the
bridge, and it is wrong. `NativeHttpBridge.fetchOnce` (line 64) does:

```java
String body = stream == null ? "" : new String(readAll(stream, 32 * 1024 * 1024), StandardCharsets.UTF_8);
```

It reads the **whole** body (32 MiB cap) and decodes it as UTF-8, returning a
`String[]` to C++. That is fine for playlist and JSON text, which is what it was
written for. It is unusable for binary MPEG-TS segments: UTF-8 decoding corrupts
arbitrary bytes, and buffering a whole segment in memory defeats streaming.

So the gap is wider than "HTTPS is rejected":

- no binary-capable streaming path over TLS;
- the native reader does not follow redirects the way a real HTTP client does;
- HLS handling is a simplified playlist walk (first segment onward) without
  master-playlist / variant-stream handling.

**Recommendation, revised:** fixing this properly needs either a **byte-oriented**
Java streaming bridge (a new path that hands chunks to JNI, not a `String`), or
libcurl on that one path with redirect support. Both are real work, and both
should be driven by a reproducible channel that only works over HTTPS — not done
speculatively. **Trigger:** a reproducible HLS-over-HTTPS channel, and a stated
scope of "broad HLS compatibility" if that is actually wanted.

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
