# Decision: stay on Android Views, do not migrate to Compose for TV

Status: decided. Applies to the configuration screen (`HapConfigActivity`) and any
near-term UI work.

## What the repository actually contains

`gradle/libs.versions.toml` declares a large modern stack: Compose BOM
2025.06.01, `androidx.tv:tv-foundation` 1.0.0-**beta01**, `tv-material` 1.0.1,
Media3, Room, Hilt, Retrofit, Coroutines, Coil, WorkManager, LeakCanary.

`app/build.gradle.kts` depends on **`core-ktx` only** (plus JUnit for tests). No
Kotlin plugin is applied, there is no Compose compiler, and there is no
`app/src/main/res/layout/` — every view is built programmatically in one 1749-line
Java file. So the catalog is aspirational: it describes an application that was
planned, not the one that exists. Any statement of the form "HaP uses Compose"
would be false.

## The decision

Do not migrate. Keep Java + Android Views.

## Why

1. **The measured problem was not the toolkit.** The screen re-inflated its view
   hierarchy and allocated a new `Drawable` per pill every second, and created a
   new `Button` per endpoint per tick that accumulated in `busyControls` forever.
   Those were fixed in place, with no migration: idle RSS went from climbing to
   247 MB to flat/declining 116 → 107 MB, and threads stabilised at 23. A rewrite
   would have delivered the same result at far higher cost and risk.

2. **Correction: `tv-foundation` is NOT still beta.** This document originally
   claimed it was, citing the `1.0.0-beta01` pin in `libs.versions.toml`. That was
   wrong: Google Maven shows a stable `androidx.tv:tv-foundation` 1.0.0 and a
   stable `androidx.tv:tv-material` 1.1.0. The pin is stale, not the library. The
   API-stability objection is withdrawn.

3. **The workload does not match what Compose TV is for.** One configuration
   screen with sections, switches, buttons and short lists is exactly the case
   where Views are cheap and predictable. Compose TV's value is in
   focus-driven browse surfaces with large lazy grids and hero content. There is
   no such surface here.

4. **The device makes additions expensive.** 32-bit `armeabi-v7a` userspace,
   1.96 GB RAM, ~700–850 MB free, and an APK already dominated by a 62 MB kubo
   binary plus the AceServe zip. Adding a composition runtime and its lifecycle
   plumbing for a single screen spends memory and install size on this device for
   no behavioural gain it does not already have.

5. **It would put working integrations at risk.** The StreamVault plugin surface,
   the exported `CONFIGURE` activity, LAN/Cast behaviour through
   `ProxyExposure`/`HapBridge`, and the D-pad focus model all work today. A UI
   rewrite touches all of them at once for no measured benefit.

## What was done instead

The UI work targeted the actual defect: incremental refresh (build once, write
only changed properties, no per-tick allocation), `HapBridge.status()` moved off
the main thread, channel testing moved off the shared executor, and real
cancellation for in-flight requests.

## Limitations of this decision

- It is **not** backed by an A/B benchmark of Compose versus Views on this device.
  Producing one would mean building both variants and measuring frame timing and
  memory; that was not done, and this document does not claim it was.
- If a future requirement introduces a genuine browse/browse-heavy surface (a
  large channel grid with artwork, filtering and hero rows), revisit this
  decision for **that screen only**, with a measured prototype and a stable,
  non-beta Compose TV foundation.
- The unused version catalog should eventually be either wired up or trimmed. A
  catalog that advertises dependencies the build does not use misleads every
  reader, including this audit at first glance.
