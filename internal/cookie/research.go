package cookie

import (
	"context"
	"crypto/sha1"
	"encoding/hex"
	"encoding/xml"
	"fmt"
	"html"
	"io"
	"math"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"
)

// Item un hallazgo que Cookie cree que te importa.
type Item struct {
	ID        string    `json:"id"`
	Topic     string    `json:"topic"`
	Title     string    `json:"title"`
	URL       string    `json:"url"`
	Summary   string    `json:"summary,omitempty"`
	Why       string    `json:"why,omitempty"` // por qué te lo muestra
	Source    string    `json:"source,omitempty"`
	Published time.Time `json:"published"`
	FoundAt   time.Time `json:"found_at"`
	Score     float64   `json:"score"`
	Delivered bool      `json:"delivered"`
	Feedback  int       `json:"feedback"` // +1 útil, -1 no me interesa
}

// Topic tema a investigar con su peso en tu perfil.
type Topic struct {
	Name   string
	Weight float64
	Kind   string
}

// Source cualquier sitio donde Cookie puede investigar.
type Source interface {
	Name() string
	Search(ctx context.Context, p *Profile, topics []Topic) ([]*Item, error)
}

// ItemID identificador estable (para no repetir noticias).
func ItemID(u, title string) string {
	key := u
	if key == "" {
		key = strings.ToLower(title)
	}
	h := sha1.Sum([]byte(key))
	return hex.EncodeToString(h[:6])
}

// ==================== RSS ====================

// RSSSource busca en Google News RSS por cada tema (sin API key).
// Feeds añade feeds fijos que quieras seguir; se filtran por relevancia.
type RSSSource struct {
	Client   *http.Client
	Lang     string // p. ej. "es-419"
	Country  string // p. ej. "MX"
	PerTopic int
	Feeds    []string
	// SearchURL permite cambiar el buscador (tests). %s = query escapada.
	SearchURL string
}

// NewRSSSource fuente RSS con valores por defecto en español.
func NewRSSSource() *RSSSource {
	return &RSSSource{
		Client:   &http.Client{Timeout: 20 * time.Second},
		Lang:     "es-419",
		Country:  "MX",
		PerTopic: 5,
	}
}

func (s *RSSSource) Name() string { return "rss" }

func (s *RSSSource) searchURL(q string) string {
	if s.SearchURL != "" {
		return fmt.Sprintf(s.SearchURL, url.QueryEscape(q))
	}
	lang := strings.SplitN(s.Lang, "-", 2)[0]
	return fmt.Sprintf("https://news.google.com/rss/search?q=%s&hl=%s&gl=%s&ceid=%s:%s",
		url.QueryEscape(q+" when:7d"), s.Lang, s.Country, s.Country, lang)
}

func (s *RSSSource) Search(ctx context.Context, p *Profile, topics []Topic) ([]*Item, error) {
	var out []*Item
	var errs []string
	for _, t := range topics {
		items, err := s.fetch(ctx, s.searchURL(t.Name))
		if err != nil {
			errs = append(errs, fmt.Sprintf("%s: %v", t.Name, err))
			continue
		}
		if s.PerTopic > 0 && len(items) > s.PerTopic {
			items = items[:s.PerTopic]
		}
		for _, it := range items {
			it.Topic = t.Name
		}
		out = append(out, items...)
	}
	for _, f := range s.Feeds {
		items, err := s.fetch(ctx, f)
		if err != nil {
			errs = append(errs, fmt.Sprintf("%s: %v", f, err))
			continue
		}
		out = append(out, items...) // el tema se asigna al puntuar
	}
	if len(out) == 0 && len(errs) > 0 {
		return nil, fmt.Errorf("rss: %s", strings.Join(errs, "; "))
	}
	return out, nil
}

type rssDoc struct {
	Channel struct {
		Items []struct {
			Title       string `xml:"title"`
			Link        string `xml:"link"`
			Description string `xml:"description"`
			PubDate     string `xml:"pubDate"`
			Source      string `xml:"source"`
		} `xml:"item"`
	} `xml:"channel"`
	// Atom
	Entries []struct {
		Title   string `xml:"title"`
		Summary string `xml:"summary"`
		Updated string `xml:"updated"`
		Links   []struct {
			Href string `xml:"href,attr"`
		} `xml:"link"`
	} `xml:"entry"`
}

