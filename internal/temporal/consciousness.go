package temporal

import (
	"math"
	"sync"
	"time"
)

// TemporalConsciousness sistema de percepción y razonamiento temporal
type TemporalConsciousness struct {
	mu sync.RWMutex

	// Tiempo "ahora" (puede ser simulado)
	Now time.Time

	// Percepción subjetiva
	perception *TimePerception

	// Atención temporal
	attention *TemporalAttention

	// Razonamiento
	reasoning *TemporalReasoning

	// Estado interno
	eventHistory []TemporalEvent
	lastUpdate   time.Time
}

// TimePerception representa cómo el sistema "siente" el tiempo
type TimePerception struct {
	// Escala subjetiva: 1.0 = normal, >1 = tiempo pasa más rápido, <1 = más lento
	SubjectivityScale float64

	// Densidad de eventos: más eventos = tiempo "más lento" (más denso)
	EventDensity float64

	// Ritmos circadianos simulados (0-2π)
	CircadianPhase float64
	CyclePeriod    time.Duration

	// Último evento registrado
	LastEventTime time.Time

	// Historial de densidad para suavizado
	densityHistory []float64
}

// TemporalAttention atención focalizada en el tiempo
type TemporalAttention struct {
	// Ventana de atención actual
	FocusCenter time.Time
	FocusWidth  time.Duration

	// Intensidad de atención (0-1)
	Intensity float64

	// Drift natural (hacia dónde tiende a moverse la atención)
	DriftVelocity time.Duration // por segundo real

	// Historial de focos
	focusHistory []FocusPoint
}

type FocusPoint struct {
	Timestamp time.Time
	Center    time.Time
	Intensity float64
}

// TemporalReasoning razonamiento sobre causas y efectos temporales
type TemporalReasoning struct {
	// Reglas causales aprendidas
	CausalRules []CausalRule

	// Patrones de secuencia
	SequencePatterns map[string]*SequencePattern

	// Predicciones activas
	ActivePredictions map[int64]*TemporalPrediction
	predictionCounter int64
}

// CausalRule regla causal aprendida
type CausalRule struct {
	Cause       string
	Effect      string
	Confidence  float64
	TypicalLag  time.Duration
	LagVariance time.Duration
	Occurrences int
	LastSeen    time.Time
}

// SequencePattern patrón de secuencia temporal
type SequencePattern struct {
	Sequence        []string // [A, B, C] significa A→B→C
	Frequency       float64
	LastMatched     time.Time
	TypicalDuration time.Duration
}

// TemporalPrediction predicción temporal activa
type TemporalPrediction struct {
	ID             int64
	ExpectedEvent  string
	ExpectedTime   time.Time
	Confidence     float64
	BasedOnRule    *CausalRule
	BasedOnPattern *SequencePattern
	Triggered      bool
	Cancelled      bool
}

// TemporalEvent evento con marca temporal
type TemporalEvent struct {
	ID        int64
	Concept   string
	Timestamp time.Time
	Duration  time.Duration
	Intensity float64 // Qué tan "intenso" fue el evento
	Metadata  map[string]interface{}
}

// NewTemporalConsciousness constructor
func NewTemporalConsciousness() *TemporalConsciousness {
	now := time.Now()

	return &TemporalConsciousness{
		Now:        now,
		lastUpdate: now,
		perception: &TimePerception{
			SubjectivityScale: 1.0,
			EventDensity:      1.0,
			CircadianPhase:    0,
			CyclePeriod:       time.Hour * 24,
			LastEventTime:     now,
			densityHistory:    make([]float64, 0, 100),
		},
		attention: &TemporalAttention{
			FocusCenter:   now,
			FocusWidth:    time.Hour,
			Intensity:     0.5,
			DriftVelocity: 0,
			focusHistory:  make([]FocusPoint, 0, 1000),
		},
		reasoning: &TemporalReasoning{
			CausalRules:       make([]CausalRule, 0),
			SequencePatterns:  make(map[string]*SequencePattern),
			ActivePredictions: make(map[int64]*TemporalPrediction),
		},
		eventHistory: make([]TemporalEvent, 0, 10000),
	}
}

// RegisterEvent registra un evento en la conciencia temporal
func (tc *TemporalConsciousness) RegisterEvent(concept string,
	duration time.Duration, intensity float64) *TemporalEvent {

	tc.mu.Lock()
	defer tc.mu.Unlock()

	now := tc.Now

	event := TemporalEvent{
		ID:        int64(len(tc.eventHistory)),
		Concept:   concept,
		Timestamp: now,
		Duration:  duration,
		Intensity: intensity,
		Metadata:  make(map[string]interface{}),
	}

	tc.eventHistory = append(tc.eventHistory, event)

	// Actualizar percepción
	tc.updatePerception(intensity)

	// Actualizar atención
	tc.attention.FocusCenter = now
	tc.attention.Intensity = math.Min(1.0, tc.attention.Intensity+intensity*0.1)

	// Aprender/actualizar reglas causales
	tc.learnFromEvent(event)

	// Verificar predicciones
	tc.checkPredictions(event)

	return &event
}

