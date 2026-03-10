package memory

import (
	"fmt"
	"math"
	"sort"
	"time"
)

// RelationType tipos de relaciones causales
type RelationType int

const (
	Causes   RelationType = iota // A causa B
	Implies                      // A implica B
	Prevents                     // A previene B
	Enables                      // A habilita B
	Related                      // Relación débil
	PartOf                       // Jerárquica
	Opposes                      // Contraria
)

func (r RelationType) String() string {
	names := []string{"CAUSES", "IMPLIES", "PREVENTS", "ENABLES", "RELATED", "PART_OF", "OPPOSES"}
	if int(r) < len(names) {
		return names[r]
	}
	return "UNKNOWN"
}

// CausalWeight peso de relación con temporalidad
type CausalWeight struct {
	Strength   float64 // 0.0 - 1.0
	Confidence float64 // 0.0 - 1.0
	CreatedAt  time.Time
	HalfLife   float64 // Días para decaimiento 50%
}

// CurrentStrength calcula fuerza actual con decaimiento temporal
func (w CausalWeight) CurrentStrength() float64 {
	ageHours := time.Since(w.CreatedAt).Hours()
	ageDays := ageHours / 24.0

	// Ley de decaimiento: S = S0 * e^(-λt) donde λ = ln(2)/t_half
	decayRate := math.Ln2 / w.HalfLife
	decay := math.Exp(-decayRate * ageDays)

	return w.Strength * decay * w.Confidence
}

// MemoryNode nodo en el grafo causal
type MemoryNode struct {
	ID       int64
	Content  []float32 // Embedding
	Concept  string
	Metadata map[string]interface{}

	// Temporal consciousness
	CreatedAt   time.Time
	AccessedAt  time.Time
	AccessCount int

	// Activation dynamics
	BaseActivation    float64 // Importancia inherente
	CurrentActivation float64 // Activación actual con decay
	DecayRate         float64 // λ personalizado por nodo

	// Grafo
	Incoming map[int64]*CausalEdge // Desde otro nodo
	Outgoing map[int64]*CausalEdge // Hacia otro nodo
}

// CausalEdge arista con peso temporal
type CausalEdge struct {
	From   int64
	To     int64
	Type   RelationType
	Weight CausalWeight

	// Contexto temporal específico
	TemporalContext time.Duration // Lag típico entre causa y efecto
	ContextWindow   time.Duration // Ventana de validez
}

// CausalGraph grafo de memoria causal
type CausalGraph struct {
	nodes map[int64]*MemoryNode
	edges map[string]*CausalEdge // key: "from:to"

	// Índices
	conceptIndex map[string][]int64
	timeIndex    *TimeIndex

	// Configuración
	DefaultHalfLife float64 // Días
	MaxNodes        int
	PruneThreshold  float64

	// UMBRALES OPTIMIZADOS
	SimilarityThreshold float64 // Umbral para considerar similitud
	MinEdgeStrength     float64 // Fuerza mínima para considerar arista válida
}

// TimeIndex índice temporal para búsquedas eficientes
type TimeIndex struct {
	slots map[int64][]int64 // bucket hora -> IDs
}

// NewCausalGraph constructor con valores optimizados
func NewCausalGraph() *CausalGraph {
	return &CausalGraph{
		nodes:           make(map[int64]*MemoryNode),
		edges:           make(map[string]*CausalEdge),
		conceptIndex:    make(map[string][]int64),
		timeIndex:       &TimeIndex{slots: make(map[int64][]int64)},
		DefaultHalfLife: 7.0, // 7 días por defecto
		MaxNodes:        10000,
		PruneThreshold:  0.05, // Más permisivo (era 0.1)

		// UMBRALES OPTIMIZADOS
		SimilarityThreshold: 0.4,  // Bajado de 0.7 para más matches
		MinEdgeStrength:     0.05, // Bajado de 0.3 para más cadenas causales
	}
}

// AddNode añade nuevo nodo de memoria
func (cg *CausalGraph) AddNode(id int64, content []float32, concept string,
	baseActivation float64) *MemoryNode {

	now := time.Now()

	// Asegurar activación mínima para que no muera inmediatamente
	if baseActivation < 0.3 {
		baseActivation = 0.3
	}

	node := &MemoryNode{
		ID:                id,
		Content:           content,
		Concept:           concept,
		CreatedAt:         now,
		AccessedAt:        now,
		BaseActivation:    baseActivation,
		CurrentActivation: baseActivation,
		DecayRate:         math.Ln2 / (baseActivation * 30), // Mayor importancia = más lento
		Incoming:          make(map[int64]*CausalEdge),
		Outgoing:          make(map[int64]*CausalEdge),
		Metadata:          make(map[string]interface{}),
	}

	cg.nodes[id] = node
	cg.conceptIndex[concept] = append(cg.conceptIndex[concept], id)

	// Indexar temporalmente
	bucket := now.Unix() / 3600
	cg.timeIndex.slots[bucket] = append(cg.timeIndex.slots[bucket], id)

	return node
}

