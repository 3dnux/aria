package memory

import (
	"context"
	"sync"
	"time"

	"aria/internal/temporal"
)

// Module3Full integración completa del Módulo 3
type Module3Full struct {
	// Componentes principales
	Graph         *CausalGraph
	Consciousness *temporal.TemporalConsciousness

	// Referencias a M1 y M2
	liquidProcessor func(ctx context.Context, input []float32) ([]float32, error)
	earlyExitCheck  func(hiddenState []float32) (bool, float64, int)

	// Configuración
	config M3FullConfig

	// Estado
	mu         sync.RWMutex
	queryCount int64
}

type M3FullConfig struct {
	MaxContextNodes   int
	EnrichmentAlpha   float64
	TemporalWindow    time.Duration
	EnablePredictions bool
	MinContextScore   float64 // ← NUEVO: umbral mínimo para contexto
}

// DefaultM3FullConfig configuración optimizada
func DefaultM3FullConfig() M3FullConfig {
	return M3FullConfig{
		MaxContextNodes:   10,
		EnrichmentAlpha:   0.3,
		TemporalWindow:    time.Hour * 24,
		EnablePredictions: true,
		MinContextScore:   0.05, // ← MÁS PERMISIVO (era implícito 0.3)
	}
}

// NewModule3Full crea el módulo 3 completo
func NewModule3Full(
	liquidProc func(ctx context.Context, input []float32) ([]float32, error),
	earlyExit func(hiddenState []float32) (bool, float64, int),
) *Module3Full {

	m3 := &Module3Full{
		Graph:           NewCausalGraph(),
		Consciousness:   temporal.NewTemporalConsciousness(),
		liquidProcessor: liquidProc,
		earlyExitCheck:  earlyExit,
		config:          DefaultM3FullConfig(),
	}

	// Iniciar mantenimiento
	go m3.maintenanceLoop()

	return m3
}

// Process flujo completo con consciencia temporal
func (m3 *Module3Full) Process(ctx context.Context,
	input []float32, concept string, intensity float64) (*M3FullResult, error) {

	start := time.Now()

	// 1. Registrar evento en conciencia temporal
	event := m3.Consciousness.RegisterEvent(concept, time.Since(start), intensity)

	// 2. Enfocar atención temporal en "ahora"
	m3.Consciousness.FocusAt(m3.Consciousness.Now, intensity)

	// 3. Recuperar contexto del grafo causal (ponderado por relevancia temporal)
	contextNodes := m3.retrieveTemporalContext(input, concept)

	// 4. Enriquecer input
	enrichedInput := m3.enrichInput(input, contextNodes)

	// 5. Procesar con M1 (LiquidNN)
	hiddenState, err := m3.liquidProcessor(ctx, enrichedInput)
	if err != nil {
		return nil, err
	}

	// 6. Decisión M2 (Early Exit)
	shouldExit, confidence, exitLayer := m3.earlyExitCheck(hiddenState)

	// 7. Almacenar en grafo causal
	node := m3.storeMemory(hiddenState, concept, contextNodes, confidence, event)

	// 8. Generar predicciones temporales
	var predictions []*temporal.TemporalPrediction
	if m3.config.EnablePredictions {
		predictions = m3.Consciousness.GeneratePredictions(concept)
	}

	// 9. Avanzar tiempo subjetivo
	m3.Consciousness.AdvanceTime(time.Since(start))

	// Actualizar métricas
	m3.mu.Lock()
	m3.queryCount++
	m3.mu.Unlock()

	return &M3FullResult{
		Output:          hiddenState,
		NodeID:          node.ID,
		Concept:         concept,
		Confidence:      confidence,
		ExitLayer:       exitLayer,
		EarlyExited:     shouldExit,
		ContextNodes:    len(contextNodes),
		TemporalEventID: event.ID,
		Predictions:     predictions,
		SubjectiveTime:  m3.Consciousness.GetSubjectiveTime(time.Since(start)),
		Latency:         time.Since(start),
	}, nil
}

// retrieveTemporalContext recupera contexto considerando relevancia temporal
type TemporalContext struct {
	*QueryResult
	TemporalRelevance float64
}

func (m3 *Module3Full) retrieveTemporalContext(input []float32,
	concept string) []TemporalContext {

	// Query al grafo
	results := m3.Graph.Query(input, concept, 2, m3.config.TemporalWindow)

	// Ponderar por relevancia temporal de la conciencia
	var contexts []TemporalContext
	for _, res := range results {
		tempRel := m3.Consciousness.GetTemporalRelevance(res.Node.AccessedAt)

		contexts = append(contexts, TemporalContext{
			QueryResult:       res,
			TemporalRelevance: tempRel,
		})
	}

	// Ordenar por score combinado (original * temporal)
	for i := range contexts {
		contexts[i].Score *= contexts[i].TemporalRelevance
	}

	// Limitar
	if len(contexts) > m3.config.MaxContextNodes {
		contexts = contexts[:m3.config.MaxContextNodes]
	}

	return contexts
}

