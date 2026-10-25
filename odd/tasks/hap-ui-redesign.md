# HaP UI redesign — TV-first navigation, five sections, Android Views

Redesign the HaP interface for Google TV (Xiaomi TV Box S 3rd Gen) as shown in the
reference mockup: left navigation rail, dark navy palette, large focusable cards, and a
persistent key-hint footer. Stay on **Java + Android Views**. No Kotlin, no Compose, no new
frameworks. Every existing capability must survive the move.

The reference image is the target: sidebar `HaP / IPTV Server` with five items (Inicio,
Canales, Fuentes, Conexiones, Ajustes), a status card, one dominant blue CTA, two secondary
cards, a services row (AceServe / Proxy HTTP / IPFS), and a footer hint bar.

## Specs

**S1 — Stack and constraints.** Keep Java + Android Views. No Compose, no Kotlin, no new
dependency. Do not change `LIVE_CACHE_SIZE_BYTES = 104857600`. No integrated player. Preserve
StreamVault, AceServe, HTTPAceProxy, IPFS, M3U/EPG, external clients and GitHub Releases
updates. Preserve existing preferences and stored data.

**S2 — Navigation structure.** Five sections with these exact labels: `Inicio`, `Canales`,
`Fuentes`, `Conexiones`, `Ajustes`. Brand block at the top of the rail reads `HaP` and
`IPTV Server`. The selected section is visibly distinct from the focused one.

**S3 — Home screen.** Exactly this content and order:
1. Title `Inicio`, subtitle `Servidor IPTV con AceStream, proxy HTTP e IPFS opcional`.
2. Status card: `Servidor funcionando` / `Listo para tu reproductor IPTV` /
   `HaP está sirviendo tus canales correctamente.` Also stopped, starting and error states in
   plain language.
3. Primary CTA: `Cómo ver mis canales` with `Conectar TiviMate, StreamVault u otro reproductor`.
4. Two secondary cards: `Mis listas` with `Gestionar listas M3U y canales personalizados`, and
   `Comprobar canales` with `Verificar disponibilidad y estado`.
5. Services row headed `Estado de los servicios` with three entries:
   `AceServe` / `Caché: 100 MiB`, `Proxy HTTP` / `Puerto: 8888`, `IPFS (Kubo)` / `Función opcional`.
   Each shows a state word: `Activo`, `Activa`, `Desactivado`, `Detenido`, `Iniciando`, `Error`.
6. Footer hint bar: `OK para seleccionar` and `Atrás para volver`.

**S4 — HaP is a server, not a player.** Never present HaP as an integrated player. The
`Cómo ver mis canales` screen explains connecting TiviMate, StreamVault or another IPTV player,
and shows the URLs to use.

**S5 — Plain language.** User-facing text is simple and avoids jargon. Errors say what
happened and what to do. Destructive actions ask for confirmation.

**S6 — D-pad only.** The whole app is operable with a remote: visible consistent focus, logical
UP/DOWN/LEFT/RIGHT between rail and content, focus restored when returning to a section, correct
OK and BACK, scroll follows focus without jumps, dialogs and forms reachable, focus preserved
across 1 Hz status refreshes and async results. Handle TV overscan.

**S7 — Implementation quality.** No `removeAllViews()` rebuild cycles for state changes. Screens
are built once and reused; state updates touch only the affected views. Stable view identities.
No network, disk or blocking work on the main thread. No leaked listeners, executors or
references.

**S8 — Every existing function is preserved.** Status start/stop, M3U sources, custom channels,
channel testing, AIO/connections, clients, IPFS with its advanced options, logs, settings,
update check/download/install and its settings. Nothing is deleted for the sake of simplicity;
it is reorganised.

**S9 — Accessibility.** Text legible at TV distance, sufficient contrast, focus clearly visible,
controls have content descriptions.

**S10 — Verification honesty.** No device is connected during this work. Prove correctness by
compilation, JVM tests and static reasoning only, and state plainly in every report that
on-device navigation, jank and focus behaviour remain unverified.

## Tasks

| ID | Spec | Route | Outcome |
| --- | --- | --- | --- |
| T1 | S1, S9 | inline | Design system: colors, dimens, styles, focus-state drawables, text styles |
| T2 | S2, S3, S6 | worker | Shell: `activity_hap.xml` with nav rail + content container + footer hints |
| T3 | S3 | worker | `HomeScreen`: status card, CTA, two cards, services row, four states |
| T4 | S8, S2 | worker | Rehome every existing panel into Canales / Fuentes / Conexiones / Ajustes |
| T5 | S4 | worker | `Cómo ver mis canales` screen with player URLs |
| T6 | S6 | worker | Deterministic focus chain, focus restore per section, scroll follows focus |
| T7 | S7 | worker | Replace full rebuilds with targeted updates; no main-thread I/O |
| T8 | S6 | inline | First-run wizard, shown only when needed |
| T9 | S10 | inline | Build + JVM tests + diff review; document what remains unverified |