// AddRelation añade relación causal entre nodos
func (cg *CausalGraph) AddRelation(from, to int64, relType RelationType,
	strength, confidence float64, typicalLag time.Duration) error {

	fromNode, ok := cg.nodes[from]
	if !ok {
		return fmt.Errorf("nodo origen %d no existe", from)
	}

	toNode, ok := cg.nodes[to]
	if !ok {
		return fmt.Errorf("nodo destino %d no existe", to)
	}

	// Asegurar valores mínimos para que la arista sea útil
	if strength < 0.1 {
		strength = 0.1
	}
	if confidence < 0.1 {
		confidence = 0.1
	}

	edge := &CausalEdge{
		From:            from,
		To:              to,
		Type:            relType,
		TemporalContext: typicalLag,
		ContextWindow:   typicalLag * 2, // Ventana 2x el lag típico
		Weight: CausalWeight{
			Strength:   strength,
			Confidence: confidence,
			CreatedAt:  time.Now(),
			HalfLife:   cg.DefaultHalfLife * (1 + confidence), // Mayor confianza = más persistente
		},
	}

	key := fmt.Sprintf("%d:%d", from, to)
	cg.edges[key] = edge
	fromNode.Outgoing[to] = edge
	toNode.Incoming[from] = edge

	return nil
}

// Query consulta con spreading activation temporal
func (cg *CausalGraph) Query(embedding []float32, concept string,
	maxDepth int, temporalWindow time.Duration) []*QueryResult {

	// 1. Activar nodos semánticamente similares
	activated := cg.activateBySimilarity(embedding, concept)

	// 2. Spreading activation por el grafo
	cg.spreadActivation(activated, maxDepth)

	// 3. Filtrar por relevancia temporal y calcular scores
	now := time.Now()
	var results []*QueryResult

	for id, activation := range activated {
		node := cg.nodes[id]

		// Calcular fuerza temporal actual
		temporalScore := cg.calculateTemporalScore(node, now, temporalWindow)

		// Score combinado
		finalScore := activation * temporalScore * node.BaseActivation

		if finalScore > cg.PruneThreshold {
			results = append(results, &QueryResult{
				Node:       node,
				Score:      finalScore,
				Activation: activation,
				Temporal:   temporalScore,
			})

			// Reforzar nodo accedido (plasticidad)
			node.AccessedAt = now
			node.AccessCount++
			node.CurrentActivation = math.Min(1.0, node.CurrentActivation+0.1)
		}
	}

	// Ordenar por score
	sort.Slice(results, func(i, j int) bool {
		return results[i].Score > results[j].Score
	})

	return results
}

// QueryResult resultado de búsqueda
type QueryResult struct {
	Node       *MemoryNode
	Score      float64
	Activation float64
	Temporal   float64
}

// activateBySimilarity activa nodos por similitud
func (cg *CausalGraph) activateBySimilarity(embedding []float32,
	concept string) map[int64]float64 {

	activated := make(map[int64]float64)

	// Si hay concepto específico, activar esos nodos primero
	if concept != "" {
		for _, id := range cg.conceptIndex[concept] {
			activated[id] = 1.0
		}
	}

	// Activar por similitud de embedding
	for id, node := range cg.nodes {
		if len(node.Content) == 0 || len(embedding) == 0 {
			continue
		}

		sim := cosineSimilarity(embedding, node.Content)
		if sim > cg.SimilarityThreshold { // ← USAR UMBRAL CONFIGURABLE
			// Combinar con activación existente
			if existing, ok := activated[id]; ok {
				activated[id] = math.Max(existing, sim*node.CurrentActivation)
			} else {
				activated[id] = sim * node.CurrentActivation
			}
		}
	}

	return activated
}

// spreadActivation propaga activación por el grafo causal
func (cg *CausalGraph) spreadActivation(activated map[int64]float64, depth int) {
	if depth <= 0 {
		return
	}

	newActivation := make(map[int64]float64)

	for id, act := range activated {
		node := cg.nodes[id]

		// Propagar a outgoing (efectos)
		for _, edge := range node.Outgoing {
			currentStrength := edge.Weight.CurrentStrength()
			propagated := act * currentStrength * 0.6 // Decay por salto

			if existing, ok := activated[edge.To]; ok {
				newActivation[edge.To] = math.Max(existing, propagated)
			} else {
				newActivation[edge.To] = propagated
			}
		}

		// Propagar a incoming (causas) con menor peso
		for _, edge := range node.Incoming {
			currentStrength := edge.Weight.CurrentStrength()
			propagated := act * currentStrength * 0.3 // Menor peso hacia atrás

			if existing, ok := activated[edge.From]; ok {
				newActivation[edge.From] = math.Max(existing, propagated)
			} else {
				newActivation[edge.From] = propagated
			}
		}
	}

	// Merge nuevas activaciones
	for id, act := range newActivation {
		if existing, ok := activated[id]; ok {
			activated[id] = math.Max(existing, act)
		} else {
			activated[id] = act
		}
	}

	cg.spreadActivation(activated, depth-1)
}

