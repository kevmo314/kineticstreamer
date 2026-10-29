//go:build cgo && !android && (darwin || linux)

package kinetic

import (
	"fmt"
	"os"
	"time"
)

// SRTSegment describes one recorded connection.
type SRTSegment struct {
	Path        string
	Bytes       int
	ListenedAt  time.Time // when the listener started waiting for this connection
	FirstByteAt time.Time
}

// RecordSRTSegments is the receiver for the end-to-end streaming test. It accepts one SRT
// connection per path in paths and records it to that file. Every connection but the last is
// cut after segDur, and the listener stays down for gap before the next one, so the sender has
// to reconnect. The last connection is recorded until the sender hangs up. Waiting for any
// connection fails after acceptTimeout.
func RecordSRTSegments(port int, paths []string, segDur, gap, acceptTimeout time.Duration) ([]SRTSegment, error) {
	var segs []SRTSegment
	for i, path := range paths {
		seg, err := recordSRTSegment(port, path, acceptTimeout, segDur, i < len(paths)-1)
		segs = append(segs, seg)
		if err != nil {
			return segs, err
		}
		if i < len(paths)-1 {
			time.Sleep(gap)
		}
	}
	return segs, nil
}

func recordSRTSegment(port int, path string, acceptTimeout, segDur time.Duration, cut bool) (SRTSegment, error) {
	seg := SRTSegment{Path: path, ListenedAt: time.Now()}
	l, err := newSRTTestListenerOn(port)
	if err != nil {
		return seg, err
	}
	defer l.Close()

	// Accept blocks inside libsrt; closing the listener is what unblocks it.
	timer := time.AfterFunc(acceptTimeout, l.Close)
	conn, err := l.Accept()
	if !timer.Stop() {
		return seg, fmt.Errorf("no SRT connection within %v", acceptTimeout)
	}
	if err != nil {
		return seg, err
	}
	defer conn.Close()
	if cut {
		time.AfterFunc(segDur, conn.Close)
	}

	f, err := os.Create(path)
	if err != nil {
		return seg, err
	}
	defer f.Close()
	buf := make([]byte, 1500)
	for {
		n, err := conn.Read(buf)
		if err != nil {
			return seg, nil
		}
		if seg.FirstByteAt.IsZero() {
			seg.FirstByteAt = time.Now()
		}
		seg.Bytes += n
		if _, err := f.Write(buf[:n]); err != nil {
			return seg, err
		}
	}
}
