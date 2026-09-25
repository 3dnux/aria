package earlyexit

import "testing"

func TestGateExitsOnConfidenceAndConsensus(t *testing.T) {
	g := NewGate()
	d := g.Decide([]Candidate{{Source: "rutina", Answer: "gimnasio", Key: "gimnasio", Confidence: 0.6}})
	if d.Exit {
		t.Fatal("0.6 no debería bastar con umbral 0.7")
	}
	d = g.Decide([]Candidate{
		{Source: "rutina", Answer: "Normalmente vas al gimnasio", Key: "gimnasio", Confidence: 0.6},
		{Source: "contexto", Answer: "gimnasio", Key: "gimnasio", Confidence: 0.5},
	})
	if !d.Exit || d.Answer != "Normalmente vas al gimnasio" {
		t.Fatalf("el consenso debería permitir salir: %+v", d)
	}
	if g.Decide(nil).Exit {
		t.Fatal("sin candidatos no se sale")
	}
}

func TestGateLearnsFromCorrections(t *testing.T) {
	g := NewGate()
	g.Observe(0.9, false)
	if g.Threshold < 0.92 {
		t.Fatalf("un error confiado debe subir el umbral por encima de su confianza: %.2f", g.Threshold)
	}
	for i := 0; i < 100; i++ {
		g.Observe(0.8, true)
	}
	if g.Threshold != g.Min {
		t.Fatalf("muchos aciertos deben bajarlo hasta el mínimo: %.2f", g.Threshold)
	}
	if acc, ok := g.Accuracy(); !ok || acc < 0.99 {
		t.Fatalf("precisión %.2f", acc)
	}
}
