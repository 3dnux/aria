package main

import (
	"aria/internal/earlyexit"
	"aria/internal/liquid"
	"aria/internal/memory"
	"aria/internal/temporal"
	"context"
	"fmt"
	"hash/fnv"
	"math"
	"strings"
	"sync"
	"time"
)

// AriaSystem integración M1 + M2 + M3
type AriaSystem struct {
	LiquidNN       *liquid.Controller
	Memory         *memory.Module3Full
	embeddingCache map[string][]float32
	cacheMu        sync.RWMutex
	config         Config
}

type Config struct {
	EmbeddingSize int
	CacheEnabled  bool
}

// NewAriaSystem crea el sistema integrado
func NewAriaSystem(strategy earlyexit.Strategy) *AriaSystem {
	sys := &AriaSystem{
		LiquidNN:       liquid.NewController(strategy),
		embeddingCache: make(map[string][]float32),
		config: Config{
			EmbeddingSize: 128,
			CacheEnabled:  true,
		},
	}

	// Adaptador para M1
	liquidAdapter := func(ctx context.Context, input []float32) ([]float32, error) {
		query := sys.embeddingToString(input)
		result, err := sys.LiquidNN.ProcessRequest(ctx, query)
		if err != nil {
			return nil, err
		}
		return sys.resultToEmbedding(result), nil
	}

	// Adaptador para M2
	earlyExitAdapter := func(hiddenState []float32) (bool, float64, int) {
		if len(hiddenState) < 3 {
			return false, 0.5, 4
		}
		confidence := float64(hiddenState[len(hiddenState)-3])
		shouldExit := hiddenState[len(hiddenState)-2] > 0.5
		exitLayer := int(hiddenState[len(hiddenState)-1])
		return shouldExit, confidence, exitLayer
	}

	sys.Memory = memory.NewModule3Full(liquidAdapter, earlyExitAdapter)
	return sys
}

// Process ejecuta pipeline M1→M2→M3
func (sys *AriaSystem) Process(ctx context.Context, query string) (*Result, error) {
	start := time.Now()

	// 1. Embedding del query
	inputEmb := sys.stringToEmbedding(query)

	// 2. M3: Query para recuperar contexto (método público)
	contextResults := sys.Memory.Query(inputEmb, "", 2, time.Hour)

	// 3. Enriquecer query
	enrichedQuery := sys.enrichQuery(query, contextResults)

	// 4. M1/M2: Procesar con LiquidNN real
	liquidResult, err := sys.LiquidNN.ProcessRequest(ctx, enrichedQuery)
	if err != nil {
		return nil, err
	}

	// 5. M3: Process() almacena y genera predicciones (método público)
	concept := sys.extractConcept(query)
	m3Result, err := sys.Memory.Process(ctx, inputEmb, concept, liquidResult.FinalConfidence)
	if err != nil {
		return nil, err
	}

	return &Result{
		Query:          query,
		EnrichedQuery:  enrichedQuery,
		Output:         liquidResult.LayerResults[len(liquidResult.LayerResults)-1].EnhancedQuery,
		Confidence:     liquidResult.FinalConfidence,
		EarlyExited:    liquidResult.EarlyExited,
		ExitReason:     liquidResult.ExitReason,
		LayersUsed:     liquidResult.LayersUsed,
		TotalLayers:    liquidResult.Topology.AssignedLayers,
		NodeID:         m3Result.NodeID,
		ContextNodes:   m3Result.ContextNodes,
		Predictions:    m3Result.Predictions,
		ProcessingTime: time.Since(start),
	}, nil
}

type Result struct {
	Query          string
	EnrichedQuery  string
	Output         string
	Confidence     float64
	EarlyExited    bool
	ExitReason     string
	LayersUsed     int
	TotalLayers    int
	NodeID         int64
	ContextNodes   int
	Predictions    []*temporal.TemporalPrediction
	ProcessingTime time.Duration
}

// ==================== HELPERS ====================

func (sys *AriaSystem) stringToEmbedding(s string) []float32 {
	sys.cacheMu.RLock()
	if cached, ok := sys.embeddingCache[s]; ok {
		sys.cacheMu.RUnlock()
		return cached
	}
	sys.cacheMu.RUnlock()

	vec := make([]float32, sys.config.EmbeddingSize)
	h := fnv.New64a()
	h.Write([]byte(s))
	seed := int64(h.Sum64())

	words := strings.Fields(s)
	wordCount := len(words)

	for i := 0; i < sys.config.EmbeddingSize; i++ {
		val := math.Sin(float64(seed+int64(i))*0.1) * 0.5
		if i == 0 {
			val += float64(wordCount) / 100.0
		}
		vec[i] = float32(val)
	}

	vec = normalize(vec)

	if sys.config.CacheEnabled {
		sys.cacheMu.Lock()
		sys.embeddingCache[s] = vec
		sys.cacheMu.Unlock()
	}

	return vec
}

