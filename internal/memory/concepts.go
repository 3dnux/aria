// Package memory es la mitad asociativa del Módulo 3 de ARIA: una red de
// conceptos de tu vida con activación propagada.
//
// Cada recuerdo (y cada hora de tu línea de tiempo) conecta los conceptos que
// aparecen juntos. Al buscar, la activación se propaga desde los conceptos de
// la pregunta a sus vecinos: preguntar por "maratón" activa "correr", "dormir"
// o "rodilla" si en tu vida van juntos, aunque no se parezcan como palabras.
// Así la memoria de Cookie encuentra recuerdos por asociación, como la humana.
package memory

import (
	"math"
	"sort"
)

// ConceptGraph grafo no dirigido y ponderado de coocurrencias.
type ConceptGraph struct {
	edges map[string]map[string]float64
}

// NewConceptGraph grafo vacío.
func NewConceptGraph() *ConceptGraph { return &ConceptGraph{edges: map[string]map[string]float64{}} }

// AddDocument conecta todos los conceptos que aparecen juntos.
// El peso se reparte para que un documento con muchos conceptos no domine.
func (g *ConceptGraph) AddDocument(concepts []string, weight float64) {
	uniq := unique(concepts)
	if len(uniq) < 2 {
		return
	}
	w := weight / float64(len(uniq)-1)
	for i, a := range uniq {
		for _, b := range uniq[i+1:] {
			g.add(a, b, w)
			g.add(b, a, w)
		}
	}
}

func (g *ConceptGraph) add(a, b string, w float64) {
	if g.edges[a] == nil {
		g.edges[a] = map[string]float64{}
	}
	g.edges[a][b] += w
}

// Activation concepto activado y su nivel.
type Activation struct {
	Concept string
	Level   float64
}

// Spread propaga la activación desde las semillas durante [hops] saltos con
// decaimiento [decay], normalizando por la fuerza total de cada nodo.
// Devuelve los conceptos nuevos (no semillas) con nivel relativo ≥ minLevel
// (1 = el más activado).
func (g *ConceptGraph) Spread(seeds []string, hops int, decay, minLevel float64) []Activation {
	act := map[string]float64{}
	frontier := map[string]float64{}
	for _, s := range unique(seeds) {
		if _, ok := g.edges[s]; ok {
			act[s] = 1
			frontier[s] = 1
		}
	}
	for h := 0; h < hops && len(frontier) > 0; h++ {
		next := map[string]float64{}
		for node, level := range frontier {
			total := 0.0
			for _, w := range g.edges[node] {
				total += w
			}
			if total == 0 {
				continue
			}
			for nb, w := range g.edges[node] {
				gain := level * decay * w / total
				// Nodos muy conectados reparten menos (evita que "hoy" lo active todo).
				gain *= 1 / math.Sqrt(float64(len(g.edges[nb])))
				if gain > next[nb] {
					next[nb] = gain
				}
			}
		}
		frontier = map[string]float64{}
		for n, l := range next {
			if l > act[n] {
				act[n] = l
				frontier[n] = l
			}
		}
	}
	seedSet := map[string]bool{}
	for _, s := range seeds {
		seedSet[s] = true
	}
	// Niveles relativos al concepto nuevo más activado (0..1).
	top := 0.0
	for c, l := range act {
		if !seedSet[c] && l > top {
			top = l
		}
	}
	var out []Activation
	for c, l := range act {
		if !seedSet[c] && top > 0 && l/top >= minLevel {
			out = append(out, Activation{c, l / top})
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Level != out[j].Level {
			return out[i].Level > out[j].Level
		}
		return out[i].Concept < out[j].Concept
	})
	return out
}

// Size número de conceptos.
func (g *ConceptGraph) Size() int { return len(g.edges) }

func unique(xs []string) []string {
	seen := map[string]bool{}
	var out []string
	for _, x := range xs {
		if x != "" && !seen[x] {
			seen[x] = true
			out = append(out, x)
		}
	}
	return out
}
