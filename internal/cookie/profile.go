package cookie

import (
	"math"
	"sort"
	"strings"
	"time"
)

// Profile es el "yo digital": lo que Cookie sabe de su dueño.
// Todo vive en local (ver Store) y el dueño puede verlo u olvidarlo.
type Profile struct {
	Name       string               `json:"name,omitempty"`
	Occupation string               `json:"occupation,omitempty"`
	Location   string               `json:"location,omitempty"`
	Interests  map[string]*Interest `json:"interests"`
	Dislikes   map[string]time.Time `json:"dislikes"`

	// Rhythm cuenta interacciones por [día de la semana][hora].
	// Sirve para saber cuándo sueles estar activo y entregar el resumen ahí.
	Rhythm [7][24]int `json:"rhythm"`

	// Routines aprende a qué hora/día haces cada actividad.
	Routines map[string]*Routine `json:"routines"`

	// Transitions cuenta qué actividad sigue a cuál (cadena de Markov simple).
	Transitions  map[string]map[string]int `json:"transitions"`
	LastActivity string                    `json:"last_activity,omitempty"`
	LastActiveAt time.Time                 `json:"last_active_at"`

	Observations int       `json:"observations"`
	CreatedAt    time.Time `json:"created_at"`
}

// Interest tema que le importa al dueño, con peso que decae con el tiempo.
type Interest struct {
	Topic     string    `json:"topic"`
	Weight    float64   `json:"weight"`
	Mentions  int       `json:"mentions"`
	Kind      string    `json:"kind"` // gusto, trabajo, actividad, palabra, feedback
	FirstSeen time.Time `json:"first_seen"`
	LastSeen  time.Time `json:"last_seen"`
}

// Routine actividad recurrente con su distribución horaria.
type Routine struct {
	Activity   string    `json:"activity"`
	HourCounts [24]int   `json:"hour_counts"`
	DayCounts  [7]int    `json:"day_counts"`
	Count      int       `json:"count"`
	LastSeen   time.Time `json:"last_seen"`
}

// InterestHalfLife tiempo en que un interés pierde la mitad de su peso
// si no vuelve a aparecer. Los gustos cambian; Cookie también.
const InterestHalfLife = 30 * 24 * time.Hour

// NewProfile perfil vacío.
func NewProfile(now time.Time) *Profile {
	return &Profile{
		Interests:   make(map[string]*Interest),
		Dislikes:    make(map[string]time.Time),
		Routines:    make(map[string]*Routine),
		Transitions: make(map[string]map[string]int),
		CreatedAt:   now,
	}
}

// ensure inicializa mapas tras cargar un JSON antiguo o parcial.
func (p *Profile) ensure() {
	if p.Interests == nil {
		p.Interests = make(map[string]*Interest)
	}
	if p.Dislikes == nil {
		p.Dislikes = make(map[string]time.Time)
	}
	if p.Routines == nil {
		p.Routines = make(map[string]*Routine)
	}
	if p.Transitions == nil {
		p.Transitions = make(map[string]map[string]int)
	}
}

// EffectiveWeight peso del interés aplicando el decaimiento temporal.
func (i *Interest) EffectiveWeight(now time.Time) float64 {
	age := now.Sub(i.LastSeen)
	if age < 0 {
		age = 0
	}
	return i.Weight * math.Pow(0.5, float64(age)/float64(InterestHalfLife))
}

// Reinforce suma (o resta) peso a un tema.
func (p *Profile) Reinforce(topic, kind string, delta float64, now time.Time) {
	topic = normalizeTopic(topic)
	if topic == "" {
		return
	}
	if _, disliked := p.Dislikes[topic]; disliked && delta > 0 {
		return
	}
	in, ok := p.Interests[topic]
	if !ok {
		in = &Interest{Topic: topic, Kind: kind, FirstSeen: now, LastSeen: now}
		p.Interests[topic] = in
	}
	// Consolidar el decaimiento antes de sumar.
	in.Weight = in.EffectiveWeight(now) + delta
	in.LastSeen = now
	if delta > 0 {
		in.Mentions++
	}
	// Un gusto explícito o el trabajo pesan más que una palabra suelta.
	if kindRank(kind) > kindRank(in.Kind) {
		in.Kind = kind
	}
	if in.Weight <= 0.01 {
		delete(p.Interests, topic)
	}
}

func kindRank(kind string) int {
	switch kind {
	case "trabajo":
		return 4
	case "gusto", "feedback":
		return 3
	case "actividad":
		return 2
	default:
		return 1
	}
}

// Dislike marca un tema como no deseado y lo elimina de intereses.
func (p *Profile) Dislike(topic string, now time.Time) {
	topic = normalizeTopic(topic)
	if topic == "" {
		return
	}
	p.Dislikes[topic] = now
	delete(p.Interests, topic)
}

// Forget olvida un tema concreto (interés o rechazo).
func (p *Profile) Forget(topic string) bool {
	topic = normalizeTopic(topic)
	_, a := p.Interests[topic]
	_, b := p.Dislikes[topic]
	delete(p.Interests, topic)
	delete(p.Dislikes, topic)
	delete(p.Routines, topic)
	return a || b
}

// TopInterests intereses ordenados por peso efectivo.
func (p *Profile) TopInterests(n int, now time.Time) []*Interest {
	list := make([]*Interest, 0, len(p.Interests))
	for _, in := range p.Interests {
		list = append(list, in)
	}
	sort.Slice(list, func(a, b int) bool {
		wa, wb := list[a].EffectiveWeight(now), list[b].EffectiveWeight(now)
		if wa != wb {
			return wa > wb
		}
		return list[a].Topic < list[b].Topic
	})
	if n > 0 && len(list) > n {
		list = list[:n]
	}
	return list
}

