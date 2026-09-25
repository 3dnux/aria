// Package cookie es el Módulo 4 de ARIA: un asistente personal que
// aprende tu ritmo de vida (a qué te dedicas, qué te gusta, qué haces y
// cuándo), investiga por su cuenta sin que se lo pidas y te mantiene al
// día en el momento en que sueles estar disponible.
//
// Inspirado en la "cookie" de Black Mirror, pero con una diferencia
// importante: es tuya. Todo se guarda en local, puedes ver lo que sabe
// (Profile) y borrarlo cuando quieras (Store.Wipe / Profile.Forget).
package cookie

import (
	"context"
	"fmt"
	"io"
	"sort"
	"strings"
	"sync"
	"time"
)

// Pesos con que cada señal refuerza un interés.
var signalWeights = map[string]float64{
	"trabajo":   3.0,
	"gusto":     2.0,
	"actividad": 1.0,
	"palabra":   0.3,
}

const (
	maxInbox   = 200
	seenMaxAge = 30 * 24 * time.Hour
)

// Cookie orquesta aprendizaje, investigación y resúmenes.
type Cookie struct {
	Store   *Store
	State   *State
	Sources []Source
	Now     func() time.Time
	Log     io.Writer

	mu sync.Mutex
}

// Open carga (o crea) el estado desde el Store.
func Open(store *Store, sources ...Source) (*Cookie, error) {
	c := &Cookie{Store: store, Sources: sources, Now: time.Now, Log: io.Discard}
	st, err := store.Load(c.Now())
	if err != nil {
		return nil, err
	}
	c.State = st
	return c, nil
}

// Profile acceso directo al perfil.
func (c *Cookie) Profile() *Profile { return c.State.Profile }

// Save persiste el estado.
func (c *Cookie) Save() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.Store.Save(c.State)
}

// reload vuelve a leer del disco (otro proceso pudo aprender algo).
func (c *Cookie) reload() error {
	st, err := c.Store.Load(c.Now())
	if err != nil {
		return err
	}
	c.State = st
	return nil
}

// Learn aprende de una frase libre y marca que estás activo ahora.
// Devuelve las señales que entendió.
func (c *Cookie) Learn(text string) []Signal {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.Now()
	p := c.State.Profile
	signals := Extract(text)
	for _, s := range signals {
		switch s.Kind {
		case "nombre":
			p.Name = s.Value
		case "lugar":
			p.Location = s.Value
		case "trabajo":
			p.Occupation = s.Value
			p.Reinforce(s.Value, "trabajo", signalWeights["trabajo"], now)
		case "rechazo":
			p.Dislike(s.Value, now)
		case "actividad":
			p.RecordActivity(s.Value, now)
			p.Reinforce(s.Value, "actividad", signalWeights["actividad"], now)
		default:
			p.Reinforce(s.Value, s.Kind, signalWeights[s.Kind], now)
		}
	}
	p.Observations++
	p.MarkActive(now)
	return signals
}

// Touch registra actividad sin texto (abrir la app, leer un resumen...).
// Así Cookie aprende tu ritmo aunque no le cuentes nada.
func (c *Cookie) Touch() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.State.Profile.MarkActive(c.Now())
	c.State.Profile.Observations++
}

// Topics temas que Cookie investigará, por importancia.
func (c *Cookie) Topics(n int) []Topic {
	now := c.Now()
	var out []Topic
	for _, in := range c.State.Profile.TopInterests(0, now) {
		// Una palabra suelta necesita repetirse para convertirse en tema.
		if in.Kind == "palabra" && in.Mentions < 3 {
			continue
		}
		out = append(out, Topic{Name: in.Topic, Weight: in.EffectiveWeight(now), Kind: in.Kind})
		if n > 0 && len(out) == n {
			break
		}
	}
	return out
}