// calculateTemporalScore calcula relevancia temporal
func (cg *CausalGraph) calculateTemporalScore(node *MemoryNode,
	now time.Time, window time.Duration) float64 {

	// Decay desde creación
	age := now.Sub(node.CreatedAt)
	creationDecay := math.Exp(-float64(age.Hours()) * node.DecayRate)

	// Recency boost (más fuerte)
	recency := now.Sub(node.AccessedAt)
	recencyBoost := math.Exp(-float64(recency) / float64(window/2)) // Ventana más corta

	// Frecuencia de acceso (más peso)
	freqBoost := math.Log1p(float64(node.AccessCount)) / 5.0 // Factor más grande

	return (creationDecay + recencyBoost + freqBoost) / 3.0
}

// DecayAll aplica decaimiento temporal a todas las memorias
func (cg *CausalGraph) DecayAll() {
	now := time.Now()

	for _, node := range cg.nodes {
		// Decay exponencial de activación
		ageHours := now.Sub(node.AccessedAt).Hours()
		node.CurrentActivation = node.BaseActivation *
			math.Exp(-ageHours*node.DecayRate)
	}

	// Decay de aristas (implícito en CurrentStrength())
}

// Prune elimina nodos débiles
func (cg *CausalGraph) Prune() int {
	removed := 0

	for id, node := range cg.nodes {
		if node.CurrentActivation < cg.PruneThreshold &&
			node.BaseActivation < 0.5 &&
			time.Since(node.AccessedAt) > time.Hour*24 {

			// Eliminar aristas
			for to := range node.Outgoing {
				key := fmt.Sprintf("%d:%d", id, to)
				delete(cg.edges, key)
			}
			for from := range node.Incoming {
				key := fmt.Sprintf("%d:%d", from, id)
				delete(cg.edges, key)
				delete(cg.nodes[from].Outgoing, id)
			}

			delete(cg.nodes, id)
			removed++
		}
	}

	return removed
}

// GetCausalChain traza cadena causal hacia atrás
func (cg *CausalGraph) GetCausalChain(effectID int64, maxDepth int) []CausalPath {
	var paths []CausalPath

	var trace func(current int64, depth int, currentPath []CausalStep)
	trace = func(current int64, depth int, currentPath []CausalStep) {
		if depth <= 0 {
			if len(currentPath) > 0 {
				paths = append(paths, CausalPath{
					Steps:           currentPath,
					FinalActivation: cg.nodes[current].CurrentActivation,
				})
			}
			return
		}

		node := cg.nodes[current]
		for fromID, edge := range node.Incoming {
			// ← USAR UMBRAL CONFIGURABLE MÁS BAJO
			if edge.Weight.CurrentStrength() > cg.MinEdgeStrength {
				step := CausalStep{
					From:     fromID,
					To:       current,
					Type:     edge.Type,
					Strength: edge.Weight.CurrentStrength(),
					TimeLag:  edge.TemporalContext,
				}
				newPath := append(currentPath, step)
				trace(fromID, depth-1, newPath)
			}
		}
	}

	trace(effectID, maxDepth, []CausalStep{})
	return paths
}

// CausalStep paso en cadena causal
type CausalStep struct {
	From     int64
	To       int64
	Type     RelationType
	Strength float64
	TimeLag  time.Duration
}

// CausalPath camino causal completo
type CausalPath struct {
	Steps           []CausalStep
	FinalActivation float64
}

// PredictEffects predice efectos probables de un nodo
func (cg *CausalGraph) PredictEffects(causeID int64) []Prediction {
	node := cg.nodes[causeID]
	var predictions []Prediction

	for toID, edge := range node.Outgoing {
		strength := edge.Weight.CurrentStrength()
		// ← UMBRAL MÁS BAJO PARA MÁS PREDICCIONES
		if strength > 0.1 {
			predictions = append(predictions, Prediction{
				EffectID:    toID,
				Concept:     cg.nodes[toID].Concept,
				Probability: strength,
				ExpectedLag: edge.TemporalContext,
				Relation:    edge.Type,
			})
		}
	}

	// Ordenar por probabilidad
	sort.Slice(predictions, func(i, j int) bool {
		return predictions[i].Probability > predictions[j].Probability
	})

	return predictions
}

// Prediction predicción causal
type Prediction struct {
	EffectID    int64
	Concept     string
	Probability float64
	ExpectedLag time.Duration
	Relation    RelationType
}

// cosineSimilarity calcula similitud coseno
func cosineSimilarity(a, b []float32) float64 {
	if len(a) != len(b) {
		return 0
	}

	var dot, normA, normB float64
	for i := range a {
		dot += float64(a[i]) * float64(b[i])
		normA += float64(a[i]) * float64(a[i])
		normB += float64(b[i]) * float64(b[i])
	}

	if normA == 0 || normB == 0 {
		return 0
	}

	return dot / (math.Sqrt(normA) * math.Sqrt(normB))
}

// EdgeCount devuelve número de aristas (para testing)
func (cg *CausalGraph) EdgeCount() int {
	return len(cg.edges)
}