// updatePerception actualiza la percepción subjetiva del tiempo
func (tc *TemporalConsciousness) updatePerception(eventIntensity float64) {
	now := tc.Now
	perception := tc.perception

	// Calcular tiempo real transcurrido
	realElapsed := now.Sub(perception.LastEventTime)

	// Actualizar densidad de eventos (EMA)
	timeSinceLast := realElapsed.Seconds()
	if timeSinceLast > 0 {
		instantDensity := eventIntensity / timeSinceLast
		perception.EventDensity = perception.EventDensity*0.9 + instantDensity*0.1
	}

	// Mantener historial
	perception.densityHistory = append(perception.densityHistory, perception.EventDensity)
	if len(perception.densityHistory) > 100 {
		perception.densityHistory = perception.densityHistory[1:]
	}

	// Calcular subjetividad: más densidad = tiempo "más lento" (más información por unidad)
	avgDensity := average(perception.densityHistory)
	if avgDensity > 0 {
		perception.SubjectivityScale = 1.0 / (1.0 + math.Log1p(avgDensity))
	}

	// Actualizar fase circadiana
	elapsedCycles := float64(realElapsed) / float64(perception.CyclePeriod)
	perception.CircadianPhase += 2 * math.Pi * elapsedCycles
	perception.CircadianPhase = math.Mod(perception.CircadianPhase, 2*math.Pi)

	perception.LastEventTime = now
}

// learnFromEvent aprende reglas causales y patrones
func (tc *TemporalConsciousness) learnFromEvent(event TemporalEvent) {
	reasoning := tc.reasoning

	// Buscar eventos recientes para aprender relaciones causales
	recentWindow := time.Minute * 5

	for i := len(tc.eventHistory) - 2; i >= 0; i-- {
		prevEvent := tc.eventHistory[i]
		lag := event.Timestamp.Sub(prevEvent.Timestamp)

		if lag > recentWindow {
			break
		}

		if lag <= 0 {
			continue
		}

		// Actualizar o crear regla causal
		tc.updateCausalRule(prevEvent.Concept, event.Concept, lag)
	}

	// Detectar patrones de secuencia (últimos 3 eventos)
	if len(tc.eventHistory) >= 3 {
		seq := tc.eventHistory[len(tc.eventHistory)-3:]
		patternKey := seq[0].Concept + "->" + seq[1].Concept + "->" + seq[2].Concept

		if existing, ok := reasoning.SequencePatterns[patternKey]; ok {
			existing.Frequency++
			existing.LastMatched = tc.Now
			// Actualizar duración típica
			duration := seq[2].Timestamp.Sub(seq[0].Timestamp)
			existing.TypicalDuration = time.Duration(
				(float64(existing.TypicalDuration)*existing.Frequency + float64(duration)) /
					(existing.Frequency + 1))
		} else {
			reasoning.SequencePatterns[patternKey] = &SequencePattern{
				Sequence:        []string{seq[0].Concept, seq[1].Concept, seq[2].Concept},
				Frequency:       1,
				LastMatched:     tc.Now,
				TypicalDuration: seq[2].Timestamp.Sub(seq[0].Timestamp),
			}
		}
	}
}

// updateCausalRule actualiza una regla causal existente o crea nueva
func (tc *TemporalConsciousness) updateCausalRule(cause, effect string, lag time.Duration) {
	reasoning := tc.reasoning

	// Buscar regla existente
	for i := range reasoning.CausalRules {
		rule := &reasoning.CausalRules[i]
		if rule.Cause == cause && rule.Effect == effect {
			// Actualizar regla
			n := float64(rule.Occurrences)

			// Media móvil del lag
			rule.TypicalLag = time.Duration(
				(float64(rule.TypicalLag)*n + float64(lag)) / (n + 1))

			// Varianza del lag
			diff := float64(lag - rule.TypicalLag)
			rule.LagVariance = time.Duration(
				(float64(rule.LagVariance)*n + diff*diff) / (n + 1))

			rule.Occurrences++
			rule.Confidence = math.Min(1.0, rule.Confidence+0.05)
			rule.LastSeen = tc.Now
			return
		}
	}

	// Nueva regla
	reasoning.CausalRules = append(reasoning.CausalRules, CausalRule{
		Cause:       cause,
		Effect:      effect,
		Confidence:  0.3,
		TypicalLag:  lag,
		LagVariance: lag / 2,
		Occurrences: 1,
		LastSeen:    tc.Now,
	})
}

// checkPredictions verifica si alguna predicción se cumplió
func (tc *TemporalConsciousness) checkPredictions(event TemporalEvent) {
	for _, pred := range tc.reasoning.ActivePredictions {
		if pred.Cancelled || pred.Triggered {
			continue
		}

		// Verificar si coincide con la predicción
		if pred.ExpectedEvent == event.Concept {
			timeDiff := math.Abs(float64(event.Timestamp.Sub(pred.ExpectedTime)))
			tolerance := float64(time.Second * 10)

			if timeDiff < tolerance {
				pred.Triggered = true
				// Reforzar la regla que generó la predicción
				if pred.BasedOnRule != nil {
					pred.BasedOnRule.Confidence = math.Min(1.0,
						pred.BasedOnRule.Confidence+0.1)
				}
			}
		}

		// Cancelar predicciones muy viejas
		if tc.Now.Sub(pred.ExpectedTime) > time.Minute*5 {
			pred.Cancelled = true
		}
	}
}

