package main

import (
	"context"
	"flag"
	"log"
	"net"
	"net/http"
	"os/signal"
	"sort"
	"strings"
	"sync"
	"syscall"
	"time"
)

func main() {
	envFile := flag.String("env", "", "optional KEY=VALUE environment file (does not override real env vars)")
	flag.Parse()
	log.SetFlags(log.LstdFlags)

	if *envFile != "" {
		if err := loadEnvFile(*envFile); err != nil {
			log.Fatalf("env file: %v", err)
		}
	}
	cfg, err := LoadConfig()
	if err != nil {
		log.Fatalf("config: %v", err)
	}
	auth, err := NewAuth(cfg)
	if err != nil {
		log.Fatalf("auth: %v", err)
	}
	mgr := NewManager(cfg, NewStore(cfg.DataDir))
	handler := NewAPI(cfg, auth, mgr).Handler()

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	go mgr.ReapLoop(ctx)

	b := &binder{cfg: cfg, handler: handler, servers: map[string]*http.Server{}, lastErr: map[string]string{}}
	log.Printf("cc-harness starting: data=%s claude=%s bind=%v", cfg.DataDir, cfg.ClaudeBin, cfg.Binds)
	b.run(ctx)
	b.shutdown()
	mgr.StopAll()
	log.Printf("bye")
}

// binder keeps one HTTP listener per desired address. Addresses are resolved
// again every few seconds so it copes with Tailscale coming up late, DHCP
// changes and interfaces appearing or disappearing.
type binder struct {
	cfg     Config
	handler http.Handler

	mu      sync.Mutex
	servers map[string]*http.Server
	lastErr map[string]string
}

func (b *binder) run(ctx context.Context) {
	b.reconcile()
	t := time.NewTicker(15 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			b.reconcile()
		}
	}
}

func (b *binder) reconcile() {
	want := map[string]bool{}
	for _, a := range desiredAddrs(b.cfg) {
		want[a] = true
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	for addr := range want {
		if _, ok := b.servers[addr]; ok {
			continue
		}
		ln, err := net.Listen("tcp", addr)
		if err != nil {
			if b.lastErr[addr] != err.Error() { // log each distinct failure once
				b.lastErr[addr] = err.Error()
				log.Printf("listen %s: %v (will retry)", addr, err)
			}
			continue
		}
		delete(b.lastErr, addr)
		srv := &http.Server{Handler: b.handler, ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 2 * time.Minute}
		b.servers[addr] = srv
		log.Printf("listening on http://%s", addr)
		go func() {
			if err := srv.Serve(ln); err != nil && err != http.ErrServerClosed {
				log.Printf("serve %s: %v", addr, err)
			}
		}()
	}
	for addr, srv := range b.servers {
		if !want[addr] {
			log.Printf("address %s is gone, closing listener", addr)
			srv.Close()
			delete(b.servers, addr)
		}
	}
}

func (b *binder) shutdown() {
	b.mu.Lock()
	defer b.mu.Unlock()
	for _, srv := range b.servers {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		_ = srv.Shutdown(ctx)
		cancel()
		srv.Close() // long-lived SSE streams
	}
}

// desiredAddrs expands the HARNESS_BIND entries into host:port strings.
func desiredAddrs(cfg Config) []string {
	seen := map[string]bool{}
	var out []string
	add := func(a string) {
		if !seen[a] {
			seen[a] = true
			out = append(out, a)
		}
	}
	for _, e := range cfg.Binds {
		if e == "auto" {
			for _, ip := range autoIPs() {
				add(net.JoinHostPort(ip, cfg.Port))
			}
			continue
		}
		if _, _, err := net.SplitHostPort(e); err != nil {
			e = net.JoinHostPort(strings.Trim(e, "[]"), cfg.Port)
		}
		add(e)
	}
	sort.Strings(out)
	return out
}

var (
	tailscaleNet = mustCIDR("100.64.0.0/10")
	privateNets  = []*net.IPNet{mustCIDR("10.0.0.0/8"), mustCIDR("172.16.0.0/12"), mustCIDR("192.168.0.0/16")}
	virtualIface = []string{"docker", "br-", "veth", "virbr", "lxc", "cni", "flannel", "podman", "tun", "wg"}
)

func mustCIDR(s string) *net.IPNet {
	_, n, err := net.ParseCIDR(s)
	if err != nil {
		panic(err)
	}
	return n
}

// classifyIP says whether ip on the named interface should be served.
func classifyIP(iface string, ip net.IP) bool {
	ip4 := ip.To4()
	if ip4 == nil {
		return false
	}
	if strings.HasPrefix(iface, "tailscale") {
		return tailscaleNet.Contains(ip4)
	}
	for _, p := range virtualIface {
		if strings.HasPrefix(iface, p) {
			return false
		}
	}
	for _, n := range privateNets {
		if n.Contains(ip4) {
			return true
		}
	}
	return false
}

// autoIPs returns the Tailscale address(es) and the LAN address(es) of this host.
func autoIPs() []string {
	ifs, err := net.Interfaces()
	if err != nil {
		return nil
	}
	var out []string
	for _, ifc := range ifs {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := ifc.Addrs()
		for _, a := range addrs {
			if ipn, ok := a.(*net.IPNet); ok && classifyIP(ifc.Name, ipn.IP) {
				out = append(out, ipn.IP.String())
			}
		}
	}
	return out
}
