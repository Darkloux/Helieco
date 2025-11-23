# Helieco
Plugin de economía sandbox para Helizia.

**Descripción**: Helieco agrega una moneda por Land, emisión de billetes apilables, canje con Vault y sincronización opcional con el plugin Lands.

**Requisitos**
- Java 17+
- Maven (para compilar desde código fuente)
- Servidor Paper/Spigot compatible con la API usada (versión objetivo: 1.20+)
- Plugins recomendados: `Lands` (opcional, para integración de ownership y bancos) y `Vault` (proveedor de economía)

**Instalación (compilar desde fuente)**
1. Desde la carpeta del proyecto ejecutar:

```
mvn -DskipTests package
```

2. Copiar el JAR generado (`target/helieco-0.1.0-SNAPSHOT.jar`) a la carpeta `plugins/` de tu servidor y reiniciar o recargar el servidor.

**Configuración**
El archivo de configuración principal es `src/main/resources/config.yml` (instalado en `plugins/Helieco/config.yml`). Las claves más relevantes:

- `currency.default_item`: material por defecto para billete (p.ej. `PAPER`).
- `currency.expiration_days`: días por defecto hasta expiración de un billete emitido.
- `currency.max_issue_count`: máximo billetes por emisión.
- `currency.sync.enabled`: habilita sincronización periódica con Lands (por defecto `false`).
- `currency.sync.interval_seconds`: intervalo en segundos para la sincronización periódica.
- `biome_drops`: reglas por bioma (opcional, relacionado con drops de biomas).
- `work_points`: configuración de puntos por acciones (sección aparte).

Mantén `currency.sync.enabled` desactivado si no usas Lands o no quieres sincronización automática.

**Comandos**
Todos los comandos se ejecutan con el prefijo `/landcurrency`.

- `landcurrency create <nombre>` : Crear o actualizar la moneda de la Land donde estás (se requiere ser propietario o admin).  
- `landcurrency rename <nuevo_nombre>` : Renombrar la moneda de tu land (owner).  
- `landcurrency rename <landId> <nuevo_nombre>` : Renombrar la moneda de otra land por `landId` (OP o permiso `helieco.admin`).
- `landcurrency emit <cantidad>` : Emitir billetes (sincroniza antes desde Lands automáticamente).
- `landcurrency info` : Mostrar información de la moneda de tu land (sincroniza antes automáticamente).
- `landcurrency redeem` : Canjear un billete en la mano (solo si está vencido).  
- `landcurrency forceredeem` : Forzar canje ignorando vencimiento (requiere OP o permiso `helieco.forceredeem`).
- `landcurrency sync` : Forzar sincronización desde Lands para tu land.
- `landcurrency reload` : Recargar configuración del plugin (permiso `helieco.reload`).
- `landcurrency help [página]` : Mostrar ayuda.

**Permisos**
Los permisos están definidos en `plugin.yml`. Resumen:

- `helieco.landcurrency` : Acceso wildcard a subcomandos (por defecto `op`).
- `helieco.landcurrency.create` : Crear/iniicar moneda (por defecto `op`).
- `helieco.landcurrency.rename` : Renombrar moneda (por defecto `op`).
- `helieco.landcurrency.emit` : Emitir billetes (por defecto `op`).
- `helieco.landcurrency.info` : Ver info de la moneda (por defecto `true`).
- `helieco.landcurrency.redeem` : Canjear billete (por defecto `true`).
- `helieco.landcurrency.sync` : Forzar sincronización desde Lands (por defecto `op`).
- `helieco.landcurrency.forceredeem` : Forzar canje (por defecto `op`).
- `helieco.reload` : Recargar configuración (por defecto `op`).
- `helieco.admin` : Permiso administrativo auxiliar (por defecto `op`).

Usa un sistema de permisos (por ejemplo LuckPerms) para asignar estos permisos a grupos en lugar de dar OP.

**Comportamiento clave y notas técnicas**

- Billetes apilables: Los billetes ya no llevan un id único por item en `PersistentDataContainer`, por lo que pueden apilarse. El plugin mantiene un contador `issuedCount` por land para saber cuántos billetes están en circulación.
- Valor por billete: calculado como `bankBalance / issuedCount` (redondeado a 2 decimales). Siempre se recalcula al emitir o canjear.
- Canje (`redeem`): sólo se permite si el billete tiene fecha de vencimiento y ya está vencido. Para administradores existe `forceredeem`.
- Sincronización con Lands: el plugin intenta `syncFromLands(landId)` antes de operaciones clave (`emit`, `info`, `redeem`) y llama `syncToLands` tras cambios locales (por ejemplo después de un canje exitoso). La integración usa reflexión y varias estrategias (métodos directos, UUID/ULID, inspección de colecciones) para soportar distintas versiones de Lands.
- Identificadores: se usan `String` para `landId` (ULID/UUID/etc). El plugin rechaza identificadores sentinela como `-1` o `0` y trata de extraer ULID de `toString()` del objeto Land como fallback.

**Logging**
- El plugin reduce el ruido en consola por defecto: trazas de reflexión y pasos internos están en nivel `FINE`. Las advertencias y errores importantes permanecen visibles.
- Si necesitas depuración detallada (FINE), habilita el nivel de logging apropiado en la configuración de tu servidor (consultar documentación de Paper/Spigot sobre cómo cambiar el nivel de `java.util.logging` o tu sistema de logback/log4j si usas uno). También puedo añadir una opción para volcar trazas a un archivo debug bajo petición.

**Pruebas y uso rápido**
1. Instala el JAR en `plugins/` y arranca el servidor.
2. Entra como jugador propietario de una land y ejecuta:

```
/landcurrency create MiMoneda
/landcurrency emit 10
/landcurrency info
/landcurrency redeem
```

3. Para renombrar (owner):

```
/landcurrency rename NuevoNombre
```

4. Como admin (por id):

```
/landcurrency rename <landId> NuevoNombre
```

**Contribuciones / Desarrollo**
- El código fuente está en este repositorio. Para compilar usa `mvn -DskipTests package`.
- Si encuentras problemas con la integración de Lands (métodos que cambian entre versiones), incluye logs en nivel FINE con las llamadas reflectivas para que podamos ampliar las heurísticas.

**Contacto / Soporte**
- Abre un issue con pasos para reproducir y logs relevantes. Incluye la versión de `Lands` y la salida de consola si hay fallas en la identificación de la land.