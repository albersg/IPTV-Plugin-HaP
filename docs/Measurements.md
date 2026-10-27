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

## El analizador de transporte, medido en el host

Esta medida no se tomó en la caja: responde a otra pregunta, cuánto cuesta mirar el MPEG-TS mientras
se reenvía, no cómo se ve la imagen. Banco: x86-64, g++ 14, `-O2`, paquetes TS sintéticos de 188
bytes con continuidad correcta, alimentados en bloques de 64 KiB (348 paquetes exactos por bloque,
para que el último paquete no quede partido y la medida no mida una resincronización inventada).

| Métrica | Valor |
| --- | --- |
| Bytes analizados | **268.500.096** (256 MiB) |
| Paquetes | **1.428.192** |
| Errores de continuidad / sincronización | 0 / 0 |
| Tiempo de pared | **12 ms** |
| Throughput | **~21 GiB/s** en un hilo |

A 1,5 Mbps reales eso es aproximadamente el **0,009 % de un núcleo**. El analizador va dentro del
lector de upstream que ya existía: no añade hilos, no reserva memoria por paquete y mantiene como
mucho 64 PIDs en una tabla fija.

La segunda medida es la que importa en la caja y todavía no existe: con un canal real, cuántos
errores de continuidad aparecen en cinco minutos y si el contador de «sin datos» coincide con lo que
ve el reproductor. Hasta entonces, un transporte limpio no afirma nada sobre la pantalla.

## Un stream real, en la caja, con el analizador de transporte

Canal `ALTITUDE SPORTS *` (`content_id` de la lista del usuario), servido por el proxy de HaP hacia
`127.0.0.1:8888` a través de `adb forward`, con un cliente `curl` durante 13 minutos. Los números los
publica el propio proxy en `/stat?action=get_clients`, es decir, salen del analizador que corre dentro
del hilo de lectura, no de una estimación externa.

| Métrica | Valor |
| --- | --- |
| Paquetes TS analizados | **1.271.559** |
| Errores de continuidad | **0** |
| Discontinuidades de PCR | **0** |
| Bytes sin encuadrar (`syncErrors`) | **164**, una sola vez al inicio y sin crecer después |
| Bitrate de llegada (picos) | **8,39 Mb/s** |
| Antigüedad del último payload | 0,2–1,8 s: la entrega es a ráfagas, no continua |
| `stalled` | false en todo el tramo |

Los 164 bytes iniciales son el desalineado del primer paquete de la sesión (el cuerpo empieza a mitad
de paquete), no corrupción: no crecieron en 1,27 millones de paquetes. La lección para la interfaz es
que `syncErrors` no debe leerse como «errores»; por eso la app muestra continuidad y frescura, no ese
contador.

### Dos clientes sobre un solo upstream

Con un segundo cliente limitado a 100 KB/s: `active_broadcasts: 1`, `streams: 1`, `clients: 2`, uno
con 56 MB servidos y el lento con 7 MB. El upstream no se duplicó y la continuidad siguió en 0. Al
cerrar ambos clientes, el broadcast desaparece (`streams: []`) y el proxy sigue respondiendo `/aio`.

### Memoria, CPU, hilos y sockets con el stream en curso

Medido con `tools/device-check.sh footprint` (ventana de CPU de 2 s sobre `/proc/<pid>/stat`; RSS de
`ps`, que **cuenta las páginas compartidas en cada proceso**, así que la suma es una cota superior,
no memoria única).

| Estado | App | AceServe (`libacepython.so`) | IPFS | Suma RSS | Hilos | Sockets |
| --- | --- | --- | --- | --- | --- | --- |
| Stream + IPFS | 112 MB, 1,0 % CPU | **299 MB, 39,5 % CPU** | 101 MB | **512 MB** | 89 | **329** (320 establecidos) |
| Stream, IPFS parado | 115 MB, 1,5 % CPU | **270 MB, 49,5 % CPU** | — | **387 MB** | 75 | 23 (18) |
| Stream + IPFS arrancado después | 115 MB | 284 MB, 43,5 % CPU | — | 399 MB | 75 | 23 |

