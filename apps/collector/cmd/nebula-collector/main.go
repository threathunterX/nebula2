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
	"github.com/threathunterX/nebula2/apps/collector/internal/metrics"
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
		srcType   = flag.String("source", "", "数据源: stdin | file | http | syslog")
		srcPath   = flag.String("source-path", "", "source=file 时的文件路径")
		srcAddr   = flag.String("source-addr", "", "source=http/syslog 时的监听地址")
		// syslog 的默认传输是 UDP —— 绝大多数网络设备只支持它。UDP 会丢包,
		// 而丢的那条可能正是要命中策略的那条,所以支持切到 TCP。
		syslogNet = flag.String("syslog-network", "", "source=syslog 时的传输: udp(默认)| tcp")
		// 接入口的共享令牌。不从命令行传值 —— 命令行参数会出现在 ps 输出、
		// shell 历史与容器的 inspect 里。空表示不校验,启动时会打印警告。
		tokenEnv = "NEBULA_COLLECTOR_TOKEN"
		outPath  = flag.String("out", "", "输出文件路径,缺省写 stdout")
		strict   = flag.Bool("strict", false, "事件类型不在模型中时丢弃")
		showVer  = flag.Bool("version", false, "显示版本")
		quiet    = flag.Bool("quiet", false, "不输出运行摘要")
		// 独立于接入端口:接入端口通常只对业务网段开放,而指标要给监控系统抓
		metricsAddr = flag.String("metrics-addr", "", "Prometheus 指标监听地址,如 127.0.0.1:9100")
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
		cfg.Source.Addr = *srcAddr
		// 只在没显式指定数据源类型时才推断为 http。写死成 http 会让
		// `-source syslog -source-addr :514` 被悄悄改回 http —— 参数都对,
		// 行为却不是要的那个,而且不报错。
		if *srcType == "" && cfg.Source.Type != "syslog" {
			cfg.Source.Type = "http"
		}
	}
	if *syslogNet != "" {
		cfg.Source.Network = *syslogNet
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
		token := os.Getenv(tokenEnv)
		if token == "" && !*quiet {
			// 不静默放行:「忘了配」与「明确决定不配」在日志里必须能区分开。
			// 采集器入口是名单投毒最直接的路径 —— 能连到端口的人都可以伪造事件。
			fmt.Fprintf(os.Stderr,
				"警告:未设置 %s,接入口不校验来源。任何能连到 %s 的人都可以伪造事件。\n",
				tokenEnv, cfg.Source.Addr)
		}
		drv = driver.NewHTTP(cfg.Source.Addr, cfg.DefaultEventName, token)
	case "syslog":
		network := cfg.Source.Network
		if network == "" {
			network = "udp"
		}
		// syslog 协议本身没有认证的位置。写明白,而不是让人以为配了 token 就管用。
		if !*quiet {
			fmt.Fprintf(os.Stderr,
				"提示:syslog 协议无认证机制,请把 %s 绑定到内网地址并用网络策略限制来源。\n",
				cfg.Source.Addr)
		}
		drv = driver.NewSyslog(network, cfg.Source.Addr, cfg.DefaultEventName)
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

	// 采集器是常驻进程,退出时才打印摘要对监控毫无用处
	ms := metrics.Start(*metricsAddr, func() metrics.Snapshot {
		return metrics.Snapshot{
			Received:    p.Stats.Received.Load(),
			Emitted:     p.Stats.Emitted.Load(),
			Dropped:     p.Stats.Dropped.Load(),
			WriteErrs:   p.Stats.WriteErrs.Load(),
			MaskApplied: masker.Stats.Snapshot(),
		}
	}, func(err error) {
		fmt.Fprintf(os.Stderr, "指标服务异常: %v\n", err)
	})
	defer func() { _ = ms.Close() }()
	if *metricsAddr != "" {
		fmt.Fprintf(os.Stderr, "指标端点: http://%s/metrics\n", *metricsAddr)
	}

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
