# HaP — focus must survive a service transition

Found on the box while validating the IPFS switch: turning IPFS on or off disables every control that
can start an operation (`setBusy`), including the switch the user is on. Android then moves focus to
the first focusable view, the Inicio rail entry, and the next press opens Inicio instead of operating
the switch. The navigator's repair did not help: while every control of the section is disabled its
graph is empty, so there was nothing to restore to.

## Specs

S1 — Verbatim from the brief: «Foco D-pad siempre visible. Navegación determinista.» A transition the
user started must not move the focus out of the section.

S2 — Disabling the controls stays. A second press while an operation runs must not start a second
one, so the control is still disabled for the duration; only the focus is given back afterwards.

S3 — One owner for focus. `FocusNavigator` is the only class that requests focus (pinned by
`FocusRefreshPurityTest`), so the memory and the restore live there, not in the activity.

S4 — The repair rule is pure and pinned on the JVM: focus that reaches the rail is only taken back
when the control it left was disabled or detached, never when the navigator moved it there on LEFT.

S5 — Evidence: unit tests for the rule and the restore path, plus the box: toggling IPFS off and on
leaves focus on the switch, with the state pill following to `Parado` and `En línea`.

## Tasks

| ID | Task | Specs | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | `FocusRepair` rule and its JVM tests | S1, S4 | parent | this commit |
| T2 | Transition memory in `FocusNavigator` and `setBusy` | S1, S2, S3 | parent | this commit |
| T3 | Box walk: IPFS off, on, off, on | S5 | parent | verified on the box, this commit |

## Log

L1 — Originating request, verbatim: «ya esta la tv box conectada por usb» and, from the brief,
«Foco D-pad siempre visible». The full brief is the message that opened the session.

L2 — Measured on the box before the fix: focus the IPFS switch, press OK; after the transition the
focused view was the `Inicio` rail entry, and the next OK opened Inicio. `setBusy(true)` disables
`ipfsSwitch` (it is in `busyControls`), so the platform moved focus away.

L3 — The repair in `onFocusChanged` did run, but `focusAnchor` had nothing to place: while the
transition is in progress every control of the section is disabled, so `Graph.of(content)` is empty.
The memory therefore has to survive the transition, not react to the focus change.

L4 — After the fix, on the box, twice each way: `Parado` → OK → `En línea` with focus still on the
switch, and `En línea` → OK → `Parado` with focus still on the switch. IPFS process present/absent
accordingly (PID 10572, 10710).

L5 — Not covered: the same transition on a slow box where the section changes while busy (the
remembered control is hidden); the restore then falls back to the section anchor, and if that fails
the focus stays where the platform put it. Untested because the UI does not let a section change
while busy in the paths walked.
