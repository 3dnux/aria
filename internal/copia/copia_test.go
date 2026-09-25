package copia

import (
	"os"
	"testing"
)

const fixture = "../../testdata/aria/copia.cookie"

// La copia de prueba la genera Go y la descifran tanto Go como la app Kotlin.
func TestDecryptSharedBackup(t *testing.T) {
	if src := os.Getenv("ARIA_MAKE_BACKUP"); src != "" {
		plain, err := os.ReadFile(src)
		if err != nil {
			t.Fatal(err)
		}
		data, err := Encrypt(plain, "contraseña-aria")
		if err != nil {
			t.Fatal(err)
		}
		os.WriteFile(fixture, data, 0o644)
	}
	data, err := os.ReadFile(fixture)
	if err != nil {
		t.Fatal(err)
	}
	s, err := Load(data, "contraseña-aria")
	if err != nil {
		t.Fatal(err)
	}
	if s.Profile.Name != "Prueba" || len(s.Timeline) != 170 || len(s.Memories) != 2 {
		t.Fatalf("estado inesperado: %s, %d eventos, %d recuerdos", s.Profile.Name, len(s.Timeline), len(s.Memories))
	}
	if _, err := Load(data, "otra"); err == nil {
		t.Fatal("una contraseña incorrecta debe fallar")
	}
}
