package liquid

import (
	"aria/internal/earlyexit"
	"context"
	"fmt"
	"sync"
)

// Controller controla la arquitectura líquida
type Controller struct {
	analyzer     *AdvancedAnalyzer
	earlyExit    *earlyexit.Gated
	maxLayers    int
	activeLayers []*Layer
	mu           sync.RWMutex
}

// NewController crea nuevo controlador
func NewController(strategy earlyexit.Strategy) *Controller {
	return &Controller{
		analyzer:  NewAnalyzer(),
		earlyExit: earlyexit.NewGated(strategy), // ← Usa el parámetro
		maxLayers: 32,
	}
}

// ProcessRequest procesa un request
func (c *Controller) ProcessRequest(ctx context.Context, query string) (*LiquidResult, error) {
	c.earlyExit.Reset()

	analysis := c.analyzer.Analyze(query)

	c.mu.Lock()
	c.activeLayers = c.buildLayers(analysis.AssignedLayers)
	c.mu.Unlock()

	result := &LiquidResult{
		Topology:     analysis,
		LayerResults: make([]LayerResult, 0),
	}

	prevConfidence := 0.35
	minLayers := c.calculateMinLayers(analysis)

	for i, layer := range c.activeLayers {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		default:
		}

		// CORREGIDO: Llamada con índices para cálculos internos si los necesita layer
		layerResult, err := c.processLayer(ctx, layer, query, prevConfidence, i, len(c.activeLayers))
		if err != nil {
			return nil, fmt.Errorf("capa %d: %w", i, err)
		}

		// Registrar voto en Module 2
		c.earlyExit.RegisterVote(i, layerResult.Confidence, layerResult.LayerType.String())

		result.LayerResults = append(result.LayerResults, *layerResult)
		result.FinalConfidence = layerResult.Confidence
		result.LayersUsed = i + 1
		prevConfidence = layerResult.Confidence

		// Module 2: Check early exit
		shouldExit, reason, stats := c.earlyExit.ShouldExit(
			i,
			layerResult.Confidence,
			minLayers,
			analysis.Level.String(),
		)

		if shouldExit {
			result.EarlyExited = true
			result.ExitReason = reason
			result.ExitStats = stats
			break
		}

		query = layerResult.EnhancedQuery
	}

	if !result.EarlyExited {
		_, _, stats := c.earlyExit.ShouldExit(
			len(result.LayerResults)-1,
			result.FinalConfidence,
			minLayers,
			analysis.Level.String(),
		)
		result.ExitStats = stats
		result.ExitReason = "completed_all_layers"
	}

	return result, nil
}

// buildLayers construye las capas
func (c *Controller) buildLayers(count int) []*Layer {
	layers := make([]*Layer, count)

	layers[0] = NewLayer(1, InputLayer)

	for i := 1; i < count-1; i++ {
		var layerType LayerType
		switch i % 3 {
		case 0:
			layerType = ProcessingLayer
		case 1:
			layerType = AttentionLayer
		case 2:
			layerType = ReasoningLayer
		}
		layers[i] = NewLayer(i+1, layerType)
	}

	if count > 1 {
		layers[count-1] = NewLayer(count, OutputLayer)
	}

	return layers
}

// processLayer procesa una capa individual
// CORREGIDO: Firma completa con índices
func (c *Controller) processLayer(ctx context.Context, layer *Layer, query string, prevConfidence float64, layerIndex int, totalLayers int) (*LayerResult, error) {
	// Si Layer.Process necesita los índices, pásalos. Si no, ignóralos aquí.
	output, err := layer.Process(ctx, query, prevConfidence, layerIndex, totalLayers)
	if err != nil {
		return nil, err
	}

	return &LayerResult{
		LayerID:        layer.ID,
		LayerType:      layer.Type,
		Confidence:     output.Confidence,
		EnhancedQuery:  output.Output,
		ProcessingTime: output.ProcessingTime,
	}, nil
}

// calculateMinLayers calcula mínimo de capas según complejidad
func (c *Controller) calculateMinLayers(analysis TopologyAnalysis) int {
	switch analysis.Level {
	case Low:
		return analysis.AssignedLayers / 2
	case Medium:
		return analysis.AssignedLayers * 2 / 3
	case High:
		return analysis.AssignedLayers * 3 / 4
	case Critical:
		return analysis.AssignedLayers * 4 / 5
	default:
		return analysis.AssignedLayers / 2
	}
}