// Research ejecuta un ciclo de investigación en todas las fuentes y
// guarda en la bandeja lo nuevo y relevante. Devuelve cuántos hallazgos
// nuevos encontró.
func (c *Cookie) Research(ctx context.Context) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.Now()
	p := c.State.Profile
	topics := c.Topics(6)
	if len(topics) == 0 {
		return 0, fmt.Errorf("todavía no sé qué te interesa: cuéntame algo con 'aprender'")
	}

	var all []*Item
	var errs []string
	for _, src := range c.Sources {
		items, err := src.Search(ctx, p, topics)
		if err != nil {
			errs = append(errs, fmt.Sprintf("%s: %v", src.Name(), err))
			fmt.Fprintf(c.Log, "⚠️  fuente %s: %v\n", src.Name(), err)
			continue
		}
		all = append(all, items...)
	}

	ranked := Rank(all, p, topics, c.State.Seen, now)
	for _, it := range ranked {
		c.State.Seen[it.ID] = now.Unix()
	}
	c.State.Inbox = append(c.State.Inbox, ranked...)
	c.compact(now)
	c.State.LastResearch = now

	if len(ranked) == 0 && len(errs) == len(c.Sources) && len(errs) > 0 {
		return 0, fmt.Errorf("ninguna fuente respondió: %s", strings.Join(errs, "; "))
	}
	return len(ranked), nil
}

// compact limita la bandeja y olvida IDs vistos hace mucho.
func (c *Cookie) compact(now time.Time) {
	sortItems(c.State.Inbox)
	if len(c.State.Inbox) > maxInbox {
		c.State.Inbox = c.State.Inbox[:maxInbox]
	}
	for id, ts := range c.State.Seen {
		if now.Sub(time.Unix(ts, 0)) > seenMaxAge {
			delete(c.State.Seen, id)
		}
	}
}

// sortItems no entregados primero, luego por puntuación.
func sortItems(items []*Item) {
	sort.SliceStable(items, func(a, b int) bool {
		if items[a].Delivered != items[b].Delivered {
			return !items[a].Delivered
		}
		return items[a].Score > items[b].Score
	})
}

// Unread hallazgos aún no entregados.
func (c *Cookie) Unread() []*Item {
	var out []*Item
	for _, it := range c.State.Inbox {
		if !it.Delivered {
			out = append(out, it)
		}
	}
	return out
}

// Briefing resumen personal listo para leer.
type Briefing struct {
	Greeting   string
	Items      []*Item
	Prediction *Prediction
	Pending    int
	At         time.Time
}

// Briefing arma tu resumen con los mejores hallazgos. Si deliver es true
// los marca como entregados.
func (c *Cookie) Briefing(max int, deliver bool) *Briefing {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.Now()
	p := c.State.Profile

	unread := c.Unread()
	if max > 0 && len(unread) > max {
		unread = unread[:max]
	}
	b := &Briefing{
		Greeting:   greeting(p, now),
		Items:      unread,
		Prediction: p.PredictNext(now),
		At:         now,
	}
	if deliver {
		for _, it := range unread {
			it.Delivered = true
		}
		c.State.LastBriefing = now
		p.MarkActive(now)
	}
	b.Pending = len(c.Unread())
	return b
}

func greeting(p *Profile, now time.Time) string {
	var g string
	switch h := now.Hour(); {
	case h >= 5 && h < 12:
		g = "Buenos días"
	case h >= 12 && h < 20:
		g = "Buenas tardes"
	default:
		g = "Buenas noches"
	}
	if p.Name != "" {
		g += ", " + p.Name
	}
	return g
}

