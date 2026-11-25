# Pro OBD — Instrucciones del proyecto

App Android propia (APK) que se conecta por Bluetooth a un adaptador ELM327 enchufado en la toma OBD del **Nissan Qashqai J11 gasolina de 2018** (1.2 o 1.3 DIG-T). Funciona en el **Xiaomi Redmi 13 Pro 5G** y en la pantalla **Junsun V9** del coche. Dueño: Francisco Serrano.

**Responder siempre en español.** Francisco no programa: explicar los pasos de forma muy detallada y sencilla.

## Dónde está el código
- Repositorio GitHub: **franciscoserranoadt/pro-obd** (rama `main`). La copia del proyecto de Claude es solo de consulta.
- Es una app separada de Pro Maps y Pro Radio. No mezclar código.

## Cómo está hecha
- Android nativo en **Java** (minSdk 26, targetSdk 34), sin librerías externas. Un **WebView** carga `assets/index.html`, `app.css`, `app.js` y `dtc.js`.
- `ObdLink.java`: conexión Bluetooth clásica (SPP) con el adaptador. Prueba 3 formas de conectar (normal, insegura y el «truco» del canal 1) para que vayan también los clónicos baratos. Se reconecta sola.
  - Arranque: `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH0`, `ATAT1`, `ATSP0` y `0100`. Si no contesta, fuerza `ATSP6` (CAN 11 bits a 500 kbit/s, el del Qashqai J11).
  - Lee: revoluciones (010C), velocidad (010D), caudal de aire MAF (0110), temperatura del motor (0105), carga (0104), batería (`ATRV`) y depósito (012F, si el coche lo da).
  - Consumo: litros/hora = MAF × 3600 / (14,7 × 745) × corrección. Si no hay MAF, se calcula con la presión de admisión (010B), la temperatura del aire (010F), las revoluciones y la cilindrada (1,197 L o 1,332 L, según el motor elegido en Ajustes). Al soltar el acelerador en marcha cuenta 0 (corte de inyección).
  - Viaje: kilómetros y litros; se pone a cero solo tras 30 minutos sin datos, o con el botón.
  - Averías: 03 (guardadas), 07 (pendientes), 0A (permanentes), 0101 (testigo encendido). Borrar: 04.
- `ObdParse.java`: lee las respuestas de texto del adaptador. No usa Android, así que se puede probar con `javac` normal.
- `dtc.js`: descripciones en español de los códigos genéricos más habituales.
- Modo demostración en Ajustes, para ver la app sin coche.

## Publicar una versión nueva
1. Cambiar el código.
2. Escribir las novedades en `CAMBIOS.txt` (salen en el aviso de actualización).
3. `git commit` y `git push` a `main`.
4. GitHub Actions (`.github/workflows/build.yml`) compila, firma, numera la versión sola (`1.0.<número de compilación>`) y publica `ProOBD.apk` y `version.txt` en la release **«ultima»**.
5. Al abrir la app, `Updater.java` lee `version.txt` y ofrece actualizar.

## Firma (NO CAMBIAR NUNCA)
- Llave `pro-obd.jks` en la raíz del repositorio, alias `proobd`, contraseña `proobd2026` (puesta en `app/build.gradle`, como en Pro Radio).
- Si se cambia o se pierde, las actualizaciones dejarán de instalarse encima.

## Probar antes de publicar
Abrir `assets/index.html` en un navegador (Playwright) con el modo demostración, y hacer capturas: móvil en vertical (393×873), móvil en horizontal (873×393) y Junsun (1280×720).

## Decisiones tomadas
- Adaptador: Francisco usa uno **ELM327 clónico transparente** que ya le funciona. Si diera problemas, el recomendado es el **Vgate vLinker MC+** (Bluetooth clásico para Android y apagado automático).
- No se usan adaptadores WiFi, porque en la Junsun cortarían el internet de Pro Maps y Pro Radio.
- Solo se leen averías del motor (OBD genérico). ABS, airbag y otras centralitas de Nissan quedan fuera.
