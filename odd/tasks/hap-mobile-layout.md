# Mobile layout — same app, TV rail on television, bottom bar on a phone

The human asked whether HaP can keep its television interface and gain a phone interface like the
supplied mockup. Answer: yes, and the existing structure is already shaped for it.

## Why this fits without a rewrite

The shell is a single `res/layout/activity_hap.xml` whose rail is an ordinary horizontal
`LinearLayout` inside a container, and the activity binds the rail by view id (`hap_nav_home`,
`hap_nav_sources`, …). Android resolves `res/layout/` by configuration: give the phone the default
directory and the television `values-w600dp`/`layout-w600dp`, and the same activity drives both. No
forking of the activity, no second app, no Compose.

The one thing that cannot simply be re-skinned is the navigation model: the television has five rail
entries and the mockup's phone has four bottom entries, with `Más` standing in for the last two.

## Specs

**S1 — The television interface must not regress.** The box reports 960x540dp, so `w600dp` selects the
television layout exactly as it does today. Rail order, icons, selection-versus-focus, D-pad behaviour,
the fixed page title and the 1 Hz refresh stay exactly as verified.

**S2 — Phone shell from the mockup.** Top bar with the `HaP` brand and `Tu servidor IPTV` under it,
settings and overflow actions at the right; content below; a bottom navigation bar with exactly these
labels: `Inicio`, `Listas`, `Canales`, `Más`. The selected entry is visibly distinct. Bottom entries
are touch targets, not D-pad items.

**S3 — Home on a phone**, in this order: server status card with a coloured state and an
`Online`-style pill; the primary action `Cómo ver mis canales` with its subtitle; two cards side by
side, `Mis listas` and `Comprobar canales`; then `Estado de los servicios` with three cards,
`AceServe`, `Proxy HTTP`, `IPFS`, each carrying its state and its detail line.

**S4 — No invented data.** The mockup also shows `Tiempo activo: 2 días, 14 horas` and
`Clientes conectados: 3`. Only show them if the runtime actually exposes them; `HapBridge` already
reports a connected-client count, so that one is real. If uptime is not available, omit the line
rather than fabricate or derive it from an app-launch timestamp that would be wrong after a restart.

**S5 — Mapping, not duplication.** `HapSections` stays the single source of truth for the five
sections. The phone bottom bar maps `Inicio`, `Listas`→Fuentes, `Canales`, and `Más` opens the two
remaining sections as a small menu (`Conexiones`, `Ajustes`). Television keeps its five direct entries.

**S6 — Preserve every capability.** Every panel, every flow and every string keeps working on both
shells. Nothing is removed to fit a smaller screen.

**S7 — Touch and D-pad.** The phone layout is for touch: focus rings are a television affordance and
must not dominate a tap-driven screen, while the television layout keeps them. Both must remain
keyboard-navigable for a phone with a keyboard or a remote-armed device.

## Tasks

| ID | Spec | Task |
| --- | --- | --- |
| M1 | S1 | Move the current shell to `res/layout-w600dp/`, leaving its content untouched |
| M2 | S2 | New `res/layout/` phone shell: top bar, content container, four-entry bottom bar |
| M3 | S5 | Navigation mapping so one activity drives both shells, including the `Más` menu |
| M4 | S3, S4 | Phone home: status card with pill, primary action, two cards, three service cards |
| M5 | S1, S7 | Verify the television layout is byte-identical in behaviour and re-check it on the box |

## Log

**L1 — user request (verbatim).** «y si la uso con un movil, podemos mantener la interfaz acutal para
tv, y para mvoil, poder hacer algo como esto?» with the phone mockup: brand block with a settings gear
and an overflow dot menu, a green status card reading `Servidor funcionando` / `Listo para tu
reproductor IPTV` with an `Online` pill and `Tiempo activo` / `Clientes conectados` rows, the blue
`Cómo ver mis canales` action, two cards `Mis listas` and `Comprobar canales`, a `Estado de los
servicios` row with `AceServe` (`Caché: 100 MiB`), `Proxy HTTP` (`Puerto 8888`) and `IPFS`
(`Opcional`, `Desactivado`), and a bottom bar with `Inicio`, `Listas`, `Canales`, `Más`.

**L2 — constraint carried from the television work.** The box's interface was verified on hardware
after several rounds of fixing, and it is the one thing that must not regress. Any layout split must
prove the television path still resolves to the same shell.