// String formato legible del resumen.
func (b *Briefing) String() string {
	var s strings.Builder
	fmt.Fprintf(&s, "🍪 %s. ", b.Greeting)
	if len(b.Items) == 0 {
		s.WriteString("No hay nada nuevo que valga tu tiempo ahora mismo.\n")
	} else {
		fmt.Fprintf(&s, "Esto es lo que investigué para ti:\n\n")
		for i, it := range b.Items {
			fmt.Fprintf(&s, "%d. [%s] %s\n", i+1, it.Topic, it.Title)
			if it.Summary != "" && it.Summary != it.Title {
				fmt.Fprintf(&s, "   %s\n", it.Summary)
			}
			if it.Why != "" {
				fmt.Fprintf(&s, "   ↳ Por qué: %s\n", it.Why)
			}
			if it.URL != "" {
				fmt.Fprintf(&s, "   🔗 %s\n", it.URL)
			}
			fmt.Fprintf(&s, "   id: %s\n", it.ID)
		}
	}
	if b.Prediction != nil {
		fmt.Fprintf(&s, "\n🔮 Creo que ahora toca: %s (%.0f%%, %s)\n",
			b.Prediction.Activity, b.Prediction.Confidence*100, b.Prediction.Reason)
	}
	if b.Pending > 0 {
		fmt.Fprintf(&s, "\n(%d hallazgos más esperando en tu bandeja)\n", b.Pending)
	}
	return s.String()
}

// Feedback aprende de tu reacción a un hallazgo: si te sirvió refuerza
// el tema; si no, lo debilita (y tras varias veces deja de buscarlo).
func (c *Cookie) Feedback(id string, useful bool) (*Item, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.Now()
	for _, it := range c.State.Inbox {
		if it.ID != id {
			continue
		}
		p := c.State.Profile
		if useful {
			it.Feedback = 1
			p.Reinforce(it.Topic, "feedback", 1.0, now)
			for _, k := range Keywords(it.Title) {
				p.Reinforce(k, "palabra", 0.2, now)
			}
		} else {
			it.Feedback = -1
			p.Reinforce(it.Topic, "feedback", -1.0, now)
		}
		p.MarkActive(now)
		return it, nil
	}
	return nil, fmt.Errorf("no encuentro el hallazgo %q", id)
}

// RunOptions configura el modo autónomo.
type RunOptions struct {
	Tick          time.Duration // cada cuánto despierta (def. 1 min)
	ResearchEvery time.Duration // cada cuánto investiga (def. 3 h)
	MinGap        time.Duration // mínimo entre resúmenes (def. 6 h)
	MaxItems      int           // hallazgos por resumen (def. 5)
	Notify        func(*Briefing)
}

// Run modo autónomo: investiga periódicamente sin que se lo pidas y te
// entrega el resumen cuando tu ritmo dice que estás disponible.
// Se detiene al cancelar ctx.
func (c *Cookie) Run(ctx context.Context, opt RunOptions) error {
	if opt.Tick == 0 {
		opt.Tick = time.Minute
	}
	if opt.ResearchEvery == 0 {
		opt.ResearchEvery = 3 * time.Hour
	}
	if opt.MinGap == 0 {
		opt.MinGap = 6 * time.Hour
	}
	if opt.MaxItems == 0 {
		opt.MaxItems = 5
	}
	ticker := time.NewTicker(opt.Tick)
	defer ticker.Stop()
	for {
		if err := c.Step(ctx, opt); err != nil {
			fmt.Fprintf(c.Log, "⚠️  %v\n", err)
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
		}
	}
}

// Step una iteración del modo autónomo (expuesta para pruebas).
func (c *Cookie) Step(ctx context.Context, opt RunOptions) error {
	c.mu.Lock()
	err := c.reload()
	c.mu.Unlock()
	if err != nil {
		return err
	}
	now := c.Now()

	if now.Sub(c.State.LastResearch) >= opt.ResearchEvery && len(c.Topics(1)) > 0 {
		n, err := c.Research(ctx)
		if err != nil {
			fmt.Fprintf(c.Log, "⚠️  investigación: %v\n", err)
		} else {
			fmt.Fprintf(c.Log, "🔎 %s investigué por mi cuenta: %d hallazgos nuevos\n", now.Format("15:04"), n)
		}
	}

	if len(c.Unread()) > 0 && now.Sub(c.State.LastBriefing) >= opt.MinGap &&
		c.State.Profile.IsGoodMoment(now) && opt.Notify != nil {
		opt.Notify(c.Briefing(opt.MaxItems, true))
	}
	return c.Save()
}
