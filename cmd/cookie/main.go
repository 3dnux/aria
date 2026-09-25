// Cookie: el asistente de ARIA que aprende tu ritmo de vida, investiga
// por su cuenta y te mantiene al día.
//
//	cookie aprender "Trabajo como diseñadora y me encanta la fotografía"
//	cookie perfil
//	cookie investigar
//	cookie resumen
//	cookie vivir            # modo autónomo
package main

import (
	"bufio"
	"context"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"sort"
	"strings"
	"syscall"
	"time"

	"aria/internal/cookie"
)

const usage = `🍪 Cookie — tu asistente personal de ARIA

Uso: cookie [--datos RUTA] <comando> [argumentos]

Comandos:
  aprender "texto"     Cuéntale algo de ti (o "-" para leer de stdin, una frase por línea)
  perfil               Muestra todo lo que Cookie sabe de ti
  investigar           Investiga ahora mismo en tus temas
  resumen              Muestra tu resumen personal con lo nuevo
  util ID              Ese hallazgo te sirvió (refuerza el tema)
  nomeinteresa ID      Ese hallazgo no te sirvió (debilita el tema)
  olvidar TEMA         Olvida un tema concreto
  olvidar --todo       Borra todo lo que Cookie sabe de ti
  vivir                Modo autónomo: investiga solo y te avisa cuando sueles estar disponible
  demo                 Demostración sin conexión

Fuentes: Google News RSS (siempre) + Claude con búsqueda web si hay ANTHROPIC_API_KEY.
Datos: %s (solo en tu máquina)
`

func main() {
	global := flag.NewFlagSet("cookie", flag.ExitOnError)
	dataPath := global.String("datos", cookie.DefaultPath(), "archivo donde Cookie guarda lo que sabe")
	noClaude := global.Bool("sin-claude", false, "no usar Claude aunque haya API key")
	global.Usage = func() { fmt.Fprintf(os.Stderr, usage, *dataPath) }
	global.Parse(os.Args[1:])
	args := global.Args()
	if len(args) == 0 {
		global.Usage()
		os.Exit(2)
	}

	cmd, rest := args[0], args[1:]
	if cmd == "demo" {
		runDemo()
		return
	}

	sources := []cookie.Source{cookie.NewRSSSource()}
	if !*noClaude && cookie.ClaudeAvailable() {
		sources = append(sources, cookie.NewClaudeSource())
	}
	c, err := cookie.Open(&cookie.Store{Path: *dataPath}, sources...)
	check(err)
	c.Log = os.Stderr

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	switch cmd {
	case "aprender":
		cmdLearn(c, rest)
	case "perfil":
		printProfile(c)
		c.Touch()
	case "investigar":
		fmt.Println("🔎 Investigando en:", topicNames(c.Topics(6)))
		n, err := c.Research(ctx)
		check(err)
		fmt.Printf("✅ %d hallazgos nuevos. Usa 'cookie resumen' para verlos.\n", n)
	case "resumen":
		fmt.Print(c.Briefing(intArg(rest, 5), true))
	case "util", "nomeinteresa":
		if len(rest) == 0 {
			fail("indica el id del hallazgo")
		}
		it, err := c.Feedback(rest[0], cmd == "util")
		check(err)
		if cmd == "util" {
			fmt.Printf("👍 Anotado: buscaré más sobre «%s».\n", it.Topic)
		} else {
			fmt.Printf("👎 Anotado: te mostraré menos sobre «%s».\n", it.Topic)
		}
	case "olvidar":
		if len(rest) == 0 {
			fail("indica un tema o --todo")
		}
		if rest[0] == "--todo" {
			check(c.Store.Wipe())
			fmt.Println("🧹 Listo. He olvidado todo lo que sabía de ti.")
			return
		}
		topic := strings.Join(rest, " ")
		if c.Profile().Forget(topic) {
			fmt.Printf("🧹 He olvidado «%s».\n", topic)
		} else {
			fmt.Printf("No sabía nada de «%s».\n", topic)
		}
	case "vivir":
		cmdLive(ctx, c, rest)
		return
	default:
		global.Usage()
		os.Exit(2)
	}
	check(c.Save())
}

