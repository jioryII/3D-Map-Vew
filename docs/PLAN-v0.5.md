# Maqueta v0.5: plan a partir de las pruebas reales

## 0. Diagnóstico de tu partida

- **El minimapa no se veía**: no estaba roto. Tu `config/maqueta.json` tenía `"minimapEnabled": false`. **MiniHUD** usa **H** para su menú, igual que el atajo del minimapa, y cada pulsación lo apagaba. → El atajo del minimapa queda **sin tecla por defecto**, se avisa al activarlo o desactivarlo, y el panel ⚙ del mapa lo muestra.
- En el log no hay errores de Maqueta. Convive con Sodium, Iris, Bobby, JourneyMap, Litematica…

## 1. Geometría: nada se rellena

| Problema | Causa encontrada | Solución |
| --- | --- | --- |
| Estructuras complejas y grandes (torres, manglares, voladizos, bloques translúcidos) aún se rellenan hasta abajo | (a) Se tomaba como «suelo» cualquier bloque natural, aunque flotara (una torre de arenisca o piedra contaba como suelo); (b) el límite de 24 tramos o 72 bloques de profundidad convertía el resto en suelo | Suelo = material natural **con sólido continuo debajo** (al menos 4 bloques). Límites de 96 tramos y 192 bloques. Sin el relleno de emergencia |
| Lo que está muy abajo (fosos, perímetros, cuevas grandes abiertas) desaparece y solo se ve el fondo | Las paredes del terreno se cortaban a la altura de la mesa | Sin ese corte: las paredes llegan hasta el suelo real del vecino |
| Raíces de manglar, estructuras translúcidas | Muchos tramos alternados de agua y raíz → límite superado | Mismos límites nuevos; el agua entre raíces es un tramo más |

## 2. Dimensiones separadas

- Lo del Overworld aparecía en el Nether y al revés. **Causa**: los chunks de la dimensión nueva llegan antes de que la sesión cambie de dimensión y se guardaban en la anterior. → Cada captura comprueba que el chunk es de la dimensión de la sesión y, si no, la cambia antes de guardarlo.

## 3. Criaturas sin tirones

- **Causa del lag**: cada icono se dibujaba con unas 100 franjas de píxel (círculos rasterizados en la interfaz). Con muchos mobs eran miles de elementos por cuadro. → La insignia es una textura que se dibuja de una vez.
- Rastreo cada 4 ticks. Caras para los 32 más cercanos; los demás, un punto de color.

## 4. Distancia: igual de bien de cerca y de lejos

- **Sombras que parpadean al cambiar el zoom**: se mezclaba la sombra horneada con la sombra por píxel según la distancia. → La sombra por píxel se usa **siempre**. Sale del mapa de alturas en la GPU, ampliado a 2048×2048, así que no cambia con el nivel de detalle ni con el zoom.
- **Solo se ve bien a unos 25 m**: el detalle completo llega ahora hasta unos 250 m; luego baja por niveles.
- **Árboles feos a distancia**: en los niveles gruesos las copas eran columnas macizas hasta el suelo. → A distancia son losas de copa sobre el suelo, sin paredes.
- **El mapa desaparecía fuera de un círculo** al alejarse. → Se dibuja todo lo explorado a cualquier zoom, con los niveles gruesos lejos.
- **Bruma demasiado espesa** → más suave, con tope.

## 5. Fondo: océano y bancos de nubes por bioma

- Lo no explorado es **mar** al estilo pixel, como el agua del mapa.
- El borde de lo explorado queda bajo **bancos de nubes espesas en 3D** que lo tapan. Dependen del bioma de esa orilla y la transición entre biomas es suave:
  - llanura y bosque: nubes blancas;
  - montaña y océano: nubes de tormenta con relámpagos;
  - nieve: nubes con copos;
  - desierto y badlands: tormenta de arena.
- Mar adentro: nubes sueltas, alguna tormentosa, solo para dar vida.

## 6. Color y texturas

- Más color, en tono de animación: saturación un poco más alta.
- El cerezo salía blanco; ahora se ve rosa. Los troncos usan el color de su **corteza** en los lados, no el de los anillos de arriba (cerezo morado oscuro; solo el tronco pelado es rosa).
- **Sin cuadrícula visible en materiales naturales**: pasto, piedra, arena y tierra a la misma altura se ven continuos (sin bisel y con orientación aleatoria por bloque). Los bloques que sí marcan su borde (oro, diamante, ladrillos…) lo conservan.
- **Faros**: el haz se dibuja solo si el faro está activo, con el color de su cristal.

## 7. Al instante

- Las mallas ya horneadas se guardan en memoria y se reutilizan entre aperturas del mapa. Al volver a una zona no se recalcula; solo se rehace lo que cambia (bloques puestos o quitados).