// enrichInput enriquece con contexto temporal
func (m3 *Module3Full) enrichInput(input []float32,
	context []TemporalContext) []float32 {

	if len(context) == 0 {
		return input
	}

	// Vector de contexto ponderado por relevancia temporal
	contextVec := make([]float32, len(input))
	totalWeight := 0.0

	for _, ctx := range context {
		weight := ctx.Score * ctx.TemporalRelevance
		for i := range input {
			if i < len(ctx.Node.Content) {
				contextVec[i] += float32(weight) * ctx.Node.Content[i]
			}
		}
		totalWeight += weight
	}

	if totalWeight > 0 {
		for i := range contextVec {
			contextVec[i] /= float32(totalWeight)
		}
	}

	// Combinar
	enriched := make([]float32, len(input))
	alpha := float32(m3.config.EnrichmentAlpha)
	for i := range input {
		enriched[i] = input[i]*(1-alpha) + contextVec[i]*alpha
	}

	return enriched
}

// storeMemory almacena memoria con enlace al evento temporal
func (m3 *Module3Full) storeMemory(content []float32, concept string,
	context []TemporalContext, confidence float64,
	event *temporal.TemporalEvent) *MemoryNode {

	id := time.Now().UnixNano()

	// Calcular activación base
	baseActivation := confidence
	if len(context) > 0 {
		avgAct := 0.0
		for _, ctx := range context {
			avgAct += ctx.Node.CurrentActivation
		}
		baseActivation = (confidence + avgAct/float64(len(context))) / 2
	}

	// Ajustar por intensidad del evento
	baseActivation = baseActivation * (0.5 + event.Intensity*0.5)

	// Asegurar mínimo
	if baseActivation < 0.3 {
		baseActivation = 0.3
	}

	node := m3.Graph.AddNode(id, content, concept, baseActivation)

	// Crear relaciones causales ponderadas por relevancia temporal
	// ← USAR UMBRAL CONFIGURABLE MÁS BAJO
	for _, ctx := range context {
		if ctx.Score > m3.config.MinContextScore {
			relType := Related
			lag := time.Since(ctx.Node.AccessedAt)

			// Determinar tipo por proximidad temporal
			if ctx.TemporalRelevance > 0.8 {
				relType = Causes
			} else if ctx.TemporalRelevance > 0.5 {
				relType = Enables
			}

			m3.Graph.AddRelation(
				ctx.Node.ID,
				node.ID,
				relType,
				ctx.Score,
				ctx.Score*ctx.TemporalRelevance,
				lag,
			)
		}
	}

	return node
}

// maintenanceLoop mantenimiento periódico
func (m3 *Module3Full) maintenanceLoop() {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()

	for range ticker.C {
		m3.Graph.DecayAll()
		removed := m3.Graph.Prune()
		_ = removed
	}
}

// M3FullResult resultado completo
type M3FullResult struct {
	Output          []float32
	NodeID          int64
	Concept         string
	Confidence      float64
	ExitLayer       int
	EarlyExited     bool
	ContextNodes    int
	TemporalEventID int64
	Predictions     []*temporal.TemporalPrediction
	SubjectiveTime  time.Duration
	Latency         time.Duration
}

// GetStats estadísticas completas
func (m3 *Module3Full) GetStats() map[string]interface{} {
	m3.mu.RLock()
	defer m3.mu.RUnlock()

	return map[string]interface{}{
		"graph_nodes":    len(m3.Graph.nodes),
		"graph_edges":    m3.Graph.EdgeCount(), // ← USAR MÉTODO PÚBLICO
		"concepts":       len(m3.Graph.conceptIndex),
		"temporal_state": m3.Consciousness.GetState(),
		"queries":        m3.queryCount,
	}
}

// GetCausalChain wrapper
func (m3 *Module3Full) GetCausalChain(nodeID int64, depth int) []CausalPath {
	return m3.Graph.GetCausalChain(nodeID, depth)
}

// GetTemporalState wrapper
func (m3 *Module3Full) GetTemporalState() map[string]interface{} {
	return m3.Consciousness.GetState()
}

// Query expone la funcionalidad de búsqueda del grafo causal
func (m3 *Module3Full) Query(embedding []float32, concept string,
	maxDepth int, window time.Duration) []*QueryResult {
	return m3.Graph.Query(embedding, concept, maxDepth, window)
}

// StoreMemory expone el almacenamiento de memoria
func (m3 *Module3Full) StoreMemory(content []float32, concept string,
	context []TemporalContext, confidence float64) *MemoryNode {
	return m3.storeMemory(content, concept, context, confidence, nil)
}

// GeneratePredictions expone la generación de predicciones
func (m3 *Module3Full) GeneratePredictions(concept string) []*temporal.TemporalPrediction {
	return m3.Consciousness.GeneratePredictions(concept)
}

// RetrieveContext recupera contexto temporal
func (m3 *Module3Full) RetrieveContext(embedding []float32, concept string) []TemporalContext {
	return m3.retrieveTemporalContext(embedding, concept)
}