func cmdLearn(c *cookie.Cookie, args []string) {
	var lines []string
	if len(args) == 1 && args[0] == "-" {
		sc := bufio.NewScanner(os.Stdin)
		for sc.Scan() {
			if l := strings.TrimSpace(sc.Text()); l != "" {
				lines = append(lines, l)
			}
		}
	} else if len(args) > 0 {
		lines = []string{strings.Join(args, " ")}
	} else {
		fail(`cuéntame algo, p. ej.: cookie aprender "Trabajo como enfermera y me encanta el senderismo"`)
	}
	for _, l := range lines {
		signals := c.Learn(l)
		if len(signals) == 0 {
			fmt.Println("🤔 Lo guardé en tu ritmo, pero no entendí nada concreto.")
			continue
		}
		fmt.Println("🧠 Aprendí:")
		for _, s := range signals {
			if s.Kind == "palabra" {
				continue
			}
			fmt.Printf("   • %s: %s\n", s.Kind, s.Value)
		}
	}
}

func cmdLive(ctx context.Context, c *cookie.Cookie, args []string) {
	fs := flag.NewFlagSet("vivir", flag.ExitOnError)
	every := fs.Duration("cada", 3*time.Hour, "cada cuánto investigar")
	gap := fs.Duration("entre-resumenes", 6*time.Hour, "tiempo mínimo entre resúmenes")
	tick := fs.Duration("tick", time.Minute, "cada cuánto despertar a revisar")
	fs.Parse(args)

	fmt.Printf("🍪 Cookie en modo autónomo. Investigo cada %v y te aviso en tus horas activas. Ctrl+C para parar.\n", *every)
	err := c.Run(ctx, cookie.RunOptions{
		Tick: *tick, ResearchEvery: *every, MinGap: *gap, MaxItems: 5,
		Notify: func(b *cookie.Briefing) {
			fmt.Print("\a\n", b, "\n")
		},
	})
	if err != nil && err != context.Canceled {
		check(err)
	}
	fmt.Println("\n👋 Cookie en pausa. Lo aprendido queda guardado.")
}

func printProfile(c *cookie.Cookie) {
	p := c.Profile()
	now := time.Now()
	fmt.Println("🍪 Esto es lo que sé de ti")
	fmt.Println(strings.Repeat("─", 50))
	field := func(k, v string) {
		if v == "" {
			v = "(aún no lo sé)"
		}
		fmt.Printf("%-12s %s\n", k, v)
	}
	field("Nombre:", p.Name)
	field("Trabajo:", p.Occupation)
	field("Vives en:", p.Location)
	fmt.Printf("%-12s %d\n", "Charlas:", p.Observations)

	fmt.Println("\n❤️  Intereses (peso actual):")
	for _, in := range p.TopInterests(12, now) {
		bar := strings.Repeat("█", min(20, int(in.EffectiveWeight(now)*2)+1))
		fmt.Printf("   %-22s %-20s %s\n", in.Topic, bar, in.Kind)
	}
	if len(p.Dislikes) > 0 {
		var ds []string
		for d := range p.Dislikes {
			ds = append(ds, d)
		}
		sort.Strings(ds)
		fmt.Println("\n🚫 No te interesa:", strings.Join(ds, ", "))
	}
	if len(p.Routines) > 0 {
		fmt.Println("\n🔁 Rutinas:")
		for _, r := range p.Routines {
			best := 0
			for h, n := range r.HourCounts {
				if n > r.HourCounts[best] {
					best = h
				}
			}
			fmt.Printf("   %-20s %d veces, normalmente ~%02d:00\n", r.Activity, r.Count, best)
		}
	}
	if peaks := p.PeakHours(now.Weekday()); len(peaks) > 0 {
		if len(peaks) > 3 {
			peaks = peaks[:3]
		}
		var hs []string
		for _, h := range peaks {
			hs = append(hs, fmt.Sprintf("%02d:00", h))
		}
		fmt.Println("\n⏰ Sueles estar activo a las:", strings.Join(hs, ", "))
	}
	if pred := p.PredictNext(now); pred != nil {
		fmt.Printf("🔮 Ahora probablemente: %s (%s)\n", pred.Activity, pred.Reason)
	}
	fmt.Println("\n🔎 Investigaré sobre:", topicNames(c.Topics(6)))
	fmt.Printf("📥 Bandeja: %d sin leer\n", len(c.Unread()))
}

