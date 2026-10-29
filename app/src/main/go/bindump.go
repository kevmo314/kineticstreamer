package kinetic

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
)

// Records are stored in "<pts>.ucf" files, one file per keyframe. Each record is a
// fixed-size little-endian header followed by the payload:
//
//	[0:2]   track index
//	[2:4]   MediaCodecBufferFlag (all defined flags fit in 16 bits)
//	[4:12]  pts in microseconds
//	[12:16] payload size
const (
	recordHeaderSize = 16
	recordFileSuffix = ".ucf"
	// maxRecordSize bounds the allocation made from the (untrusted) size field.
	maxRecordSize = 64 << 20
)

var errCorruptRecord = errors.New("corrupt record")

type BinaryDumpSample struct {
	Track int
	Flags MediaCodecBufferFlag
	PTS   int64
	Data  []byte
}

// encodeRecord returns the header and payload as a single buffer so that it can be written
// with one write call.
func encodeRecord(track int, flags MediaCodecBufferFlag, pts int64, data []byte) []byte {
	b := make([]byte, recordHeaderSize+len(data))
	binary.LittleEndian.PutUint16(b[0:2], uint16(track))
	binary.LittleEndian.PutUint16(b[2:4], uint16(flags))
	binary.LittleEndian.PutUint64(b[4:12], uint64(pts))
	binary.LittleEndian.PutUint32(b[12:16], uint32(len(data)))
	copy(b[recordHeaderSize:], data)
	return b
}

// decodeRecord reads one record. It returns io.EOF at a clean end of file,
// io.ErrUnexpectedEOF if the record is truncated and errCorruptRecord if the size is implausible.
func decodeRecord(r io.Reader) (*BinaryDumpSample, error) {
	header := make([]byte, recordHeaderSize)
	if _, err := io.ReadFull(r, header); err != nil {
		return nil, err
	}
	size := binary.LittleEndian.Uint32(header[12:16])
	if size > maxRecordSize {
		return nil, fmt.Errorf("%w: size %d", errCorruptRecord, size)
	}
	data := make([]byte, size)
	if _, err := io.ReadFull(r, data); err != nil {
		if errors.Is(err, io.EOF) {
			err = io.ErrUnexpectedEOF
		}
		return nil, err
	}
	return &BinaryDumpSample{
		Track: int(binary.LittleEndian.Uint16(header[0:2])),
		Flags: MediaCodecBufferFlag(binary.LittleEndian.Uint16(header[2:4])),
		PTS:   int64(binary.LittleEndian.Uint64(header[4:12])),
		Data:  data,
	}, nil
}

type BinaryDumpSink struct {
	directory string
	video     []bool // per track: only video keyframes start a new file

	mu   sync.Mutex
	file *os.File
}

// NewBinaryDumpSink records into directory. encodedMediaFormatMimeTypes lists the
// tracks (";"-separated, as for the other sinks); audio encoders flag every
// sample as a keyframe, so when there is video only its keyframes split files.
func NewBinaryDumpSink(directory, encodedMediaFormatMimeTypes string) *BinaryDumpSink {
	var video []bool
	for _, m := range strings.Split(encodedMediaFormatMimeTypes, ";") {
		video = append(video, strings.HasPrefix(m, "video/"))
	}
	if !slices.Contains(video, true) {
		// Audio-only: there is nothing else to split on.
		for i := range video {
			video[i] = true
		}
	}
	return &BinaryDumpSink{directory: directory, video: video}
}

