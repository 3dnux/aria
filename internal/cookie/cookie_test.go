package cookie

import (
	"context"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func kinds(sig []Signal) map[string][]string {
	m := make(map[string][]string)
	for _, s := range sig {
		m[s.Kind] = append(m[s.Kind], s.Value)
	}
	return m
}

func has(list []string, v string) bool {
	for _, x := range list {
		if x == v {
			return true
		}
	}
	return false
}

func TestExtract(t *testing.T) {
	k := kinds(Extract("Me llamo Ana, trabajo como diseñadora gráfica y vivo en Monterrey. " +
		"Me encanta la fórmula 1 y la fotografía, pero no me gusta el fútbol. Hoy fui al gimnasio."))

	if !has(k["nombre"], "Ana") {
		t.Errorf("nombre: %v", k["nombre"])
	}
	if !has(k["trabajo"], "diseñadora gráfica") {
		t.Errorf("trabajo: %v", k["trabajo"])
	}
	if !has(k["lugar"], "Monterrey") {
		t.Errorf("lugar: %v", k["lugar"])
	}
	if !has(k["gusto"], "fórmula 1") || !has(k["gusto"], "fotografía") {
		t.Errorf("gustos: %v", k["gusto"])
	}
	if has(k["gusto"], "fútbol") || !has(k["rechazo"], "fútbol") {
		t.Errorf("fútbol debe ser rechazo: gustos=%v rechazos=%v", k["gusto"], k["rechazo"])
	}
	if !has(k["actividad"], "gimnasio") {
		t.Errorf("actividad: %v", k["actividad"])
	}
}

func TestInterestDecayAndDislike(t *testing.T) {
	now := time.Date(2026, 1, 1, 9, 0, 0, 0, time.UTC)
	p := NewProfile(now)
	p.Reinforce("jazz", "gusto", 2, now)
	if w := p.Interests["jazz"].EffectiveWeight(now.Add(InterestHalfLife)); w < 0.99 || w > 1.01 {
		t.Fatalf("tras una vida media el peso debería ser ~1, es %.2f", w)
	}
	p.Dislike("jazz", now)
	p.Reinforce("jazz", "gusto", 2, now)
	if _, ok := p.Interests["jazz"]; ok {
		t.Fatal("un tema rechazado no debe volver a ser interés")
	}
}

func TestRoutinePrediction(t *testing.T) {
	base := time.Date(2026, 1, 5, 7, 0, 0, 0, time.UTC) // lunes
	p := NewProfile(base)
	for d := 0; d < 5; d++ {
		day := base.AddDate(0, 0, d)
		p.RecordActivity("correr", day)
		p.RecordActivity("oficina", day.Add(2*time.Hour))
	}
	pred := p.PredictNext(base.AddDate(0, 0, 7).Add(2 * time.Hour))
	if pred == nil || pred.Activity != "oficina" {
		t.Fatalf("esperaba oficina, obtuve %+v", pred)
	}
}

func TestGoodMomentFollowsRhythm(t *testing.T) {
	base := time.Date(2026, 1, 5, 21, 0, 0, 0, time.UTC)
	p := NewProfile(base)
	for i := 0; i < 12; i++ {
		p.MarkActive(base.AddDate(0, 0, i))
		p.Observations++
	}
	if !p.IsGoodMoment(base.AddDate(0, 0, 20)) {
		t.Error("las 21h deberían ser buen momento")
	}
	if p.IsGoodMoment(base.AddDate(0, 0, 20).Add(-12 * time.Hour)) {
		t.Error("las 9h no deberían ser buen momento")
	}
}

const feed = `<?xml version="1.0"?><rss><channel>
<item><title>Nueva cámara mirrorless para fotografía nocturna</title><link>https://ej.com/1</link>
<description>&lt;b&gt;Lanzamiento&lt;/b&gt; importante</description><pubDate>Mon, 05 Jan 2026 08:00:00 GMT</pubDate><source>Ej</source></item>
<item><title>Resultados del fútbol del domingo</title><link>https://ej.com/2</link><pubDate>Mon, 05 Jan 2026 08:00:00 GMT</pubDate></item>
<item><title>Noticia vieja de fotografía</title><link>https://ej.com/3</link><pubDate>Mon, 01 Dec 2025 08:00:00 GMT</pubDate></item>
</channel></rss>`

func TestResearchWithRSSAndBriefing(t *testing.T) {
	var queries []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		queries = append(queries, r.URL.Query().Get("q"))
		w.Write([]byte(feed))
	}))
	defer srv.Close()

	now := time.Date(2026, 1, 5, 12, 0, 0, 0, time.UTC)
	rss := NewRSSSource()
	rss.SearchURL = srv.URL + "?q=%s"
	c, err := Open(&Store{Path: filepath.Join(t.TempDir(), "c.json")}, rss)
	if err != nil {
		t.Fatal(err)
	}
	c.Now = func() time.Time { return now }
	c.Learn("Me llamo Ana. Me encanta la fotografía y no me gusta el fútbol")

	n, err := c.Research(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Fatalf("esperaba 1 hallazgo (sin fútbol ni viejos), hubo %d: %+v", n, c.State.Inbox)
	}
	if !has(queries, "fotografía") {
		t.Errorf("no buscó fotografía: %v", queries)
	}

	b := c.Briefing(5, true)
	out := b.String()
	if !strings.Contains(out, "Ana") || !strings.Contains(out, "mirrorless") || strings.Contains(out, "<b>") {
		t.Errorf("resumen inesperado:\n%s", out)
	}
	if len(c.Unread()) != 0 {
		t.Error("tras entregar no debería quedar nada pendiente")
	}

	// Segunda investigación: no repite lo ya visto.
	if n, _ := c.Research(context.Background()); n != 0 {
		t.Errorf("repitió %d hallazgos", n)
	}

	// Persistencia.
	if err := c.Save(); err != nil {
		t.Fatal(err)
	}
	c2, err := Open(c.Store)
	if err != nil {
		t.Fatal(err)
	}
	if c2.Profile().Name != "Ana" || len(c2.State.Inbox) != 1 {
		t.Errorf("no persistió: %+v", c2.Profile())
	}
}

