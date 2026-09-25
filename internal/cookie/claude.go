package cookie

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/anthropics/anthropic-sdk-go"
	"github.com/anthropics/anthropic-sdk-go/shared/constant"
)

// ClaudeSource investiga en la web con Claude (herramienta web_search)
// y devuelve hallazgos ya resumidos y explicados para ti.
// Requiere ANTHROPIC_API_KEY (u otra credencial del SDK).
type ClaudeSource struct {
	Client   anthropic.Client
	Model    string
	MaxItems int
	MaxUses  int64
}

// DefaultClaudeModel modelo usado para investigar.
const DefaultClaudeModel = "claude-opus-5"

// NewClaudeSource crea la fuente con credenciales del entorno.
func NewClaudeSource() *ClaudeSource {
	model := os.Getenv("ARIA_CLAUDE_MODEL")
	if model == "" {
		model = DefaultClaudeModel
	}
	return &ClaudeSource{
		Client:   anthropic.NewClient(),
		Model:    model,
		MaxItems: 6,
		MaxUses:  6,
	}
}

// ClaudeAvailable indica si hay una API key configurada.
func ClaudeAvailable() bool {
	return os.Getenv("ANTHROPIC_API_KEY") != "" || os.Getenv("ANTHROPIC_AUTH_TOKEN") != ""
}

func (c *ClaudeSource) Name() string { return "claude" }

const claudeSystem = `Eres Cookie, el asistente personal de investigación de una sola persona.
Conoces su perfil (abajo). Tu trabajo: buscar en la web novedades RECIENTES (últimos 7 días)
que de verdad le importen a esta persona según su trabajo, gustos y rutina, y explicarle
en una frase por qué le importa. Prefiere hechos concretos y fuentes fiables; evita
clickbait y duplicados. Respeta los temas que rechaza: nunca los incluyas.

Responde SOLO con un array JSON (sin texto antes ni después) de objetos:
{"title": "...", "url": "...", "topic": "<uno de los temas dados>", "summary": "<2 frases en español>", "why": "<por qué le importa a esta persona>", "published": "<YYYY-MM-DD o vacío>"}`

func (c *ClaudeSource) Search(ctx context.Context, p *Profile, topics []Topic) ([]*Item, error) {
	if len(topics) == 0 {
		return nil, nil
	}
	params := anthropic.BetaMessageNewParams{
		Model:     anthropic.Model(c.Model),
		MaxTokens: 16000,
		System:    []anthropic.BetaTextBlockParam{{Text: claudeSystem}},
		Messages: []anthropic.BetaMessageParam{
			anthropic.NewBetaUserMessage(anthropic.NewBetaTextBlock(researchPrompt(p, topics, c.MaxItems, time.Now()))),
		},
		Tools: []anthropic.BetaToolUnionParam{{
			OfWebSearchTool20260209: &anthropic.BetaWebSearchTool20260209Param{
				MaxUses: anthropic.Int(c.MaxUses),
			},
		}},
		OutputConfig: anthropic.BetaOutputConfigParam{Effort: anthropic.BetaOutputConfigEffortMedium},
		// Si el modelo rechaza la petición, el servidor la reintenta con otro modelo.
		Fallbacks: anthropic.BetaFallbacksParamUnion{OfDefault: constant.ValueOf[constant.Default]()},
		Betas:     []anthropic.AnthropicBeta{anthropic.AnthropicBetaServerSideFallback2026_07_01},
	}

	var text strings.Builder
	// La búsqueda web puede pausar el turno (pause_turn); se continúa
	// reenviando la respuesta tal cual.
	for turn := 0; turn < 4; turn++ {
		resp, err := c.Client.Beta.Messages.New(ctx, params)
		if err != nil {
			var apiErr *anthropic.Error
			if errors.As(err, &apiErr) {
				return nil, fmt.Errorf("claude: HTTP %d: %w", apiErr.StatusCode, err)
			}
			return nil, fmt.Errorf("claude: %w", err)
		}
		if resp.StopReason == anthropic.BetaStopReasonRefusal {
			return nil, fmt.Errorf("claude: la petición fue rechazada")
		}
		for _, block := range resp.Content {
			if t, ok := block.AsAny().(anthropic.BetaTextBlock); ok {
				text.WriteString(t.Text)
			}
		}
		if resp.StopReason != anthropic.BetaStopReasonPauseTurn {
			break
		}
		params.Messages = append(params.Messages, resp.ToParam())
		text.Reset()
	}
	return parseClaudeItems(text.String(), time.Now())
}

func researchPrompt(p *Profile, topics []Topic, maxItems int, now time.Time) string {
	var b strings.Builder
	fmt.Fprintf(&b, "Fecha de hoy: %s\n\nPERFIL\n", now.Format("2006-01-02"))
	if p.Name != "" {
		fmt.Fprintf(&b, "- Nombre: %s\n", p.Name)
	}
	if p.Occupation != "" {
		fmt.Fprintf(&b, "- Trabajo: %s\n", p.Occupation)
	}
	if p.Location != "" {
		fmt.Fprintf(&b, "- Vive en: %s\n", p.Location)
	}
	if len(p.Routines) > 0 {
		var acts []string
		for a := range p.Routines {
			acts = append(acts, a)
		}
		fmt.Fprintf(&b, "- Rutinas: %s\n", strings.Join(acts, ", "))
	}
	if len(p.Dislikes) > 0 {
		var ds []string
		for d := range p.Dislikes {
			ds = append(ds, d)
		}
		fmt.Fprintf(&b, "- NO le interesa: %s\n", strings.Join(ds, ", "))
	}
	b.WriteString("\nTEMAS (de más a menos importante)\n")
	for _, t := range topics {
		fmt.Fprintf(&b, "- %s (%s)\n", t.Name, t.Kind)
	}
	fmt.Fprintf(&b, "\nBusca y devuelve como máximo %d hallazgos.", maxItems)
	return b.String()
}

func parseClaudeItems(text string, now time.Time) ([]*Item, error) {
	start, end := strings.Index(text, "["), strings.LastIndex(text, "]")
	if start < 0 || end <= start {
		return nil, fmt.Errorf("claude: respuesta sin JSON")
	}
	var raw []struct {
		Title     string `json:"title"`
		URL       string `json:"url"`
		Topic     string `json:"topic"`
		Summary   string `json:"summary"`
		Why       string `json:"why"`
		Published string `json:"published"`
	}
	if err := json.Unmarshal([]byte(text[start:end+1]), &raw); err != nil {
		return nil, fmt.Errorf("claude: JSON inválido: %w", err)
	}
	out := make([]*Item, 0, len(raw))
	for _, r := range raw {
		it := &Item{
			ID:      ItemID(r.URL, r.Title),
			Title:   r.Title,
			URL:     r.URL,
			Topic:   normalizeTopic(r.Topic),
			Summary: r.Summary,
			Why:     r.Why,
			Source:  "claude",
			FoundAt: now,
		}
		if t, err := time.Parse("2006-01-02", r.Published); err == nil {
			it.Published = t
		}
		out = append(out, it)
	}
	return out, nil
}
