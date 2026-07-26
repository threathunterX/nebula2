package sink

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/twmb/franz-go/pkg/kgo"

	"github.com/threathunterX/nebula2/apps/collector/internal/event"
)

// Kafka 输出。
//
// # 为什么这里破了「零依赖」
//
// 采集器此前是零外部依赖的单二进制,这条写在 README、SECURITY.md 与快速开始里,
// 理由是「要装到客户环境里,依赖越少越好」。引入 franz-go 是**项目层面的决定**,
// 不是顺手改掉的:
//
//   - 零依赖是**手段**不是目的。目的是部署简单、要审计的东西少。
//   - 而「让使用方自己接管道」的代价更高:管道断了采集器不知道,它的指标看不到
//     管道那一头,也就没人告诉你。风控链路上少一段数据不会报错,只会让策略不命中。
//   - 自己实现 Kafka 协议子集能保住零依赖,但版本协商、分区路由、错误码处理是长期
//     维护负担,写错的表现往往是**静默丢数据** —— 比多一个依赖糟得多。
//
// 依赖代价是可量化的:franz-go 加 5 个传递模块(compress、lz4、kmsg、x/crypto)。
// 全部是 Go 生态里维护活跃、被广泛使用的库。
//
// # 投递语义
//
// 用**异步**生产 + 显式 flush。同步逐条发会把吞吐压到网络往返的量级 ——
// 采集器实测能到 22 万条/秒,逐条同步会掉到几千。
//
// 代价是:`Write` 返回成功只表示「已进入发送队列」,不表示已落到 broker。真正的
// 投递结果通过回调计入 `failed` 计数,并在 `Close` 时 flush 干净。
// **调用方要读这个计数** —— 只看 Write 的返回值会漏掉全部投递失败。
type kafkaSink struct {
	client *kgo.Client
	topic  string

	// 投递失败数。异步生产下这是唯一能看到投递结果的地方。
	failed atomic.Int64
	// 最近一次失败的原因,供退出摘要显示 —— 只有计数没有原因时,排查得从头开始。
	mu        sync.Mutex
	lastErr   error
	lastErrAt time.Time
}

// KafkaOptions Kafka 输出的配置。
type KafkaOptions struct {
	// Brokers 逗号分隔的地址列表。
	Brokers string
	// Topic 目标主题。
	Topic string
	// ClientID 便于在 broker 侧识别来源。空则用 nebula-collector。
	ClientID string
	// Acks 投递确认强度:all(默认)| leader | none。
	Acks string
	// Compression 压缩:none | gzip | snappy | lz4(默认)| zstd。
	Compression string
}

// NewKafka 连接 Kafka 并返回输出。
func NewKafka(opt KafkaOptions) (Sink, error) {
	brokers := splitTrim(opt.Brokers)
	if len(brokers) == 0 {
		return nil, fmt.Errorf("kafka 输出缺少 brokers")
	}
	if opt.Topic == "" {
		return nil, fmt.Errorf("kafka 输出缺少 topic")
	}
	clientID := opt.ClientID
	if clientID == "" {
		clientID = "nebula-collector"
	}

	acks, idempotent, err := parseAcks(opt.Acks)
	if err != nil {
		return nil, err
	}
	comp, err := parseCompression(opt.Compression)
	if err != nil {
		return nil, err
	}

	opts := []kgo.Opt{
		kgo.SeedBrokers(brokers...),
		kgo.DefaultProduceTopic(opt.Topic),
		kgo.ClientID(clientID),
		kgo.RequiredAcks(acks),
		kgo.ProducerBatchCompression(comp),
	}
	if !idempotent {
		// franz-go 的幂等生产要求 acks=all,acks 降级时不关掉它会直接报错。
		// 把这个组合约束显式处理掉,而不是让人撞上再去查。
		opts = append(opts, kgo.DisableIdempotentWrite())
	}
	client, err := kgo.NewClient(opts...)
	if err != nil {
		return nil, fmt.Errorf("连接 Kafka: %w", err)
	}

	// 连不上时立刻失败,而不是等到第一条事件才发现。采集器是常驻进程,
	// 「起来了但一条也发不出去」是最难察觉的故障形态。
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := client.Ping(ctx); err != nil {
		client.Close()
		return nil, fmt.Errorf("Kafka 不可达(%s): %w", opt.Brokers, err)
	}

	return &kafkaSink{client: client, topic: opt.Topic}, nil
}

