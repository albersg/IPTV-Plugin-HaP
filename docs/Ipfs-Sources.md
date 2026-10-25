# Fuentes IPFS: pasarela pública o nodo local

HaP admite tres formas de llegar a una lista M3U publicada en IPFS. Se comportan igual de cara al
reproductor, pero no cuestan lo mismo.

## Las tres formas

1. **Pasarela pública** (`https://ipfs.io/ipfs/<CID>`, `https://dweb.link/ipfs/<CID>`, …). Se pega la URL
   como cualquier otra fuente. No hay nada que instalar.
2. **Kubo embebido** en la propia caja (`http://127.0.0.1:8080/ipfs/<CID>`). Se activa en `Ajustes` →
   `IPFS`. La app arranca su propio nodo y su propio gateway.
3. **Nodo externo** (IPFS Desktop en un PC de la red). Se activa en `Ajustes` → `IPFS` → `Usar nodo
   externo`, con host y puerto. La app no arranca nada: usa el nodo que ya corre.

La app reconoce además un infohash suelto de 40 caracteres y las URLs de la API de AceStream.

## Cuál conviene

**Kubo embebido primero.** Es el que no depende de nadie: no necesita el PC encendido ni un nodo
auxiliar, obtiene el contenido en el momento y no cuenta a un tercero qué está pidiendo la caja. El coste
es que ocupa espacio en disco y consume algo de CPU y memoria.

**Nodo externo después,** si ya tienes IPFS Desktop en marcha o quieres que el almacenamiento viva fuera
de la caja. Misma inmediatez, pero el PC tiene que estar encendido, igual que con cualquier otro servicio
de la red local.

**Pasarela pública al final.** Es la única que no requiere nada, y por eso es la que más se usa para
probar. Sus dos problemas:

- **Tarda en actualizar.** Una pasarela cachea lo que sirve y no consulta la red hasta que su caché
  caduca. Un CID recién publicado puede no aparecer durante minutos u horas. No es que IPFS tarde: es
  que la pasarela no ha mirado todavía.
- **Cuenta quién pregunta.** Para servirte el contenido, la pasarela ve tu IP y el CID. Eso es
  exactamente lo que un nodo local evita.

## Límite que no cambia con nada de esto

IPFS solo afecta a **la lista**. Los canales dentro de ella siguen apuntando a los servidores del
proveedor, así que la reproducción no gana privacidad por usar IPFS. La ganancia está en no revelar qué
listas consumes.
