package kinetic

import (
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestHandlesFindOrAdd(t *testing.T) {
	var h Handles[*int]
	x, y := new(int), new(int)
	same := func(p *int) func(*int) bool { return func(q *int) bool { return p == q } }
	a := h.FindOrAdd(x, same(x))
	if a == 0 || h.FindOrAdd(x, same(x)) != a {
		t.Fatal("same value got a new handle")
	}
	if b := h.FindOrAdd(y, same(y)); b == a || b == 0 {
		t.Fatalf("distinct value got handle %d (x=%d)", b, a)
	}
}

// The JNI stop/close exports Remove a handle and then run a slow Close. The table
// must stay usable while that Close blocks.
func TestHandlesRemoveDoesNotHoldLockWhileClosing(t *testing.T) {
	type res struct{ closing, release chan struct{} }
	var h Handles[*res]
	r := &res{make(chan struct{}), make(chan struct{})}
	id := h.Add(r)

	closed := make(chan struct{})
	go func() {
		if v, ok := h.Remove(id); ok {
			close(v.closing)
			<-v.release // blocks like RTMPServer.Stop
		}
		close(closed)
	}()
	<-r.closing

	done := make(chan struct{})
	go func() {
		defer close(done)
		if _, ok := h.Get(id); ok {
			t.Error("removed handle still visible")
		}
		o := &res{}
		oid := h.Add(o)
		if v, ok := h.Get(oid); !ok || v != o {
			t.Error("Add/Get failed during blocked Close")
		}
		h.FindOrAdd(o, func(q *res) bool { return q == o })
		h.Remove(oid)
	}()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("table locked while Close was blocked")
	}
	close(r.release)
	<-closed
}

func TestHandlesConcurrentRemoveOnce(t *testing.T) {
	var h Handles[int]
	id := h.Add(1)
	var wg sync.WaitGroup
	var removed atomic.Int32
	for g := 0; g < 16; g++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			h.Get(id)
			if _, ok := h.Remove(id); ok {
				removed.Add(1)
			}
		}()
	}
	wg.Wait()
	if removed.Load() != 1 {
		t.Fatalf("handle removed %d times", removed.Load())
	}
}
