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

## Cómo servir una lista con el Kubo embebido

Verificado en la caja: el gateway embebido trae contenido de la red IPFS (`HTTP 200` para un CID de
prueba). Los pasos, de principio a fin:

1. En el PC, publica la lista en IPFS y apunta el CID:

   ```bash
   ipfs add lista-unificada.m3u
   # added bafk... lista-unificada.m3u  -> ese es el CID
   ```

2. Mantén el contenido accesible. Si tu nodo deja de anunciarlo, la caja no podrá traerlo:

   ```bash
   ipfs pin add <CID>
   ```

3. En la caja, `Ajustes` → `IPFS` → activa el interruptor. Espera a que el estado deje de estar en
   `Parado`. El gateway queda en `http://127.0.0.1:8080`.

4. En `Fuentes`, añade una fuente con la URL del gateway y el CID:

   ```
   http://127.0.0.1:8080/ipfs/<CID>
   ```

5. Comprueba que se sirve: con el servidor de HaP en marcha, `http://127.0.0.1:8888/userm3u` debe
   listar los canales de la lista.

La lista se actualiza en el momento, sin caché de terceros, y ningún intermediario ve qué CID pides.
Si en algún momento añades una versión nueva, el CID cambia: vuelve a añadir la fuente con el nuevo.

### Meter la lista en el nodo de la propia caja

El nodo embebido escucha la API de Kubo en `127.0.0.1:5001`, así que se le puede añadir contenido desde
el PC sin instalar nada en la caja. Verificado con una lista de 388 canales, que volvió por el gateway
byte a byte idéntica.

```bash
adb forward tcp:15001 tcp:5001
adb forward tcp:18080 tcp:8080

# alta, fijada para que no se recoja
curl -F "file=@lista-unificada.m3u" "http://127.0.0.1:15001/api/v0/add?pin=true"
# -> {"Hash":"Qm...","Size":"..."}

# comprobar que el gateway la sirve
curl -s "http://127.0.0.1:18080/ipfs/Qm..." | head -1   # #EXTM3U
```

Con el CID, la fuente es `http://127.0.0.1:8080/ipfs/<CID>`. Ventaja frente al fichero suelto: el CID es
inmutable y verificable, y sirve para comprobar que lo que sirves es exactamente lo que publicaste. En
contra: cada versión nueva de la lista es un CID nuevo, así que hay que volver a añadir la fuente.

La API solo escucha en `127.0.0.1`, de modo que no queda expuesta a la red local: para llegar a ella hay
que pasar por `adb`.

## Límite que no cambia con nada de esto

IPFS solo afecta a **la lista**. Los canales dentro de ella siguen apuntando a los servidores del
proveedor, así que la reproducción no gana privacidad por usar IPFS. La ganancia está en no revelar qué
listas consumes.
