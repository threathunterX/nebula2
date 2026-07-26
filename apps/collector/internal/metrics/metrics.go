// Package metrics 以 Prometheus 的文本格式暴露采集器运行指标。
//
// # 为什么自己写而不是用 client_golang
//
// 采集器要装到客户环境里,依赖越少越好 —— 这条写进了 README 与 ADR,采集器至今
// 此前是零外部依赖的。现在有一个直接依赖(franz-go,Kafka 输出用),但这不构成
// 「反正已经有依赖了」的理由 —— 指标端点用标准库几十行就够,而 client_golang
// 是个要长期跟踪 CVE 的依赖。
//
// 而 Prometheus 的 exposition format 就是纯文本:每行一个 `名字{标签} 值`,
// 前面可选 HELP/TYPE 注释。为这点格式引入一个带反射、带 collector 注册表、带
// goroutine 的库,换来的是一个需要长期跟踪 CVE 的依赖。这里几十行就够。
//
// # 为什么指标端口独立于接入端口
//
// `-source http` 的接入端口通常只对业务网段开放,而指标要给监控系统抓 —— 两者的
// 访问方不同,合在一起意味着要么监控系统进不来,要么接入端口对监控网段敞开。
package metrics

import (
	"fmt"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
)

// Snapshot 一次采样的全部计数。由调用方从各处汇总后传入 —— 本包不持有业务状态,
// 只负责渲染,这样它可以被任意来源复用,也不会成为并发写入的第二个入口。
type Snapshot struct {
	Received  int64
	Emitted   int64
	Dropped   int64
	WriteErrs int64
	// MaskApplied 字段名 -> 脱敏次数
	MaskApplied map[string]int64
	MaskErrors  int64
}

// Handler 返回 /metrics 的处理函数。collect 在每次抓取时被调用。
func Handler(collect func() Snapshot, startedAt time.Time) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		s := collect()
		var b strings.Builder

		counter(&b, "nebula_collector_events_received_total",
			"接收到的事件条数", s.Received)
		counter(&b, "nebula_collector_events_emitted_total",
			"成功写出的事件条数", s.Emitted)
		// 上游改字段名时这个会涨,而链路本身看起来完全正常 —— 单独暴露才能发现
		counter(&b, "nebula_collector_events_dropped_total",
			"被丢弃的事件条数(事件类型不在模型中等)", s.Dropped)
		counter(&b, "nebula_collector_write_errors_total",
			"写出失败次数", s.WriteErrs)
		counter(&b, "nebula_collector_mask_errors_total",
			"脱敏执行失败次数", s.MaskErrors)

		// 按字段分的脱敏命中。某个字段的命中数突然归零,通常意味着上游不再发这个
		// 字段了 —— 而那意味着它的原值可能正从别的字段流过去。
		if len(s.MaskApplied) > 0 {
			b.WriteString("# HELP nebula_collector_mask_applied_total 按字段统计的脱敏次数\n")
			b.WriteString("# TYPE nebula_collector_mask_applied_total counter\n")
			fields := make([]string, 0, len(s.MaskApplied))
			for f := range s.MaskApplied {
				fields = append(fields, f)
			}
			sort.Strings(fields) // 输出稳定,便于 diff
			for _, f := range fields {
				fmt.Fprintf(&b, "nebula_collector_mask_applied_total{field=%q} %d\n",
					f, s.MaskApplied[f])
			}
		}

		gauge(&b, "nebula_collector_uptime_seconds",
			"进程已运行秒数", int64(time.Since(startedAt).Seconds()))

		w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
		_, _ = w.Write([]byte(b.String()))
	}
}

func counter(b *strings.Builder, name, help string, v int64) {
	fmt.Fprintf(b, "# HELP %s %s\n# TYPE %s counter\n%s %d\n", name, help, name, name, v)
}

func gauge(b *strings.Builder, name, help string, v int64) {
	fmt.Fprintf(b, "# HELP %s %s\n# TYPE %s gauge\n%s %d\n", name, help, name, name, v)
}

// Server 在独立端口上提供 /metrics。addr 为空则不启动。
type Server struct {
	srv *http.Server
	mu  sync.Mutex
}

// Start 启动指标服务。返回的错误只表示监听失败;运行期错误写到 onError。
func Start(addr string, collect func() Snapshot, onError func(error)) *Server {
	if addr == "" {
		return nil
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/metrics", Handler(collect, time.Now()))
	// 存活探针。指标端点在数据为空时也返回 200,单独给一个语义明确的路径。
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok\n"))
	})
	s := &Server{srv: &http.Server{
		Addr:              addr,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
	}}
	go func() {
		if err := s.srv.ListenAndServe(); err != nil && err != http.ErrServerClosed && onError != nil {
			onError(err)
		}
	}()
	return s
}

// Close 停止指标服务。
func (s *Server) Close() error {
	if s == nil {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.srv.Close()
}
