# HaP — D-pad rows must follow the screen, not the view tree

Found on the emulator while validating the transport line (V2 in the backlog): in `Conexiones`, DOWN
from the second URL landed on the LAN switch, which sits one visual row **above** it. The graph was
following the view tree (left column's two URL rows, then the right column's switch) instead of what
a person sees (URL + switch on one line, the second URL below). No unit caught it because every
existing case had a single column.

## Specs

S1 — The navigation contract, verbatim: «Foco D-pad siempre visible. Navegación determinista.» A
DOWN press must land on the next row down, or stay where it is at the end of a section. It must never
move up.

S2 — The structural rule that stays, verbatim from the class contract: «Este clase reemplaza esa
conjetura con un orden derivado de **cómo se construye la pantalla**». The fix keeps structural
movement: one row per press, the column preserved and clamped (`FocusGrid`). It only decides which
cells share a line.

S3 — The cost rule, from the brief: «Eficiencia de RAM, CPU, almacenamiento, batería y red». Ordering
runs on a key press over a few dozen cells, allocates two small arrays, and never touches the network
or the disk.

S4 — Honesty: a screen that has not been laid out must keep the tree order, because zero-sized views
carry no position. A reorder based on unmeasured views would be a guess.

S5 — Evidence: the unit suite pins the arithmetic, and the emulator shows the actual panel moving
down (`docs/Emulator.md`, evidence list at the end of this file).

## Tasks

| ID | Task | Specs | Route | Commit |
| --- | --- | --- | --- | --- |
| F1 | `FocusRows` pure row ordering and its JVM tests | S1, S2, S4 | parent | pending |
| F2 | `FocusNavigator` measures cells and rebuilds rows from it | S1, S2, S3 | parent | pending |
| F3 | Emulator walk of every section: rail at speed, clamp, RIGHT within a row, BACK | S5 | parent | pending |

## Log

L1 — Originating request, verbatim: «continua. Para validar, tenemos el simulador android que puedes
lanzarlo y esta docuemtnado» and, from the brief, «Navegación determinista» / «Foco D-pad siempre
visible». The full brief is the message that opened the session.

L2 — Measured on the emulator, before the fix (`haptv`, 1920x1080 at 320dpi, arm64 debug build):

| Press | Focus after | Screen position |
| --- | --- | --- |
| start | Copy (local M3U) | y=206 |
| DOWN | Copy (local EPG) | y=282 |
| DOWN | LAN switch | **y=226 — moved up** |
| DOWN | External-player switch | y=624 |

L3 — After the fix, same walk: Copy (M3U) → Copy (EPG) → External-player switch → Refresh clients →
Restart proxy, and RIGHT from Copy (M3U) lands on the LAN switch on the same line, while DOWN from
the switch lands on the EPG copy below it. DOWN at the last control of the section stays there.

L4 — Other checks on the emulator, all passing: rail DOWN at 0.2 s and 1.5 s spacing reaches
`Conexiones` in four presses (the earlier "swallowed press" was an artifact of one `adb shell` per
event, not the app); the rail scrolls to keep the focused entry visible; BACK from a section returns
to `Inicio`; the Home cards walk one per DOWN.

L5 — The transport line was verified on the emulator through a host fixture served over
`adb reverse tcp:8888 tcp:8888`: all three wordings rendered (`data 0 s ago`, `no MPEG-TS
recognised`, `no data for 120 s`) with three upstreams, and the block is visible above the Refresh
button. It sits at the top of the panel because below the client rows it fell past the last focusable
control, and a scroll that only follows focus never revealed it. A real stream is still owed (V2).
