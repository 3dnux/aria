package liquid

import (
	"context"
	"fmt"
	"time"
)

// LayerType tipo de capa
type LayerType int

const (
	InputLayer LayerType = iota
	ProcessingLayer
	AttentionLayer
	ReasoningLayer
	OutputLayer
)

func (lt LayerType) String() string {
	names := []string{"Input", "Processing", "Attention", "Reasoning", "Output"}
	if int(lt) < len(names) {
		return names[lt]
	}
	return "Unknown"
}

// Layer capa individual de procesamiento
type Layer struct {
	ID         int
	Type       LayerType // Campo Type agregado
	Confidence float64
	Active     bool
	Processed  bool
}

// NewLayer crea nueva capa
func NewLayer(id int, layerType LayerType) *Layer {
	return &Layer{
		ID:         id,
		Type:       layerType,
		Confidence: 0.0,
		Active:     true,
		Processed:  false,
	}
}

// LayerOutput resultado del procesamiento
type LayerOutput struct {
	LayerID        int
	Type           LayerType
	Output         string
	Confidence     float64
	ProcessingTime time.Duration
}

func (l *Layer) Process(ctx context.Context, input string, prevConfidence float64, layerIndex int, totalLayers int) (*LayerOutput, error) {
	if !l.Active {
		return nil, fmt.Errorf("capa %d inactiva", l.ID)
	}

	start := time.Now()

	// Incremento base según tipo
	var baseIncrement float64
	switch l.Type {
	case InputLayer:
		baseIncrement = 0.12
	case ProcessingLayer:
		baseIncrement = 0.10
	case AttentionLayer:
		baseIncrement = 0.08
	case ReasoningLayer:
		baseIncrement = 0.12
	case OutputLayer:
		baseIncrement = 0.06
	}

	// Bonus por posición (aprendizaje rápido al principio)
	positionBonus := 0.0
	if layerIndex == 0 {
		positionBonus = 0.10 // Primera capa: boost grande
	} else if layerIndex == 1 {
		positionBonus = 0.06 // Segunda capa: boost medio
	}

	// Penalización si vamos muy lento (para forzar early exit en queries simples)
	if layerIndex > 2 && prevConfidence < 0.5 {
		baseIncrement += 0.05 // Acelerar si vamos lento
	}

	increment := baseIncrement + positionBonus
	confidence := prevConfidence + increment

	if confidence > 0.98 {
		confidence = 0.98
	}

	l.Confidence = confidence
	l.Processed = true

	return &LayerOutput{
		LayerID:        l.ID,
		Type:           l.Type,
		Output:         fmt.Sprintf("[L%d] %s", l.ID, input),
		Confidence:     confidence,
		ProcessingTime: time.Since(start),
	}, nil
}
func truncate(s string, maxLen int) string {
	if len(s) <= maxLen {
		return s
	}
	return s[:maxLen] + "..."
}
