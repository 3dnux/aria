# Cookie para Android 🍪📱

Tu "segunda copia" en el teléfono: aprende de ti (lo que le cuentas, tu **Spotify** y tu rutina),
investiga por su cuenta y te avisa en tus horas activas. App nativa en Kotlin + Jetpack Compose;
es el port del módulo `internal/cookie` (Go) de ARIA.

## Pantallas

- **Hoy** — lo que investigó para ti, por qué te lo muestra, 👍/👎 para enseñarle, qué cree que harás ahora y qué suena en tu Spotify.
- **Tu copia** — chatea con tu gemelo digital (Claude con tu perfil, tu música, tus rutinas y tus frases). Todo lo que le dices también lo aprende.
- **Tú** — cuéntale cosas ("trabajo como…", "me encanta…", "voy al gimnasio"), mira todo lo que sabe de ti y olvida temas.
- **Ajustes** — conectar Spotify, clave de Claude, avisos y "olvidar todo".

## Qué aprende de Spotify (solo lectura)

| Dato | Para qué |
|---|---|
| Top artistas y géneros (último mes) | Se vuelven intereses → te trae conciertos, lanzamientos y noticias de ellos |
| Canciones del momento / lo que suena ahora | Tu copia sabe qué escuchas |
| Historial reciente (hora de cada canción) | Aprende a qué horas estás despierto y qué escuchas a cada hora |

Permisos pedidos: `user-read-private user-top-read user-read-recently-played user-read-currently-playing user-read-playback-state user-library-read user-follow-read`.

## Conectar Spotify

1. Entra en <https://developer.spotify.com/dashboard> → **Create app**.
2. **Redirect URI**: `ariacookie://spotify-callback` · marca **Web API**.
3. En **User Management** añade el correo de tu cuenta de Spotify (las apps en modo desarrollo solo funcionan para usuarios añadidos).
4. Copia el **Client ID** en Ajustes → Spotify → *Conectar con Spotify* (o compila con `-PspotifyClientId=...`).

Se usa OAuth *Authorization Code + PKCE*: no hay secreto de cliente en la app.

## Compilar

```bash
cd android
./gradlew :app:assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # tests del núcleo
```

Requiere JDK 17+ y el Android SDK (plataforma 35). En GitHub, el workflow **Android APK** compila el APK en
cada push y lo deja como artefacto descargable (`cookie-apk`); define la variable de repositorio
`SPOTIFY_CLIENT_ID` si quieres que venga con tu Client ID.

## Segundo plano y privacidad

- WorkManager despierta cada hora (con red): sincroniza Spotify, investiga cada 3 h y, si es una de tus
  3 horas más activas y hay novedades, te manda una notificación.
- Todo el perfil vive en el almacenamiento privado de la app (`cookie.json`). Solo salen: búsquedas a
  Google News, lecturas a Spotify y —si pones tu clave— tu perfil resumido a Claude (`claude-opus-5`,
  con *fallback* de servidor si el modelo rechaza una petición).