## Log

**L1 — user request (verbatim, opening).** «Hasta que tengamos de nuevo la xiamoi disponible,
vamos a mejorar la interafz. Tengo esta proupuesta, reproducela fielmente en la media de lo
spoisible como nueva interfaz para la app.» followed by the brief «Rediseño y optimización de la
interfaz de HaP para Google TV», which asks for: a full redesign keeping Java + Android Views
without migrating to Kotlin or Compose; a modern, simple, fast experience tailored to the Xiaomi
TV Box S 3rd Gen; usability, accessibility, navigation, organisation and performance rather than
decoration alone; a clean minimal interface; clear hierarchy and TV-legible typography; simple
lateral navigation; one evident primary action per screen; comprehensible states (working,
stopped, starting, error); plain-language explanatory messages; advanced settings reachable
without cluttering main screens; confirmation of dangerous actions and useful errors; coherent
reusable components; and no unnecessary animations.

**L2 — user request (verbatim, organisation).** Home must be especially simple: overall server
state, a clear action to start or stop it, a main button «Cómo ver mis canales» explaining how to
connect TiviMate, StreamVault or another player, shortcuts to «Mis listas» and «Comprobar
canales», and discreet secondary technical information. «No presentes HaP como un reproductor
integrado». Include a first-run assistant helping activate required services, use or add an
existing M3U list, and connect an external player, appearing only when needed. Organise the rest
into intuitive sections: channels and tests, M3U sources and custom channels, M3U/EPG connections
and clients, IPFS, diagnostics/logs, settings and updates. «Conserva absolutamente todas las
funcionalidades existentes». Do not remove options to simplify: organise them better.

**L3 — user request (verbatim, remote and optimisation).** Fully operable with the Xiaomi remote,
no mouse or touch: clearly visible and consistent focus, logical predictable D-pad movement, focus
restoration on returning from another screen, correct OK and BACK, smooth scroll without jumps or
lost selection, fully accessible dialogs forms lists and menus, focus preserved when data changes
or async results arrive, adaptation to different resolutions, densities and TV safe areas. Test
real complete journeys with the remote. Investigate `HapConfigActivity`'s rebuilds and periodic
refreshes: separate presentation, state and business operations; split the monolithic interface
into maintainable components or screens; use `RecyclerView`, `ListAdapter`, `DiffUtil` and partial
updates where justified; avoid `removeAllViews()` and needless full rebuilds; keep stable
identities and focus/scroll state; update only changed elements; reduce polling, invalidations,
layouts and allocations; no network, disk or blocking work on the main thread; manage listeners,
tasks, references and lifecycle to avoid leaks; keep the interface fluid during mass testing,
startups, shutdowns and state changes; avoid deeply nested views or scroll structures that break
TV. Free to improve internal architecture, «pero no añadas frameworks ni complejidad sin
beneficios demostrables».

**L4 — user request (verbatim, validation).** Before modifying, inspect the current interface and
establish a baseline. Use the Xiaomi connected over USB ADB to check before/after performance,
opening time and key responsiveness, jank, slow frames and memory, navigation and focus
restoration, large lists and async results, forms, empty/loading/error states, accessibility and
distance legibility, and that every existing option works. Automate with UI Automator, ADB,
Maestro or similar, and use profiling. «No des por finalizado el trabajo únicamente porque el
diseño se vea bien en una captura.»

**L5 — user request (verbatim, constraints and autonomy).** Keep Java + Android Views; preserve
all functions and compatibility with StreamVault, AceServe, HTTPAceProxy, IPFS, M3U/EPG, external
clients and GitHub Releases updates; do not modify the 100 MiB AceServe cache; do not introduce an
integrated player unless expressly agreed; do not sacrifice streaming stability for visual
effects; preserve existing settings and data; avoid regressions and verify on the real device.
«No sigas este prompt de forma rígida», question the proposed structure, use judgement, deep UI
architecture redesign allowed if justified; effort is not the problem, unnecessary complexity,
regressions and worse performance are. Expected result: a visually modern and professional HaP
that is above all extraordinarily simple to use, fully remote-navigable, light, fast, stable and
with all functionality intact. Start by examining the current interface, define a coherent
navigation structure, then implement and test incrementally. Prioritise simplicity, functionality
and real performance over decoration.

**L6 — user request (verbatim, autonomy).** «Me voy a dormir, no te detengas hasta mañana, cuando
me despierte veo el resutlado. Itera de manera autonoma sin pregutarme nada.»

