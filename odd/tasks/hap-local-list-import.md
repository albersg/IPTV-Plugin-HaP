# Fuentes M3U locales junto a las remotas

## Specs

- **S1.** La app debe admitir **las dos clases de fuente a la vez**: por URL (lo que ya hace) y por
  fichero local, conviviendo en la misma lista de `Fuentes` y seleccionables por separado.
  Cita del usuario: «podemos tener ambas opciones? tanto url como fichero local?»
- **S2.** El fichero debe poder llegar a la caja **por `adb push`** y la app tiene que encontrarlo, sin
  depender de permisos de almacenamiento externo ni de apps de terceros.
  Cita: «que me lo pases mediante adb a la tvbox»
- **S3.** Cuando el dispositivo tenga un explorador de ficheros, la app debe ofrecer **importar
  abriéndolo**; cuando no lo tenga, la importación no puede quedar inutilizable.
  Cita: «y se pueda improtar desde la apk un fichero abriendo el expllorador de ficheros.»
- **S4.** Las dos vías de IPFS, **pasarelas públicas** y **nodo local**, se admiten ambas; la app ya
  sirve las tres (pasarela pública, Kubo embebido, nodo externo) y la documentación debe decir cuál
  conviene y por qué.
  Cita: «admito ambas?¿ Pasarelas IPFS (Tardan más en actualizar) o Nodos IPFS Locales (Requiere IPFS
  Desktop instalado y en ejecución)»
- **S5.** Sin cambios en la cuota de caché: `LIVE_CACHE_SIZE_BYTES` sigue siendo 104857600.
- **S6.** La interfaz de televisión no puede retroceder: el rail, el D-pad determinista y el foco
  inicial ya están verificados en hardware.

## Lo que se comprobó en la caja antes de diseñar

- `ACTION_OPEN_DOCUMENT` **no tiene manejador**: la caja no trae `DocumentsUI`.
- `ACTION_GET_CONTENT` + `CATEGORY_OPENABLE` resuelve a `com.lonelycatgames.Xplore` (X-plore, instalado
  por el usuario) y a `com.android.tv.frameworkpackagestubs.Stubs$DocumentsStub`, que es el **stub** de
  Android TV para documentos: en una caja sin gestor de ficheros no abre nada útil.
- Conclusión: no se puede depender del selector del sistema. La vía garantizada es una carpeta de la
  propia app, y el selector es una comodidad adicional cuando exista.

## Diseño

- Una fuente local se guarda como **copia** en `getExternalFilesDir("lists")`: accesible por `adb push`
  desde `/sdcard/Android/data/com.streamvault.plugin.hap/files/lists/`, sin permisos en tiempo de
  ejecución, y borrada al desinstalar.
- En `USER_M3U_SOURCES` la entrada viaja como `nombre<TAB>localfile:/ruta/absoluta.m3u`. El proxy ya
  sirve `/user_m3u` y es él quien obtiene cada fuente, así que **solo** hay que hacer que lea el fichero
  cuando la entrada empieza por `localfile:` en lugar de hacer la petición HTTP. No hace falta ninguna
  ruta nueva en el servidor ni pasar el contenido por variables de entorno.
- La validación es la misma en las dos vías: cabecera `#EXTM3U`, al menos un canal AceStream, y el
  límite de 10 MiB.

## Tasks

- **T1 — catálogo y validación local** (S1, S2). Tipo de fuente de fichero, normalización del valor,
  validación y tests JVM puros.
- **T2 — lectura nativa** (S1). `plugins.cpp`: si la entrada empieza por `localfile:`, leer el fichero
  con límite de bytes en lugar de pedirlo por HTTP; test nativo de la función pura.
- **T3 — interfaz de Fuentes** (S1, S3). Botón de importar, lista de ficheros encontrados en la carpeta,
  acción de borrar; teclado y D-pad; nada de `requestFocus` en refrescos.
- **T4 — textos y documentación** (S4). EN y ES en sincronía, `docs/` explicando pasarela frente a nodo
  local, `odd/BACKLOG.md`.
- **T5 — verificación en la caja** (S2, S3, S6). `adb push` de un M3U de prueba, importación real en la
  tele, la fuente aparece y `Test lista` la lee; captura de evidencia.

## Estado

- **T1, T2, T3a y T3b entregados** en `adf49e6` y `6eaeaef`, en la rama `feat/local-m3u-import`.
- **T5 verificado en la caja el 2026-02-14**, con estas evidencias:
  - `adb push prueba-local.m3u` a
    `/sdcard/Android/data/com.streamvault.plugin.hap/files/lists/` (250 B) y la app lo lista como
    `prueba-local.m3u`, 250 B, con su botón `Añadir`.
  - Al pulsar `Añadir`: «Fuente añadida», fila nueva con el valor
    `localfile:/storage/emulated/0/.../files/lists/prueba-local.m3u` y la casilla marcada.
  - Con el servidor en marcha, el proxy sirve el contenido del fichero:
    `GET /userm3u` devuelve los dos canales con `group-title="Prueba"`, y `GET /aio` los expande a
    `content_id/<infohash>/...`, que es la ruta real de reproducción.
- **T4 entregado**: `docs/Ipfs-Sources.md` compara pasarela pública, Kubo embebido y nodo externo.

### Frontera de la rebanada

La función se entrega como una sola unidad: el lector nativo, el tipo de fuente, la validación y la
pantalla que la crea son un mismo comportamiento, y separarlos dejaría tests sin el código que prueban.
Los dos commits del trabajo suman unas 900 líneas y van juntos en el mismo PR.

### Lo que no se probó

- El selector `GET_CONTENT`: en esta caja resuelve, y el botón `Importar archivo` aparece, pero no se
  pulsó ni se eligió un archivo con él. La vía verificada es la carpeta.
- Borrar la fuente desde la fila (`Quitar`) no se ejercitó en pantalla.

## Log

- **L1.** «podemos tener ambas opciones? tanto url como fichero local? que me lo pases mediante adb a la
  tvbox y se pueda improtar desde la apk un fichero abriendo el expllorador de ficheros.»
- **L2.** «en cuanto a m3u, tengo dos opciones, que las difernecia? cual es mejor? admito ambas?¿
  Pasarelas IPFS (Tardan más en actualizar) o Nodos IPFS Locales (Requiere IPFS Desktop instalado y en
  ejecución)»