// runDemo simula una semana de vida sin conexión.
func runDemo() {
	dir, err := os.MkdirTemp("", "cookie-demo")
	check(err)
	defer os.RemoveAll(dir)

	now := time.Date(2026, 3, 2, 7, 30, 0, 0, time.Local) // lunes
	src := &cookie.StaticSource{Items: []*cookie.Item{
		{Title: "Nuevo modelo de IA generativa acelera el diseño de interfaces", Summary: "Herramientas de diseño gráfico integran generación de imágenes.", URL: "https://ejemplo.com/ia-diseno", Published: now.Add(-3 * time.Hour)},
		{Title: "Guía: fotografía nocturna con el móvil", URL: "https://ejemplo.com/foto-noche", Published: now.Add(-10 * time.Hour)},
		{Title: "Rutina de fuerza de 30 minutos para antes del trabajo", Summary: "Ideal para quien entrena en el gimnasio temprano.", URL: "https://ejemplo.com/gimnasio", Published: now.Add(-20 * time.Hour)},
		{Title: "Resultados de la jornada de fútbol", URL: "https://ejemplo.com/futbol", Published: now.Add(-1 * time.Hour)},
		{Title: "Precio del petróleo sube 2%", URL: "https://ejemplo.com/petroleo", Published: now},
	}}
	c, err := cookie.Open(&cookie.Store{Path: filepath.Join(dir, "cookie.json")}, src)
	check(err)
	c.Now = func() time.Time { return now }

	say := func(text string) {
		fmt.Printf("🗣️  %s  «%s»\n", now.Format("Mon 15:04"), text)
		c.Learn(text)
	}

	fmt.Println("═══ Una semana con Cookie (simulada) ═══")
	say("Me llamo Sofía, trabajo como diseñadora gráfica y vivo en Monterrey")
	say("Me encanta la fotografía y me interesa la inteligencia artificial, pero no me gusta el fútbol")
	for d := 0; d < 5; d++ {
		day := time.Date(2026, 3, 2+d, 0, 0, 0, 0, time.Local)
		now = day.Add(7 * time.Hour)
		c.Learn("Voy al gimnasio")
		now = day.Add(9 * time.Hour)
		c.Learn("Acabo de llegar a la oficina, hoy toca diseño de interfaces")
		now = day.Add(21 * time.Hour)
		c.Touch()
	}
	fmt.Println("   … (5 días de rutina: gimnasio 7:00 → oficina 9:00, te conectas a las 21:00)")

	now = time.Date(2026, 3, 9, 7, 5, 0, 0, time.Local)
	fmt.Println()
	printProfileTo(c)

	fmt.Println("\n═══ Cookie investiga por su cuenta (sin que se lo pidas) ═══")
	n, err := c.Research(context.Background())
	check(err)
	fmt.Printf("🔎 %d hallazgos relevantes de %d (descartó fútbol y lo que no te importa)\n\n", n, len(src.Items))
	fmt.Print(c.Briefing(5, true))
}

func printProfileTo(c *cookie.Cookie) {
	p := c.Profile()
	now := c.Now()
	fmt.Printf("🍪 Perfil de %s — %s en %s\n", p.Name, p.Occupation, p.Location)
	for _, in := range p.TopInterests(6, now) {
		fmt.Printf("   ❤️  %-24s peso %.1f (%s)\n", in.Topic, in.EffectiveWeight(now), in.Kind)
	}
	if pred := p.PredictNext(now); pred != nil {
		fmt.Printf("   🔮 Son las %s: creo que vas a %s (%s)\n", now.Format("15:04"), pred.Activity, pred.Reason)
	}
}

func topicNames(ts []cookie.Topic) string {
	if len(ts) == 0 {
		return "(nada aún — usa 'cookie aprender')"
	}
	names := make([]string, len(ts))
	for i, t := range ts {
		names[i] = t.Name
	}
	return strings.Join(names, ", ")
}

func intArg(args []string, def int) int {
	var n int
	if len(args) > 0 {
		if _, err := fmt.Sscan(args[0], &n); err == nil && n > 0 {
			return n
		}
	}
	return def
}

func check(err error) {
	if err != nil {
		fail(err.Error())
	}
}

func fail(msg string) {
	fmt.Fprintln(os.Stderr, "❌", msg)
	os.Exit(1)
}
