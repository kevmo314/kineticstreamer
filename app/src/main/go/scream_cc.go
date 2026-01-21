package kinetic

import (
	"log"
	"sync"
	"time"

	scream "github.com/mengelbart/scream-go"
	"github.com/pion/interceptor"
	"github.com/pion/rtcp"
	"github.com/pion/rtp"
)

// ScreamBWE implements cc.BandwidthEstimator using SCReAM congestion control
type ScreamBWE struct {
	mu              sync.RWMutex
	tx              *scream.Tx
	videoSSRC       uint32
	audioSSRC       uint32
	targetBitrate   int
	onBitrateChange func(int)
	closed          bool

	// Stats
	lastStats     time.Time
	packetsLogged uint64
}

// ScreamBWEOption configures the SCReAM bandwidth estimator
type ScreamBWEOption func(*ScreamBWE)

// ScreamBWEInitialBitrate sets the initial bitrate
func ScreamBWEInitialBitrate(bitrate int) ScreamBWEOption {
	return func(s *ScreamBWE) {
		s.targetBitrate = bitrate
	}
}

// NewScreamBWE creates a new SCReAM bandwidth estimator
func NewScreamBWE(opts ...ScreamBWEOption) (*ScreamBWE, error) {
	s := &ScreamBWE{
		tx:            scream.NewTx(),
		targetBitrate: 2_000_000, // Default 2 Mbps
	}

	for _, opt := range opts {
		opt(s)
	}

	log.Printf("SCReAM BWE initialized with initial bitrate %d kbps", s.targetBitrate/1000)
	return s, nil
}

// AddStream implements cc.BandwidthEstimator
// Wraps the RTPWriter to track transmitted packets
func (s *ScreamBWE) AddStream(info *interceptor.StreamInfo, writer interceptor.RTPWriter) interceptor.RTPWriter {
	ssrc := info.SSRC
	isVideo := info.MimeType == "video/H264" || info.MimeType == "video/h264" ||
		info.MimeType == "video/VP8" || info.MimeType == "video/VP9" ||
		info.MimeType == "video/AV1"

	s.mu.Lock()
	if isVideo {
		s.videoSSRC = ssrc
		// Register video stream with SCReAM
		// Priority 1.0 (highest), min 400kbps, start at current target, max 7.5Mbps
		queue := &screamRTPQueue{}
		s.tx.RegisterNewStream(queue, ssrc, 1.0, 400_000, float64(s.targetBitrate), 7_500_000)
		log.Printf("SCReAM: registered video stream SSRC=%d", ssrc)
	} else {
		s.audioSSRC = ssrc
		// Audio gets lower priority, fixed bitrate range
		queue := &screamRTPQueue{}
		s.tx.RegisterNewStream(queue, ssrc, 0.5, 32_000, 64_000, 128_000)
		log.Printf("SCReAM: registered audio stream SSRC=%d", ssrc)
	}
	s.mu.Unlock()

	// Return a wrapper that notifies SCReAM of transmitted packets
	return &screamRTPWriter{
		bwe:     s,
		ssrc:    ssrc,
		isVideo: isVideo,
		writer:  writer,
	}
}

// WriteRTCP implements cc.BandwidthEstimator
// Processes incoming RTCP feedback (TWCC)
func (s *ScreamBWE) WriteRTCP(pkts []rtcp.Packet, _ interceptor.Attributes) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.closed {
		return nil
	}

	for _, pkt := range pkts {
		// Handle Transport-Wide Congestion Control feedback
		if twcc, ok := pkt.(*rtcp.TransportLayerCC); ok {
			// Marshal the TWCC packet to raw bytes for SCReAM
			raw, err := twcc.Marshal()
			if err != nil {
				log.Printf("SCReAM: failed to marshal TWCC: %v", err)
				continue
			}

			// Feed to SCReAM
			s.tx.IncomingStandardizedFeedback(time.Now(), raw)

			// Update target bitrate from SCReAM
			if s.videoSSRC != 0 {
				newBitrate := s.tx.GetTargetBitrate(time.Now(), s.videoSSRC)
				if newBitrate > 0 {
					oldBitrate := s.targetBitrate
					s.targetBitrate = int(newBitrate)

					// Notify callback if bitrate changed significantly (>5%)
					if s.onBitrateChange != nil && abs(s.targetBitrate-oldBitrate) > oldBitrate/20 {
						go s.onBitrateChange(s.targetBitrate)
					}
				}
			}
		}
	}

	return nil
}

