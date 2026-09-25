package cookie

import (
	"regexp"
	"strings"
	"unicode"
)

// Signal algo que Cookie aprendió de una frase.
type Signal struct {
	Kind  string // nombre, trabajo, lugar, gusto, rechazo, actividad, palabra
	Value string
}

var (
	reName       = regexp.MustCompile(`(?i)\b(?:me llamo|mi nombre es)\s+([\p{L}]+)`)
	reOccupation = regexp.MustCompile(`(?i)\b(?:trabajo como|trabajo de|me dedico a|mi trabajo es|soy de profesión|trabajo en)\s+([^.,;!?]+)`)
	reLocation   = regexp.MustCompile(`(?i)\b(?:vivo en|soy de)\s+([\p{Lu}][\p{L}]+(?:\s+[\p{Lu}][\p{L}]+)*)`)
	reDislike    = regexp.MustCompile(`(?i)\b(?:no me gustan?|no me interesan?|odio|detesto|no soporto|me aburren?)\s+([^.,;!?]+)`)
	reLike       = regexp.MustCompile(`(?i)\b(?:me gustan?|me encantan?|me interesan?|me apasionan?|disfruto(?: de)?|soy fan de|sigo mucho|estoy aprendiendo|quiero aprender)\s+([^.,;!?]+)`)
	reActivity   = regexp.MustCompile(`(?i)\b(?:voy a|voy al|fui a|fui al|estoy en|salgo a|salí a|acabo de|vengo de|vengo del|terminé de|empiezo a|me voy a)\s+([\p{L}]+(?:\s+[\p{L}]+){0,3})`)
	reLikePrefix = regexp.MustCompile(`(?i)^(?:me gustan?|me encantan?|me interesan?|me apasionan?|disfruto(?: de)?|soy fan de)\s+`)
	reSplitList  = regexp.MustCompile(`(?i)\s*(?:,|\by\b|\be\b|\bo\b)\s*`)
)

// Extract analiza una frase libre ("hoy fui al gym, me encanta la F1")
// y devuelve las señales que contiene. Funciona sin conexión.
func Extract(text string) []Signal {
	var out []Signal
	lower := strings.ToLower(text)

	if m := reName.FindStringSubmatch(text); m != nil {
		out = append(out, Signal{"nombre", strings.TrimSpace(m[1])})
	}
	if m := reOccupation.FindStringSubmatch(lower); m != nil {
		job := cutClause(m[1])
		if i := strings.Index(job, " y "); i >= 0 {
			job = job[:i] // "diseñadora y vivo en..." → "diseñadora"
		}
		out = append(out, Signal{"trabajo", normalizeTopic(job)})
	}
	if m := reLocation.FindStringSubmatch(text); m != nil {
		out = append(out, Signal{"lugar", strings.TrimSpace(m[1])})
	}

	// Rechazos primero: sus rangos se excluyen de los gustos
	// ("no me gusta el fútbol" no es un gusto).
	var negSpans [][]int
	for _, m := range reDislike.FindAllStringSubmatchIndex(lower, -1) {
		negSpans = append(negSpans, m[:2])
		for _, t := range splitList(lower[m[2]:m[3]]) {
			out = append(out, Signal{"rechazo", t})
		}
	}
	for _, m := range reLike.FindAllStringSubmatchIndex(lower, -1) {
		if insideAny(m[0], negSpans) {
			continue
		}
		for _, t := range splitList(lower[m[2]:m[3]]) {
			out = append(out, Signal{"gusto", t})
		}
	}
	for _, m := range reActivity.FindAllStringSubmatch(lower, -1) {
		if a := activityName(m[1]); a != "" {
			out = append(out, Signal{"actividad", a})
		}
	}

	// Palabras relevantes sueltas: señal débil de interés.
	seen := make(map[string]bool)
	for _, s := range out {
		for _, w := range strings.Fields(s.Value) {
			seen[w] = true
		}
	}
	for _, w := range Keywords(lower) {
		if !seen[w] {
			out = append(out, Signal{"palabra", w})
			seen[w] = true
		}
	}
	return out
}

