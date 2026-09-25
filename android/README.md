# Cookie para Android 🍪📱

Tu **segunda copia**: un gemelo digital que vive cifrado en tu teléfono, aprende de ti sin que se lo
pidas, piensa como tú (y mide cuánto), se adelanta a lo que necesitas, trabaja tus objetivos por su
cuenta y actúa por ti con el nivel de autonomía que elijas. Kotlin + Jetpack Compose + Claude.

## Qué la hace distinta

| Capa | Qué hace |
|---|---|
| 🧠 **Memoria semántica** | Cada cosa que dices se convierte (por lotes, para ahorrar) en recuerdos con etiquetas de significado. La búsqueda es híbrida: raíces en español, sinónimos ("mamá" = "madre"), etiquetas de Claude y parecido de letras. Deduplica, refuerza y olvida como una memoria humana. |
| 📔 **Diario automático** | Cada noche escribe en primera persona tu día a partir de su **línea de tiempo** (lugares, música, eventos, movimiento, apps, mensajes, sueño, lo que dijiste). |
| 🪞 **"Quién soy"** | Cada semana relee todo y reescribe tu retrato: valores, cómo decides, cómo hablas, qué te preocupa, qué buscas y **cómo has cambiado** (guarda el historial). La copia habla desde ese retrato. |
| 🧬 **Fidelidad medible** | "¿Qué haría yo?": la copia responde a escondidas y Claude compara. El **examen** reevalúa a la copia sobre tus respuestas guardadas, sin chuleta (quita de su memoria la respuesta de cada pregunta), y muestra la evolución. |
| 🔮 **Anticipación que aprende** | Avisos por agenda (con **tráfico real** si pones clave de Google Routes), **clima** (Open-Meteo), sueño, **estrés por pulso**, llegada a lugares, rutina, pasos y misiones. Cada tipo de aviso aprende de tus 👍/👎 con **muestreo de Thompson** por franja horaria: lo inútil se apaga solo y lo útil sale más. Un **predictor bayesiano** aprende qué haces según hora, día y lugar. |
| 🎯 **Misiones** | Objetivos de días o semanas ("prepara mi maratón"). Un agente con búsqueda web los trabaja en segundo plano: revisa tu contexto, ajusta el plan y propone acciones. La copia puede crearlas sola. |
| 🤖 **Autonomía por niveles** | Por tipo de acción: preguntar o automático (música, recordatorios, enlaces, casa, alarmas). Los mensajes nunca se envían solos. |
| 🗣️ **Voz natural** | Streaming de Claude leído **frase a frase mientras se escribe**, mejor voz en español del teléfono, conversación continua sin tocar la pantalla, modo manos libres **"Oye Cookie"** (experimental), responder desde notificaciones y **desde el reloj**. |
| 👀 **Sentidos** | Spotify, calendario, lugares, Health Connect (sueño, pasos, **pulso**), **movimiento** (caminar/correr/bici/coche), uso de apps, **con quién hablas** (de notificaciones, sin guardar el texto) y **momentos** (días con muchas fotos, solo fechas). |
| 🔒 **Seguridad** | Estado y claves cifrados con AES-GCM (Android Keystore), bloqueo biométrico, **copia de seguridad cifrada con contraseña** (PBKDF2 + AES-256-GCM) para no perder tu copia, diagnóstico de errores sin datos personales. |
| ⚡ **Coste y rapidez** | Caché de prompts (la parte estable del sistema), recuerdos por lotes, streaming, entradas de herramientas validadas en el cliente. |

## Configurar

La app tiene una bienvenida guiada. Resumen:

1. **Claude**: API key (cerebro de todo lo avanzado). Modelo por defecto `claude-opus-5`, con *fallback* de servidor si rechaza una petición.
2. **Spotify**: app en <https://developer.spotify.com/dashboard>, Redirect URI `ariacookie://spotify-callback`, tu correo en *User Management*, Client ID en la app.
3. **Sentidos**: Ajustes → Mis sentidos (cada uno con su permiso; notificaciones y uso de apps se activan en los ajustes del sistema; salud necesita Health Connect).
4. Opcional: **Google Routes API key** (tráfico), **Home Assistant** (URL + token), **widget**, **manos libres**.

## Compilar y probar

```bash
cd android
./gradlew :app:testDebugUnitTest          # núcleo (JVM)
./gradlew :app:connectedDebugAndroidTest  # en un emulador/teléfono conectado
./gradlew :app:assembleDebug              # → app/build/outputs/apk/debug/app-debug.apk
```

Requiere JDK 17+ y Android SDK con plataforma 36. En GitHub, el workflow **Android APK** ejecuta los tests,
publica el APK (`cookie-apk`) y lanza las pruebas en un emulador.

## Límites honestos

- Pensado para instalar directamente (APK). Google Play pone muchas restricciones a la ubicación en segundo plano, el uso de apps, las notificaciones y la salud.
- El modo manos libres usa el reconocedor del sistema: gasta batería y en algunos teléfonos suena al reiniciarse.
- Lectura de salud en segundo plano limitada por Android: se actualiza al abrir la app.
- El tráfico real necesita tu clave de Google (de pago por uso).
