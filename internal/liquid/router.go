package liquid

import (
	"fmt"
	"regexp"
	"strings"
)

// Módulo 1 de ARIA: cómputo adaptativo real.
//
// En vez de "capas líquidas" simuladas, M1 decide cuánto cómputo merece cada
// mensaje que le dices a tu copia: si se puede responder en el teléfono sin
// llamar a ninguna IA (instantáneo, gratis, sin conexión), o si va a Claude,
// con qué nivel de esfuerzo y si necesita buscar en la web. Así se gasta
// mucho solo cuando hace falta.

// Tier dónde se resuelve un mensaje.
type Tier string

const (
	TierLocal  Tier = "local"  // se intenta responder en el dispositivo (M2 decide si basta)
	TierClaude Tier = "claude" // necesita razonar o generar lenguaje
)

// Effort nivel de esfuerzo para Claude (se corresponde con output_config.effort).
type Effort string

const (
	EffortLow    Effort = "low"
	EffortMedium Effort = "medium"
	EffortHigh   Effort = "high"
)

// Route decisión del enrutador.
type Route struct {
	Tier        Tier
	Intent      string // intención local detectada (agenda, rutina, música...)
	Effort      Effort
	WebSearches int     // 0 = sin búsqueda web (más rápido)
	Complexity  float64 // 0..1 del analizador de texto
	Reason      string
}

// Intenciones que se pueden responder con lo que Cookie ya sabe de ti.
var localIntents = []struct {
	name string
	re   *regexp.Regexp
}{
	{"agenda", regexp.MustCompile(`(?i)(qu[eé] tengo (hoy|mañana|ahora|despu[eé]s)|pr[oó]xim[oa] (evento|reuni[oó]n|cita)|mi agenda|a qu[eé] hora es mi)`)},
	{"rutina", regexp.MustCompile(`(?i)(qu[eé] (suelo|acostumbro|toca) (hacer)?|qu[eé] hago normalmente|qu[eé] me toca)`)},
	{"musica", regexp.MustCompile(`(?i)(qu[eé] (suelo escuchar|escucho|estoy escuchando)|mi m[uú]sica|qu[eé] (canci[oó]n|artista) (escucho|me gusta m[aá]s))`)},
	{"lugar", regexp.MustCompile(`(?i)(d[oó]nde estoy|en qu[eé] lugar estoy)`)},
	{"sueno", regexp.MustCompile(`(?i)(c[oó]mo dorm[ií]|cu[aá]nto dorm[ií]|mi sue[nñ]o)`)},
	{"clima", regexp.MustCompile(`(?i)^(\W*)(qu[eé] (tiempo|clima) hace|va a llover|hace (fr[ií]o|calor))\W*$`)},
	{"pasos", regexp.MustCompile(`(?i)(cu[aá]ntos pasos|me he movido)`)},
}

// Límites de palabra que entienden tildes (\b en Go solo conoce ASCII).
const (
	wb = `(?:^|[^\p{L}\p{N}])`
	we = `(?:[^\p{L}\p{N}]|$)`
)

var (
	// Señales de que hace falta información actual de internet.
	reWeb = regexp.MustCompile(`(?i)` + wb + `(noticias?|[uú]ltim[oa]s?|hoy en|actual(es|mente)?|precio|cu[aá]nto cuesta|resultado|marcador|estreno|concierto|gira|lanz(a|ó|amiento)|qui[eé]n gan[oó]|20[2-3]\d|busca(r|me)?|investiga|partido|cu[aá]ndo (es|juega|sale|abre|empieza|estrena))` + we)
	// Señales de razonamiento profundo.
	reDeep = regexp.MustCompile(`(?i)` + wb + `(por qu[eé]|planifica|plan de|estrategia|ay[uú]dame a (decidir|pensar|organizar)|compara|pros y contras|analiza|deber[ií]a|qu[eé] har[ií]as|consejo|reflexiona)` + we)
	// Órdenes simples para herramientas.
	reAction = regexp.MustCompile(`(?i)^\W*(pon|ponme|recu[eé]rdame|av[ií]same|crea|agenda|enciende|apaga|abre|sube|baja|pausa|alarma)` + we)
)

// Router enruta mensajes usando el analizador de texto de ARIA.
type Router struct {
	analyzer *AdvancedAnalyzer
}

// NewRouter crea el enrutador.
func NewRouter() *Router { return &Router{analyzer: NewAnalyzer()} }

// Route decide cómo resolver un mensaje.
func (r *Router) Route(message string) Route {
	a := r.analyzer.Analyze(strings.TrimSpace(message))
	// Las reglas se aplican en minúsculas: Go y Java tratan distinto las mayúsculas con tilde.
	msg := strings.ToLower(strings.TrimSpace(message))
	complexity := clamp01(a.Score)
	words := a.Features.WordCount

	// 1. Preguntas cortas sobre ti mismo: se intentan en local.
	if words <= 12 && !reDeep.MatchString(msg) {
		for _, in := range localIntents {
			if in.re.MatchString(msg) {
				return Route{Tier: TierLocal, Intent: in.name, Effort: EffortLow, Complexity: complexity,
					Reason: fmt.Sprintf("pregunta sobre ti (%s): la respondo con lo que ya sé", in.name)}
			}
		}
	}

	route := Route{Tier: TierClaude, Complexity: complexity}
	switch {
	case reAction.MatchString(msg) && words <= 20:
		route.Effort = EffortLow
		route.Reason = "orden directa: poco razonamiento"
	case reDeep.MatchString(msg) || complexity >= 0.6 || words > 60:
		route.Effort = EffortHigh
		route.Reason = "pide razonar o decidir"
	case complexity >= 0.3 || words > 20:
		route.Effort = EffortMedium
		route.Reason = "conversación normal"
	default:
		route.Effort = EffortLow
		route.Reason = "mensaje sencillo"
	}
	if reWeb.MatchString(msg) {
		route.WebSearches = 3
		if route.Effort == EffortHigh {
			route.WebSearches = 5
		}
		route.Reason += " + necesita datos actuales de la web"
	}
	return route
}

func clamp01(x float64) float64 {
	if x < 0 {
		return 0
	}
	if x > 1 {
		return 1
	}
	return x
}
