# Maqueta v0.4: lo que hay que mejorar

Feedback de la v0.3 ordenado por tema. La funcionalidad gustó; falta sobre todo lo visual y que el mapa sea **fiel** a lo que hay en el mundo.

## 1. Geometría fiel (lo más importante)

| Problema | Causa | Solución |
| --- | --- | --- |
| Lo que flota se rellena hasta el suelo (un bloque a 10 de altura con aire debajo sale como una columna maciza) | El mapa guardaba una sola altura por columna | **Columnas por capas**: cada columna guarda los tramos de bloques que hay, con aire entre ellos, hasta llegar al suelo natural. Voladizos, puentes, arcos y ramas se ven como son |
| Árboles con «4 raíces»: cada rama sale como un tronco vertical hasta el suelo | El tronco se inventaba bajo cada copa | Los troncos y ramas son bloques reales del mundo (troncos horizontales incluidos). Se quita el tronco inventado |
| Antorchas, escaleras, losas, alfombras, lianas, faroles, bambú… salen como bloques enteros | Todo se dibujaba como cubo | **Formas reales**: los bloques no enteros usan su forma de colisión o contorno (hasta 4 cajas por bloque y por estado: escalera girada, losa arriba o abajo). Flores y hierba siguen como motas de color en el suelo |
| Las paredes 3D del borde de lo explorado se ven | Paredes de estratos hacia lo desconocido | **Relieve que se funde con la mesa**: sin paredes hacia lo desconocido; el terreno baja suavemente hasta el papel, como un mapa en relieve tallado. No son nubes |

## 2. Color fiel a cada bioma y bloque

- La hierba se veía verde lima, como en Minecraft alfa: el tinte del bioma se aplicaba sin el brillo de la textura. Ahora color = tinte del bioma × luminancia de la textura, como hace el juego.
- Menos saturación y menos suavizado: la paleta armoniza sin arrastrar el tono hacia una rampa fija.
- Cerezos rosas, abedules y abetos con su color, troncos con su corteza real.
- La nieve sobre un árbol pinta solo la cara de arriba, no el árbol entero.

## 3. Ambiente

- **Bruma atmosférica** tipo Google Earth: casi nada a 20 chunks y más densa a 200. Se calcula en el shader, no es niebla 3D.
- **Nubes** reales sobre el mapa, con su sombra sobre el terreno. Se desvanecen al acercar la cámara.

## 4. Transparencia y capas

- Sin transparencia en el centro por defecto. Solo se vuelve transparente lo que está **entre la cámara y el jugador**. Vale para el mapa y el minimapa.
- **Mobs por capa**: en la superficie solo los de la superficie, en una cueva solo los de la cueva. Si estás muy alto, los de tu altura se ven destacados y los del suelo atenuados.
- **Icono = cara del mob**, no el huevo de generación.

## 5. Controles y opciones

- **Tab** en el minimapa cambia el alcance (cerca, medio, lejos). Se puede reasignar en Controles.
- Panel de **ajustes dentro del mapa** (botón de engranaje): tamaño y alcance del minimapa, posición, rastro, nubes, bruma, criaturas.
- **Rastro** con longitud configurable, 500 bloques por defecto.

## 6. Rendimiento y backends

- Las criaturas se leen una vez por tick, solo en el radio útil, y se interpolan por cuadro. Antes se leían por cuadro y daban tirones con muchos mobs.
- Rayos de sombra adaptativos: paso creciente y corte en la altura máxima de la escena.
- Más detalle de textura a distancia sin bajar la resolución.
- **Vulkan**: el minimapa no se veía; se corrige y se prueba en ambos backends.