**L7 — evidence: current UI shape.** `HapConfigActivity.java` is 2908 lines and builds every
control programmatically; `app/src/main/res/layout/` does not exist. Existing panel builders that
must be preserved: `buildHeaderPanel`, `buildExternalPlayersPanel`, `buildIpfsPanel`,
`buildIpfsAdvancedSection`, `buildAioPanel`, `buildSourcesPanel`, `buildCustomSection`,
`buildClientsPanel`, `buildChannelStatusPanel`, `buildLogsPanel`, `buildUpdatePanel`,
`buildUpdateSettingsSection`. Refresh runs at 1 Hz through `refreshRuntimeState` →
`applyRuntimeSnapshot` → `applyCaption`.

**L8 — constraint accepted by the human.** The device is disconnected for this work. T9 and S10
therefore record on-device validation as pending, not as passed.

**L9 — evidence: focus work exists.** `stash@{0}` ("WIP dpad deterministic focus") holds
`FocusChain.java`, `FocusGrid.java`, an `HapConfigActivity` change and a test, produced before
the device was disconnected and therefore never verified on hardware. Treat it as unproven input;
reuse only what the new shell makes obviously correct.

**L10 — slice evidence and the next brief.** `b2e53ee` delivered T1-T4 and the S6 groundwork:
design system, `activity_hap.xml` rail shell, `HomeScreen`, and all eleven panel builders
re-parented exactly once as pinned by `HapSectionsTest`. Design decisions taken there that later
slices must respect: TV dimens live in `values-w600dp` rather than `values-sw600dp` because a TV
box reports 960x540dp, where smallest-width would not match; rail selection and rail focus are
separate states rendered by two drawables at once; `removeAllViews()` remains in eight list
renderers (endpoints, sources, custom, clients, tabs, channel rows, ipfs gateway, and one client
refresh) and is T7's subject, not a regression.

T6 brief: finish the deterministic focus chain. Focus must survive the 1 Hz refresh and async
results, scroll must follow focus inside each section's scroll view, the last focused view per
section must be restored when returning, and the rail's UP/DOWN must not jump out of the rail.
`stash@{0}` holds unverified `FocusChain`/`FocusGrid` work; read it for ideas but re-derive against
the real view tree rather than applying it blindly.

T7 brief: replace the eight `removeAllViews()` renderers with in-place updates or a recycler where
the list can grow, keeping stable identities so focus is not lost. Measure by reasoning about
allocation counts per refresh; no device is available to measure jank.

**L11 — end of the autonomous run: T1-T8 delivered, device verification owed.** Every task in the
table above is implemented and committed, with the S# each one serves recorded in its commit message.

| Task | Commit | Evidence |
| --- | --- | --- |
| T1-T4 | `b2e53ee` | shell, home, all eleven panels re-hosted; `HapSectionsTest` pins one call each |
| T5 | `21f16d6` | `ConnectPlayerActivity`; addresses come from `HapBridge`, never composed |
| T6 | `bb35710` | `FocusGrid` pure rule, 17 tests, RED observed; `FocusNavigator` owns the D-pad |
| T7 | `3797db9` | all eight `removeAllViews()` gone; `ListPatch`/`InPlaceList`/`HapRows`, 17 tests |
| T8 | `068438d` | `FirstRunDecision` pure rule, 8 tests; card offered only while nothing is configured |

Test totals: 99 JVM tests, 0 failures (30 before this work began). Native suite: 38, 0 failures.
Lint: 0 errors, warnings identical to the pre-redesign baseline plus the two deliberate
`ApplySharedPref` the wizard's durable `commit()` adds.

**The debt this run leaves, stated plainly.** Nothing here has been seen on a television. The
build, the tests and lint prove the code is coherent and internally consistent; they prove nothing
about how it looks, how the remote feels, or whether a 1 Hz refresh keeps focus. `docs/Tv-Verification-Checklist.md`
is the list that settles it, section by section, and it should be run before any of this is called
finished. The Views decision in `docs/Decisions-UI-Toolkit.md` stays conditional until it passes.

**An installable build exists.** `hap-ui-redesign-armv7-release.apk` (67,029,254 B) is staged in
`C:\Users\alber\hap-tools`. It is `versionCode 16` / `1.4.1`, `armeabi-v7a`, signed with the same key
as the installed build (certificate SHA-256 `b9f4146a7bfa18dde3a864f1f93f34489068ca6d19bd960c96a849ac16ae1f51`,
verified with `apksigner`), so `adb install -r` replaces it in place and keeps data. No tag was
published: a release should follow a device pass, not precede it.
