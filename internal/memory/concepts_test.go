package memory

import "testing"

func TestSpreadFindsAssociations(t *testing.T) {
	g := NewConceptGraph()
	g.AddDocument([]string{"maraton", "abril", "entren"}, 1)
	g.AddDocument([]string{"entren", "rodill", "dolor"}, 1)
	g.AddDocument([]string{"maraton", "dorm", "temprano"}, 1)
	g.AddDocument([]string{"sushi", "laura", "viern"}, 1)
	act := g.Spread([]string{"maraton"}, 2, 0.5, 0.05)
	has := map[string]bool{}
	for _, a := range act {
		has[a.Concept] = true
	}
	if !has["entren"] || !has["dorm"] || !has["rodill"] {
		t.Fatalf("debería asociar maratón con entrenar, dormir y (a dos saltos) rodilla: %+v", act)
	}
	if has["sushi"] {
		t.Fatal("no debería activar conceptos sin relación")
	}
	if act[0].Level < act[len(act)-1].Level {
		t.Fatal("orden descendente")
	}
}