// MarkActive registra que el dueño está activo ahora (ritmo de vida).
func (p *Profile) MarkActive(now time.Time) {
	p.Rhythm[int(now.Weekday())][now.Hour()]++
	p.LastActiveAt = now
}

// RecordActivity aprende una actividad: cuándo ocurre y qué la precede.
func (p *Profile) RecordActivity(activity string, now time.Time) {
	activity = normalizeTopic(activity)
	if activity == "" {
		return
	}
	r, ok := p.Routines[activity]
	if !ok {
		r = &Routine{Activity: activity}
		p.Routines[activity] = r
	}
	r.HourCounts[now.Hour()]++
	r.DayCounts[int(now.Weekday())]++
	r.Count++
	r.LastSeen = now

	if prev := p.LastActivity; prev != "" && prev != activity {
		if p.Transitions[prev] == nil {
			p.Transitions[prev] = make(map[string]int)
		}
		p.Transitions[prev][activity]++
	}
	p.LastActivity = activity
}

// Prediction lo que Cookie cree que harás.
type Prediction struct {
	Activity   string
	Confidence float64
	Reason     string
}

// PredictNext combina la rutina horaria y la transición desde la última
// actividad para adivinar qué harás a continuación.
func (p *Profile) PredictNext(now time.Time) *Prediction {
	scores := make(map[string]float64)
	reasons := make(map[string]string)

	// 1. ¿Qué sueles hacer a esta hora? (ventana de ±1h)
	h := now.Hour()
	for name, r := range p.Routines {
		if r.Count < 2 {
			continue
		}
		hits := r.HourCounts[h] + r.HourCounts[(h+1)%24] + r.HourCounts[(h+23)%24]
		if hits == 0 {
			continue
		}
		scores[name] += 0.6 * float64(hits) / float64(r.Count)
		reasons[name] = "sueles hacerlo a esta hora"
	}

	// 2. ¿Qué sigue normalmente a lo último que hiciste?
	if next := p.Transitions[p.LastActivity]; len(next) > 0 {
		total := 0
		for _, c := range next {
			total += c
		}
		for name, c := range next {
			if c < 2 {
				continue
			}
			scores[name] += 0.4 * float64(c) / float64(total)
			if reasons[name] == "" {
				reasons[name] = "suele venir después de " + p.LastActivity
			} else {
				reasons[name] += " y después de " + p.LastActivity
			}
		}
	}

	var best string
	var bestScore float64
	for name, s := range scores {
		if s > bestScore || (s == bestScore && name < best) {
			best, bestScore = name, s
		}
	}
	if best == "" {
		return nil
	}
	return &Prediction{Activity: best, Confidence: math.Min(bestScore, 1), Reason: reasons[best]}
}

// DefaultDeliveryHour hora del resumen mientras no haya datos de ritmo.
const DefaultDeliveryHour = 8

// PeakHours horas del día (0-23) en que sueles estar activo ese día,
// ordenadas de más a menos actividad.
func (p *Profile) PeakHours(day time.Weekday) []int {
	type hc struct{ hour, count int }
	var list []hc
	for h := 0; h < 24; h++ {
		// Mezcla el día concreto con el total semanal para tener señal pronto.
		c := p.Rhythm[int(day)][h] * 3
		for d := 0; d < 7; d++ {
			c += p.Rhythm[d][h]
		}
		if c > 0 {
			list = append(list, hc{h, c})
		}
	}
	sort.Slice(list, func(a, b int) bool {
		if list[a].count != list[b].count {
			return list[a].count > list[b].count
		}
		return list[a].hour < list[b].hour
	})
	hours := make([]int, len(list))
	for i, x := range list {
		hours[i] = x.hour
	}
	return hours
}

// IsGoodMoment ¿es buen momento para interrumpirte con novedades?
// Sí si esta hora está entre tus 3 horas más activas del día
// (o es la hora por defecto mientras Cookie aún no te conoce).
func (p *Profile) IsGoodMoment(now time.Time) bool {
	peaks := p.PeakHours(now.Weekday())
	if p.Observations < 10 || len(peaks) == 0 {
		return now.Hour() == DefaultDeliveryHour
	}
	if len(peaks) > 3 {
		peaks = peaks[:3]
	}
	for _, h := range peaks {
		if h == now.Hour() {
			return true
		}
	}
	return false
}

// normalizeTopic minúsculas, sin artículos ni espacios sobrantes.
func normalizeTopic(s string) string {
	s = strings.ToLower(strings.TrimSpace(s))
	s = strings.Trim(s, " .,;:!¡?¿\"'()[]")
	words := strings.Fields(s)
	for len(words) > 0 && leadingFiller[words[0]] {
		words = words[1:]
	}
	for len(words) > 0 && trailingFiller[words[len(words)-1]] {
		words = words[:len(words)-1]
	}
	if len(words) > 4 {
		words = words[:4]
	}
	return strings.Join(words, " ")
}

var leadingFiller = map[string]bool{
	"el": true, "la": true, "los": true, "las": true, "un": true, "una": true,
	"unos": true, "unas": true, "de": true, "del": true, "al": true, "a": true,
	"mucho": true, "mucha": true, "muchos": true, "muchas": true, "muchísimo": true,
	"bastante": true, "todo": true, "toda": true, "lo": true, "mi": true, "mis": true,
	"sobre": true, "que": true, "ver": true, "leer": true, "hacer": true,
}

var trailingFiller = map[string]bool{
	"también": true, "tambien": true, "mucho": true, "muchísimo": true, "hoy": true,
	"ayer": true, "ahora": true, "siempre": true, "bastante": true,
}
