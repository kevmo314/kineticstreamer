package kinetic

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"runtime/debug"
	"slices"
	"strconv"
	"strings"
	"time"
)

type DiskSink struct {
	directory string
	keys      []string

	tracks []*DiskTrack
}

func NewDiskSink(directory string, encodedKeys string) (*DiskSink, error) {
	defer func() {
		if r := recover(); r != nil {
			fmt.Printf("panic: %v\n", debug.Stack())
		}
	}()
	keys := strings.Split(encodedKeys, ";")
	tracks := make([]*DiskTrack, len(keys))
	for i, key := range keys {
		if err := os.MkdirAll(fmt.Sprintf("%s/%s", directory, key), 0755); err != nil {
			return nil, err
		}
		tracks[i] = &DiskTrack{path: fmt.Sprintf("%s/%s", directory, key)}
	}
	return &DiskSink{directory: directory, keys: keys, tracks: tracks}, nil
}

func (s *DiskSink) Track(i int) *DiskTrack {
	return s.tracks[i]
}

type DiskTrack struct {
	path string

	file *os.File
}

type MediaCodecBufferFlag int32

const (
	MediaCodecBufferFlagKeyFrame     MediaCodecBufferFlag = 1
	MediaCodecBufferFlagCodecConfig  MediaCodecBufferFlag = 2
	MediaCodecBufferFlagEndOfStream  MediaCodecBufferFlag = 4
	MediaCodecBufferFlagPartialFrame MediaCodecBufferFlag = 8
	MediaCodecBufferFlagMuxerData    MediaCodecBufferFlag = 16
	MediaCodecBufferFlagDecodeOnly   MediaCodecBufferFlag = 32
)

func (t *DiskTrack) WriteSample(buf []byte, ptsMicroseconds int64, mediaCodecFlags int32) error {
	ntp := time.Now()
	// if it's a keyframe, create a new file based on the pts.
	if MediaCodecBufferFlag(mediaCodecFlags)&MediaCodecBufferFlagKeyFrame != 0 {
		if t.file != nil {
			if err := t.file.Close(); err != nil {
				return err
			}
		}
		file, err := os.Create(fmt.Sprintf("%s/%d.ucf", t.path, ptsMicroseconds))
		if err != nil {
			return err
		}
		t.file = file
	}
	// write the flags and pts to the buffer
	if t.file != nil {
		header := make([]byte, 24)
		binary.LittleEndian.PutUint32(header[0:4], uint32(mediaCodecFlags))
		binary.LittleEndian.PutUint64(header[4:12], uint64(ptsMicroseconds))
		binary.LittleEndian.PutUint64(header[12:20], uint64(ntp.UnixNano()))
		binary.LittleEndian.PutUint32(header[20:24], uint32(len(buf)))

		if _, err := t.file.Write(header); err != nil {
			return err
		}
		if _, err := t.file.Write(buf); err != nil {
			return err
		}
	}
	return nil
}

type ManifestEntry struct {
	PTS              int64
	FileAbsolutePath string
}

func (t *DiskTrack) ReadManifest() ([]ManifestEntry, error) {
	files, err := os.ReadDir(t.path)
	if err != nil {
		return nil, err
	}
	var manifest []ManifestEntry
	for _, f := range files {
		if f.IsDir() {
			continue
		}
		pts, err := strconv.ParseInt(f.Name()[:len(f.Name())-4], 10, 64)
		if err != nil {
			return nil, err
		}
		manifest = append(manifest, ManifestEntry{PTS: pts, FileAbsolutePath: fmt.Sprintf("%s/%s", t.path, f.Name())})
	}
	slices.SortFunc(manifest, func(a, b ManifestEntry) int {
		if a.PTS < b.PTS {
			return -1
		} else if a.PTS > b.PTS {
			return 1
		} else {
			return 0
		}
	})
	return manifest, nil
}

type SampleReader struct {
	t    *DiskTrack
	file *os.File
	PTS0 int64
}

func (t *DiskTrack) SampleReader(ptsMicroseconds int64) (*SampleReader, error) {
	// read the manifest and find the last entry that is before the pts.
	manifest, err := t.ReadManifest()
	if err != nil {
		return nil, err
	}

	var lastEntry ManifestEntry
	for _, e := range manifest {
		if e.PTS <= ptsMicroseconds {
			lastEntry = e
		} else {
			break
		}
	}
	file, err := os.Open(lastEntry.FileAbsolutePath)
	if err != nil {
		return nil, err
	}
	return &SampleReader{t: t, file: file, PTS0: lastEntry.PTS}, nil
}

type Sample struct {
	Flags MediaCodecBufferFlag
	PTS   int64
	Data  []byte
}

func (r *SampleReader) Next() (*Sample, error) {
	header := make([]byte, 24)
	if _, err := r.file.Read(header); err != nil {
		if errors.Is(err, io.EOF) {
			// try to find the next manifest entry
			manifest, err := r.t.ReadManifest()
			if err != nil {
				return nil, err
			}
			for _, e := range manifest {
				if e.PTS > r.PTS0 {
					file, err := os.Open(e.FileAbsolutePath)
					if err != nil {
						return nil, err
					}
					if err := r.file.Close(); err != nil {
						return nil, err
					}
					r.file = file
					r.PTS0 = e.PTS
					return r.Next()
				}
			}
			return nil, io.EOF
		}
		return nil, err
	}
	flags := MediaCodecBufferFlag(binary.LittleEndian.Uint32(header[0:4]))
	pts := binary.LittleEndian.Uint64(header[4:12])
	// ntp := binary.LittleEndian.Uint64(header[12:20])
	size := binary.LittleEndian.Uint32(header[20:24])
	data := make([]byte, size) // TODO: mmap this file
	if _, err := r.file.Read(data); err != nil {
		return nil, err
	}
	return &Sample{Flags: flags, PTS: int64(pts), Data: data}, nil
}
