// Package copia lee la copia de seguridad cifrada que exporta la app Cookie
// (PBKDF2-SHA256 + AES-256-GCM), para que ARIA pueda analizarla en tu ordenador.
package copia

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha256"
	"encoding/json"
	"errors"

	"aria/internal/temporal"
)

const (
	magic      = "COOKIE1"
	iterations = 150_000
)

// State la parte del estado de Cookie que ARIA usa.
type State struct {
	Profile struct {
		Name       string `json:"name"`
		Occupation string `json:"occupation"`
	} `json:"profile"`
	Timeline []temporal.Event `json:"timeline"`
	Memories []struct {
		Kind string   `json:"kind"`
		Text string   `json:"text"`
		Tags []string `json:"tags"`
	} `json:"memories"`
	Chat []struct {
		FromUser bool   `json:"fromUser"`
		Text     string `json:"text"`
	} `json:"chat"`
	Gate *struct {
		Threshold float64 `json:"threshold"`
		Correct   int     `json:"correct"`
		Wrong     int     `json:"wrong"`
	} `json:"gate"`
}

func key(password string, salt []byte) ([]byte, error) {
	return pbkdf2.Key(sha256.New, password, salt, iterations, 32)
}

// Decrypt descifra una copia y devuelve el JSON.
func Decrypt(data []byte, password string) ([]byte, error) {
	if len(data) < len(magic)+16+12+16 || string(data[:len(magic)]) != magic {
		return nil, errors.New("no es una copia de Cookie")
	}
	salt := data[len(magic) : len(magic)+16]
	iv := data[len(magic)+16 : len(magic)+28]
	k, err := key(password, salt)
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(k)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	plain, err := gcm.Open(nil, iv, data[len(magic)+28:], nil)
	if err != nil {
		return nil, errors.New("contraseña incorrecta o copia dañada")
	}
	return plain, nil
}

// Encrypt cifra en el mismo formato que la app (útil para pruebas).
func Encrypt(plain []byte, password string) ([]byte, error) {
	salt := make([]byte, 16)
	iv := make([]byte, 12)
	rand.Read(salt)
	rand.Read(iv)
	k, err := key(password, salt)
	if err != nil {
		return nil, err
	}
	block, _ := aes.NewCipher(k)
	gcm, _ := cipher.NewGCM(block)
	out := append([]byte(magic), salt...)
	out = append(out, iv...)
	return gcm.Seal(out, iv, plain, nil), nil
}

// Load descifra y decodifica.
func Load(data []byte, password string) (*State, error) {
	plain, err := Decrypt(data, password)
	if err != nil {
		return nil, err
	}
	var s State
	if err := json.Unmarshal(plain, &s); err != nil {
		return nil, err
	}
	return &s, nil
}
