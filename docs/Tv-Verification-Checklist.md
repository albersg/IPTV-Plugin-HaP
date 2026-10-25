# TV verification checklist — what must be observed on the device

The UI redesign was built and reviewed without a device. Compilation, 52 JVM tests and lint prove
that the code is coherent; they prove nothing about how it looks or behaves on a television. This
file is the debt list, written so a single session with the box can settle every open claim.

Run this before believing any screenshot. The brief says it plainly: the work is not finished
because the design looks good in a capture.

## 0. Setup

```bash
ADB=/mnt/c/Users/alber/hap-tools/platform-tools/adb.exe
$ADB devices                       # expect MiTV-AFMU0  device
$ADB shell dumpsys display | grep -E 'mBaseDisplayInfo|real'
$ADB shell wm size; $ADB shell wm density
```

Stage the APK under `C:\Users\alber\hap-tools` first: the Windows adb cannot read a WSL path.

Two hard-won rules for this box: `uiautomator dump` never reaches idle, so use `screencap` plus
`input keyevent`; and wake it with `KEYCODE_WAKEUP` before reading the screen.

## 1. Rail geometry, selection versus focus (S2, S3)

```bash
$ADB shell input keyevent KEYCODE_WAKEUP
$ADB shell am start -n com.streamvault.plugin.hap/.HapConfigActivity
$ADB exec-out screencap -p > /mnt/c/Users/alber/hap-tools/v_home.png
```

- [ ] The rail shows `HaP` over `IPTV Server` and five items; nothing is clipped.
- [ ] `Inicio` is visibly **selected** while `Inicio` is also **focused** — both signals at once.
- [ ] The status card reads `Servidor funcionando` with the green dot.
- [ ] The dominant card is unmistakably the biggest element on screen.
- [ ] `Caché: 100 MiB`, `Puerto: 8888`, `Función opcional` are correct and the state words match.
- [ ] The footer shows `OK para seleccionar` and `Atrás para volver`.
- [ ] No overscan clipping at 1080p; repeat after `wm size 1280x720` and compare.

## 2. The D-pad journey (S6 points 1, 3, 5)

```bash
for k in DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_UP DPAD_RIGHT \
         DPAD_DOWN DPAD_DOWN DPAD_UP DPAD_LEFT DPAD_DOWN DPAD_UP; do
  $ADB shell input keyevent KEYCODE_$k
  $ADB exec-out screencap -p > /mnt/c/Users/alber/hap-tools/v_$k.png
done
```

- [ ] UP/DOWN inside the rail never leaves the rail or wraps past the ends.
- [ ] DOWN on the last home card does not jump somewhere unexpected.
- [ ] RIGHT from the rail enters the content; LEFT from the left edge returns to the rail.
- [ ] Focus is visible on every single stop — no control where the outline disappears.
- [ ] The focused control is never off-screen: the scroll follows it with a margin.

## 3. Focus survives the refresh (S6 point 2)

```bash
# Hold focus on a control, then capture the same screen four seconds apart.
$ADB exec-out screencap -p > /mnt/c/Users/alber/hap-tools/v_t0.png
sleep 4
$ADB exec-out screencap -p > /mnt/c/Users/alber/hap-tools/v_t4.png
```

- [ ] The focus outline is on the same control in both captures.
- [ ] Then press start, and again capture during the transition: focus must not jump while the
      status card changes from stopped to starting to running.

## 4. Section memory and BACK (S6 points 4, 6)

- [ ] Focus the third control in `Ajustes`, go to `Inicio`, come back: the third control is
      focused again, not the first.
- [ ] BACK from `Canales`, `Fuentes`, `Conexiones`, `Ajustes` returns to `Inicio`.
- [ ] BACK on `Inicio` leaves the app.
- [ ] Open `Cómo ver mis canales`, press BACK: the same home card is focused again.
- [ ] Start an update download, press BACK on `Inicio`: the download is cancelled, not the app.

## 5. The how-to-watch screen (S4)

- [ ] The addresses shown equal what `Conexiones` prints.
- [ ] With LAN mode off, only the local address appears and the sentence points to `Conexiones`.
- [ ] With LAN mode on, the LAN address appears and is reachable from a phone on the same network.
- [ ] Pressing OK on the selectable address does not trap the user in text selection.
- [ ] A long LAN URL is readable; the horizontal scroll works with the D-pad.
- [ ] Legible from three metres: check the monospace address and the body text.

## 6. Cost of the redesign (S7, brief §5)

```bash
$ADB shell dumpsys gfxinfo com.streamvault.plugin.hap reset
#   ... navigate for ~30 s ...
$ADB shell dumpsys gfxinfo com.streamvault.plugin.hap | head -30
$ADB shell dumpsys meminfo com.streamvault.plugin.hap | head -20
```

- [ ] Cold start time recorded and compared against the 2084 ms measured before the redesign.
- [ ] Frame times during a rail journey: no slow frames introduced by the new shell.
- [ ] RSS after 60 s idle, compared against the 107 MB measured before.
- [ ] Idle CPU flat; the 1 Hz refresh did not become more expensive.
- [ ] No focus-driven allocation storm: repeat the journey twice and compare jank counts.

## 7. Everything still works (S8)

Walk each panel and confirm the control responds: start/stop, restart proxy, prepare `/aio`,
`Mis listas` add/validate, custom channels add/edit/delete, clients, channel test with the
parallel batch, IPFS plus its advanced section, logs, update check and its settings. Nothing was
supposed to be deleted; this proves it.

## 8. Network and privacy, unproven since the audit

```bash
PID=$($ADB shell pidof com.jopsis.httpaceserveproxy 2>/dev/null || $ADB shell pidof com.streamvault.plugin.hap)
$ADB shell "cat /proc/net/tcp /proc/net/tcp6" | head -40
```

- [ ] With LAN mode **off**, is the AceStream engine listening on `0.0.0.0`? This settles privacy
      item P1: if it is, `--bind-all` is exposing the engine and must follow the LAN switch.
- [ ] With LAN mode **on**, a LAN client that sends `X-Forwarded-For: 192.168.1.10` must no longer
      be trusted, and a normal LAN client must still be served.
- [ ] `/stat` in a browser on another machine: a channel named `<img src=x onerror=alert(1)>` must
      render as text.
- [ ] A channel whose name contains a newline must be refused by the engine rather than executing
      a second command.

## 9. Updater negative paths

- [ ] Install an APK signed with a different key: it must be rejected with a clear message.
- [ ] Interrupt a download (pull the network): resumable or a clear error, no corrupt install.
- [ ] Low storage: the download must fail before filling the disk.
- [ ] Corrupt the downloaded APK: verification must refuse it.
- [ ] GitHub rate limit: a plain message, not a silent failure.

## Recording the outcome

For each block, keep the captures and write the observed value next to the expectation. Anything
not observed stays recorded as unverified in `odd/tasks/hap-ui-redesign.md` and
`odd/tasks/hap-security-hardening.md`; nothing is marked done because the design looks plausible.
