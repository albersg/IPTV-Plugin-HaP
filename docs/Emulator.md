# Running HaP on a local emulator

HaP needs a television to be tested for real, but not to be *seen*. This boots an emulator with the
box's geometry on a development machine, installs the app and screenshots it.

```bash
tools/emulator.sh                # boot, install, open, screenshot
tools/emulator.sh --screenshot   # capture whatever is on screen
tools/emulator.sh --stop         # shut it down
```

## The one thing that decides whether it works

**Use the `arm64` build, never `armv7`.**

Measured on an `android-34;google_apis;x86_64` image:

| APK | Result |
| --- | --- |
| `armeabi-v7a` | `INSTALL_FAILED_NO_MATCHING_ABIS` (res=-113) |
| `arm64-v8a` | installs and runs |

The image translates 64-bit ARM through its native bridge and not 32-bit ARM, which is the opposite of
what the box needs. Both variants exist, so nothing has to be recompiled for the emulator — but the
distinction has to be known, because the failure message says nothing about it.

## System packages

The emulator needs Qt, X libraries and audio even when it runs headless. `libsm6` is the one that is
easy to miss, because the emulator blames `xcb-cursor` instead. Without them it aborts with
messages that point somewhere else entirely:

```bash
sudo apt-get install -y libpulse0 libnss3 libxcb1 libxdamage1 libxcomposite1 libxrandr2 \
    libxtst6 libasound2t64 libxkbfile1 libxi6 libxcursor1 libxinerama1 libgl1 libglu1-mesa \
    libx11-xcb1 libxcb-keysyms1 libxcb-cursor0 libxcb-icccm4 libxcb-image0 libxcb-render-util0 \
    libxcb-shape0 libxcb-xinerama0 libxkbcommon-x11-0 libsm6 libice6
```

And the user must be able to open `/dev/kvm`:

```bash
sudo usermod -aG kvm $USER   # permanent, applies to the next session
sudo chmod 666 /dev/kvm      # applies immediately
```

## Three failures worth knowing, because each one lies about its cause

1. **`no Qt platform plugin could be initialized`** — and it is **not** the `xcb-cursor` the emulator
   suggests. The plugin is present and still fails because one of its own dependencies is missing:
   **`libSM.so.6`** (package `libsm6`, with `libice6`). The only way to see that is
   `QT_DEBUG_PLUGINS=1`, which prints the `dlopen` error the normal message hides:

   ```
   libqxcbAndroidEmu.so cannot load: (libSM.so.6: cannot open shared object file)
   ```

   With `libsm6` installed the window opens normally under WSLg. `-no-window` still works and is used
   when no display is wanted, but it is no longer a workaround for a broken plugin.
2. **`FATAL: Failed to create jwk directory ... Permission denied`** — a previous run as root left
   `/run/user/<uid>/avd` unwritable. The fix is a private runtime directory, which the script sets:
   `XDG_RUNTIME_DIR=/tmp/xdg-$USER`.
3. **`adb pull` reports success and writes nothing** — with a Windows path from WSL the file simply
   does not appear. `adb exec-out screencap -p > file.png` works.

## Switching the emulator to Spanish

The emulator starts in English. The app's Spanish strings are the ones verified on the box; to see the
same thing here:

```bash
adb shell "setprop persist.sys.locale es-ES; setprop ctl.restart zygote"
```

## What this replaces, and what it does not

It replaces the round trip of "I cannot tell how it looks". Layout, spacing, focus rings, list
truncation and the events screen can all be judged here.

It does not replace the box: the AceStream engine, the real channels, the 2 GB of RAM and the actual
network only exist there. Playback and server behaviour stay a device check.
