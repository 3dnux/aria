package cookie

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"time"
)

// State todo lo que Cookie persiste: el perfil y la bandeja de hallazgos.
type State struct {
	Version      int              `json:"version"`
	Profile      *Profile         `json:"profile"`
	Inbox        []*Item          `json:"inbox"`
	Seen         map[string]int64 `json:"seen"` // id → unix de cuando se vio
	LastResearch time.Time        `json:"last_research"`
	LastBriefing time.Time        `json:"last_briefing"`
}

// Store persistencia local en JSON. Nada sale de tu máquina salvo las
// búsquedas que Cookie hace en tu nombre.
type Store struct {
	Path string
}

// DefaultPath ~/.aria/cookie.json (o $ARIA_HOME/cookie.json).
func DefaultPath() string {
	if dir := os.Getenv("ARIA_HOME"); dir != "" {
		return filepath.Join(dir, "cookie.json")
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "cookie.json"
	}
	return filepath.Join(home, ".aria", "cookie.json")
}

// Load lee el estado; si no existe devuelve uno nuevo.
func (s *Store) Load(now time.Time) (*State, error) {
	data, err := os.ReadFile(s.Path)
	if errors.Is(err, os.ErrNotExist) {
		return newState(now), nil
	}
	if err != nil {
		return nil, err
	}
	st := &State{}
	if err := json.Unmarshal(data, st); err != nil {
		return nil, err
	}
	if st.Profile == nil {
		st.Profile = NewProfile(now)
	}
	st.Profile.ensure()
	if st.Seen == nil {
		st.Seen = make(map[string]int64)
	}
	return st, nil
}

// Save escribe de forma atómica y solo legible por el dueño (0600).
func (s *Store) Save(st *State) error {
	if err := os.MkdirAll(filepath.Dir(s.Path), 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(st, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.Path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.Path)
}

// Wipe borra todo lo que Cookie sabe.
func (s *Store) Wipe() error {
	err := os.Remove(s.Path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return err
}

func newState(now time.Time) *State {
	return &State{
		Version: 1,
		Profile: NewProfile(now),
		Seen:    make(map[string]int64),
	}
}
