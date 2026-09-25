package liquid

import (
	"encoding/json"
	"os"
	"testing"
)

type routerCase struct {
	Q      string `json:"q"`
	Tier   string `json:"tier"`
	Intent string `json:"intent"`
	Effort string `json:"effort"`
	Web    *bool  `json:"web"`
}

// Casos compartidos con la app Android (mismo resultado en Go y Kotlin).
func TestRouterSharedCases(t *testing.T) {
	data, err := os.ReadFile("../../testdata/aria/router_cases.json")
	if err != nil {
		t.Fatal(err)
	}
	var cases []routerCase
	if err := json.Unmarshal(data, &cases); err != nil {
		t.Fatal(err)
	}
	r := NewRouter()
	for _, c := range cases {
		got := r.Route(c.Q)
		if string(got.Tier) != c.Tier || (c.Intent != "" && got.Intent != c.Intent) ||
			(c.Effort != "" && string(got.Effort) != c.Effort) || (c.Web != nil && (got.WebSearches > 0) != *c.Web) {
			t.Errorf("%q → %+v; esperaba %+v", c.Q, got, c)
		}
	}
}