func (s *RSSSource) fetch(ctx context.Context, u string) ([]*Item, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "aria-cookie/1.0")
	client := s.Client
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return ParseFeed(io.LimitReader(resp.Body, 5<<20))
}

// ParseFeed interpreta RSS 2.0 o Atom.
func ParseFeed(r io.Reader) ([]*Item, error) {
	var doc rssDoc
	if err := xml.NewDecoder(r).Decode(&doc); err != nil {
		return nil, err
	}
	var out []*Item
	for _, it := range doc.Channel.Items {
		out = append(out, &Item{
			ID:        ItemID(it.Link, it.Title),
			Title:     strings.TrimSpace(it.Title),
			URL:       strings.TrimSpace(it.Link),
			Summary:   cleanHTML(it.Description),
			Source:    strings.TrimSpace(it.Source),
			Published: parseTime(it.PubDate),
		})
	}
	for _, e := range doc.Entries {
		link := ""
		if len(e.Links) > 0 {
			link = e.Links[0].Href
		}
		out = append(out, &Item{
			ID:        ItemID(link, e.Title),
			Title:     strings.TrimSpace(e.Title),
			URL:       link,
			Summary:   cleanHTML(e.Summary),
			Published: parseTime(e.Updated),
		})
	}
	return out, nil
}

var reTags = regexp.MustCompile(`<[^>]*>`)

func cleanHTML(s string) string {
	s = html.UnescapeString(reTags.ReplaceAllString(s, " "))
	s = strings.Join(strings.Fields(s), " ")
	if r := []rune(s); len(r) > 280 {
		s = string(r[:277]) + "..."
	}
	return s
}

func parseTime(s string) time.Time {
	s = strings.TrimSpace(s)
	for _, layout := range []string{time.RFC1123Z, time.RFC1123, time.RFC3339, "Mon, 2 Jan 2006 15:04:05 MST", "Mon, 2 Jan 2006 15:04:05 -0700"} {
		if t, err := time.Parse(layout, s); err == nil {
			return t
		}
	}
	return time.Time{}
}

// ==================== ESTÁTICA (demo / pruebas) ====================

// StaticSource devuelve siempre los mismos ítems. Útil sin conexión.
type StaticSource struct {
	Items []*Item
}

func (s *StaticSource) Name() string { return "static" }

func (s *StaticSource) Search(ctx context.Context, p *Profile, topics []Topic) ([]*Item, error) {
	out := make([]*Item, 0, len(s.Items))
	for _, it := range s.Items {
		cp := *it
		if cp.ID == "" {
			cp.ID = ItemID(cp.URL, cp.Title)
		}
		out = append(out, &cp)
	}
	return out, nil
}

// ==================== RELEVANCIA ====================

// Rank puntúa y filtra hallazgos según el perfil:
// relevancia con tus intereses × peso del interés × frescura; descarta
// lo que rechazas y lo que ya viste.
func Rank(items []*Item, p *Profile, topics []Topic, seen map[string]int64, now time.Time) []*Item {
	var maxW float64
	for _, t := range topics {
		maxW = math.Max(maxW, t.Weight)
	}
	if maxW == 0 {
		maxW = 1
	}

	var out []*Item
	dup := make(map[string]bool)
	var kept [][]string
	for _, it := range items {
		if it.ID == "" {
			it.ID = ItemID(it.URL, it.Title)
		}
		if _, ok := seen[it.ID]; ok || dup[it.ID] || it.Title == "" {
			continue
		}
		text := strings.ToLower(it.Title + " " + it.Summary + " " + it.Topic)
		if mentionsAny(text, p.Dislikes) {
			continue
		}

		// Mejor tema coincidente.
		bestTopic, bestScore := "", 0.0
		for _, t := range topics {
			m := matchScore(text, t.Name)
			if t.Name == it.Topic {
				m = math.Max(m, 0.6) // la búsqueda ya se hizo por este tema
			}
			if s := m * (t.Weight / maxW); s > bestScore {
				bestTopic, bestScore = t.Name, s
			}
		}
		if bestScore < 0.15 {
			continue
		}
		kw := Keywords(it.Title)
		if nearDuplicate(kw, kept) {
			continue
		}

		fresh := 1.0
		if !it.Published.IsZero() {
			age := now.Sub(it.Published)
			if age > 14*24*time.Hour {
				continue
			}
			fresh = math.Exp(-math.Max(age.Hours(), 0) / 96)
		}
		it.Topic = bestTopic
		it.Score = bestScore * (0.4 + 0.6*fresh)
		if it.Why == "" {
			it.Why = whyText(p, bestTopic)
		}
		if it.FoundAt.IsZero() {
			it.FoundAt = now
		}
		if strings.HasPrefix(it.Summary, it.Title) {
			it.Summary = "" // Google News repite el título como resumen
		}
		dup[it.ID] = true
		kept = append(kept, kw)
		out = append(out, it)
	}
	sortItems(out)
	return out
}