func TestFeedbackAndAutonomousStep(t *testing.T) {
	now := time.Date(2026, 1, 5, 8, 0, 0, 0, time.UTC) // hora por defecto de entrega
	src := &StaticSource{Items: []*Item{
		{Title: "Lanzan nuevo framework de Go para IA", URL: "https://ej.com/go", Published: now.Add(-time.Hour)},
		{Title: "Receta de cocina", URL: "https://ej.com/cocina", Published: now},
	}}
	c, err := Open(&Store{Path: filepath.Join(t.TempDir(), "c.json")}, src)
	if err != nil {
		t.Fatal(err)
	}
	c.Now = func() time.Time { return now }
	c.Learn("Me dedico a programar en go y me interesa la ia")
	if err := c.Save(); err != nil {
		t.Fatal(err)
	}

	var got *Briefing
	if err := c.Step(context.Background(), RunOptions{ResearchEvery: time.Hour, MinGap: time.Hour,
		MaxItems: 5, Notify: func(b *Briefing) { got = b }}); err != nil {
		t.Fatal(err)
	}
	if got == nil || len(got.Items) != 1 || !strings.Contains(got.Items[0].Title, "Go") {
		t.Fatalf("el modo autónomo debió entregar 1 hallazgo sobre Go: %+v", got)
	}

	before := c.Profile().Interests[got.Items[0].Topic].Weight
	if _, err := c.Feedback(got.Items[0].ID, true); err != nil {
		t.Fatal(err)
	}
	if after := c.Profile().Interests[got.Items[0].Topic].Weight; after <= before {
		t.Errorf("el feedback positivo debería reforzar el tema (%.2f → %.2f)", before, after)
	}
}

func TestExtractChainedLikesAndMotion(t *testing.T) {
	k := kinds(Extract("Me encanta la fotografía y me interesa la inteligencia artificial. Acabo de llegar a la oficina"))
	if !has(k["gusto"], "inteligencia artificial") {
		t.Errorf("gustos: %v", k["gusto"])
	}
	if !has(k["actividad"], "oficina") || has(k["actividad"], "llegar") {
		t.Errorf("actividad: %v", k["actividad"])
	}
	if matchScore("nuevo modelo de ia generativa", "inteligencia artificial") != 1 {
		t.Error("IA debería encajar con inteligencia artificial")
	}
}

func TestParseClaudeItems(t *testing.T) {
	items, err := parseClaudeItems("Aquí va:\n[{\"title\":\"A\",\"url\":\"https://x/a\",\"topic\":\"La Fotografía\",\"summary\":\"s\",\"why\":\"w\",\"published\":\"2026-01-02\"}]", time.Now())
	if err != nil || len(items) != 1 || items[0].Topic != "fotografía" || items[0].Published.IsZero() {
		t.Fatalf("items=%+v err=%v", items, err)
	}
	if _, err := parseClaudeItems("sin json", time.Now()); err == nil {
		t.Fatal("esperaba error")
	}
}
