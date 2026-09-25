package liquid

import (
	"math"
	"regexp"
	"sort"
	"strings"
	"unicode"
)

// ComplexityLevel represents complexity tiers
type ComplexityLevel int

const (
	Low ComplexityLevel = iota
	Medium
	High
	Critical
)

func (c ComplexityLevel) String() string {
	return []string{"Low", "Medium", "High", "Critical"}[c]
}

// TopologyAnalysis resultado del análisis
type TopologyAnalysis struct {
	Level           ComplexityLevel
	Score           float64
	AssignedLayers  int
	ReasoningDepth  int
	RequiresDebate  bool
	EstimatedTokens int
	Features        TextFeatures // Debug info
}

// TextFeatures métricas universales extraídas
type TextFeatures struct {
	Entropy            float64
	ZipfComplexity     float64
	SyntacticDepth     float64
	InformationDensity float64
	WordCount          int
	CharCount          int
	AvgWordLength      float64
	PunctuationRatio   float64
	NounRatio          float64
	VerbRatio          float64
	HasNumbers         bool
	HasSpecialChars    bool
}

// AdvancedAnalyzer analizador multilingüe robusto
type AdvancedAnalyzer struct {
	userHistory map[string]float64
}

// NewAnalyzer crea el analizador avanzado (pública para uso externo)
func NewAnalyzer() *AdvancedAnalyzer {
	return &AdvancedAnalyzer{
		userHistory: make(map[string]float64),
	}
}

// Analyze método principal (reemplaza el anterior)
func (a *AdvancedAnalyzer) Analyze(query string) TopologyAnalysis {
	features := a.extractFeatures(query)
	score := a.calculateScore(features)

	// Ajustar por historial
	score = a.applyContext(score, query)

	return a.buildTopology(score, features)
}

// extractFeatures extrae métricas universales
func (a *AdvancedAnalyzer) extractFeatures(query string) TextFeatures {
	words := tokenizeUnicode(query)
	chars := []rune(query)

	f := TextFeatures{
		WordCount: len(words),
		CharCount: len(chars),
	}

	// Entropía de Shannon
	f.Entropy = calculateEntropy(words)

	// Complejidad Zipf
	f.ZipfComplexity = calculateZipfDeviation(words)

	// Profundidad sintáctica
	f.SyntacticDepth = calculateSyntacticDepth(query)

	// Densidad informativa
	if len(chars) > 0 {
		f.InformationDensity = float64(len(words)) / float64(len(chars)) * f.Entropy
	}

	// Longitud promedio
	if len(words) > 0 {
		totalLen := 0
		for _, w := range words {
			totalLen += len([]rune(w))
		}
		f.AvgWordLength = float64(totalLen) / float64(len(words))
	}

	// Puntuación
	punctCount := 0
	for _, r := range query {
		if unicode.IsPunct(r) {
			punctCount++
		}
	}
	f.PunctuationRatio = float64(punctCount) / float64(maxInt(len(chars), 1))

	// POS universal
	f.NounRatio, f.VerbRatio = detectPOSUniversal(words)

	// Especial
	f.HasNumbers = regexp.MustCompile(`\d`).MatchString(query)
	f.HasSpecialChars = regexp.MustCompile(`[+\-*/=<>{}[\]()@#$%^&*]`).MatchString(query)

	return f
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}

// calculateScore combina señales
func (a *AdvancedAnalyzer) calculateScore(f TextFeatures) float64 {
	weights := map[string]float64{
		"entropy":     0.25,
		"zipf":        0.20,
		"syntactic":   0.20,
		"density":     0.15,
		"punctuation": 0.10,
		"special":     0.10,
	}

	score := 0.0
	score += f.Entropy * weights["entropy"]
	score += f.ZipfComplexity * weights["zipf"]
	score += f.SyntacticDepth * weights["syntactic"]

	if f.InformationDensity > 0.5 {
		score += weights["density"]
	}

	if f.PunctuationRatio > 0.1 {
		score += weights["punctuation"]
	}

	if f.HasNumbers {
		score += weights["special"] * 0.5
	}
	if f.HasSpecialChars {
		score += weights["special"] * 0.5
	}

	// Corrección por longitud
	if f.WordCount <= 3 {
		score *= 0.3
	} else if f.WordCount >= 30 {
		score = min(score*1.2, 1.0)
	}

	return min(max(score, 0.0), 1.0)
}

// applyContext aprendizaje adaptativo
func (a *AdvancedAnalyzer) applyContext(baseScore float64, query string) float64 {
	hash := simpleHash(query)
	if historical, exists := a.userHistory[hash]; exists {
		return baseScore*0.7 + historical*0.3
	}
	return baseScore
}

