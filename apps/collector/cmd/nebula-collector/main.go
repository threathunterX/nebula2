// nebula-collector 是星云 2.0 的数据采集器。
//
// 它把各种来源的业务流量与日志还原成标准化事件,并在数据离开客户网络边界
// 之前完成敏感字段脱敏,再交给下游。
//
// 用法:
//
//	nebula-collector -config collector.json
//	cat events.jsonl | nebula-collector -events ../../seeds/events
package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/threathunterX/nebula2/apps/collector/internal/config"
	"github.com/threathunterX/nebula2/apps/collector/internal/driver"
	"github.com/threathunterX/nebula2/apps/collector/internal/event"
	"github.com/threathunterX/nebula2/apps/collector/internal/mask"
	"github.com/threathunterX/nebula2/apps/collector/internal/pipeline"
	"github.com/threathunterX/nebula2/apps/collector/internal/sink"
)

// version 由构建时注入:go build -ldflags "-X main.version=..."
var version = "dev"

func main() {
	if err := run(); err != nil {
		fmt.Fprintf(os.Stderr, "nebula-collector: %v\n", err)
		os.Exit(1)
	}
}

func run() error {
	var (
		cfgPath   = flag.String("config", "", "配置文件路径(JSON)")
		eventsDir = flag.String("events", "", "事件模型目录,用于按敏感级别脱敏")
		srcType   = flag.String("source", "", "数据源: stdin | file | http")
		srcPath   = flag.String("source-path", "", "source=file 时的文件路径")
		srcAddr   = flag.String("source-addr", "", "source=http 时的监听地址")
		outPath   = flag.String("out", "", "输出文件路径,缺省写 stdout")
		strict    = flag.Bool("strict", false, "事件类型不在模型中时丢弃")
		showVer   = flag.Bool("version", false, "显示版本")
		quiet     = flag.Bool("quiet", false, "不输出运行摘要")
	)
	flag.Parse()

	if *showVer {
		fmt.Println("nebula-collector", version)
		return nil
	}

	cfg, err := config.Load(*cfgPath)
	if err != nil {
		return err
	}
	// 命令行优先级高于配置文件
	if *eventsDir != "" {
		cfg.EventsDir = *eventsDir
	}
	if *srcType != "" {
		cfg.Source.Type = *srcType
	}
	if *srcPath != "" {
		cfg.Source.Type, cfg.Source.Path = "file", *srcPath
	}
	if *srcAddr != "" {
		cfg.Source.Type, cfg.Source.Addr = "http", *srcAddr
	}
	if *outPath != "" {
		cfg.Sink.Type, cfg.Sink.Path = "file", *outPath
	}

	var registry *event.Registry
	if cfg.EventsDir != "" {
		registry, err = event.LoadRegistry(cfg.EventsDir)
		if err != nil {
			return err
		}
	}

	masker, err := mask.New(cfg.Masking, registry)
	if err != nil {
		return err
	}

	var drv driver.Driver
	switch cfg.Source.Type {
	case "stdin":
		drv = driver.NewStdin(cfg.DefaultEventName)
	case "file":
		drv = driver.NewFile(cfg.Source.Path, cfg.DefaultEventName)
	case "http":
		drv = driver.NewHTTP(cfg.Source.Addr, cfg.DefaultEventName)
	default:
		return fmt.Errorf("不支持的数据源类型 %q", cfg.Source.Type)
	}

	var sk sink.Sink
	switch cfg.Sink.Type {
	case "stdout":
		sk = sink.NewStdout()
	case "file":
		sk, err = sink.NewFile(cfg.Sink.Path)
		if err != nil {
			return err
		}
	default:
		return fmt.Errorf("不支持的输出类型 %q", cfg.Sink.Type)
	}

	p := pipeline.New(drv, masker, sk, registry)
	p.StrictEventName = *strict

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	start := time.Now()
	if err := p.Run(ctx); err != nil {
		return err
	}
	if !*quiet {
		fmt.Fprintln(os.Stderr, p.Report(time.Since(start)))
		if s := masker.Stats.Snapshot(); len(s) > 0 {
			fmt.Fprint(os.Stderr, "脱敏命中:")
			for f, n := range s {
				fmt.Fprintf(os.Stderr, " %s=%d", f, n)
			}
			fmt.Fprintln(os.Stderr)
		}
	}
	return nil
}
