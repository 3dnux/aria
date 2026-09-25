package temporal

import (
	"encoding/json"
	"math"
	"os"
	"testing"
)

func loadTimeline(t *testing.T) []Event {
	data, err := os.ReadFile("../../testdata/aria/timeline.json")
	if err != nil {
		t.Fatal(err)
	}
	var ev []Event
	if err := json.Unmarshal(data, &ev); err != nil {
		t.Fatal(err)
	}
	return ev
}

// Encuentra los patrones escondidos en 30 días sintéticos y no inventa otros
// con lo que pasa todos los días (Instagram cada noche).
func TestMineFindsHiddenPatterns(t *testing.T) {
	got := Mine(loadTimeline(t), DefaultConfig())
	find := func(c, e string) *Pattern {
		for i := range got {
			if got[i].Cause == c && got[i].Effect == e {
				return &got[i]
			}
		}
		return nil
	}
	sleep := find("sueño:corto", "estrés:alto")
	if sleep == nil || sleep.WindowHours != 24 || sleep.Lift < 2 {
		t.Fatalf("no encontró sueño corto → estrés: %+v", got)
	}
	gym := find("lugar:gimnasio", "música:bad bunny")
	if gym == nil || gym.WindowHours != 3 || gym.Support < 10 {
		t.Fatalf("no encontró gimnasio → Bad Bunny: %+v", gym)
	}
	for _, p := range got {
		if p.Cause == "app:instagram" || p.Effect == "app:instagram" {
			t.Errorf("patrón espurio con algo diario: %s", Describe(p))
		}
	}
	t.Log(Describe(*sleep))
	t.Log(Describe(*gym))

	// Resultado de referencia para la versión Kotlin (misma salida exacta).
	ref := "../../testdata/aria/patterns_expected.json"
	if os.Getenv("ARIA_UPDATE") == "1" {
		data, _ := json.MarshalIndent(got, "", " ")
		if err := os.WriteFile(ref, data, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	data, err := os.ReadFile(ref)
	if err != nil {
		t.Fatal(err)
	}
	var want []Pattern
	if err := json.Unmarshal(data, &want); err != nil {
		t.Fatal(err)
	}
	if len(want) != len(got) {
		t.Fatalf("referencia con %d patrones, salida con %d", len(want), len(got))
	}
	for i := range want {
		if want[i].Cause != got[i].Cause || want[i].Effect != got[i].Effect || math.Abs(want[i].Lift-got[i].Lift) > 1e-9 {
			t.Errorf("patrón %d distinto: %+v vs %+v", i, want[i], got[i])
		}
	}
}

func TestSymbols(t *testing.T) {
	cases := map[Event]string{
		{Kind: "sueño", Text: "a dormir (5h 10min)"}:           "sueño:corto",
		{Kind: "sueño", Text: "a dormir (7h 0min)"}:            "sueño:normal",
		{Kind: "app", Text: "Instagram 15 min"}:                "app:instagram",
		{Kind: "mensaje", Text: "mensaje de Laura (WhatsApp)"}: "mensaje:laura",
		{Kind: "fotos", Text: "12 fotos"}:                      "fotos:muchas",
		{Kind: "dijo", Text: "hola"}:                           "",
	}
	for e, want := range cases {
		if got := Symbol(e); got != want {
			t.Errorf("%+v → %q, esperaba %q", e, got, want)
		}
	}
}
