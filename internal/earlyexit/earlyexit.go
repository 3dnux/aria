package earlyexit

import (
	"fmt"
	"math"
	"sync"
	"time"
)

// Strategy defines the decision algorithm for early exit
type Strategy int

const (
	// StrategyThreshold: Simple confidence threshold (baseline)
	StrategyThreshold Strategy = iota

	// StrategyConsensus: Multiple layers must agree (voting mechanism)
	StrategyConsensus

	// StrategyGradient: Detect confidence plateau or degradation
	StrategyGradient

	// StrategyAdaptive: Combines all signals dynamically (recommended)
	StrategyAdaptive
)

func (s Strategy) String() string {
	switch s {
	case StrategyThreshold:
		return "Threshold"
	case StrategyConsensus:
		return "Consensus"
	case StrategyGradient:
		return "Gradient"
	case StrategyAdaptive:
		return "Adaptive"
	default:
		return "Unknown"
	}
}

// Vote represents a single layer's "vote" on confidence
type Vote struct {
	LayerID    int
	Confidence float64
	Gradient   float64 // Rate of change from previous
	Timestamp  time.Time
	LayerType  string // Input, Processing, Attention, etc.
}

// Statistics holds runtime metrics
type Statistics struct {
	TotalVotes      int
	AvgConfidence   float64
	MaxConfidence   float64
	MinConfidence   float64
	StrategyUsed    Strategy
	ExitReason      string
	ConvergenceRate float64 // How fast confidence grew
}

// Gated is the main early exit controller (Module 2)
type Gated struct {
	strategy     Strategy
	threshold    float64 // Base confidence threshold (0.0 - 1.0)
	windowSize   int     // Lookback window for consensus
	minConsensus int     // Minimum agreeing votes in window
	minGradient  float64 // Minimum improvement to continue

	// State
	mu           sync.RWMutex
	history      []Vote
	startTime    time.Time
	layerWeights map[string]float64 // Weight by layer type

	// Adaptive tuning
	adaptiveThreshold float64 // Dynamically adjusted
}

// NewGated creates a robust early exit controller
func NewGated(strategy Strategy) *Gated {
	g := &Gated{
		strategy:          strategy,
		threshold:         0.88,
		windowSize:        3,
		minConsensus:      2,
		minGradient:       0.02,
		history:           make([]Vote, 0),
		startTime:         time.Now(),
		adaptiveThreshold: 0.88,

		// Layer type weights (some layers contribute more to confidence)
		layerWeights: map[string]float64{
			"Input":      0.8,
			"Processing": 1.0,
			"Attention":  1.2,
			"Reasoning":  1.5, // Reasoning layers boost confidence more
			"Output":     0.9,
		},
	}

	return g
}

// Reset clears state for new query (thread-safe)
func (g *Gated) Reset() {
	g.mu.Lock()
	defer g.mu.Unlock()

	g.history = make([]Vote, 0)
	g.startTime = time.Now()
	g.adaptiveThreshold = g.threshold // Reset adaptive threshold
}

// RegisterVote records a layer's confidence vote (thread-safe)
func (g *Gated) RegisterVote(layerID int, confidence float64, layerType string) {
	g.mu.Lock()
	defer g.mu.Unlock()

	vote := Vote{
		LayerID:    layerID,
		Confidence: confidence,
		Timestamp:  time.Now(),
		LayerType:  layerType,
	}

	// Calculate gradient if we have history
	if len(g.history) > 0 {
		prev := g.history[len(g.history)-1]
		vote.Gradient = confidence - prev.Confidence
	}

	g.history = append(g.history, vote)

	// Update adaptive threshold based on history
	g.updateAdaptiveThreshold()
}

// ShouldExit determines if processing should stop (main API)
func (g *Gated) ShouldExit(currentLayer int, currentConfidence float64, minLayers int, complexityLevel string) (bool, string, *Statistics) {
	// Safety: Always process minimum layers
	if currentLayer < minLayers {
		return false, "minimum_layers_not_met", nil
	}

	g.mu.RLock()
	defer g.mu.RUnlock()

	// Calculate current statistics
	stats := g.calculateStats()

	var exit bool
	var reason string

	// Route to appropriate strategy
	switch g.strategy {
	case StrategyThreshold:
		exit, reason = g.checkThreshold(currentConfidence, complexityLevel)
	case StrategyConsensus:
		exit, reason = g.checkConsensus()
	case StrategyGradient:
		exit, reason = g.checkGradient()
	case StrategyAdaptive:
		exit, reason = g.checkAdaptive(currentConfidence, complexityLevel)
	default:
		exit, reason = g.checkThreshold(currentConfidence, complexityLevel)
	}

	if exit {
		stats.ExitReason = reason
		stats.StrategyUsed = g.strategy
	}

	return exit, reason, stats
}

