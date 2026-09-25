// ARIA: el cerebro local de Cookie, en tu ordenador.
//
//	aria ruta "¿qué tengo hoy?"            # M1: ¿local o Claude? ¿cuánto esfuerzo? ¿web?
//	aria analizar copia.cookie             # M1+M2+M3 sobre tu copia de seguridad cifrada
//	aria demo                              # lo mismo con 30 días de datos de ejemplo
//
// La contraseña de la copia se pide por la terminal o se toma de ARIA_PASSWORD.
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"sort"
	"strings"
	"unicode"

	"aria/internal/copia"
	"aria/internal/earlyexit"
	"aria/internal/liquid"
	"aria/internal/memory"
	"aria/internal/temporal"
)

const usage = `ARIA — el cerebro local de Cookie

  aria ruta "mensaje"        Cómo se resolvería un mensaje (M1)
  aria analizar ARCHIVO      Analiza tu copia de seguridad de Cookie (M1 + M2 + M3)
  aria demo                  Análisis de ejemplo con 30 días sintéticos

M1 Enrutador   decide si un mensaje se responde en el teléfono o con Claude, con qué esfuerzo y si busca en la web
M2 Cascada     responde en local cuando es lo bastante seguro; su umbral aprende de tus correcciones
M3 Patrones    descubre relaciones temporales en tu vida y asocia conceptos para recordar mejor
`

func main() {
	if len(os.Args) < 2 {
		fmt.Print(usage)
		os.Exit(2)
	}
	switch os.Args[1] {
	case "ruta":
		if len(os.Args) < 3 {
			fail("escribe el mensaje entre comillas")
		}
		printRoute(liquid.NewRouter().Route(strings.Join(os.Args[2:], " ")))
	case "analizar":
		if len(os.Args) < 3 {
			fail("indica el archivo de la copia (Ajustes → Copia de seguridad → Exportar)")
		}
		data, err := os.ReadFile(os.Args[2])
		check(err)
		s, err := copia.Load(data, password())
		check(err)
		report(s)
	case "demo":
		data, err := os.ReadFile("testdata/aria/timeline.json")
		check(err)
		s := &copia.State{}
		check(json.Unmarshal(data, &s.Timeline))
		s.Profile.Name = "Demo"
		for _, m := range []string{"¿Qué tengo hoy?", "pon música de Bad Bunny", "¿qué suelo hacer a esta hora?",
			"ayúdame a decidir si cambio de trabajo", "¿cuándo es el próximo concierto de Bad Bunny?", "hola, ¿cómo estás?"} {
			s.Chat = append(s.Chat, struct {
				FromUser bool   `json:"fromUser"`
				Text     string `json:"text"`
			}{true, m})
		}
		report(s)
	default:
		fmt.Print(usage)
		os.Exit(2)
	}
}

func printRoute(r liquid.Route) {
	fmt.Printf("  → %s", r.Tier)
	if r.Tier == liquid.TierLocal {
		fmt.Printf(" (%s)", r.Intent)
	} else {
		fmt.Printf(" · esfuerzo %s", r.Effort)
		if r.WebSearches > 0 {
			fmt.Printf(" · hasta %d búsquedas web", r.WebSearches)
		}
	}
	fmt.Printf(" · complejidad %.2f\n    %s\n", r.Complexity, r.Reason)
}