// GetTargetBitrate implements cc.BandwidthEstimator
func (s *ScreamBWE) GetTargetBitrate() int {
	s.mu.RLock()
	defer s.mu.RUnlock()

	// Get fresh estimate from SCReAM
	if s.videoSSRC != 0 && !s.closed {
		bitrate := s.tx.GetTargetBitrate(time.Now(), s.videoSSRC)
		if bitrate > 0 {
			return int(bitrate)
		}
	}

	return s.targetBitrate
}

// OnTargetBitrateChange implements cc.BandwidthEstimator
func (s *ScreamBWE) OnTargetBitrateChange(f func(int)) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.onBitrateChange = f
}

// GetStats implements cc.BandwidthEstimator
func (s *ScreamBWE) GetStats() map[string]any {
	s.mu.RLock()
	defer s.mu.RUnlock()

	stats := map[string]any{
		"type":          "scream",
		"targetBitrate": s.targetBitrate,
	}

	// Get SCReAM statistics string (includes detailed info)
	if !s.closed && s.videoSSRC != 0 {
		screamStats := s.tx.GetStatistics(time.Now())
		stats["screamStats"] = screamStats
	}

	return stats
}

// Close implements cc.BandwidthEstimator
func (s *ScreamBWE) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.closed {
		return nil
	}

	s.closed = true
	if s.tx != nil {
		s.tx.Close()
	}

	log.Printf("SCReAM BWE closed")
	return nil
}

// screamRTPWriter wraps an RTPWriter to notify SCReAM of transmitted packets
type screamRTPWriter struct {
	bwe     *ScreamBWE
	ssrc    uint32
	isVideo bool
	writer  interceptor.RTPWriter
}

func (w *screamRTPWriter) Write(header *rtp.Header, payload []byte, attrs interceptor.Attributes) (int, error) {
	// Write the packet first
	n, err := w.writer.Write(header, payload, attrs)
	if err != nil {
		return n, err
	}

	// Notify SCReAM that packet was transmitted
	w.bwe.mu.Lock()
	if !w.bwe.closed {
		packetSize := len(payload) + 12 // payload + RTP header
		isMark := header.Marker

		// Tell SCReAM about the transmitted packet
		w.bwe.tx.AddTransmitted(time.Now(), w.ssrc, packetSize, header.SequenceNumber, isMark)

		// For video, also notify of new media frame on marker bit (end of frame)
		if w.isVideo && isMark {
			w.bwe.tx.NewMediaFrame(time.Now(), w.ssrc, packetSize, true)
		}
	}
	w.bwe.mu.Unlock()

	return n, nil
}

// screamRTPQueue implements scream.RTPQueue interface
// This is a minimal implementation since we're not using SCReAM's pacing
type screamRTPQueue struct {
	mu            sync.RWMutex
	lastSeqNr     uint16
	lastSize      int
	lastTimestamp time.Time
	bytesInQueue  int
}

func (q *screamRTPQueue) SizeOfNextRTP() int {
	q.mu.RLock()
	defer q.mu.RUnlock()
	return q.lastSize
}

func (q *screamRTPQueue) SeqNrOfNextRTP() uint16 {
	q.mu.RLock()
	defer q.mu.RUnlock()
	return q.lastSeqNr
}

func (q *screamRTPQueue) SeqNrOfLastRTP() uint16 {
	q.mu.RLock()
	defer q.mu.RUnlock()
	return q.lastSeqNr
}

func (q *screamRTPQueue) BytesInQueue() int {
	q.mu.RLock()
	defer q.mu.RUnlock()
	return q.bytesInQueue
}

func (q *screamRTPQueue) SizeOfQueue() int {
	q.mu.RLock()
	defer q.mu.RUnlock()
	if q.bytesInQueue > 0 {
		return 1
	}
	return 0
}

func (q *screamRTPQueue) GetDelay(ts float64) float64 {
	q.mu.RLock()
	defer q.mu.RUnlock()
	if q.lastTimestamp.IsZero() {
		return 0
	}
	// Return delay in seconds
	return time.Since(q.lastTimestamp).Seconds()
}

func (q *screamRTPQueue) GetSizeOfLastFrame() int {
	q.mu.RLock()
	defer q.mu.RUnlock()
	return q.lastSize
}

func (q *screamRTPQueue) Clear() int {
	q.mu.Lock()
	defer q.mu.Unlock()
	count := 0
	if q.bytesInQueue > 0 {
		count = 1
	}
	q.bytesInQueue = 0
	return count
}

func abs(x int) int {
	if x < 0 {
		return -x
	}
	return x
}