// GeneratePredictions genera predicciones basadas en evento actual
func (tc *TemporalConsciousness) GeneratePredictions(concept string) []*TemporalPrediction {
	tc.mu.Lock()
	defer tc.mu.Unlock()

	var predictions []*TemporalPrediction

	for i := range tc.reasoning.CausalRules {
		rule := &tc.reasoning.CausalRules[i]
		if rule.Cause == concept && rule.Confidence > 0.3 {
			tc.reasoning.predictionCounter++

			pred := &TemporalPrediction{
				ID:            tc.reasoning.predictionCounter,
				ExpectedEvent: rule.Effect,
				ExpectedTime:  tc.Now.Add(rule.TypicalLag),
				Confidence:    rule.Confidence,
				BasedOnRule:   rule,
			}

			tc.reasoning.ActivePredictions[pred.ID] = pred
			predictions = append(predictions, pred)
		}
	}

	return predictions
}

// FocusAt enfoca la atención en un momento específico
func (tc *TemporalConsciousness) FocusAt(t time.Time, intensity float64) {
	tc.mu.Lock()
	defer tc.mu.Unlock()

	tc.attention.FocusCenter = t
	tc.attention.Intensity = math.Min(1.0, intensity)

	// Registrar en historial
	tc.attention.focusHistory = append(tc.attention.focusHistory, FocusPoint{
		Timestamp: tc.Now,
		Center:    t,
		Intensity: intensity,
	})

	if len(tc.attention.focusHistory) > 1000 {
		tc.attention.focusHistory = tc.attention.focusHistory[100:]
	}
}

// GetTemporalRelevance devuelve qué tan relevante es un momento temporal
func (tc *TemporalConsciousness) GetTemporalRelevance(t time.Time) float64 {
	tc.mu.RLock()
	defer tc.mu.RUnlock()

	// Distancia desde el centro de atención
	dist := math.Abs(float64(t.Sub(tc.attention.FocusCenter)))
	width := float64(tc.attention.FocusWidth)

	// Función gaussiana centrada en el foco
	relevance := math.Exp(-(dist * dist) / (2 * (width * 0.3) * (width * 0.3)))

	return relevance * tc.attention.Intensity
}

// AdvanceTime avanza el tiempo (para simulación o procesamiento por lotes)
func (tc *TemporalConsciousness) AdvanceTime(delta time.Duration) {
	tc.mu.Lock()
	defer tc.mu.Unlock()

	// Aplicar escala subjetiva
	subjectiveDelta := time.Duration(float64(delta) * tc.perception.SubjectivityScale)
	tc.Now = tc.Now.Add(subjectiveDelta)

	// Drift natural de la atención
	tc.attention.FocusCenter = tc.attention.FocusCenter.Add(
		time.Duration(float64(delta) * tc.perception.SubjectivityScale))
}

// GetSubjectiveTime convierte tiempo real a tiempo subjetivo
func (tc *TemporalConsciousness) GetSubjectiveTime(realTime time.Duration) time.Duration {
	tc.mu.RLock()
	defer tc.mu.RUnlock()

	return time.Duration(float64(realTime) * tc.perception.SubjectivityScale)
}

// GetState devuelve estado completo de la conciencia temporal
func (tc *TemporalConsciousness) GetState() map[string]interface{} {
	tc.mu.RLock()
	defer tc.mu.RUnlock()

	return map[string]interface{}{
		"now":                 tc.Now,
		"subjectivity":        tc.perception.SubjectivityScale,
		"event_density":       tc.perception.EventDensity,
		"circadian_phase":     tc.perception.CircadianPhase,
		"attention_center":    tc.attention.FocusCenter,
		"attention_width":     tc.attention.FocusWidth,
		"attention_intensity": tc.attention.Intensity,
		"causal_rules":        len(tc.reasoning.CausalRules),
		"patterns":            len(tc.reasoning.SequencePatterns),
		"predictions":         len(tc.reasoning.ActivePredictions),
		"event_history":       len(tc.eventHistory),
	}
}

// GetRecentEvents devuelve eventos recientes
func (tc *TemporalConsciousness) GetRecentEvents(window time.Duration) []TemporalEvent {
	tc.mu.RLock()
	defer tc.mu.RUnlock()

	cutoff := tc.Now.Add(-window)
	var recent []TemporalEvent

	for i := len(tc.eventHistory) - 1; i >= 0; i-- {
		if tc.eventHistory[i].Timestamp.Before(cutoff) {
			break
		}
		recent = append([]TemporalEvent{tc.eventHistory[i]}, recent...)
	}

	return recent
}

// average calcula promedio de slice
func average(vals []float64) float64 {
	if len(vals) == 0 {
		return 0
	}
	sum := 0.0
	for _, v := range vals {
		sum += v
	}
	return sum / float64(len(vals))
}