func report(s *copia.State) {
	who := s.Profile.Name
	if who == "" {
		who = "ti"
	}
	fmt.Printf("🧠 ARIA analiza la copia de %s\n", who)
	fmt.Printf("   %d eventos en la línea de tiempo · %d recuerdos · %d mensajes\n\n", len(s.Timeline), len(s.Memories), len(s.Chat))

	// ---- M3: patrones ----
	fmt.Println("🔗 M3 · Patrones de tu vida")
	patterns := temporal.Mine(s.Timeline, temporal.DefaultConfig())
	if len(patterns) == 0 {
		fmt.Println("   Aún no hay datos suficientes (hacen falta días con y sin cada cosa).")
	}
	for _, p := range patterns {
		fmt.Printf("   • %s\n", temporal.Describe(p))
	}

	// ---- M3: asociaciones ----
	g := memory.NewConceptGraph()
	for _, m := range s.Memories {
		g.AddDocument(append(words(m.Text), m.Tags...), 1)
	}
	for _, day := range groupByBlock(s.Timeline) {
		g.AddDocument(day, 0.5)
	}
	if g.Size() > 0 {
		fmt.Println("\n🕸️ M3 · Asociaciones (lo que en tu vida va junto)")
		for _, seed := range topConcepts(s, 4) {
			var names []string
			for _, a := range g.Spread([]string{seed}, 2, 0.5, 0.2) {
				if len(names) == 5 {
					break
				}
				names = append(names, a.Concept)
			}
			if len(names) > 0 {
				fmt.Printf("   • %s → %s\n", seed, strings.Join(names, ", "))
			}
		}
	}

	// ---- M1: enrutado de tus mensajes ----
	router := liquid.NewRouter()
	var local, low, medium, high, web, total int
	for _, c := range s.Chat {
		if !c.FromUser || strings.HasPrefix(c.Text, "✏️") {
			continue
		}
		total++
		r := router.Route(c.Text)
		switch {
		case r.Tier == liquid.TierLocal:
			local++
		case r.Effort == liquid.EffortLow:
			low++
		case r.Effort == liquid.EffortMedium:
			medium++
		default:
			high++
		}
		if r.WebSearches > 0 {
			web++
		}
	}
	if total > 0 {
		fmt.Println("\n🧭 M1 · Cómo se reparten tus mensajes")
		fmt.Printf("   %d en el teléfono (sin IA) · %d esfuerzo bajo · %d medio · %d alto · %d con búsqueda web\n", local, low, medium, high, web)
		// Coste relativo aproximado: alto=4, medio=2, bajo=1, local=0 (vs. todo en alto).
		used := float64(low + medium*2 + high*4)
		fmt.Printf("   Ahorro estimado frente a usar siempre el máximo: %.0f%%\n", 100*(1-used/float64(total*4)))
	}

	// ---- M2: cascada ----
	fmt.Println("\n⚡ M2 · Respuestas locales")
	if s.Gate != nil {
		gate := earlyexit.Gate{Threshold: s.Gate.Threshold, Correct: s.Gate.Correct, Wrong: s.Gate.Wrong}
		fmt.Printf("   Umbral aprendido: %.0f%%", gate.Threshold*100)
		if acc, ok := gate.Accuracy(); ok {
			fmt.Printf(" · aciertos valorados: %.0f%% (%d/%d)", acc*100, gate.Correct, gate.Correct+gate.Wrong)
		}
		fmt.Println()
	} else {
		fmt.Println("   Umbral inicial 70%: bajará si aciertan y subirá si las corriges.")
	}
}

// words palabras con contenido (≥4 letras, sin tildes, sin palabras vacías).
func words(text string) []string {
	var out []string
	for _, w := range strings.FieldsFunc(strings.ToLower(text), func(r rune) bool { return !unicode.IsLetter(r) && !unicode.IsDigit(r) }) {
		w = fold(w)
		if len([]rune(w)) >= 4 && !stop[w] {
			out = append(out, w)
		}
	}
	return out
}

var stop = map[string]bool{}

func init() {
	for _, w := range strings.Fields("para como cuando pero porque esta este esto estoy estas mucho muy todo todos tengo tiene hace desde sobre entre donde cual quien algo nada siempre nunca ahora luego despues tambien solo mas menos otra otro cada") {
		stop[w] = true
	}
}

func fold(s string) string {
	r := strings.NewReplacer("á", "a", "é", "e", "í", "i", "ó", "o", "ú", "u", "ü", "u")
	return r.Replace(s)
}

// groupByBlock los símbolos de cada bloque de 3 horas (lo que pasa a la vez se asocia).
func groupByBlock(events []temporal.Event) [][]string {
	days := map[int64][]string{}
	for _, e := range events {
		if s := temporal.Symbol(e); s != "" {
			days[e.At/(3*3_600_000)] = append(days[e.At/(3*3_600_000)], s)
		}
	}
	var out [][]string
	for _, d := range days {
		out = append(out, d)
	}
	return out
}

func topConcepts(s *copia.State, n int) []string {
	count := map[string]int{}
	for _, e := range s.Timeline {
		if sym := temporal.Symbol(e); sym != "" {
			count[sym]++
		}
	}
	for _, m := range s.Memories {
		for _, w := range words(m.Text) {
			count[w] += 2
		}
	}
	keys := make([]string, 0, len(count))
	for k := range count {
		keys = append(keys, k)
	}
	sort.Slice(keys, func(i, j int) bool {
		if count[keys[i]] != count[keys[j]] {
			return count[keys[i]] > count[keys[j]]
		}
		return keys[i] < keys[j]
	})
	if len(keys) > n {
		keys = keys[:n]
	}
	return keys
}

func password() string {
	if p := os.Getenv("ARIA_PASSWORD"); p != "" {
		return p
	}
	fmt.Fprint(os.Stderr, "Contraseña de la copia: ")
	line, _ := bufio.NewReader(os.Stdin).ReadString('\n')
	return strings.TrimSpace(line)
}

func check(err error) {
	if err != nil {
		fail(err.Error())
	}
}

func fail(msg string) {
	fmt.Fprintln(os.Stderr, "❌", msg)
	os.Exit(1)
}
