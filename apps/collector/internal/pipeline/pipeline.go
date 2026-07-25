// Package pipeline 把驱动、脱敏、输出串成一条流水线。
package pipeline

import (
	"context"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"github.com/threathunterX/nebula2/apps/collector/internal/driver"
	"github.com/threathunterX/nebula2/apps/collector/internal/event"
	"github.com/threathunterX/nebula2/apps/collector/internal/mask"
	"github.com/threathunterX/nebula2/apps/collector/internal/sink"
)

// Stats 流水线运行指标。
type Stats struct {
	Received  atomic.Int64
	Emitted   atomic.Int64
	Dropped   atomic.Int64 // 未知事件类型等原因被丢弃
	WriteErrs atomic.Int64
}

// Pipeline 采集流水线。
type Pipeline struct {
	drv      driver.Driver
	masker   *mask.Masker
	sink     sink.Sink
	registry *event.Registry
	// StrictEventName 为 true 时,事件类型不在模型中即丢弃;
	// 为 false 时放行但计数,便于接入初期排查字段映射。
	StrictEventName bool
	Stats           Stats
	bufSize         int
}

// New 构建流水线。
func New(drv driver.Driver, m *mask.Masker, sk sink.Sink, reg *event.Registry) *Pipeline {
	return &Pipeline{drv: drv, masker: m, sink: sk, registry: reg, bufSize: 4096}
}

// Run 阻塞运行直到数据源结束或 ctx 取消。
func (p *Pipeline) Run(ctx context.Context) error {
	ch := make(chan *event.Event, p.bufSize)
	var wg sync.WaitGroup

	var drvErr error
	wg.Add(1)
	go func() {
		defer wg.Done()
		defer close(ch)
		drvErr = p.drv.Run(ctx, ch)
	}()

	for e := range ch {
		p.Stats.Received.Add(1)

		if p.registry != nil {
			if _, ok := p.registry.Get(e.Name); !ok {
				if p.StrictEventName {
					p.Stats.Dropped.Add(1)
					continue
				}
			}
		}

		// 脱敏必须在写出之前 —— 这是隐私设计的核心不变式
		if p.masker != nil {
			p.masker.Apply(e)
		}

		if err := p.sink.Write(e); err != nil {
			p.Stats.WriteErrs.Add(1)
			continue
		}
		p.Stats.Emitted.Add(1)
	}

	wg.Wait()
	if err := p.sink.Close(); err != nil {
		return fmt.Errorf("关闭输出: %w", err)
	}
	if drvErr != nil && drvErr != context.Canceled {
		return drvErr
	}
	return nil
}

// Report 返回一行可读的运行摘要。
func (p *Pipeline) Report(elapsed time.Duration) string {
	recv := p.Stats.Received.Load()
	rate := float64(0)
	if elapsed > 0 {
		rate = float64(recv) / elapsed.Seconds()
	}
	return fmt.Sprintf("接收 %d 条,输出 %d 条,丢弃 %d 条,写出失败 %d 次,耗时 %s(%.0f 条/秒)",
		recv, p.Stats.Emitted.Load(), p.Stats.Dropped.Load(), p.Stats.WriteErrs.Load(),
		elapsed.Round(time.Millisecond), rate)
}
