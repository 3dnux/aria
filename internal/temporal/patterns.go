// Package temporal es la mitad temporal del Módulo 3 de ARIA: descubre
// patrones reales en tu vida a partir de la línea de tiempo de Cookie.
//
// Busca relaciones "cuando pasa A, en las siguientes W horas suele pasar B"
// y solo las acepta si B ocurre después de A bastante más que a esa misma
// hora en los días sin A (control por horario), con suficientes casos y una
// cota inferior de Wilson para no fiarse de rachas. Es correlación temporal, no prueba de
// causa: por eso las llama "patrones" y muestra cuántas veces se cumplió.
package temporal

import (
	"fmt"
	"math"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

// Event algo que Cookie anotó en tu línea de tiempo.
type Event struct {
	At   int64  `json:"at"` // milisegundos Unix
	Kind string `json:"kind"`
	Text string `json:"text"`
}

// Pattern relación temporal descubierta.
type Pattern struct {
	Cause       string  `json:"cause"`
	Effect      string  `json:"effect"`
	WindowHours int     `json:"window_hours"`
	Count       int     `json:"count"`   // veces que ocurrió la causa
	Support     int     `json:"support"` // veces que después vino el efecto
	Probability float64 `json:"probability"`
	Baseline    float64 `json:"baseline"`
	Lift        float64 `json:"lift"`
	MedianLagH  float64 `json:"median_lag_hours"`
	Score       float64 `json:"score"`
}

// Config parámetros del minado.
type Config struct {
	Windows    []int // horas
	MinCount   int
	MinSupport int
	MinProb    float64
	MinLift    float64
	MaxSymbols int
	Max        int
}

// DefaultConfig valores prudentes para datos personales.
func DefaultConfig() Config {
	return Config{Windows: []int{3, 24}, MinCount: 3, MinSupport: 3, MinProb: 0.5, MinLift: 2, MaxSymbols: 60, Max: 12}
}

var (
	reSleep = regexp.MustCompile(`\((\d+)h`)
	reAppMn = regexp.MustCompile(`\s+\d+ min$`)
	reMsg   = regexp.MustCompile(`^mensaje de (.+?)(\s*\(.*\))?$`)
	rePhoto = regexp.MustCompile(`^(\d+) fotos`)
)

// Symbol convierte un evento en un símbolo comparable ("lugar:gimnasio").
// Devuelve "" si el evento no sirve para buscar patrones.
func Symbol(e Event) string {
	t := strings.ToLower(strings.TrimSpace(e.Text))
	switch e.Kind {
	case "lugar":
		return "lugar:" + strings.TrimPrefix(t, "llegaste a ")
	case "actividad":
		return "actividad:" + t
	case "movimiento":
		return "movimiento:" + t
	case "calendario":
		return "evento:" + t
	case "música":
		return "música:" + t
	case "estrés":
		return "estrés:" + t
	case "sueño":
		if m := reSleep.FindStringSubmatch(t); m != nil {
			if h, _ := strconv.Atoi(m[1]); h < 6 {
				return "sueño:corto"
			}
			return "sueño:normal"
		}
	case "app":
		return "app:" + reAppMn.ReplaceAllString(t, "")
	case "mensaje":
		if m := reMsg.FindStringSubmatch(t); m != nil {
			return "mensaje:" + strings.TrimSpace(m[1])
		}
	case "fotos":
		if m := rePhoto.FindStringSubmatch(t); m != nil {
			if n, _ := strconv.Atoi(m[1]); n >= 8 {
				return "fotos:muchas"
			}
		}
	}
	return ""
}

// Mine descubre patrones en una línea de tiempo.
func Mine(events []Event, cfg Config) []Pattern {
	occ := map[string][]int64{}
	var first, last int64
	for _, e := range events {
		s := Symbol(e)
		if s == "" {
			continue
		}
		occ[s] = append(occ[s], e.At)
		if first == 0 || e.At < first {
			first = e.At
		}
		if e.At > last {
			last = e.At
		}
	}
	if len(occ) < 2 || last-first < 2*24*hourMs {
		return nil
	}
	symbols := make([]string, 0, len(occ))
	for s, ts := range occ {
		sort.Slice(ts, func(i, j int) bool { return ts[i] < ts[j] })
		occ[s] = dedupe(ts, 30*60_000) // la misma cosa repetida en media hora cuenta una vez
		symbols = append(symbols, s)
	}
	sort.Slice(symbols, func(i, j int) bool {
		if len(occ[symbols[i]]) != len(occ[symbols[j]]) {
			return len(occ[symbols[i]]) > len(occ[symbols[j]])
		}
		return symbols[i] < symbols[j]
	})
	if len(symbols) > cfg.MaxSymbols {
		symbols = symbols[:cfg.MaxSymbols]
	}

	best := map[[2]string]Pattern{}
	for _, w := range cfg.Windows {
		wMs := int64(w) * hourMs
		for _, a := range symbols {
			if len(occ[a]) < cfg.MinCount {
				continue
			}
			controls := controlTimes(occ[a], first, last)
			if len(controls) < minControls {
				continue // pasa (casi) todos los días: no hay con qué comparar
			}
			for _, b := range symbols {
				if a == b {
					continue
				}
				support, lags := followed(occ[a], occ[b], wMs)
				if support < cfg.MinSupport {
					continue
				}
				n := len(occ[a])
				p := float64(support) / float64(n)
				ctrlHits, _ := followed(controls, occ[b], wMs)
				// Tasa base: misma hora del día, en días sin la causa (suavizada).
				base := (float64(ctrlHits) + 0.5) / (float64(len(controls)) + 1)
				lift := p / base
				if p < cfg.MinProb || lift < cfg.MinLift {
					continue
				}
				score := wilsonLower(support, n) * math.Log2(lift)
				key := [2]string{a, b}
				if old, ok := best[key]; ok && old.Score >= score {
					continue
				}
				best[key] = Pattern{
					Cause: a, Effect: b, WindowHours: w, Count: n, Support: support,
					Probability: p, Baseline: base, Lift: lift, MedianLagH: median(lags) / float64(hourMs), Score: score,
				}
			}
		}
	}
	out := make([]Pattern, 0, len(best))
	for _, p := range best {
		out = append(out, p)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Score != out[j].Score {
			return out[i].Score > out[j].Score
		}
		return out[i].Cause+out[i].Effect < out[j].Cause+out[j].Effect
	})
	if len(out) > cfg.Max {
		out = out[:cfg.Max]
	}
	return out
}