// Keywords palabras con contenido (sin stopwords, ≥4 letras).
func Keywords(text string) []string {
	var out []string
	seen := make(map[string]bool)
	for _, w := range strings.FieldsFunc(strings.ToLower(text), func(r rune) bool {
		return !unicode.IsLetter(r) && !unicode.IsDigit(r) && r != '+' && r != '#'
	}) {
		if len([]rune(w)) < 4 || stopwords[w] || seen[w] {
			continue
		}
		seen[w] = true
		out = append(out, w)
	}
	return out
}

func splitList(s string) []string {
	s = cutClause(s)
	var out []string
	for _, part := range reSplitList.Split(s, -1) {
		// "la foto y me interesa la IA" → "la foto", "la IA"
		part = reLikePrefix.ReplaceAllString(strings.TrimSpace(part), "")
		if t := normalizeTopic(part); t != "" && !stopwords[t] {
			out = append(out, t)
		}
	}
	return out
}

// cutClause corta en conectores que inician otra idea
// ("la fotografía y no me gusta..." → "la fotografía y").
func cutClause(s string) string {
	s = " " + s + " "
	for _, sep := range []string{" pero ", " aunque ", " porque ", " cuando ", " mientras ", " desde hace ", " hace ", " no ", " ni "} {
		if i := strings.Index(s, sep); i >= 0 {
			s = s[:i]
		}
	}
	return strings.TrimSpace(s)
}

func activityName(s string) string {
	words := strings.Fields(strings.ToLower(s))
	// "acabo de llegar a la oficina" → "oficina"
	if len(words) > 0 && motionVerbs[words[0]] {
		words = words[1:]
	}
	for len(words) > 0 && (leadingFiller[words[0]] || stopwords[words[0]]) {
		words = words[1:]
	}
	// "voy a correr un rato" → "correr"; "voy al gimnasio" → "gimnasio"
	if len(words) == 0 || motionVerbs[words[0]] {
		return ""
	}
	if len(words) > 2 {
		words = words[:2]
	}
	if len(words) > 1 && (stopwords[words[1]] || leadingFiller[words[1]]) {
		words = words[:1]
	}
	return strings.Join(words, " ")
}

func insideAny(pos int, spans [][]int) bool {
	for _, s := range spans {
		if s[0] <= pos && pos < s[1] {
			return true
		}
	}
	return false
}

var motionVerbs = map[string]bool{
	"llegar": true, "ir": true, "salir": true, "volver": true, "regresar": true,
	"entrar": true, "pasar": true, "estar": true,
}

var stopwords = func() map[string]bool {
	m := make(map[string]bool)
	for _, w := range strings.Fields(`
		a al algo algunas algunos ante antes aquí así aun aunque bien cada casa como cómo con contra cual cuando
		de del desde donde dos el él ella ellas ellos en entre era eran eres es esa ese eso esta está están estar
		este esto estos estoy fue fueron fui gran ha hace hacer hacia han has hasta hay hoy la las le les lo los
		mas más me mi mis mientras mucho muy nada ni no nos nosotros nuestra nuestro o otra otro para pero poco
		por porque que qué quien se sea ser si sí sin sobre solo sólo son soy su sus también tan tanto te tengo
		tiene tienen todo todos tu tus un una uno unos usted va vamos van voy y ya yo ayer mañana tarde noche
		semana días día siempre nunca cosas cosa gusta gustan encanta encantan interesa interesan quiero puedo
		creo estaba estado había bueno buena mejor peor luego ahora después entonces rato vez veces todavía
		the and for with that this from have about your what when just like
		mucha muchas muchos otras otros cuál dónde quién tener tenía ahí allí
		acabo llegar llegué vengo salgo salí toca tocó hice hago estuve voy fui termino terminé empiezo
		llamo nombre trabajo vivo dedico encantan interesa gustaría
	`) {
		m[w] = true
	}
	return m
}()
