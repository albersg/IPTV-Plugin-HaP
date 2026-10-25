# Eventos: emparejar la guía con los canales

## Specs

- **S1.** Ningún canal puede desaparecer por no tener guía. Eventos es una **vista** encima de la lista
  existente, nunca un filtro de la lista servida en `/aio` ni de la sección `Canales`.
  Cita: «que sea lo mejor posible sin perder ningún canal ni nada».
- **S2.** El emparejamiento canal→guía se resuelve por capas, de más fiable a menos: `tvg-id` exacto,
  nombre normalizado, alias explícitos, y similitud por tokens marcada como **probable**.
- **S3.** Una coincidencia dudosa nunca se presenta como certeza: la capa de similitud se marca.
- **S4.** La guía se parsea en streaming y solo se retiene la ventana temporal, para que 34,7 MB no
  lleguen nunca a memoria de una caja de 2 GB.
- **S5.** Si la guía falla, el resto de la app funciona igual: la sección de eventos dice que no hay datos.
- **S6.** El emparejamiento se cachea por huella de la lista: se recalcula cuando cambia la lista, no en
  cada refresco.

## Medido antes de diseñar

- La lista del usuario: 388 canales, **todos** con `tvg-id`, pero generados desde el nombre
  (`DAZN_LaLiga_720p_`), así que **no casan** con la guía real (`DAZN LaLiga HD`): 0 de 357.
- Normalizando el nombre: **75 de 388** casan, incluidos los tres `DAZN LaLiga` que emiten el partido.
- La guía útil es la de 642 canales y 34,7 MB; la de 150 canales y 17,6 MB **no contiene** el evento.

## Tasks

- **T1 — `ChannelGuideMatcher`** con las cuatro capas y tests puros. Sin red, sin Android. **(hecho)**
- **T2 — `EpgWindow`**: parseo en streaming del XMLTV y ventana temporal. **(hecho)** La caché en
  disco queda para T3.
- **Caché en disco de la guía** — `GuideCache`, 7 tests. **(hecho)** Falta conectarla a la descarga y a la pantalla.
- **T3 — Eventos**: agrupación por título y número de canales. **(datos hechos)** Faltan la caché en
  disco de la guía y la pantalla.
- **T4 — Evento → filtrar → sonda ligera → orden por calidad → reproducir.**
- **T5 — Alias editables** y sugerencias a partir de los canales sin casar.

## Log

- **L1.** «Pues impleméntalo todo robusto y perfecto», tras pedir «que me salga el Real Madrid - Barcelona
  en los canales acestream que tengo en la apk».

## Refinamiento (sesión nocturna)

- La guía ya parseada se reutiliza 30 minutos: reparsear 34 MB en cada refresco era desperdicio.
- Los chips se bloquean mientras se lee, para que una pulsación no compita con la carga, y el texto de
  estado dice qué está pasando.
- Los fallos se distinguen: sin red y sin copia guardada se avisa de forma distinta a una guía ilegible.
- El evento elegido se marca en negrita con `▸`, y el canal que se reproduce también.

Pendiente de ver con datos: cargar una lista en el emulador y comprobar los filtros y el recorrido
evento → canales → reproducir.

## Verificado en el emulador (con datos reales)

- Canales cargados **sin el motor**: 388 canales de dos listas locales, mediante `M3uParser`.
- Eventos con datos reales: **19 eventos**, 93 canales emparejados, ordenados por hora.
- Clasificación corregida y vista en pantalla: `DP World Tour` → Otros, `Tennis: ATP 250 · Grand Prix`
  → Tenis, `Copa del mundo de escalada` → Otros.
- La lista **se recorre y se desplaza** con el mando, con barra de resaltado en la fila enfocada.
- Pulsar un evento muestra `Channels showing this: …` con sus canales reales.

Pendiente: el modo de un solo evento (la lista se oculta y quedan solo sus canales) está implementado y
con tests, sin captura todavía; y el arranque de un canal desde ahí, que en el emulador acabará en
"no hay reproductor" porque no hay AceStream instalado.

### Verificación completa en el emulador (noche)

Todo lo siguiente se vio en pantalla, con dos listas reales (388 canales) y la guía real:

| Comprobación | Resultado |
| --- | --- |
Canales sin motor | 388 canales leídos del fichero, servidor parado |
Eventos con datos | 19–20 eventos, 93 canales emparejados, orden por hora |
Filtro Fútbol | 20 → **6 eventos**, y el resumen pasa a «6 events on now» |
Recorrido con mando | DOWN desplaza y la fila enfocada lleva barra de resaltado |
Pulsar un evento | La lista se oculta y quedan sus canales, foco en el primero |
Volver | «Back to events» devuelve a la lista con el mismo filtro |
Repetición | Volver a entrar es **instantáneo**: no relee la guía (caché de 30 min) |
Sin reproductor | El intento sale como `acestream://…` y la pantalla lo avisa |
Traducciones | 364 cadenas en EN y 364 en ES, ninguna huérfana |
Lint | sin errores |

Un detalle conocido: en el emulador **los toques no disparan las filas** aunque el D-pad sí; la navegación
está pensada para mando y así se verificó.