func (sys *AriaSystem) embeddingToString(emb []float32) string {
	sys.cacheMu.RLock()
	defer sys.cacheMu.RUnlock()
	for str, cached := range sys.embeddingCache {
		if cosineSimilarity(emb, cached) > 0.95 {
			return str
		}
	}
	return "[embedded_query]"
}

func (sys *AriaSystem) resultToEmbedding(r *liquid.LiquidResult) []float32 {
	vec := make([]float32, sys.config.EmbeddingSize)
	for i := 0; i < sys.config.EmbeddingSize; i++ {
		vec[i] = float32(r.FinalConfidence * math.Sin(float64(i)*0.1))
	}
	if len(vec) >= 3 {
		vec[len(vec)-3] = float32(r.FinalConfidence)
		if r.EarlyExited {
			vec[len(vec)-2] = 1.0
		}
		vec[len(vec)-1] = float32(r.LayersUsed)
	}
	return normalize(vec)
}

func (sys *AriaSystem) enrichQuery(query string, ctx []*memory.QueryResult) string {
	if len(ctx) == 0 {
		return query
	}

	var concepts []string
	for _, c := range ctx {
		if c.Score > 0.3 {
			concepts = append(concepts, c.Node.Concept)
		}
	}

	if len(concepts) == 0 {
		return query
	}

	return fmt.Sprintf("[Contexto: %s] %s",
		strings.Join(concepts, ", "), query)
}

func (sys *AriaSystem) extractConcept(q string) string {
	words := strings.Fields(q)
	if len(words) == 0 {
		return "unknown"
	}
	if len(words) > 3 {
		words = words[:3]
	}
	return strings.ToLower(strings.Join(words, "_"))
}

func normalize(v []float32) []float32 {
	var sum float64
	for _, x := range v {
		sum += float64(x * x)
	}
	if sum == 0 {
		return v
	}
	norm := float32(math.Sqrt(sum))
	for i := range v {
		v[i] /= norm
	}
	return v
}

func cosineSimilarity(a, b []float32) float64 {
	if len(a) != len(b) {
		return 0
	}
	var dot, na, nb float64
	for i := range a {
		dot += float64(a[i]) * float64(b[i])
		na += float64(a[i]) * float64(a[i])
		nb += float64(b[i]) * float64(b[i])
	}
	if na == 0 || nb == 0 {
		return 0
	}
	return dot / (math.Sqrt(na) * math.Sqrt(nb))
}

// ==================== MAIN ====================

func main() {
	fmt.Println("╔══════════════════════════════════════════════════════════╗")
	fmt.Println("║     ARIA - Sistema Integrado (M1 + M2 + M3)             ║")
	fmt.Println("╚══════════════════════════════════════════════════════════╝")

	sys := NewAriaSystem(earlyexit.StrategyAdaptive)
	ctx := context.Background()

	queries := []string{
		"Analiza el impacto de la inteligencia artificial",
		"Explica la teoría de la relatividad de Einstein",
		"Resume los beneficios del ejercicio físico",
		"¿Qué es la computación cuántica?",
	}

	for i, q := range queries {
		fmt.Printf("\n🔄 Query %d: %s\n", i+1, q)
		fmt.Println(strings.Repeat("-", 60))

		res, err := sys.Process(ctx, q)
		if err != nil {
			fmt.Printf("   ❌ Error: %v\n", err)
			continue
		}

		fmt.Printf("   ⚡ Tiempo: %v\n", res.ProcessingTime)
		fmt.Printf("   🎯 Confianza: %.1f%%\n", res.Confidence*100)
		fmt.Printf("   🚪 Capas: %d/%d (EarlyExit: %v)\n",
			res.LayersUsed, res.TotalLayers, res.EarlyExited)
		fmt.Printf("   📝 Razón: %s\n", res.ExitReason)
		fmt.Printf("   💾 Memoria: Nodo %d | Contexto: %d nodos\n",
			res.NodeID, res.ContextNodes)

		if len(res.Predictions) > 0 {
			fmt.Printf("   🔮 Predicciones: %d\n", len(res.Predictions))
			for _, p := range res.Predictions {
				fmt.Printf("      → %s (%.0f%%)\n",
					p.ExpectedEvent, p.Confidence*100)
			}
		}

		if res.ContextNodes > 0 {
			fmt.Printf("   🧠 Enriquecido: %s\n", res.EnrichedQuery)
		}
	}

	fmt.Println("\n✅ Sistema ARIA operativo (M1+M2+M3 integrados)")
}
