# Rendimiento: auditoría, problemas conocidos y plan

Estado al 2026-11-16, versión 0.6.3. **Nada de esto está aplicado todavía**: es la auditoría del
código actual, contrastada con fuentes externas, y el orden propuesto para optimizar sin perder
calidad visual.

## Síntomas reportados

- El mod baja los FPS del juego y "no usa la GPU".
- Se pone más lento cuanto más se explora.
- Cambiar de dimensión (portales) es notablemente más pesado con el mod.

Conclusión: el mod sí dibuja en la GPU. El costo viene de **dónde** y **cuántas veces** se hace el
trabajo: tareas pesadas en el hilo principal, el minimapa redibujado completo cada frame, un
bucle de sombra de hasta 64 pasos por píxel, basura constante para el GC y memoria sin límite.

## Cómo funciona hoy

```
Chunk carga ──► [HILO PRINCIPAL] snapshot (copia secciones + escanea 4 vecinos)
                    │
                    ▼
            [Workers, núcleos−2] process(): columnas en capas + ChunkVoxels
                    │
                    ▼
            ColumnStore (RAM, nunca se libera)
                    │
                    ▼
            [Workers] TerrainMesher: malla por chunk × 4 LOD + luz horneada en CPU
                    │
                    ▼
            [Render] 1 GpuBuffer por malla → 1 draw por chunk
                    │
                    ▼
            terrain.fsh: textura 4×4, sombra por ray-march, recorte, agua, banding
                    │
                    ▼
            Textura propia → se pega al HUD (minimapa) o a la pantalla (mapa)
```

## Problemas conocidos

| # | Dónde | Qué pasa | Efecto |
|---|---|---|---|
| 1 | `ChunkCapture.snapshot` | En el hilo principal por cada chunk: además de copiar secciones, escanea los 4 vecinos (~24 000 lecturas de bloque + hasta 4 096 para la altura de borde). | Tirones al moverse y al entrar |
| 2 | `MapSession.onChunkLoad` | En el Nether el snapshot se hace dos veces (capa interior y capa sobre el techo). | Nether doblemente pesado |
| 3 | `MapSession.close()` | `flush(true)`: escritura comprimida a disco en el hilo principal. | Portal lento |
| 4 | Constructor de `MapSession` y `sync()` | Lectura de disco (regiones, waypoints, importación de otros mapas) y captura de todos los chunks cargados de golpe. | Portal lento |
| 5 | `HeightTexture.view()` | Al entrar a cada dimensión rellena 16 MB con 4 M de `putFloat` y los sube (32 MB en el Nether); las texturas se destruyen y recrean en cada cambio. | Portal lento |
| 6 | `Workers` | Los trabajos de la dimensión anterior siguen en cola; núcleos−2 hilos compiten con los del propio Minecraft. | Lentitud general |
| 7 | `MinimapHud` | Escena 3D completa a 2× (p. ej. 768×768) en cada frame. | Mayor costo constante de GPU |
| 8 | `sunRay` (`world.glsl`) | Hasta 64 lecturas de textura por píxel aunque el sol es fijo (`LightBaker.SUN_DIR`). | GPU |
| 9 | `cutAmount` (`terrain.fsh`) | Multiplica `ViewProj × Player` por píxel; el valor es constante en el frame. | GPU |
| 10 | `MapRenderer.render` | Con radio grande recorre `store.all()` completo cada frame (frustum + `computeIfAbsent` por chunk). Además un `GpuBuffer` y un draw por chunk y LOD. | CPU, crece con lo explorado |
| 11 | `ChunkColumns`, `ChunkVoxels`, `TileAtlas`, `HeightTexture.weather` | Búsquedas lineales por texto (`indexOf`, `contains`) por bloque y columna. | GC → tirones |
| 12 | `TerrainMesher` / `MeshBuilder` | Arrays nuevos por cara; buffers directos que crecen duplicándose. | GC y memoria nativa |
| 13 | `ColumnStore` | Guarda todo lo explorado, con voxels, para siempre. | Más lento con el tiempo |

## Qué ofrece Minecraft 26.3 (verificado en el jar)

