# ARIA · Cookie

Este repositorio empezó como **ARIA**, un experimento en Go con módulos que *simulan* una red líquida
(M1), salida temprana (M2) y memoria causal/temporal (M3). Son prototipos: usan embeddings por hash y
no aprenden de datos reales.

El producto real es **Cookie**, tu segunda copia digital:

| Carpeta | Qué es | Estado |
|---|---|---|
| [`android/`](android/README.md) | **App Android** (Kotlin/Compose + Claude): memoria semántica, diario, "Quién soy", fidelidad medible, anticipación que aprende, misiones autónomas, voz, sentidos, cifrado | Producto principal |
| `internal/cookie`, `cmd/cookie` | Primer prototipo de Cookie en Go (CLI): aprende de frases, investiga con Google News/Claude, resúmenes | Prototipo; la app Android lo supera |
| `internal/liquid`, `earlyexit`, `memory`, `temporal`, `cmd/aria` | Módulos ARIA M1–M3 | Experimentales, no conectados a Cookie |
