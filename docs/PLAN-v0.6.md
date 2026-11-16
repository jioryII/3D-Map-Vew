# Maqueta v0.6: feedback ordenado y qué se hizo

## 1. Geometría: "se rellenan filas enteras"

| Problema | Causa | Solución |
|---|---|---|
| Estructuras complejas rellenas hasta el suelo | El detalle se generaba por columnas (tramos verticales) | `ChunkVoxels`: ocupación 3D por bloque + "cáscara" (bloques que tocan un hueco), y caras emitidas solo hacia vecinos no opacos, igual que el culling de Minecraft |
| Formas no cúbicas (losas, escaleras) | — | Se emiten con sus cajas reales (`BlockShape`) |
| Bordes en rampa | Relieve de borde | Paredes rectas hacia lo inexplorado, cubiertas por la niebla del borde; relieve desactivado por defecto |
| Hojas y césped grises en detalle | En 26.3 `Biome.get*Color()` devuelve ARGB, y el alfa hacía el número negativo ("sin tinte") | Se enmascara a RGB al capturar y al colorear |

## 2. Atmósfera

- Banco de nubes/niebla solo en la franja explorado↔inexplorado (antes cubría todo el mar con una capa fina).
- Capa de nubes mucho más escasa, y su sombra coincide con lo visible.
- Bruma más ligera.

## 3. Compatibilidad con otros mapas

- JourneyMap: se leen sus regiones `day/<rx>,<rz>.png`, 512×512 con un píxel por bloque, en un solo jugador y en servidores, para el Overworld, el Nether, el End y otras dimensiones.
  - Solo se importan chunks completos, como bocetos planos: agua donde el color es claramente azul y tierra un bloque más arriba.
  - Nunca se guardan; la captura real siempre los reemplaza.
- Xaero's World Map usa un formato binario propio: pendiente.

## 4. Rendimiento y comodidad

- Precocinado de mallas cada segundo alrededor del jugador: LOD 0 hasta 16 chunks, luego LOD 1, 2 y 3. Hay un límite por pasada y un presupuesto de GPU de 640 MB.
- Criaturas cercanas interpoladas por frame; las lejanas (>48 bloques) usan la posición de cada 4 ticks.
- Luz de antorchas o lava suavizada por vértice.
- Pausa al abrir el mapa, centrado en el jugador y círculo de transparencia más grande.

## 5. Verificado

- 24 tests de `core` y capturas reales en OpenGL y Vulkan (`docs/capturas`).

## Pendiente o ideas

- Importar Xaero's World Map.
- Rutas usando la rejilla de vóxeles en lugar de los tramos por columna.
- Hojas de jungla de cerca algo saturadas: revisar con más biomas.

## v0.6.1: segunda ronda de feedback

| Pedido | Qué se hizo |
|---|---|
| Lo explorado se perdía al salir y volver a entrar | Al reabrir el mundo, la carga de lo guardado podía empezar antes de que la sesión quedara activa y descartaba la región. Ahora solo se cancela si la sesión está cerrada |
| Relleno de bloques flotantes en el Nether (y de lejos) | Los niveles de detalle lejanos se construyen desde la rejilla de vóxeles (celdas de 2/4/8), con caras solo entre lleno y vacío. Lo flotante sigue flotando a cualquier distancia; hay un test para esto |
| Montaña "transparente" junto a un valle | La banda vóxel baja hasta el suelo vecino, el borde usa los bloques reales del vecino, y una pared de roca cierra los huecos que queden |
| Transparencia | Círculo con tramado ordenado fijado a la rejilla de 4×4 del modelo, sin parpadeo. Nunca se corta el suelo del jugador ni lo de debajo; donde se quita un bloque se ve la cara superior del de abajo. En el mapa se apaga al alejar la cámara; en el minimapa siempre está activo. El efecto con contornos de bloque se descartó |
| Cuevas | La roca maciza en el corte es una sombra plana casi negra: solo se ven los huecos |
| Bruma | Depende sobre todo de la distancia horizontal; desde arriba la vista queda limpia |
| Fondo sin explorar | Mar con cúmulos variados: blancos y grises de tormenta, coronas iluminadas, panzas frías y sombras sobre el agua. Nunca cubren la tierra explorada |
| Sombras y noche | Sombras más claras y noches legibles |
| Nether | Dos vistas: el interior, sin el techo, y lo que hay sobre el techo. Se elige sola al abrir el mapa y se cambia con ▲/▼ o V |
| Cerezos | Rosa fijo: en 26.x sus hojas venían marcadas como teñidas y salían grises |

## v0.6.2

| Pedido | Qué se hizo |
|---|---|
| Bloques flotantes en el Nether | Causa: al quitar el techo de bedrock, el escaneo paraba en el primer bloque no sólido. Un hueco pequeño dentro del techo o unas enredaderas dejaban losas del techo flotando como placas y torres. Ahora el techo termina solo en un hueco real de 4 bloques de aire seguidos; en columnas sin hueco se usa la regla anterior. Los chunks vecinos aplican el mismo corte, así que los bordes coinciden |
| Fondo | Color plano sin textura: mar en el Overworld (con nubes), gris suave con pocas nubes en el Nether, gris sin nada en el End |
| Nubes exteriores | Estilo del juego: losas de 12×12 bloques y 6 de alto, tapa blanca y lados sombreados como bloques, algo translúcidas. Se dibujan dentro del pase del fondo, sin raymarching |
| Bancos del borde | Máscara suavizada en CPU (5×5 por chunk): esquinas redondeadas, una sola lectura por paso, 16 pasos y la altura calculada una vez por píxel. La nieve y la arena son puntos pegados a la nube en el mundo. En el Nether: rojo, azulado en soul sand, gris en basalto, verde azulado en warped. En el End: morado |
| Transparencia | Solo en el minimapa, con un borde suave y difuminado |
