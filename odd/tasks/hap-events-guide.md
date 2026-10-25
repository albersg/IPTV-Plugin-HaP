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