// checkThreshold: Basic threshold with complexity adjustments
func (g *Gated) checkThreshold(confidence float64, complexityLevel string) (bool, string) {
	threshold := g.adaptiveThreshold

	// Adjust threshold based on complexity
	switch complexityLevel {
	case "Low":
		threshold = 0.82 // Easier to exit for simple queries
	case "Medium":
		threshold = 0.88
	case "High":
		threshold = 0.91
	case "Critical":
		threshold = 0.94 // Harder to exit for complex queries
	}

	// Apply layer type weighting (check last vote)
	if len(g.history) > 0 {
		lastVote := g.history[len(g.history)-1]
		if weight, ok := g.layerWeights[lastVote.LayerType]; ok {
			// Weighted confidence = raw * weight factor
			weightedConf := confidence * weight
			if weightedConf >= threshold {
				return true, fmt.Sprintf("weighted_threshold_reached (%.2f >= %.2f)", weightedConf, threshold)
			}
		}
	}

	if confidence >= threshold {
		return true, fmt.Sprintf("threshold_reached (%.2f >= %.2f)", confidence, threshold)
	}

	return false, fmt.Sprintf("below_threshold (%.2f < %.2f)", confidence, threshold)
}

// checkConsensus: Sliding window voting mechanism
func (g *Gated) checkConsensus() (bool, string) {
	if len(g.history) < g.windowSize {
		return false, "insufficient_history_for_consensus"
	}

	// Get last N votes
	start := len(g.history) - g.windowSize
	recent := g.history[start:]

	// Count votes above threshold
	aboveThreshold := 0
	totalWeight := 0.0
	weightedSum := 0.0

	for _, vote := range recent {
		weight := 1.0
		if w, ok := g.layerWeights[vote.LayerType]; ok {
			weight = w
		}

		totalWeight += weight
		if vote.Confidence >= g.adaptiveThreshold {
			weightedSum += weight
			aboveThreshold++
		}
	}

	// Check consensus percentage
	consensusRatio := weightedSum / totalWeight
	if consensusRatio >= float64(g.minConsensus)/float64(g.windowSize) {
		return true, fmt.Sprintf("consensus_reached (%d/%d layers, %.0f%% weighted)",
			aboveThreshold, g.windowSize, consensusRatio*100)
	}

	return false, fmt.Sprintf("no_consensus (%d/%d layers)", aboveThreshold, g.windowSize)
}

// checkGradient: Detect confidence stagnation or degradation
func (g *Gated) checkGradient() (bool, string) {
	if len(g.history) < g.windowSize {
		return false, "insufficient_history_for_gradient"
	}

	// Get recent gradients
	start := len(g.history) - g.windowSize
	recent := g.history[start:]

	avgGradient := 0.0
	positiveGradients := 0

	for _, vote := range recent {
		avgGradient += vote.Gradient
		if vote.Gradient > 0 {
			positiveGradients++
		}
	}
	avgGradient /= float64(len(recent))

	currentConf := recent[len(recent)-1].Confidence

	// Scenario 1: Plateau (confidence stopped improving)
	if math.Abs(avgGradient) < g.minGradient && currentConf > 0.75 {
		return true, fmt.Sprintf("confidence_plateau (avg_gradient: %.4f)", avgGradient)
	}

	// Scenario 2: Degradation (getting worse - overthinking)
	if avgGradient < -0.05 {
		return true, fmt.Sprintf("confidence_degradation (avg_gradient: %.4f)", avgGradient)
	}

	// Scenario 3: Stalled growth (diminishing returns)
	if positiveGradients < g.minConsensus && len(recent) >= g.windowSize {
		return true, fmt.Sprintf("diminishing_returns (only %d positive gradients)", positiveGradients)
	}

	return false, fmt.Sprintf("gradient_positive (avg: %.4f)", avgGradient)
}

