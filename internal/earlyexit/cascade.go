// Package earlyexit es el Módulo 2 de ARIA: salida temprana real.
//
// Antes votaba sobre confianzas simuladas de "capas". Ahora decide si una
// respuesta calculada en el propio dispositivo (con lo que Cookie ya sabe de
// ti) es lo bastante buena para darla sin llamar a una IA, o si hay que
// escalar. Conserva sus tres estrategias originales, aplicadas a datos reales:
//
//   - Umbral: salir si la confianza supera el umbral.
//   - Consenso: si dos fuentes independientes coinciden, la confianza sube.
//   - Adaptativo: el umbral aprende de tus correcciones (si una respuesta
//     local era mala, se vuelve más exigente; si acierta, se relaja poco a poco).
package earlyexit

import (
	"fmt"
	"math"
	"sort"
	"strings"
)

// Candidate una posible respuesta local.
type Candidate struct {
	Source     string  // de dónde sale (agenda, rutina, contexto, música...)
	Answer     string  // texto para el usuario
	Key        string  // valor normalizado para comparar fuentes (p. ej. "gimnasio")
	Confidence float64 // 0..1
}

// Decision resultado de la cascada.
type Decision struct {
	Exit       bool // true = responder en local, sin IA
	Answer     string
	Confidence float64
	Reason     string
}

// Gate puerta de salida temprana con umbral adaptativo.
type Gate struct {
	Threshold float64 `json:"threshold"`
	Min       float64 `json:"min"`
	Max       float64 `json:"max"`
	Correct   int     `json:"correct"`
	Wrong     int     `json:"wrong"`
}

// NewGate umbral inicial prudente.
func NewGate() *Gate { return &Gate{Threshold: 0.7, Min: 0.55, Max: 0.95} }

// ConsensusBoost cuánto sube la confianza cuando dos fuentes coinciden.
const ConsensusBoost = 0.15

// Decide elige la mejor respuesta local y si basta para salir temprano.
func (g *Gate) Decide(cands []Candidate) Decision {
	if len(cands) == 0 {
		return Decision{Reason: "no sé responder esto sin pensar más"}
	}
	sorted := append([]Candidate(nil), cands...)
	sort.SliceStable(sorted, func(i, j int) bool { return sorted[i].Confidence > sorted[j].Confidence })
	best := sorted[0]
	conf := best.Confidence
	reason := fmt.Sprintf("umbral (%s %.0f%%)", best.Source, conf*100)

	// Consenso: otra fuente distinta llega a la misma conclusión.
	for _, c := range sorted[1:] {
		if c.Source != best.Source && best.Key != "" && strings.EqualFold(c.Key, best.Key) {
			conf = math.Min(1, conf+ConsensusBoost)
			reason = fmt.Sprintf("consenso %s + %s", best.Source, c.Source)
			break
		}
	}
	if conf >= g.Threshold {
		return Decision{Exit: true, Answer: best.Answer, Confidence: conf, Reason: reason}
	}
	return Decision{Answer: best.Answer, Confidence: conf,
		Reason: fmt.Sprintf("confianza %.0f%% < umbral %.0f%%: escalo", conf*100, g.Threshold*100)}
}

// Observe aprende de tu reacción a una respuesta local.
// Un error sube el umbral más de lo que un acierto lo baja: equivocarse
// cuesta más que gastar una llamada de más.
func (g *Gate) Observe(confidence float64, correct bool) {
	if correct {
		g.Correct++
		g.Threshold = math.Max(g.Min, g.Threshold-0.01)
	} else {
		g.Wrong++
		// Cuanto más confiado estaba al fallar, más sube.
		g.Threshold = math.Min(g.Max, math.Max(g.Threshold+0.05, confidence+0.02))
	}
}

// Accuracy aciertos de las respuestas locales valoradas.
func (g *Gate) Accuracy() (float64, bool) {
	n := g.Correct + g.Wrong
	if n == 0 {
		return 0, false
	}
	return float64(g.Correct) / float64(n), true
}
