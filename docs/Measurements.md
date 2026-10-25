# Measurements on the Xiaomi TV Box S 3rd Gen

Device: `MiTV-AFMU0` (`twilight`), Android 14 / SDK 34, Amlogic S905X5M, 4 cores, ~1.96 GB RAM,
`armeabi-v7a`, 4096-byte pages. Display reported as 3840x2160 physical with a 1920x1080 override at
density 320, which is a **960x540dp** layout surface.

Every number below was taken on that device. Nothing here is estimated, and anything not measured is
listed at the end rather than filled in.

## Application

| Metric | Value | How |
| --- | --- | --- |
| Cold start (`TotalTime`) | **358 ms** | `am start -W` after `am force-stop`, launch activity resolved |
| Total PSS while serving | **54,857 KB** | `dumpsys meminfo` |
| Java heap | 3,316 KB | `dumpsys meminfo` |
| Native heap | 14,332 KB | `dumpsys meminfo` |
| Threads | **22** | `/proc/<pid>/status` |
| Idle CPU, server running | **~0.4 % of one core** | 2 jiffies over 5 s from `/proc/<pid>/stat` |

For comparison, before this work the same box measured a 2,084 ms cold start and an idle RSS that
climbed from 116 MB to 247 MB over a minute while the server ran. The climb stopped when the AceServe
cache was unified at 100 MiB and the UI stopped rebuilding itself every second.

## Proxy

| Metric | Value |
| --- | --- |
| `/aio` time to first byte | **3.09 - 3.30 ms** (three samples) |
| `/aio` total, HTTP 200 | 3.15 - 3.36 ms |
| `/stat` time to first byte | 4.20 ms |
| `/aio` payload | 105 bytes, valid `#EXTM3U` header with the EPG URL |

Measured with `adb forward tcp:8888 tcp:8888` and `curl -w`.

## Interface, during a D-pad journey

| Metric | Value |
| --- | --- |
| Janky frames | **0 (0.00 %)** |
| 50th percentile frame | 9 ms |
| 95th percentile frame | 11 ms |

`dumpsys gfxinfo` after `reset`, six DPAD_DOWN and three DPAD_RIGHT presses. Nine milliseconds to
eleven against a 16.7 ms budget at 60 Hz.

## Interface, behaviour

| Claim | Evidence |
| --- | --- |
| Focus survives the 1 Hz refresh | Two screencaps six seconds apart with a card focused produced an identical MD5 |
| Rail is deterministic | Four DPAD_DOWN at 1.2 s spacing from Inicio land exactly on Ajustes |
| Selection and focus are both visible | Screenshot shows Inicio selected and another item focused at the same time |
| States bind live | Card moved from grey "Servidor detenido" to green "Servidor funcionando" when the switch was pressed; `/aio` then returned 200 |
| The page title no longer slices | Title moved out of the scroll container, verified after the change |

## Network exposure, measured

With the server running and LAN mode **off**, the app's listening sockets were:

```
127.0.0.1:8888    the HaP proxy, loopback only as intended
0.0.0.0:6878      AceStream stream/HTTP
0.0.0.0:62062     AceStream control API
0.0.0.0:8621      AceStream
0.0.0.0:45xxx     the peer listener
```

Removing `--bind-all` from `AceServeRuntime`, rebuilding, reinstalling and restarting produced **the
same four `0.0.0.0` ports**. The flag is not the cause of the exposure; the engine binds every
interface by default, and it must accept inbound peers to work at all. The flag was restored.

## Not measured, and why

- **IPFS consumption on versus off.** Would need the IPFS subsystem started; it was not exercised.
- **M3U and EPG parsing cost.** No playlist is configured on this device, so there is nothing to
  parse. This is the same gap that blocks playing a real channel.
- **Playback of a real channel.** No source configured, so the streaming path has not been exercised
  since the interface was rewritten. This is the most important missing verification.
- **Memory over a long session.** Only the serving steady state was sampled, not an hour of playback.
- **720p overscan.** Only the 1080p override was tested.
