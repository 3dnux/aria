# Cookie para Android 🍪📱

Tu "segunda copia" en el teléfono. Aprende de ti **sin que se lo pidas** (lo que le cuentas, tu Spotify,
tu agenda, tus lugares, tu sueño y tus apps), **piensa como tú** (recuerdos + tu forma de hablar +
pruebas de parecido), **se adelanta** a lo que necesitas y **actúa por ti** (música, alarmas,
recordatorios, eventos, mensajes y casa inteligente) siempre con tu confirmación.
App nativa en Kotlin + Jetpack Compose; es la evolución del módulo `internal/cookie` (Go) de ARIA.

## Qué hace

| Parte | Cómo |
|---|---|
| **Entenderte** | Cada cosa que le cuentas pasa por Claude, que saca datos y **recuerdos** (hechos, personas, gustos, metas, ánimo, rutinas, decisiones). La memoria se deduplica, refuerza y olvida lo menos importante. La copia busca en ella con una herramienta `buscar_recuerdos`. |
| **Sentidos** | 📅 Calendario (rutinas + eventos próximos) · 📍 Ubicación (descubre "casa", "trabajo" y tus sitios; guarda solo el centro de cada lugar, no tu recorrido) · 😴 Health Connect (sueño y pasos) · 📱 Uso de apps (tu ritmo real) · 🎧 Spotify (artistas, géneros, qué escuchas a cada hora y en cada lugar). |
| **Pensar como tú** | Botones "Así diría yo" / "Yo diría…" en cada respuesta → ejemplos de tu estilo. Modo **¿Qué haría yo?**: la copia responde a escondidas, respondes tú, Claude compara y saca una lección. La app muestra el % de parecido. |
| **Actuar** | La copia usa herramientas: poner música (Spotify API con Premium; si no, abre la app), alarma, recordatorio, evento de calendario, borrador de mensaje (nunca envía por ti), abrir enlace, Home Assistant. Todo queda **pendiente de confirmación**. |
| **Anticiparse** | Cada 30 min: evento en <75 min (con ruta), dormiste poco, llegaste a un lugar (con la música que sueles poner ahí), rutina de esta hora sin hacer, poco movimiento. Notificaciones con botón "Sí, hazlo". |
| **Voz y presencia** | Dictado por voz, respuestas leídas en voz alta, widget en la pantalla de inicio con botón de micrófono. |
| **Seguridad** | Perfil y claves cifrados con AES-GCM y clave del Android Keystore; bloqueo con huella/PIN; "olvidar todo". |

## Configurar

1. **Claude**: Ajustes → API key (necesaria para recuerdos, copia, acciones y prueba de parecido).
2. **Spotify**: <https://developer.spotify.com/dashboard> → *Create app* → Redirect URI `ariacookie://spotify-callback` →
   marca *Web API* → en *User Management* añade tu correo → pega el Client ID en Ajustes.
   Permisos: lectura + `user-modify-playback-state` (para poner música). Si conectaste una versión anterior, pulsa "Volver a conectar".
3. **Sentidos**: Ajustes → Mis sentidos. Cada uno pide su permiso (ubicación en segundo plano y uso de apps se activan en los ajustes del sistema; sueño/pasos requieren Health Connect).
4. **Home Assistant** (opcional): URL y token de larga duración.
5. Añade el **widget** de Cookie a tu pantalla de inicio.

## Compilar

```bash
cd android
./gradlew :app:assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # tests del núcleo
```

Requiere JDK 17+ y Android SDK con la plataforma 36 (Health Connect lo exige). En GitHub, el workflow
**Android APK** compila y publica el APK como artefacto `cookie-apk` (variable opcional `SPOTIFY_CLIENT_ID`).

## Privacidad

Todo vive cifrado en el teléfono. Solo sale: búsquedas a Google News, llamadas a Spotify / Home Assistant
y, si pones tu clave, lo necesario a Claude (`claude-opus-5`, con *fallback* de servidor si rechaza una
petición) para entenderte, investigar y responder como tú. Tu copia no envía mensajes ni actúa sin tu "sí".