func (s *BinaryDumpSink) WriteSample(track int, buf []byte, ptsMicroseconds int64, mediaCodecFlags int32) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	// if it's a video keyframe, create a new file based on the pts.
	if MediaCodecBufferFlag(mediaCodecFlags)&MediaCodecBufferFlagKeyFrame != 0 && track >= 0 && track < len(s.video) && s.video[track] {
		if s.file != nil {
			err := s.file.Close()
			s.file = nil
			if err != nil {
				return err
			}
		}
		if err := os.MkdirAll(s.directory, 0755); err != nil {
			return err
		}
		// append so that keyframes of different tracks sharing a pts don't truncate each other.
		file, err := os.OpenFile(filepath.Join(s.directory, strconv.FormatInt(ptsMicroseconds, 10)+recordFileSuffix), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
		if err != nil {
			return err
		}
		s.file = file
	}
	// samples before the first keyframe can't be decoded, so drop them.
	if s.file == nil {
		return nil
	}
	_, err := s.file.Write(encodeRecord(track, MediaCodecBufferFlag(mediaCodecFlags), ptsMicroseconds, buf))
	return err
}

func (s *BinaryDumpSink) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.file == nil {
		return nil
	}
	err := s.file.Close()
	s.file = nil
	return err
}

type ManifestEntry struct {
	PTS              int64
	FileAbsolutePath string
}

// ReadManifest lists the recorded files sorted by pts. Files that aren't
// recordings are ignored.
func (t *BinaryDumpSink) ReadManifest() ([]ManifestEntry, error) {
	files, err := os.ReadDir(t.directory)
	if err != nil {
		return nil, err
	}
	var manifest []ManifestEntry
	for _, f := range files {
		name, ok := strings.CutSuffix(f.Name(), recordFileSuffix)
		if f.IsDir() || !ok {
			continue
		}
		pts, err := strconv.ParseInt(name, 10, 64)
		if err != nil {
			continue
		}
		manifest = append(manifest, ManifestEntry{PTS: pts, FileAbsolutePath: filepath.Join(t.directory, f.Name())})
	}
	slices.SortFunc(manifest, func(a, b ManifestEntry) int {
		switch {
		case a.PTS < b.PTS:
			return -1
		case a.PTS > b.PTS:
			return 1
		default:
			return 0
		}
	})
	return manifest, nil
}

type BinaryDumpSampleReader struct {
	t    *BinaryDumpSink
	file *os.File
	PTS0 int64
}

// SampleReader starts reading at the last file starting at or before the given pts, or
// the first file if there is none. The caller must Close it.
func (t *BinaryDumpSink) SampleReader(ptsMicroseconds int64) (*BinaryDumpSampleReader, error) {
	manifest, err := t.ReadManifest()
	if err != nil {
		return nil, err
	}
	if len(manifest) == 0 {
		return nil, errors.New("no recordings")
	}
	entry := manifest[0]
	for _, e := range manifest {
		if e.PTS > ptsMicroseconds {
			break
		}
		entry = e
	}
	file, err := os.Open(entry.FileAbsolutePath)
	if err != nil {
		return nil, err
	}
	return &BinaryDumpSampleReader{t: t, file: file, PTS0: entry.PTS}, nil
}

func (r *BinaryDumpSampleReader) Close() error {
	return r.file.Close()
}

// Next returns the next record, continuing into the next file at the end of the current one.
// A truncated final record (e.g. after a crash) is treated as the end of its file. It returns
// io.EOF once there are no more records.
func (r *BinaryDumpSampleReader) Next() (*BinaryDumpSample, error) {
	for {
		sample, err := decodeRecord(r.file)
		if err == nil {
			return sample, nil
		}
		if !errors.Is(err, io.EOF) && !errors.Is(err, io.ErrUnexpectedEOF) {
			return nil, err
		}
		manifest, err := r.t.ReadManifest()
		if err != nil {
			return nil, err
		}
		i := slices.IndexFunc(manifest, func(e ManifestEntry) bool { return e.PTS > r.PTS0 })
		if i < 0 {
			return nil, io.EOF
		}
		file, err := os.Open(manifest[i].FileAbsolutePath)
		if err != nil {
			return nil, err
		}
		r.file.Close()
		r.file = file
		r.PTS0 = manifest[i].PTS
	}
}
