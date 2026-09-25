# M4 · Cookie 🍪

Asistente personal de ARIA que **aprende tu ritmo de vida**, **investiga por su cuenta** y **te mantiene al día**,
inspirado en la "cookie" de Black Mirror, pero tuyo: todo vive en tu máquina (`~/.aria/cookie.json`, permisos 0600)
y puedes ver o borrar lo que sabe cuando quieras.

## Cómo funciona

| Pieza | Archivo | Qué hace |
|---|---|---|
| Aprendizaje | `learn.go` | Extrae de frases libres tu nombre, trabajo, ciudad, gustos, rechazos, actividades y palabras clave. |
| Perfil | `profile.go` | Intereses con peso que **decae** (vida media 30 días), rutinas por hora/día, transiciones entre actividades (predice qué harás ahora), histograma de cuándo estás activo. |
| Investigación | `research.go`, `claude.go` | Fuentes: Google News RSS (sin clave) y Claude con búsqueda web (si hay `ANTHROPIC_API_KEY`). Puntúa por relevancia × peso del interés × frescura; descarta lo que rechazas, lo repetido y duplicados. |
| Orquestador | `cookie.go` | `Learn`, `Research`, `Briefing`, `Feedback` y `Run` (modo autónomo). |
| Memoria | `store.go` | JSON local, escritura atómica. |

**Modo autónomo** (`cookie vivir`): cada 3 h investiga sin que se lo pidas y, cuando llega una de tus
horas más activas (aprendidas de cuándo hablas con él), te entrega el resumen. Tus reacciones
(`util` / `nomeinteresa`) refuerzan o debilitan cada tema.

## Uso

```bash
go build -o cookie ./cmd/cookie

./cookie demo                         # semana simulada, sin conexión
./cookie aprender "Me llamo Sofía, trabajo como diseñadora gráfica y vivo en Monterrey"
./cookie aprender "Me encanta la fotografía, pero no me gusta el fútbol"
./cookie aprender "Voy al gimnasio"   # cuéntale tu día: aprende tus rutinas
./cookie perfil                       # qué sabe de ti
./cookie investigar                   # investigar ya
./cookie resumen                      # tu resumen personal
./cookie util <id>                    # "esto me sirvió"
./cookie vivir                        # déjalo trabajando solo
./cookie olvidar --todo               # borrar todo
```

Variables: `ARIA_HOME` (carpeta de datos), `ANTHROPIC_API_KEY` (activa la investigación con Claude),
`ARIA_CLAUDE_MODEL` (por defecto `claude-opus-5`). Con Claude se activa el *fallback* del servidor:
si el modelo rechaza una búsqueda, el propio API la reintenta con otro modelo.
