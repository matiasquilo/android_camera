# Dusk

Cámara para Android con estética de película: filtros LUT 3D en vivo, fecha impresa estilo cámara vieja
(dígitos de 7 segmentos dibujados por código) y captura rápida con Zero-Shutter-Lag. Hecha para uso personal
(Moto G51 5G, Android 12).

## Qué hace
- Vista previa con filtro en tiempo real (STD, FUJI 1, FUJI 2, B&W).
- Foto de 8 MP (la mayor 4:3 que el celular le ofrece a CameraX), guardada en `Pictures/VintageCamera`.
- Fecha impresa opcional (botón de mini calendario), flash, zoom con pellizco, cuadrícula y exposición manual (EV).
- Tocar = enfocar. Mantener apretado = bloquear enfoque y exposición.
- Botones de volumen = disparador (`VOLUME_SHUTTER` en `MainActivity.kt`).
- Miniatura de la última foto (toca para abrirla en la galería).

## Estructura
- `MainActivity.kt`: toda la app (presets, LUT, vista previa, captura, guardado y componentes de interfaz).
- `res/`: ícono adaptativo, tema oscuro de arranque.
- `assets/luts/`: LUTs Hald de nivel 8 (512x512). Para agregar un filtro: copiar el PNG a esa carpeta y sumar
  una entrada a `VintagePreset`.

## Compilar
Android Studio, Gradle (versiones en `gradle/libs.versions.toml`). minSdk 29.