// checkAdaptive: Combines all strategies with dynamic weighting
func (g *Gated) checkAdaptive(currentConfidence float64, complexityLevel string) (bool, string) {
	// Signal 1: High confidence override (>0.95)
	if currentConfidence >= 0.95 {
		return true, "adaptive_high_confidence_override"
	}

	// Signal 2: Fast convergence (recent rapid improvement)
	if len(g.history) >= 3 {
		last3 := g.history[len(g.history)-3:]
		totalGain := last3[2].Confidence - last3[0].Confidence
		if totalGain > 0.25 && currentConfidence > 0.80 {
			return true, "adaptive_fast_convergence"
		}
	}

	// Signal 3: Consensus in window
	if len(g.history) >= g.windowSize {
		consensus, _ := g.checkConsensus()
		if consensus {
			return true, "adaptive_window_consensus"
		}
	}

	// Signal 4: Time-based (if taking too long, lower threshold)
	elapsed := time.Since(g.startTime)
	if elapsed > 5*time.Second && currentConfidence > 0.85 {
		return true, "adaptive_time_based"
	}

	// Signal 5: Complexity-specific logic
	switch complexityLevel {
	case "Low":
		// For simple queries, be more aggressive
		if currentConfidence >= 0.80 && len(g.history) >= 2 {
			return true, "adaptive_low_complexity_fast_exit"
		}
	case "Critical":
		// For critical queries, require consensus or very high confidence
		if currentConfidence >= 0.93 {
			consensus, _ := g.checkConsensus()
			if consensus {
				return true, "adaptive_critical_with_consensus"
			}
		}
	}

	return false, "adaptive_waiting_more_data"
}

// updateAdaptiveThreshold dynamically adjusts threshold based on history
func (g *Gated) updateAdaptiveThreshold() {
	if len(g.history) < 2 {
		return
	}

	// If confidence is oscillating, raise threshold slightly
	if len(g.history) >= 3 {
		last3 := g.history[len(g.history)-3:]
		if (last3[1].Confidence > last3[0].Confidence && last3[2].Confidence < last3[1].Confidence) ||
			(last3[1].Confidence < last3[0].Confidence && last3[2].Confidence > last3[1].Confidence) {
			g.adaptiveThreshold = math.Min(g.adaptiveThreshold+0.02, 0.95)
		}
	}

	// If steady improvement, can afford to be slightly more lenient
	if len(g.history) >= 2 {
		last := g.history[len(g.history)-1]
		if last.Gradient > 0.1 && last.Confidence > 0.7 {
			g.adaptiveThreshold = math.Max(g.adaptiveThreshold-0.01, 0.80)
		}
	}
}

// calculateStats computes current runtime statistics
func (g *Gated) calculateStats() *Statistics {
	if len(g.history) == 0 {
		return &Statistics{TotalVotes: 0}
	}

	sum := 0.0
	max := 0.0
	min := 1.0

	for _, v := range g.history {
		sum += v.Confidence
		if v.Confidence > max {
			max = v.Confidence
		}
		if v.Confidence < min {
			min = v.Confidence
		}
	}

	avg := sum / float64(len(g.history))

	// Calculate convergence rate (slope of confidence over time)
	convergence := 0.0
	if len(g.history) >= 2 {
		first := g.history[0].Confidence
		last := g.history[len(g.history)-1].Confidence
		layers := float64(len(g.history))
		convergence = (last - first) / layers
	}

	return &Statistics{
		TotalVotes:      len(g.history),
		AvgConfidence:   avg,
		MaxConfidence:   max,
		MinConfidence:   min,
		StrategyUsed:    g.strategy,
		ConvergenceRate: convergence,
	}
}

// GetHistory returns a copy of vote history (for debugging)
func (g *Gated) GetHistory() []Vote {
	g.mu.RLock()
	defer g.mu.RUnlock()

	result := make([]Vote, len(g.history))
	copy(result, g.history)
	return result
}

// SetThreshold allows runtime threshold adjustment
func (g *Gated) SetThreshold(threshold float64) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.threshold = threshold
	g.adaptiveThreshold = threshold
}