const hourMs = int64(3_600_000)

// followed cuántas veces A fue seguido por B en (0, w], y el retraso de cada vez.
func followed(as, bs []int64, w int64) (int, []float64) {
	support := 0
	var lags []float64
	for _, a := range as {
		i := sort.Search(len(bs), func(i int) bool { return bs[i] > a })
		if i < len(bs) && bs[i]-a <= w {
			support++
			lags = append(lags, float64(bs[i]-a))
		}
	}
	return support, lags
}

// minControls días de control mínimos para poder comparar.
const minControls = 3

// controlTimes momentos de control: la misma hora del día que cada causa, en
// otros días del periodo en que la causa NO ocurrió cerca de esa hora. Así la
// comparación descuenta tu horario (lo que pasa a esa hora de todos modos).
func controlTimes(as []int64, first, last int64) []int64 {
	const day = 24 * hourMs
	var out []int64
	for _, a := range as {
		for t := a - ((a-first)/day)*day; t <= last; t += day {
			if t == a || near(as, t, 2*hourMs) {
				continue
			}
			out = append(out, t)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i] < out[j] })
	return out
}

func near(ts []int64, t, d int64) bool {
	i := sort.Search(len(ts), func(i int) bool { return ts[i] >= t-d })
	return i < len(ts) && ts[i] <= t+d
}

func dedupe(ts []int64, gap int64) []int64 {
	out := ts[:0:0]
	for _, t := range ts {
		if len(out) == 0 || t-out[len(out)-1] >= gap {
			out = append(out, t)
		}
	}
	return out
}

func median(xs []float64) float64 {
	if len(xs) == 0 {
		return 0
	}
	s := append([]float64(nil), xs...)
	sort.Float64s(s)
	return s[len(s)/2]
}

// wilsonLower cota inferior (90%) de la proporción k/n.
func wilsonLower(k, n int) float64 {
	if n == 0 {
		return 0
	}
	z := 1.645
	p := float64(k) / float64(n)
	nn := float64(n)
	den := 1 + z*z/nn
	center := p + z*z/(2*nn)
	margin := z * math.Sqrt(p*(1-p)/nn+z*z/(4*nn*nn))
	return (center - margin) / den
}

// Describe frase en español para mostrar el patrón.
func Describe(p Pattern) string {
	when := "en las horas siguientes"
	if p.WindowHours >= 24 {
		when = "en el día siguiente"
	}
	strength := fmt.Sprintf("%.1f× más de lo normal", p.Lift)
	if p.Lift > 10 {
		strength = "mucho más de lo normal"
	}
	return fmt.Sprintf("Cuando %s, %s %s (%d de %d veces; %s)",
		Human(p.Cause), Human(p.Effect), when, p.Support, p.Count, strength)
}

// Human traduce un símbolo a lenguaje natural.
func Human(sym string) string {
	kind, val, _ := strings.Cut(sym, ":")
	switch kind {
	case "lugar":
		return "vas a " + val
	case "actividad":
		return "toca " + val
	case "movimiento":
		return map[string]string{"caminar": "caminas", "correr": "corres", "bici": "vas en bici", "en coche": "vas en coche"}[val]
	case "evento":
		return "tienes «" + val + "»"
	case "música":
		return "escuchas a " + val
	case "estrés":
		return "tu estrés está " + val
	case "sueño":
		if val == "corto" {
			return "duermes poco"
		}
		return "duermes bien"
	case "app":
		return "usas " + val
	case "mensaje":
		return "hablas con " + val
	case "fotos":
		return "haces muchas fotos"
	}
	return sym
}
