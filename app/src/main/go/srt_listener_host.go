//go:build cgo && !android && (darwin || linux)

package kinetic

// Host-only SRT listener helpers used by srt_test.go. CGO is not allowed in
// _test.go files, so the C glue lives here while the test logic stays in
// srt_test.go.

/*
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <netinet/in.h>
#include <srt/srt.h>

// *out_port is the port to bind on entry (0 = any) and the bound port on exit.
static int srt_test_listen(int* out_port) {
    SRTSOCKET sock = srt_create_socket();
    if (sock == SRT_INVALID_SOCK) return -1;

    struct sockaddr_in sa = {0};
    sa.sin_family = AF_INET;
    sa.sin_port = htons(*out_port);
    sa.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (srt_bind(sock, (struct sockaddr*)&sa, sizeof(sa)) == SRT_ERROR) {
        srt_close(sock);
        return -1;
    }

    struct sockaddr_in bound;
    int boundlen = sizeof(bound);
    if (srt_getsockname(sock, (struct sockaddr*)&bound, &boundlen) == SRT_ERROR) {
        srt_close(sock);
        return -1;
    }
    *out_port = ntohs(bound.sin_port);

    if (srt_listen(sock, 1) == SRT_ERROR) {
        srt_close(sock);
        return -1;
    }
    return sock;
}

static int srt_test_accept(int listener) {
    return srt_accept(listener, NULL, NULL);
}

static int srt_test_recv(int sock, char* buf, int len) {
    return srt_recv(sock, buf, len);
}
*/
import "C"

import (
	"fmt"
	"unsafe"
)

// srtTestListener owns a libsrt listening socket bound to a loopback port.
type srtTestListener struct {
	sock C.int
	port int
}

func newSRTTestListener() (*srtTestListener, error) { return newSRTTestListenerOn(0) }

// newSRTTestListenerOn binds a specific loopback port (0 = any).
func newSRTTestListenerOn(port int) (*srtTestListener, error) {
	C.srt_startup()
	cport := C.int(port)
	sock := C.srt_test_listen(&cport)
	if sock == -1 {
		err := srtGetAndClearError()
		C.srt_cleanup()
		return nil, fmt.Errorf("srt_test_listen: %w", err)
	}
	return &srtTestListener{sock: sock, port: int(cport)}, nil
}

// AcceptAndDrain blocks waiting for a single peer, then reads bytes off the
// connection until either max bytes are collected or recv returns <=0. The
// listener socket is closed at the end either way.
func (l *srtTestListener) AcceptAndDrain(max int) ([]byte, error) {
	defer C.srt_close(l.sock)
	defer C.srt_cleanup()

	client := C.srt_test_accept(l.sock)
	if client == -1 {
		return nil, fmt.Errorf("srt_accept: %w", srtGetAndClearError())
	}
	defer C.srt_close(client)

	var chunk [1500]byte
	out := make([]byte, 0, max)
	for len(out) < max {
		n := C.srt_test_recv(client, (*C.char)(unsafe.Pointer(&chunk[0])), C.int(len(chunk)))
		if n <= 0 {
			break
		}
		out = append(out, chunk[:int(n)]...)
	}
	return out, nil
}

// Port is the loopback port the listener is bound to.
func (l *srtTestListener) Port() int { return l.port }

// srtTestConn is an accepted peer connection.
type srtTestConn struct{ sock C.int }

// Accept blocks for one peer. It fails once the listener is closed.
func (l *srtTestListener) Accept() (*srtTestConn, error) {
	c := C.srt_test_accept(l.sock)
	if c == -1 {
		return nil, fmt.Errorf("srt_accept: %w", srtGetAndClearError())
	}
	return &srtTestConn{sock: c}, nil
}

// Close closes the listening socket (accepted conns are unaffected).
func (l *srtTestListener) Close() {
	C.srt_close(l.sock)
}

// Drain reads until the peer goes away or the conn is closed.
func (c *srtTestConn) Drain() []byte {
	var chunk [1500]byte
	var out []byte
	for {
		n := C.srt_test_recv(c.sock, (*C.char)(unsafe.Pointer(&chunk[0])), C.int(len(chunk)))
		if n <= 0 {
			return out
		}
		out = append(out, chunk[:int(n)]...)
	}
}

func (c *srtTestConn) Close() { C.srt_close(c.sock) }
