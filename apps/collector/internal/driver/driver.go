// Package driver 数据源驱动。
//
// 每种驱动把外部数据转成统一的 event.Event。当前实现 stdin / file / http,
// Kafka、syslog、Zeek 旁路等见 apps/collector/README.md 的规划。
package driver

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"

	"github.com/threathunterX/nebula2/apps/collector/internal/event"
)

// Driver 数据源。Run 在 ctx 取消或数据源结束时返回。
type Driver interface {
	Name() string
	Run(ctx context.Context, out chan<- *event.Event) error
}

// lineDriver 按行读取 JSON Lines,stdin 与 file 共用。
type lineDriver struct {
	name             string
	open             func() (io.ReadCloser, error)
	defaultEventName string
	// Malformed 解析失败的行数。不静默丢弃,交由上层记录指标。
	Malformed int64
}

func (d *lineDriver) Name() string { return d.name }

func (d *lineDriver) Run(ctx context.Context, out chan<- *event.Event) error {
	rc, err := d.open()
	if err != nil {
		return err
	}
	defer rc.Close()

	sc := bufio.NewScanner(rc)
	sc.Buffer(make([]byte, 0, 64*1024), 8*1024*1024) // 请求体可能很大
	for sc.Scan() {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}
		line := sc.Bytes()
		if len(line) == 0 {
			continue
		}
		var e event.Event
		if err := json.Unmarshal(line, &e); err != nil {
			d.Malformed++
			continue
		}
		normalize(&e, d.defaultEventName)
		select {
		case out <- &e:
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	return sc.Err()
}

func normalize(e *event.Event, defaultName string) {
	if e.Name == "" {
		e.Name = defaultName
	}
	if e.Timestamp == 0 {
		e.Timestamp = time.Now().UnixMilli()
	}
	if e.Values == nil {
		e.Values = map[string]any{}
	}
}

// NewStdin 从标准输入读取 JSON Lines。
func NewStdin(defaultEventName string) Driver {
	return &lineDriver{
		name:             "stdin",
		defaultEventName: defaultEventName,
		open:             func() (io.ReadCloser, error) { return io.NopCloser(os.Stdin), nil },
	}
}

// NewFile 从文件读取 JSON Lines。
func NewFile(path, defaultEventName string) Driver {
	return &lineDriver{
		name:             "file",
		defaultEventName: defaultEventName,
		open:             func() (io.ReadCloser, error) { return os.Open(path) },
	}
}

// httpDriver 接收 SDK 埋点上报。
type httpDriver struct {
	addr             string
	defaultEventName string
}

// NewHTTP 监听 HTTP,接受单条 JSON 或 JSON 数组。
func NewHTTP(addr, defaultEventName string) Driver {
	return &httpDriver{addr: addr, defaultEventName: defaultEventName}
}

func (d *httpDriver) Name() string { return "http" }

func (d *httpDriver) Run(ctx context.Context, out chan<- *event.Event) error {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, "ok")
	})
	mux.HandleFunc("/v2/events", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "只接受 POST", http.StatusMethodNotAllowed)
			return
		}
		defer r.Body.Close()
		body, err := io.ReadAll(io.LimitReader(r.Body, 8*1024*1024))
		if err != nil {
			http.Error(w, "读取请求体失败", http.StatusBadRequest)
			return
		}
		events, err := parseBatch(body)
		if err != nil {
			http.Error(w, fmt.Sprintf("解析失败: %v", err), http.StatusBadRequest)
			return
		}
		for _, e := range events {
			normalize(e, d.defaultEventName)
			select {
			case out <- e:
			case <-ctx.Done():
				http.Error(w, "服务正在关闭", http.StatusServiceUnavailable)
				return
			}
		}
		w.WriteHeader(http.StatusAccepted)
		_, _ = fmt.Fprintf(w, `{"accepted":%d}`, len(events))
	})

	srv := &http.Server{
		Addr:              d.addr,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
	}
	errCh := make(chan error, 1)
	go func() {
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			errCh <- err
		}
	}()
	select {
	case <-ctx.Done():
		shutCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		return srv.Shutdown(shutCtx)
	case err := <-errCh:
		return err
	}
}

func parseBatch(body []byte) ([]*event.Event, error) {
	trimmed := body
	for len(trimmed) > 0 && (trimmed[0] == ' ' || trimmed[0] == '\n' || trimmed[0] == '\t' || trimmed[0] == '\r') {
		trimmed = trimmed[1:]
	}
	if len(trimmed) == 0 {
		return nil, fmt.Errorf("请求体为空")
	}
	if trimmed[0] == '[' {
		var batch []*event.Event
		if err := json.Unmarshal(trimmed, &batch); err != nil {
			return nil, err
		}
		return batch, nil
	}
	var one event.Event
	if err := json.Unmarshal(trimmed, &one); err != nil {
		return nil, err
	}
	return []*event.Event{&one}, nil
}
