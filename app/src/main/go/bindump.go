package kinetic

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"slices"
	"strconv"
)

type BinaryDumpSink struct {
	directory string
	file      *os.File
}

func NewBinaryDumpSink(directory string) *BinaryDumpSink {
	return &BinaryDumpSink{directory: directory}
}

func (s *BinaryDumpSink) WriteSample(track int, buf []byte, ptsMicroseconds int64, mediaCodecFlags int32) error {
	// if it's a keyframe, create a new file based on the pts.
	if MediaCodecBufferFlag(mediaCodecFlags)&MediaCodecBufferFlagKeyFrame != 0 {
		if s.file != nil {
			if err := s.file.Close(); err != nil {
				return err
			}
		}
		file, err := os.Create(fmt.Sprintf("%s/%d.ucf", s.directory, ptsMicroseconds))
		if err != nil {
			return err
		}
		s.file = file
	}
	// write the flags and pts to the buffer
	if s.file != nil {
		header := make([]byte, 16)
		binary.LittleEndian.PutUint16(header[0:2], uint16(track+1))
		binary.LittleEndian.PutUint16(header[2:4], uint16(mediaCodecFlags))
		binary.LittleEndian.PutUint64(header[4:12], uint64(ptsMicroseconds))
		binary.LittleEndian.PutUint32(header[12:16], uint32(len(buf)))
		if _, err := s.file.Write(header); err != nil {
			return err
		}
		if _, err := s.file.Write(buf); err != nil {
			return err
		}
	}
	return nil
}

func (t *BinaryDumpSink) ReadManifest() ([]ManifestEntry, error) {
	files, err := os.ReadDir(t.directory)
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
		manifest = append(manifest, ManifestEntry{PTS: pts, FileAbsolutePath: fmt.Sprintf("%s/%s", t.directory, f.Name())})
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

type BinaryDumpSampleReader struct {
	t    *BinaryDumpSink
	file *os.File
	PTS0 int64
}

func (t *BinaryDumpSink) SampleReader(ptsMicroseconds int64) (*BinaryDumpSampleReader, error) {
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
	return &BinaryDumpSampleReader{t: t, file: file, PTS0: lastEntry.PTS}, nil
}

type BinaryDumpSample struct {
	Track int
	Flags MediaCodecBufferFlag
	PTS   int64
	Data  []byte
}

func (r *BinaryDumpSampleReader) Next() (*BinaryDumpSample, error) {
	header := make([]byte, 16)
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
	track := int(binary.LittleEndian.Uint16(header[2:4]))
	flags := MediaCodecBufferFlag(binary.LittleEndian.Uint16(header[0:2]))
	pts := int64(binary.LittleEndian.Uint64(header[4:12]))
	size := int(binary.LittleEndian.Uint32(header[12:16]))
	data := make([]byte, size)
	if _, err := r.file.Read(data); err != nil {
		return nil, err
	}
	return &BinaryDumpSample{Track: track, Flags: flags, PTS: int64(pts), Data: data}, nil
}
