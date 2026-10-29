package kinetic

import "sync"

// Handles maps opaque int64 handles (as handed to Java over JNI) to Go values.
// Handles are never reused and start at 1, so 0 can signal failure. The zero
// value is ready to use.
type Handles[T any] struct {
	mu   sync.RWMutex
	next int64
	m    map[int64]T
}

// Add stores v and returns its new handle.
func (h *Handles[T]) Add(v T) int64 {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.m == nil {
		h.m = make(map[int64]T)
	}
	h.next++
	h.m[h.next] = v
	return h.next
}

// Get returns the value for handle without removing it.
func (h *Handles[T]) Get(handle int64) (T, bool) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	v, ok := h.m[handle]
	return v, ok
}

// Remove deletes handle and returns its value. The caller should close the
// value after Remove returns so that slow Close calls never run under the lock.
func (h *Handles[T]) Remove(handle int64) (T, bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	v, ok := h.m[handle]
	delete(h.m, handle)
	return v, ok
}
