package kinetic

import (
	"sync"
	"testing"
	"time"
)

func TestHandlesAddGetRemove(t *testing.T) {
	var h Handles[string]
	if _, ok := h.Get(1); ok {
		t.Fatal("empty table returned a value")
	}
	if _, ok := h.Remove(1); ok {
		t.Fatal("empty table removed a value")
	}
	a, b := h.Add("a"), h.Add("b")
	if a == 0 || b == 0 || a == b {
		t.Fatalf("bad handles %d %d", a, b)
	}
	if v, ok := h.Get(a); !ok || v != "a" {
		t.Fatalf("Get(a) = %q, %v", v, ok)
	}
	if v, ok := h.Remove(a); !ok || v != "a" {
		t.Fatalf("Remove(a) = %q, %v", v, ok)
	}
	if _, ok := h.Get(a); ok {
		t.Fatal("removed handle still present")
	}
	if _, ok := h.Remove(a); ok {
		t.Fatal("double remove succeeded")
	}
	if v, _ := h.Get(b); v != "b" {
		t.Fatal("unrelated handle disturbed")
	}
}

// Regression: the old JNI bridge indexed fixed 100-slot arrays by handle, so
// callbacks stopped working after ~100 allocations. Handles must keep working
// and never be reused no matter how many are created and released.
func TestHandlesNeverExhaustedOrReused(t *testing.T) {
	var h Handles[int]
	seen := map[int64]bool{}
	for i := 0; i < 10000; i++ {
		id := h.Add(i)
		if seen[id] {
			t.Fatalf("handle %d reused", id)
		}
		seen[id] = true
		if v, ok := h.Get(id); !ok || v != i {
			t.Fatalf("Get(%d) = %d, %v", id, v, ok)
		}
		h.Remove(id)
	}
}

func TestHandlesConcurrent(t *testing.T) {
	var h Handles[int]
	var wg sync.WaitGroup
	for g := 0; g < 8; g++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < 500; i++ {
				id := h.Add(i)
				if v, ok := h.Get(id); !ok || v != i {
					t.Errorf("Get(%d) = %d, %v", id, v, ok)
				}
				if _, ok := h.Remove(id); !ok {
					t.Errorf("Remove(%d) failed", id)
				}
			}
		}()
	}
	wg.Wait()
}

// A slow Close performed after Remove must not block other handles.
func TestHandlesRemoveThenSlowCloseDoesNotBlock(t *testing.T) {
	var h Handles[int]
	slow := h.Add(1)
	other := h.Add(2)

	release := make(chan struct{})
	go func() {
		h.Remove(slow)
		<-release // simulated blocking Close, outside the table lock
	}()

	done := make(chan struct{})
	go func() {
		h.Get(other)
		h.Add(3)
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("table blocked while a removed value was closing")
	}
	close(release)
}