// buildTopology construye resultado
func (a *AdvancedAnalyzer) buildTopology(score float64, f TextFeatures) TopologyAnalysis {
	var level ComplexityLevel

	switch {
	case score < 0.25:
		level = Low
	case score < 0.50:
		level = Medium
	case score < 0.75:
		level = High
	default:
		level = Critical
	}

	layers := int(4 + score*28)
	if layers < 2 {
		layers = 2
	}
	if layers > 32 {
		layers = 32
	}

	return TopologyAnalysis{
		Level:           level,
		Score:           score,
		AssignedLayers:  layers,
		ReasoningDepth:  int(1 + score*4),
		RequiresDebate:  score > 0.7,
		EstimatedTokens: f.WordCount * 3,
		Features:        f, // Debug
	}
}

// Funciones matemáticas universales

func calculateEntropy(words []string) float64 {
	if len(words) == 0 {
		return 0
	}

	freq := make(map[string]int)
	for _, w := range words {
		freq[w]++
	}

	entropy := 0.0
	total := float64(len(words))

	for _, count := range freq {
		p := float64(count) / total
		if p > 0 {
			entropy -= p * math.Log2(p)
		}
	}

	maxEntropy := math.Log2(float64(len(words)))
	if maxEntropy > 0 {
		return entropy / maxEntropy
	}
	return 0
}

func calculateZipfDeviation(words []string) float64 {
	if len(words) < 10 {
		return 0.5
	}

	freq := make(map[string]int)
	for _, w := range words {
		freq[w]++
	}

	type pair struct {
		word string
		freq int
	}
	var pairs []pair
	for w, f := range freq {
		pairs = append(pairs, pair{w, f})
	}
	sort.Slice(pairs, func(i, j int) bool {
		return pairs[i].freq > pairs[j].freq
	})

	deviation := 0.0
	for i, p := range pairs {
		if i >= 10 {
			break
		}
		rank := float64(i + 1)
		expected := float64(len(words)) / rank / 10
		actual := float64(p.freq)
		deviation += math.Abs(actual-expected) / max(expected, 1)
	}

	return min(deviation/10.0, 1.0)
}

func calculateSyntacticDepth(query string) float64 {
	depth := 0
	maxDepth := 0

	for _, r := range query {
		switch r {
		case '(', '[', '{':
			depth++
			if depth > maxDepth {
				maxDepth = depth
			}
		case ')', ']', '}':
			depth--
		}
	}

	sentences := len(regexp.MustCompile(`[.!?]\s+[A-ZÁÉÍÓÚÀÈÌÒÙ]`).FindAllString(query, -1)) + 1

	score := float64(maxDepth) * 0.3
	score += float64(sentences-1) * 0.1

	return min(score, 1.0)
}

func detectPOSUniversal(words []string) (nounRatio, verbRatio float64) {
	if len(words) == 0 {
		return 0, 0
	}

	nouns := 0
	verbs := 0

	for _, w := range words {
		lower := strings.ToLower(w)

		// Heurística: sustantivos largos sin terminaciones verbales comunes
		if len([]rune(w)) > 5 {
			last3 := ""
			if len(lower) >= 3 {
				last3 = lower[len(lower)-3:]
			}

			// Si no parece verbo
			if !containsAny(last3, []string{"ar", "er", "ir", "ed", "ng"}) {
				nouns++
			} else {
				verbs++
			}
		}
	}

	return float64(nouns) / float64(len(words)), float64(verbs) / float64(len(words))
}

func tokenizeUnicode(text string) []string {
	var words []string
	var current strings.Builder

	for _, r := range text {
		if unicode.IsLetter(r) || unicode.IsNumber(r) {
			current.WriteRune(unicode.ToLower(r))
		} else if current.Len() > 0 {
			words = append(words, current.String())
			current.Reset()
		}
	}

	if current.Len() > 0 {
		words = append(words, current.String())
	}

	return words
}

func simpleHash(query string) string {
	words := tokenizeUnicode(query)
	if len(words) > 3 {
		words = words[:3]
	}
	return strings.Join(words, "_")
}

func containsAny(s string, substrs []string) bool {
	for _, sub := range substrs {
		if strings.Contains(s, sub) {
			return true
		}
	}
	return false
}

// Helpers
func min(a, b float64) float64 {
	if a < b {
		return a
	}
	return b
}

func max(a, b float64) float64 {
	if a > b {
		return a
	}
	return b
}
