package memory

import (
	"context"
	"sync"
	"time"
)

// Module3Integrator conecta CausalGraph con LiquidNN (M1) y EarlyExit (M2)
type Module3Integrator struct {
	graph *CausalGraph

	// Referencias a módulos previos (interfaces mínimas)
	liquidProcessor func(ctx context.Context, input []float32) ([]float32, error)
	earlyExitCheck  func(hiddenState []float32) (shouldExit bool, confidence float64, layer int)

	// Configuración
	config M3Config

	// Estado
	mu         sync.RWMutex
	lastAccess time.Time
	queryCount int64
}

type M3Config struct {
	ContextWindow     time.Duration // Ventana temporal para queries
	MaxContextNodes   int           // Máximo de nodos de contexto
	EnrichmentAlpha   float64       // Peso del contexto (0-1)
	AutoPruneInterval time.Duration
}

// DefaultConfig configuración por defecto
func DefaultConfig() M3Config {
	return M3Config{
		ContextWindow:     time.Hour * 24,
		MaxContextNodes:   10,
		EnrichmentAlpha:   0.3,
		AutoPruneInterval: time.Hour,
	}
}

// NewModule3Integrator crea el integrador
func NewModule3Integrator(
	liquidProc func(ctx context.Context, input []float32) ([]float32, error),
	earlyExit func(hiddenState []float32) (bool, float64, int),
) *Module3Integrator {

	m3 := &Module3Integrator{
		graph:           NewCausalGraph(),
		liquidProcessor: liquidProc,
		earlyExitCheck:  earlyExit,
		config:          DefaultConfig(),
		lastAccess:      time.Now(),
	}

	// Iniciar mantenimiento
	go m3.maintenanceLoop()

	return m3
}

// ProcessInput flujo completo M1 → M2 → M3
func (m3 *Module3Integrator) ProcessInput(ctx context.Context,
	input []float32, conceptHint string) (*M3Result, error) {

	start := time.Now()

	// 1. M3: Recuperar contexto relevante
	contextNodes := m3.retrieveContext(input, conceptHint)

	// 2. M3: Enriquecer input con contexto
	enrichedInput := m3.enrichInput(input, contextNodes)

	// 3. M1: Procesar con LiquidNN
	hiddenState, err := m3.liquidProcessor(ctx, enrichedInput)
	if err != nil {
		return nil, err
	}

	// 4. M2: Decidir si salir temprano
	shouldExit, confidence, exitLayer := m3.earlyExitCheck(hiddenState)

	// 5. M3: Almacenar nueva memoria y enlaces causales
	newNode := m3.storeMemory(hiddenState, conceptHint, contextNodes, confidence)

	// 6. M3: Generar predicciones
	predictions := m3.graph.PredictEffects(newNode.ID)

	// Actualizar métricas
	m3.mu.Lock()
	m3.queryCount++
	m3.lastAccess = time.Now()
	m3.mu.Unlock()

	return &M3Result{
		Output:      hiddenState,
		NodeID:      newNode.ID,
		Concept:     conceptHint,
		Confidence:  confidence,
		ExitLayer:   exitLayer,
		EarlyExited: shouldExit,
		ContextUsed: len(contextNodes),
		Predictions: predictions,
		Latency:     time.Since(start),
	}, nil
}

// retrieveContext recupera contexto relevante
func (m3 *Module3Integrator) retrieveContext(input []float32,
	concept string) []*QueryResult {

	results := m3.graph.Query(input, concept, 2, m3.config.ContextWindow)

	if len(results) > m3.config.MaxContextNodes {
		results = results[:m3.config.MaxContextNodes]
	}

	return results
}

// enrichInput combina input con contexto
func (m3 *Module3Integrator) enrichInput(input []float32,
	context []*QueryResult) []float32 {

	if len(context) == 0 {
		return input
	}

	// Crear vector de contexto promedio ponderado
	contextVec := make([]float32, len(input))
	totalWeight := 0.0

	for _, ctx := range context {
		weight := ctx.Score
		for i := range input {
			if i < len(ctx.Node.Content) {
				contextVec[i] += float32(weight) * ctx.Node.Content[i]
			}
		}
		totalWeight += weight
	}

	// Normalizar contexto
	if totalWeight > 0 {
		for i := range contextVec {
			contextVec[i] /= float32(totalWeight)
		}
	}

	// Combinar: input * (1-alpha) + context * alpha
	enriched := make([]float32, len(input))
	alpha := float32(m3.config.EnrichmentAlpha)
	for i := range input {
		enriched[i] = input[i]*(1-alpha) + contextVec[i]*alpha
	}

	return enriched
}

// storeMemory almacena memoria y crea relaciones causales
func (m3 *Module3Integrator) storeMemory(content []float32, concept string,
	context []*QueryResult, confidence float64) *MemoryNode {

	// Generar ID único (en producción usar UUID o secuencia)
	id := time.Now().UnixNano()

	// Calcular activación base según confianza y novedad
	baseActivation := confidence
	if len(context) > 0 {
		// Si es similar al contexto, heredar algo de su activación
		avgContextActivation := 0.0
		for _, ctx := range context {
			avgContextActivation += ctx.Node.CurrentActivation
		}
		avgContextActivation /= float64(len(context))
		baseActivation = (confidence + avgContextActivation) / 2
	}

	node := m3.graph.AddNode(id, content, concept, baseActivation)

	// Crear relaciones causales con contexto
	for _, ctx := range context {
		if ctx.Score > 0.5 {
			// Determinar tipo de relación
			relType := Related
			lag := time.Since(ctx.Node.AccessedAt)

			if lag < time.Second*5 {
				relType = Causes // Temporalmente cercano = causal
			} else if lag < time.Minute {
				relType = Enables
			}

			// Añadir relación desde contexto hacia nuevo nodo
			m3.graph.AddRelation(
				ctx.Node.ID,
				node.ID,
				relType,
				ctx.Score,
				ctx.Score,
				lag,
			)
		}
	}

	return node
}

// maintenanceLoop mantenimiento periódico
func (m3 *Module3Integrator) maintenanceLoop() {
	ticker := time.NewTicker(m3.config.AutoPruneInterval)
	defer ticker.Stop()

	for range ticker.C {
		m3.graph.DecayAll()
		removed := m3.graph.Prune()
		if removed > 0 {
			// Log o métrica
			_ = removed
		}
	}
}

// M3Result resultado del procesamiento
type M3Result struct {
	Output      []float32
	NodeID      int64
	Concept     string
	Confidence  float64
	ExitLayer   int
	EarlyExited bool
	ContextUsed int
	Predictions []Prediction
	Latency     time.Duration
}

// GetStats estadísticas del sistema
func (m3 *Module3Integrator) GetStats() map[string]interface{} {
	m3.mu.RLock()
	defer m3.mu.RUnlock()

	return map[string]interface{}{
		"total_nodes": len(m3.graph.nodes),
		"total_edges": len(m3.graph.edges),
		"concepts":    len(m3.graph.conceptIndex),
		"queries":     m3.queryCount,
		"last_access": m3.lastAccess,
	}
}

// GetCausalChain wrapper para obtener cadena causal
func (m3 *Module3Integrator) GetCausalChain(nodeID int64, depth int) []CausalPath {
	return m3.graph.GetCausalChain(nodeID, depth)
}

// Query wrapper para consultas directas
func (m3 *Module3Integrator) Query(embedding []float32, concept string,
	maxDepth int, window time.Duration) []*QueryResult {
	return m3.graph.Query(embedding, concept, maxDepth, window)
}
