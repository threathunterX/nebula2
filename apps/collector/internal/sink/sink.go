// Package sink 事件输出。
//
// 当前实现 stdout 与 file(JSON Lines)。生产形态的 Kafka sink 见
// apps/collector/README.md 的规划。
package sink

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"sync"

	"github.com/threathunterX/nebula2/apps/collector/internal/event"
)

// Sink 事件输出目标。
type Sink interface {
	Name() string
	Write(e *event.Event) error
	Close() error
}

type writerSink struct {
	name string
	mu   sync.Mutex
	w    *bufio.Writer
	c    io.Closer
}

func (s *writerSink) Name() string { return s.name }

func (s *writerSink) Write(e *event.Event) error {
	b, err := json.Marshal(e)
	if err != nil {
		return fmt.Errorf("序列化事件: %w", err)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, err := s.w.Write(b); err != nil {
		return err
	}
	return s.w.WriteByte('\n')
}

func (s *writerSink) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := s.w.Flush(); err != nil {
		return err
	}
	if s.c != nil {
		return s.c.Close()
	}
	return nil
}

// NewStdout 输出到标准输出。
func NewStdout() Sink {
	return &writerSink{name: "stdout", w: bufio.NewWriter(os.Stdout)}
}

// NewFile 输出到文件(追加)。
func NewFile(path string) (Sink, error) {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return nil, fmt.Errorf("打开输出文件 %s: %w", path, err)
	}
	return &writerSink{name: "file", w: bufio.NewWriter(f), c: f}, nil
}
