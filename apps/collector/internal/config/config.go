// Package config 采集器配置。
//
// 配置一律外置,凭据只从环境变量注入 —— 配置文件里不允许出现任何可用凭据。
// 这是 2.0 的硬性要求,见 SECURITY.md。
package config

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"

	"github.com/threathunterX/nebula2/apps/collector/internal/mask"
)

// Config 采集器完整配置。
type Config struct {
	// Source 数据源:stdin | file | http | syslog
	Source SourceConfig `json:"source"`
	// Sink 输出:stdout | file | kafka
	Sink SinkConfig `json:"sink"`
	// Masking 脱敏规则。缺省从严,见 mask.DefaultFieldRules。
	Masking mask.Config `json:"masking"`
	// EventsDir 事件模型目录,用于按敏感级别脱敏与字段校验。
	EventsDir string `json:"events_dir,omitempty"`
	// DefaultEventName 数据源未提供事件名时的缺省值。
	DefaultEventName string `json:"default_event_name,omitempty"`
}

// SourceConfig 数据源配置。
type SourceConfig struct {
	Type string `json:"type"`
	Path string `json:"path,omitempty"` // file
	Addr string `json:"addr,omitempty"` // http / syslog 监听地址
	// Network syslog 的传输,udp(默认)或 tcp。
	Network string `json:"network,omitempty"`
}

// SinkConfig 输出配置。
type SinkConfig struct {
	Type string `json:"type"`
	Path string `json:"path,omitempty"`
	// Kafka 输出的配置,type=kafka 时使用。
	Brokers     string `json:"brokers,omitempty"`
	Topic       string `json:"topic,omitempty"`
	ClientID    string `json:"client_id,omitempty"`
	Acks        string `json:"acks,omitempty"`
	Compression string `json:"compression,omitempty"`
}

// Default 缺省配置:从 stdin 读、写 stdout,便于用管道快速验证。
func Default() Config {
	return Config{
		Source:           SourceConfig{Type: "stdin"},
		Sink:             SinkConfig{Type: "stdout"},
		DefaultEventName: "HTTP_DYNAMIC",
	}
}

// Load 从文件加载并叠加环境变量。
func Load(path string) (Config, error) {
	cfg := Default()
	if path != "" {
		b, err := os.ReadFile(path)
		if err != nil {
			return cfg, fmt.Errorf("读取配置 %s: %w", path, err)
		}
		if err := json.Unmarshal(b, &cfg); err != nil {
			return cfg, fmt.Errorf("解析配置 %s: %w", path, err)
		}
	}
	cfg.applyEnv()
	return cfg, cfg.validate()
}

func (c *Config) applyEnv() {
	// 凭据只从环境变量取,永不落配置文件
	c.Masking.HMACKey = os.Getenv("NEBULA_HMAC_KEY")
	if v := os.Getenv("NEBULA_EVENTS_DIR"); v != "" {
		c.EventsDir = v
	}
	if v := os.Getenv("NEBULA_SOURCE_TYPE"); v != "" {
		c.Source.Type = v
	}
	if v := os.Getenv("NEBULA_SOURCE_PATH"); v != "" {
		c.Source.Path = v
	}
	if v := os.Getenv("NEBULA_SINK_PATH"); v != "" {
		c.Sink.Path = v
		if c.Sink.Type == "stdout" {
			c.Sink.Type = "file"
		}
	}
}

func (c Config) validate() error {
	switch c.Source.Type {
	case "stdin":
	case "file":
		if c.Source.Path == "" {
			return fmt.Errorf("source.type=file 时必须提供 source.path")
		}
	case "http":
		if c.Source.Addr == "" {
			return fmt.Errorf("source.type=http 时必须提供 source.addr")
		}
	default:
		return fmt.Errorf("不支持的数据源类型 %q(可选: stdin, file, http)", c.Source.Type)
	}

	switch c.Sink.Type {
	case "stdout":
	case "file":
		if c.Sink.Path == "" {
			return fmt.Errorf("sink.type=file 时必须提供 sink.path")
		}
	default:
		return fmt.Errorf("不支持的输出类型 %q(可选: stdout, file)", c.Sink.Type)
	}

	for field, r := range c.Masking.Fields {
		if strings.TrimSpace(string(r.Action)) == "" {
			return fmt.Errorf("字段 %s 的脱敏规则缺少 action", field)
		}
	}
	return nil
}