Dos hechos que el brief pedía y que ahora están medidos:

- **IPFS es caro en esta caja**: ~100 MB de RSS, 13 hilos y ~300 conexiones establecidas de swarm.
  Pararlo no afectó al stream en curso: los paquetes siguieron subiendo (842.129 → 875.591) y el
  proxy no cambió de estado. Arrancarlo de nuevo tampoco.
- **El motor es el coste dominante**: 39–50 % de un núcleo y ~280 MB de RSS mientras sirve un canal.
  En un SoC de 4 núcleos eso es ~10–12 % del total, sostenido durante 13 minutos. No es una medida de
  arranque: es el estado estacionario.

## Arranque en frío de un canal, seis canales seguidos (2026-10-26)

Servidor recién arrancado, `adb forward` y `curl -w`, canal tras canal sin reutilizar ninguno:

| Canal | Primer byte | Bytes recibidos | Cierre |
| --- | --- | --- | --- |
| `ALTITUDE SPORTS *` | **1,39 s** | 52,4 MB | sigue |
| `ALTITUDE SPORTS 1080p *` | **1,40 s** | 50,3 MB | sigue |
| `DAZN Baloncesto 1080p **` | **1,73 s** | 0 | 31,7 s |
| `DAZN Baloncesto 2 720p *` | **0,98 s** | 40,9 MB | sigue |
| `DAZN Baloncesto 720p *` | **1,21 s** | 55,6 MB | sigue |
| `DAZN Baloncesto 720p **` | **1,34 s** | 0 | 31,3 s |

Y el proxy en el mismo estado: `/aio` con 388 canales (42.270 bytes) tarda **8,6–17,6 ms** hasta el
primer byte, y `/stat?action=get_clients` **4,6–11,5 ms**.

Lectura: un canal sano entrega el primer byte en ~1–1,7 s, así que el margen de mejora no está en los
buffers. Los dos canales que el motor acepta y no alimenta no son un caso de lento: envían cero bytes
durante ~31 s, que es la ventana de prebuffer del propio motor (`prebuf;0;0;…` cada segundo), no el
timeout del lector. Por eso el arranque en caliente (mantener el broadcast vivo) no se implementa: como
mucho ahorraría ~1,4 s en una vuelta, a cambio de dejar el motor encendido (39–50 % de un núcleo,
~280 MB) para un canal que nadie mira.

En la misma sesión se midió la recuperación con la bandera encendida sobre un canal silencioso: el
primer reintento llegaba tras **~30 s**, porque quien cerraba la sesión era el motor. Con el vigilante
de progreso cero (20 s, solo sin peers) el aviso aparece **23 s después de la petición** — la sesión
empieza ~3 s después — y el cliente se cierra a los **30,5 s** en vez de esperar 80 s. El mismo día, el
canal sano: primer byte 1,77 s, 25 MB en 26 s, **recoveries 0** y ninguna línea del vigilante.

## Corte de red real, con un cliente (2026-10-27)

Stream sano (`ALTITUDE SPORTS *`) con un cliente `curl`; se apaga el Wi-Fi de la caja 25 s después de
empezar y se vuelve a encender 30 s más tarde:

| Momento | Clientes | Bytes servidos | recoveries |
| --- | --- | --- | --- |
| Antes del corte | 1 | 25.163.520 | 0 |
| Corte +20 s | 1 | 25.163.520 | 2 |
| Red de vuelta +15 s | 1 | 25.163.520 | 4 |
| Red de vuelta +60 s (antes del abandono) | 1 | 25.163.520 | 8 |

El cliente **no se desconecta** durante 67 s: el proxy mantiene su respuesta HTTP abierta mientras
reintenta. Lo que falla es el reenganche: tras un `STOP` que emite el propio proxy, el motor deja de
responder en la API (`HELLOTS`/`START` sin respuesta), así que los reintentos 3-8 no llegan a abrir
sesión. Con la ventana anterior (3 intentos) el abandono llegaba a los 30 s; ahora llega a los 67 s.