- **API oficial renderpearl** (`com.mojang.renderpearl.api`): `drawIndexedIndirect`,
  `drawIndirect`, `multiDrawIndexed`, timestamps (`GpuQueryPool`, `writeTimestamp`), texel
  buffers, push constants, `DeviceFeatures` (multi-draw, indirect, persistent mapping).
- `ShaderType` solo tiene `VERTEX` y `FRAGMENT`; `UniformType` no tiene buffers de
  almacenamiento: **sin compute shaders ni SSBO** en la API.
- `FrontendRenderPass.multiDrawIndexed` lanza `UnsupportedOperationException` si el equipo no
  soporta `multiDrawDirectInterleaved`/`nonZeroFirstInstance`, y existe
  `HintsAndWorkarounds.multiDrawIndirectHasKnownIssues()`: **siempre hace falta respaldo** al
  draw por chunk.
- El backend interno `VulkanDevice` expone `vkDevice()`, `computeQueue()` y `vma()`, y
  `VulkanGpuBuffer` expone `vkBuffer()`: un mod puede lanzar compute propio con LWJGL en Vulkan
  (como hace Radiante), pero son clases internas, rompe la regla 9.1 (sin API gráfica directa) y
  Vulkan sigue siendo experimental, no la base de usuarios.

## Decisiones tomadas tras contrastar con fuentes externas

- **Vulkan directo y Rust: solo si las mediciones lo piden.** El cuello no es la JVM. La Vector
  API sigue en incubación (exige `--add-modules`), descartada para un mod público. FFM es
  estable pero exige binarios por sistema. Mallar en GPU no está demostrado que ayude: el mallado
  ocurre fuera del hilo principal.
- **Compute propio exige pruebas en NVIDIA y AMD** (Voxy tiene problemas graves en Radeon).
- **Draw calls: prioridad baja.** Unos cientos con el mismo pipeline cuestan poco; el valor real
  de los buffers compartidos es dejar de crear y destruir cientos de `GpuBuffer`, y el del
  descarte por regiones es no recorrer todo lo explorado cada frame.
- **Sombra: cotas por columna, no umbral simple.** Un umbral por columna cambiaría los bordes de
  sombra de escalones de 1/4 de bloque (el estilo actual) a escalones de bloque. Con dos cotas por
  columna (debajo de la mínima: sombra segura; encima de la máxima: luz segura) y ray-march solo
  entre ambas, la imagen es idéntica con ~1 lectura en casi todos los píxeles. Funciona porque la
  oclusión es monótona en altura.
- **Minimapa: no sirve "textura cenital rotada".** La cámara es en perspectiva, inclinada 58° y
  gira con el jugador. Lo que sí: tope de Hz configurable, reutilizar la imagen si nada cambió y
  abaratar cada frame con la sombra por cotas. Un modo cenital opcional sí podría usar esa técnica.
- `Block.getId(state)` solo para tablas en memoria (cambia con los mods instalados); en disco se
  siguen guardando nombres.

## Orden propuesto

1. **Medir**: contadores de CPU por etapa, timestamps de GPU por pasada, tirón al cruzar portal,
   heap tras 30 min explorando. Capturas de referencia en OpenGL y Vulkan
   (`./gradlew :fabric:runClientGameTest`, `-PmaquetaVulkan`).
2. **Hilo principal y portales (#1–#6)**: snapshot mínimo y perfiles de borde de vecinos
   calculados en workers; un snapshot para las dos capas del Nether; captura con presupuesto de
   tiempo por tick; disco en un hilo de I/O; cancelación por generación de sesión; texturas
   reutilizadas; menos workers.
3. **GPU sin tocar la imagen (#7–#9)**: sombra por cotas, `cutAmount` como uniform, tope de Hz
   del minimapa.
4. **Memoria y basura (#11–#13)**: IDs enteros en lugar de texto, arrays reutilizados por hilo,
   descartar `ChunkVoxels` lejanos y recargarlos del archivo de región.
5. **Render (#10)**: descarte por regiones en CPU, buffers compartidos, multi-draw indirecto solo
   si el equipo lo soporta y no hay problemas conocidos.
6. **Opcional**: compute en Vulkan directo o Rust, según las mediciones.

Metas orientativas: hilo principal < 0,5 ms/tick de media al explorar; tirón extra del mod al
cruzar un portal < 50 ms; minimapa < 1 ms de GPU a 1080p; heap estable tras 30 min.
