//go:build cgo && !android && (darwin || linux)

package kinetic

// Host-only librist receiver helpers used by rist_test.go. CGO is not
// allowed in _test.go files, so the C glue lives here while the test
// logic stays in rist_test.go.

/*
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <librist/librist.h>

// Read one block off a librist receiver context. Returns the number of bytes
// copied into out_buf on success (0 on timeout, -1 on hard error). out_buf
// must be at least RIST_MAX_PACKET_SIZE (1316) bytes.
static int rist_test_read(struct rist_ctx* ctx, void* out_buf, int out_len, int timeout_ms) {
    struct rist_data_block* block = NULL;
    int rc = rist_receiver_data_read2(ctx, &block, timeout_ms);
    if (rc < 0) return -1;
    if (rc == 0 || block == NULL) return 0;

    int copied = 0;
    if (block->payload && block->payload_len > 0) {
        copied = (int)block->payload_len;
        if (copied > out_len) copied = out_len;
        memcpy(out_buf, block->payload, copied);
    }
    rist_receiver_data_block_free2(&block);
    return copied;
}
*/
import "C"

import (
	"fmt"
	"unsafe"
)

// ristTestListener owns a librist receiver context bound to a loopback port.
type ristTestListener struct {
	ctx     *C.struct_rist_ctx
	logging *C.struct_rist_logging_settings
	url     string
}

// newRISTTestListener builds a librist receiver bound to 127.0.0.1:<port>.
// The caller picks the port; that mirrors the URL the sink will connect to.
func newRISTTestListener(port int) (*ristTestListener, error) {
	listenURL := fmt.Sprintf("rist://@127.0.0.1:%d", port)
	cURL := C.CString(listenURL)
	defer C.free(unsafe.Pointer(cURL))

	var peerCfg *C.struct_rist_peer_config
	if rc := C.rist_parse_address2(cURL, &peerCfg); rc != 0 || peerCfg == nil {
		return nil, fmt.Errorf("rist_parse_address2: %d", int(rc))
	}
	defer C.rist_peer_config_free2(&peerCfg)

	var logging *C.struct_rist_logging_settings
	if rc := C.rist_logging_set(&logging, C.RIST_LOG_WARN, nil, nil, nil, nil); rc != 0 {
		return nil, fmt.Errorf("rist_logging_set: %d", int(rc))
	}

	var ctx *C.struct_rist_ctx
	if rc := C.rist_receiver_create(&ctx, C.RIST_PROFILE_MAIN, logging); rc != 0 {
		C.rist_logging_settings_free2(&logging)
		return nil, fmt.Errorf("rist_receiver_create: %d", int(rc))
	}
	var peer *C.struct_rist_peer
	if rc := C.rist_peer_create(ctx, &peer, peerCfg); rc != 0 {
		C.rist_destroy(ctx)
		C.rist_logging_settings_free2(&logging)
		return nil, fmt.Errorf("rist_peer_create: %d", int(rc))
	}
	if rc := C.rist_start(ctx); rc != 0 {
		C.rist_destroy(ctx)
		C.rist_logging_settings_free2(&logging)
		return nil, fmt.Errorf("rist_start: %d", int(rc))
	}

	return &ristTestListener{ctx: ctx, logging: logging, url: listenURL}, nil
}

// Drain reads up to maxBytes from the receiver, calling rist_test_read with
// the given per-call timeout, until either maxBytes are collected or
// totalTimeoutMs elapses with no progress.
func (l *ristTestListener) Drain(maxBytes, perCallTimeoutMs, totalTimeoutMs int) ([]byte, error) {
	out := make([]byte, 0, maxBytes)
	var chunk [1500]byte
	deadline := totalTimeoutMs
	for len(out) < maxBytes && deadline > 0 {
		n := C.rist_test_read(l.ctx, unsafe.Pointer(&chunk[0]), C.int(len(chunk)), C.int(perCallTimeoutMs))
		if n < 0 {
			return out, fmt.Errorf("rist_receiver_data_read2 failed")
		}
		if n == 0 {
			deadline -= perCallTimeoutMs
			continue
		}
		out = append(out, chunk[:int(n)]...)
	}
	return out, nil
}

// Close tears down the receiver context.
func (l *ristTestListener) Close() {
	if l.ctx != nil {
		C.rist_destroy(l.ctx)
		l.ctx = nil
	}
	if l.logging != nil {
		C.rist_logging_settings_free2(&l.logging)
		l.logging = nil
	}
}
