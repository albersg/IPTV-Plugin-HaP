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

## Guía de eventos, con el fichero real y la lista real

Medido con `guiatv.xml` (34.771.430 B, la guía que la app consulta), la lista del usuario
(`lista-unificada.m3u`, 388 canales) y el código de producción (`EpgWindow` → `ChannelGuideMatcher` →
`EventCatalog`), sobre una JVM con `-Xmx256m` para no darle más holgura que la que tiene el dispositivo.

| Medida | Valor | Cómo se obtuvo |
| --- | --- | --- |
Guía descargada | 34.771.430 B | `curl`, coincide con la medición anterior |
Canales de la guía | 642 | `window.channels().size()` |
Programas en el fichero | 83.073 | medición previa sobre el XML completo |
Programas retenidos en la ventana (−1 h, +6 h) | **4.486** | `window.programmes().size()` |
De ellos, en emisión ahora | 578 | `window.onNow(now)` |
Tiempo de parseo completo | **766 ms** | reloj de pared, incluye descarga desde disco |
Memoria usada al terminar | **64 MB** | `totalMemory() − freeMemory()` con tope de 256 MB |
Canales de la lista emparejados con la guía | **93 de 388** | `matchAll(...).size()` |
Tiempo de emparejamiento | 319 ms | reloj de pared |
Eventos en emisión ahora | **43** | `EventCatalog.onNow(...)` |

Lectura: el parser deja **4.486 de 83.073** programas (5,4 %), que es exactamente lo que pretende —la
ventana decide cuánto se guarda, no el tamaño del fichero— y lo hace en menos de un segundo gastando 64 MB,
dentro de lo que aguanta el dispositivo.

Los 93 emparejamientos son más que los 75 estimados antes porque aquí se ejercitan las cuatro capas del
emparejador, no solo la de nombre normalizado.

Ejemplo real de la salida, con la clasificación de deporte aplicada al título:

```
Sun Oct 25 11:00:00 CET 2026 | M+ LALIGA 3                  | canales=2 | FUTBOL
Sun Oct 25 11:00:00 CET 2026 | M+ Liga de Campeones 3        | canales=2 | FUTBOL
Sun Oct 25 08:00:00 CET 2026 | M+ Baloncesto 3               | canales=1 | BALONCESTO
Sun Oct 25 09:00:00 CET 2026 | M+ Deportes 4                 | canales=2 | null
```

No medido aquí: el mismo recorrido **en la caja**. Esta medición es de escritorio; el APK de debug no se
instaló sobre el de release porque llevan firmas distintas y desinstalar habría borrado las fuentes del
usuario. Queda pendiente medirlo en el dispositivo.

## El mismo recorrido, ahora en la caja — y lo que reveló

APK de debug firmado con la clave de release (`b9f4146a…`) para poder instalarlo encima sin perder las
fuentes, `am start … --es op guide`, MiTV-AFMU0:

```
guide bytes=4550534 channels=642 programmes=4485 playlistNames=390
matched=93 events=0 parseMs=96783 matchMs=12628 playlistMs=331 totalMs=109744
```

| Medida | Escritorio | Caja | Lectura |
| --- | --- | --- | --- |
Descarga + parseo de la guía | 766 ms | **96.783 ms** | incluye la descarga de 34,7 MB (4,5 MB comprimidos) |
Emparejar 390 canales con 642 de guía | 319 ms | **12.628 ms** | **39× más lento, y demasiado para una pantalla** |
Canales emparejados | 93/388 | 93/390 | coincide |
Programas retenidos | 4.486 | 4.485 | coincide |

**Dos defectos que solo aparecen aquí:**

1. **Traer la guía tarda ~97 s en el dispositivo.** Ninguna pantalla puede esperar eso: la guía tiene que
   descargarse una vez, guardarse en disco y refrescarse en segundo plano. Sin caché, la función de eventos
   no es usable.
2. **El emparejamiento tarda 12,6 s.** 250.000 comparaciones de nombre no deberían costar eso, lo que
   apunta a normalizar las mismas cadenas una y otra vez dentro del bucle. Es corregible precalculando las
   formas normalizadas una sola vez, y hay que corregirlo antes de la pantalla: 12 s por refresco se
   percibe como que la app se ha colgado.

Y una cifra que **no** vale: `events=0` es un defecto de la herramienta de medida —la op de debug pasa un
mapa vacío a `EventCatalog`— no del catálogo, que en escritorio produjo 43 eventos. Queda como prueba
pendiente hasta corregir la op.

`heapUsedMb` tampoco se registra como buena: mide el montón en el instante final, no el pico.

## El filtro por deporte, verificado con la guía real y la lista real

Mismo banco que la medición anterior: `guiatv.xml` (34,7 MB), `lista-unificada.m3u` (388 canales), código
de producción. Eventos en emisión ahora, y qué deja cada filtro:

| Filtro | Eventos | Ejemplos reales |
| --- | --- | --- |
Todos | **43** | — |
Fútbol | **22** | M+ LALIGA 3, M+ Liga de Campeones 3, M+ Liga de Campeones 4 |
Baloncesto | **3** | M+ Baloncesto, DAZN Baloncesto, M+ Baloncesto 3 |
Motor | **1** | SS18 Full Stage Replay - Rally Sweden 2024 |
Tenis | **1** | Tennis: WTA 500 · Finale, Ningbo Open |
Ciclismo | **1** | Merckx: el caníbal del ciclismo |
Sin clasificar | **15** | títulos que no nombran deporte |

La aritmética cierra: 22 + 3 + 1 + 1 + 1 = **28 clasificados**, más **15 sin clasificar**, son los **43**
eventos. Ningún evento se cuenta dos veces y ninguno se pierde por el camino, que es la propiedad que el
filtro tenía que cumplir.

Dos aciertos y un matiz honesto:
- «Rally Sweden» cae en Motor por la palabra *rally*, sin que nadie lo haya etiquetado.
- «Tennis: WTA 500» cae en Tenis por *tennis*.
- «Merckx: el caníbal del ciclismo» cae en Ciclismo: es un documental sobre un ciclista, no una carrera. La
  clasificación por título acierta el tema y no el formato, y eso es lo que se puede pedir a una heurística
  cuando la guía no trae género.

No verificado en la caja: pulsar los chips en pantalla. La lógica que deciden los chips es la que produce
esta tabla; lo que falta es el recorrido con el mando.