func (s *kafkaSink) Name() string { return "kafka" }

func (s *kafkaSink) Write(e *event.Event) error {
	b, err := json.Marshal(e)
	if err != nil {
		return fmt.Errorf("序列化事件: %w", err)
	}
	rec := &kgo.Record{Value: b}
	// 按主体分区:同一个 IP / 账号的事件落到同一分区,下游按 key 分区计算时
	// 才不会把同一主体的事件拆到不同并行实例上。取不到主体时留空由 broker 轮询。
	if k := partitionKey(e); k != "" {
		rec.Key = []byte(k)
	}
	s.client.Produce(context.Background(), rec, func(_ *kgo.Record, err error) {
		if err != nil {
			s.failed.Add(1)
			s.mu.Lock()
			s.lastErr = err
			s.lastErrAt = time.Now()
			s.mu.Unlock()
		}
	})
	return nil
}

// partitionKey 取事件里的主体标识。顺序与名单主体类型的常见程度一致。
func partitionKey(e *event.Event) string {
	for _, f := range []string{"uid", "did", "c_ip"} {
		if v, ok := e.Values[f]; ok {
			if s, ok := v.(string); ok && s != "" {
				return s
			}
		}
	}
	return ""
}

// Failed 投递失败数。异步生产下这是唯一能看到投递结果的地方。
func (s *kafkaSink) Failed() int64 { return s.failed.Load() }

// LastError 最近一次投递失败的原因。没有失败时返回 nil。
func (s *kafkaSink) LastError() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.lastErr
}

func (s *kafkaSink) Close() error {
	// 先 flush 再关。不 flush 直接 Close 会丢掉队列里未发送的记录 ——
	// 而那正是进程退出时最容易丢的一批。
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	flushErr := s.client.Flush(ctx)
	s.client.Close()

	if n := s.failed.Load(); n > 0 {
		return fmt.Errorf("%d 条事件投递失败,最近一次: %v", n, s.LastError())
	}
	return flushErr
}

func splitTrim(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if t := strings.TrimSpace(p); t != "" {
			out = append(out, t)
		}
	}
	return out
}

// parseAcks 返回确认强度,以及**是否可以启用幂等生产**(只有 acks=all 才可以)。
func parseAcks(s string) (kgo.Acks, bool, error) {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "", "all", "-1":
		return kgo.AllISRAcks(), true, nil
	case "leader", "1":
		return kgo.LeaderAck(), false, nil
	case "none", "0":
		// 不等确认。吞吐最高,但 broker 拒收时采集器不会知道 —— 风控链路上
		// 少一段数据不会报错,只会让策略不命中。选它要清楚这一点。
		return kgo.NoAck(), false, nil
	default:
		return kgo.Acks{}, false, fmt.Errorf("acks 取值非法: %q(可取 all / leader / none)", s)
	}
}

func parseCompression(s string) (kgo.CompressionCodec, error) {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "", "lz4":
		return kgo.Lz4Compression(), nil
	case "none":
		return kgo.NoCompression(), nil
	case "gzip":
		return kgo.GzipCompression(), nil
	case "snappy":
		return kgo.SnappyCompression(), nil
	case "zstd":
		return kgo.ZstdCompression(), nil
	default:
		return kgo.CompressionCodec{}, fmt.Errorf(
			"compression 取值非法: %q(可取 none / gzip / snappy / lz4 / zstd)", s)
	}
}
