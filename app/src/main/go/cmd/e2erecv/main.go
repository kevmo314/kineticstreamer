// Command e2erecv is the host side of the Android end-to-end streaming test
// (StreamingE2ETest). It listens for SRT, records the stream to MPEG-TS files and, when the
// stream ends, analyzes them. The emulator reaches it at 10.0.2.2.
//
//	go run ./cmd/e2erecv -out /tmp/e2e
//
// The first connection is cut after -seg and the listener stays down for -gap, so the app has
// to reconnect; the second connection is recorded until the app stops streaming. The test
// fetches GET /report, which blocks until both recordings have been analyzed and returns 200
// if every expectation held, 500 otherwise. The process exits after serving the report.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"time"

	kinetic "github.com/kevmo314/kinetic"
	"github.com/kevmo314/kinetic/e2e"
)

type result struct {
	Segments   []segmentResult `json:"segments"`
	RecoverSec float64         `json:"reconnect_recovery_sec"`
	Failures   []string        `json:"failures"`
}

type segmentResult struct {
	*e2e.Report
	Bytes int `json:"bytes"`
}

func main() {
	out := flag.String("out", "e2e-out", "directory for recordings and report.json")
	port := flag.Int("srt-port", 9000, "SRT listen port")
	httpAddr := flag.String("http", "127.0.0.1:9100", "report/health listen address")
	seg := flag.Duration("seg", 12*time.Second, "how long to record the first connection before cutting it")
	gap := flag.Duration("gap", 4*time.Second, "how long the listener stays down between connections")
	maxRecovery := flag.Duration("max-recovery", 10*time.Second, "reconnect must deliver data within this long of the listener coming back")
	analyzeOnly := flag.String("analyze", "", "analyze this recording, print the report and exit")
	flag.Parse()

	if *analyzeOnly != "" {
		rep, err := e2e.Analyze(*analyzeOnly, e2e.DefaultExpectations())
		if err != nil {
			log.Fatal(err)
		}
		b, _ := json.MarshalIndent(rep, "", "  ")
		fmt.Println(string(b))
		return
	}

	if err := os.MkdirAll(*out, 0o755); err != nil {
		log.Fatal(err)
	}
	paths := []string{filepath.Join(*out, "segment1.ts"), filepath.Join(*out, "segment2.ts")}

	done := make(chan *result, 1)
	go func() {
		done <- record(paths, *port, *seg, *gap, *maxRecovery)
	}()

	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, _ *http.Request) { fmt.Fprintln(w, "ok") })
	srv := &http.Server{Addr: *httpAddr, Handler: mux}
	mux.HandleFunc("/report", func(w http.ResponseWriter, _ *http.Request) {
		res := <-done
		done <- res // later requests get the same answer
		b, _ := json.MarshalIndent(res, "", "  ")
		os.WriteFile(filepath.Join(*out, "report.json"), b, 0o644)
		if len(res.Failures) > 0 {
			w.WriteHeader(http.StatusInternalServerError)
		}
		w.Write(b)
		if len(res.Failures) == 0 {
			go srv.Close()
		}
	})
	log.Printf("e2erecv: srt :%d, http %s, out %s", *port, *httpAddr, *out)
	if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatal(err)
	}
}

func record(paths []string, port int, seg, gap, maxRecovery time.Duration) *result {
	res := &result{}
	fail := func(format string, a ...any) { res.Failures = append(res.Failures, fmt.Sprintf(format, a...)) }

	segs, err := kinetic.RecordSRTSegments(port, paths, seg, gap, 3*time.Minute)
	if err != nil {
		fail("recording: %v", err)
	}
	for i, s := range segs {
		log.Printf("segment %d: %d bytes", i+1, s.Bytes)
		if s.Bytes == 0 {
			fail("segment %d: nothing received", i+1)
			continue
		}
		exp := e2e.DefaultExpectations()
		if i == 0 {
			exp.MinDuration = seg.Seconds() - 3 // the cut and the sender's buffering eat into it
		}
		rep, err := e2e.Analyze(s.Path, exp)
		if err != nil {
			fail("segment %d: %v", i+1, err)
		}
		if rep != nil {
			res.Segments = append(res.Segments, segmentResult{rep, s.Bytes})
			for _, f := range rep.Failures {
				fail("segment %d: %s", i+1, f)
			}
		}
	}
	if len(segs) == len(paths) && segs[1].Bytes > 0 {
		res.RecoverSec = segs[1].FirstByteAt.Sub(segs[1].ListenedAt).Seconds()
		if res.RecoverSec > maxRecovery.Seconds() {
			fail("reconnect took %.1fs after the listener came back, want at most %v", res.RecoverSec, maxRecovery)
		}
	}
	return res
}