// nearDuplicate ¿la misma noticia contada por otro medio?
// (más de la mitad de palabras clave en común).
func nearDuplicate(kw []string, kept [][]string) bool {
	if len(kw) < 3 {
		return false
	}
	set := make(map[string]bool, len(kw))
	for _, w := range kw {
		set[w] = true
	}
	for _, other := range kept {
		common := 0
		for _, w := range other {
			if set[w] {
				common++
			}
		}
		if float64(common) > 0.5*math.Min(float64(len(kw)), float64(len(other))) {
			return true
		}
	}
	return false
}

func whyText(p *Profile, topic string) string {
	in := p.Interests[topic]
	switch {
	case in == nil:
		return "relacionado con lo que te interesa"
	case in.Kind == "trabajo":
		return "tiene que ver con tu trabajo (" + topic + ")"
	case in.Kind == "actividad":
		return "es algo que haces seguido (" + topic + ")"
	case in.Mentions > 1:
		return fmt.Sprintf("has hablado de %s %d veces", topic, in.Mentions)
	default:
		return "dijiste que te interesa " + topic
	}
}

// aliases formas cortas comunes de un tema.
var aliases = map[string][]string{
	"inteligencia artificial": {"ia", "ai", "chatgpt", "claude", "llm"},
	"fórmula 1":               {"f1"},
	"formula 1":               {"f1"},
	"criptomonedas":           {"bitcoin", "cripto"},
	"programación":            {"software", "código"},
}

// matchScore fracción de palabras del tema que aparecen en el texto
// (con raíz aproximada: "diseñadora" encaja con "diseño").
func matchScore(text, topic string) float64 {
	words := strings.Fields(topic)
	if len(words) == 0 {
		return 0
	}
	if strings.Contains(text, topic) {
		return 1
	}
	for _, a := range aliases[topic] {
		if containsWord(text, a) {
			return 1
		}
	}
	hits := 0
	for _, w := range words {
		if len([]rune(w)) >= 3 && (containsWord(text, w) || containsStem(text, w)) {
			hits++
		}
	}
	return float64(hits) / float64(len(words))
}

// containsStem ¿alguna palabra del texto comparte las 5 primeras letras?
func containsStem(text, w string) bool {
	r := []rune(w)
	if len(r) < 6 {
		return false
	}
	stem := string(r[:5])
	for i := 0; ; {
		j := strings.Index(text[i:], stem)
		if j < 0 {
			return false
		}
		if start := i + j; start == 0 || !isLetterByte(text, start-1) {
			return true
		}
		i += j + 1
	}
}

func mentionsAny(text string, set map[string]time.Time) bool {
	for t := range set {
		if containsWord(text, t) {
			return true
		}
	}
	return false
}

func containsWord(text, w string) bool {
	for i := 0; ; {
		j := strings.Index(text[i:], w)
		if j < 0 {
			return false
		}
		start, end := i+j, i+j+len(w)
		if (start == 0 || !isLetterByte(text, start-1)) && (end == len(text) || !isLetterAt(text, end)) {
			return true
		}
		i = start + 1
	}
}

func isLetterByte(s string, i int) bool {
	// retrocede al inicio de la runa
	for i > 0 && s[i]&0xC0 == 0x80 {
		i--
	}
	return isLetterAt(s, i)
}

func isLetterAt(s string, i int) bool {
	for _, r := range s[i:] {
		return r == '_' || ('a' <= r && r <= 'z') || ('0' <= r && r <= '9') || r > 127 && !strings.ContainsRune("¿¡«»“”‘’–—…", r)
	}
	return false
}
