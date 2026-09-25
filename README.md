# ARIA · Cookie

**Cookie** es tu segunda copia digital (app Android). **ARIA** es su cerebro local: tres módulos que
trabajan en tu teléfono sin IA y deciden cuándo hace falta llamar a Claude.

| Módulo | Qué hace de verdad | Dónde |
|---|---|---|
| **M1 · Enrutador** (`internal/liquid`) | Analiza cada mensaje (entropía, desviación de Zipf, profundidad sintáctica + intención) y decide: responder en el teléfono, o ir a Claude con esfuerzo bajo/medio/alto y con o sin búsqueda web. Menos coste y menos espera. | Chat, voz y notificaciones |
| **M2 · Cascada con salida temprana** (`internal/earlyexit`) | Responde al instante y sin conexión lo que ya sabe de ti (agenda, rutina, música, lugar, sueño, clima, pasos) si su confianza supera un umbral. Consenso entre fuentes. El umbral **aprende de tus correcciones** (✅ lo baja un poco, ✏️ o «Pregúntale a Claude» lo sube). | Chat |
| **M3 · Patrones y asociaciones** (`internal/temporal`, `internal/memory`) | Mina tu línea de tiempo: «cuando A, en las siguientes W horas suele B», comparando con la misma hora en días sin A (descuenta tu horario) y con cota de Wilson. Una red de conceptos con activación propagada encuentra recuerdos por asociación. | «Tú», avisos, memoria de la copia |

Todo existe en **Go** (módulos originales + CLI) y en **Kotlin** (dentro de la app), y ambos pasan los
**mismos casos de prueba** (`testdata/aria/`): mismas rutas, mismos patrones y el mismo formato de copia cifrada.

## Carpetas

| Carpeta | Qué es |
|---|---|
| [`android/`](android/README.md) | App Cookie (Kotlin/Compose + Claude + ARIA) |
| `cmd/aria` | CLI de ARIA en tu ordenador: analiza tu copia de seguridad cifrada |
| `internal/liquid`, `earlyexit`, `temporal`, `memory` | Módulos ARIA M1–M3 |
| `internal/copia` | Lectura de la copia cifrada de Cookie (PBKDF2 + AES-GCM) |
| `internal/cookie`, `cmd/cookie` | Primer prototipo de Cookie en Go (CLI) |
| `testdata/aria` | Casos compartidos Go ↔ Kotlin |

## ARIA en tu ordenador

```bash
go run ./cmd/aria demo                          # 30 días de ejemplo
go run ./cmd/aria ruta "¿cuándo juega el Monterrey hoy?"
go run ./cmd/aria analizar cookie-2026-09-25.cookie   # tu copia (Ajustes → Copia de seguridad → Exportar)
go test ./...
```
