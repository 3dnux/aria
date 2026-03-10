package liquid

import (
	"aria/internal/earlyexit"
	"time"
)

type LayerResult struct {
	LayerID        int
	LayerType      LayerType
	Confidence     float64
	EnhancedQuery  string
	ProcessingTime time.Duration
}

type LiquidResult struct {
	Topology        TopologyAnalysis
	LayerResults    []LayerResult
	FinalConfidence float64
	LayersUsed      int
	EarlyExited     bool
	ExitReason      string
	ExitStats       *earlyexit.Statistics
	TotalTime       time.Duration
}
