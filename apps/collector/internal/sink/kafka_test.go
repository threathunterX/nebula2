package sink

import (
	"testing"

	"github.com/threathunterX/nebula2/apps/collector/internal/event"
)

// 不连真 broker 的部分:参数解析与分区键。
// 真实投递由 compose 里的 Redpanda 做端到端验证(见 apps/collector/README.md)。

func TestParseAcks(t *testing.T) {
	cases := map[string]bool{ // 输入 -> 是否可启用幂等
		"":       true,
		"all":    true,
		"-1":     true,
		"ALL":    true,
		"leader": false,
		"1":      false,
		"none":   false,
		"0":      false,
	}
	for in, wantIdem := range cases {
		_, idem, err := parseAcks(in)
		if err != nil {
			t.Errorf("parseAcks(%q) 报错: %v", in, err)
			continue
		}
		if idem != wantIdem {
			t.Errorf("parseAcks(%q) 幂等 = %v,要 %v", in, idem, wantIdem)
		}
	}
	if _, _, err := parseAcks("bogus"); err == nil {
		t.Error("非法 acks 应当报错 —— 静默降级会让人以为配置生效了")
	}
}

func TestParseAcksIdempotencyConstraint(t *testing.T) {
	// franz-go 的幂等生产要求 acks=all。这个约束不在我们的代码里,而在库里 ——
	// 判断错了的表现是构造客户端时直接报错,所以这里把对应关系钉死。
	for _, weak := range []string{"leader", "none"} {
		if _, idem, _ := parseAcks(weak); idem {
			t.Errorf("acks=%s 时不能启用幂等,否则 kgo.NewClient 会失败", weak)
		}
	}
}

func TestParseCompression(t *testing.T) {
	for _, ok := range []string{"", "none", "gzip", "snappy", "lz4", "zstd", "LZ4"} {
		if _, err := parseCompression(ok); err != nil {
			t.Errorf("parseCompression(%q) 报错: %v", ok, err)
		}
	}
	if _, err := parseCompression("bogus"); err == nil {
		t.Error("非法 compression 应当报错")
	}
}

func TestSplitTrim(t *testing.T) {
	got := splitTrim(" a:1 , b:2 ,, c:3 ")
	want := []string{"a:1", "b:2", "c:3"}
	if len(got) != len(want) {
		t.Fatalf("得到 %v,要 %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("第 %d 个 = %q,要 %q", i, got[i], want[i])
		}
	}
	if len(splitTrim("  ")) != 0 {
		t.Error("全是空白时应当返回空 —— 否则会拿一个空地址去连")
	}
}

func TestPartitionKey(t *testing.T) {
	// 同一主体的事件必须落到同一分区,否则下游按 key 分区计算时会被拆开
	mk := func(vals map[string]any) *event.Event {
		return &event.Event{Name: "ACCOUNT_LOGIN", Values: vals}
	}
	cases := []struct {
		name string
		e    *event.Event
		want string
	}{
		{"优先 uid", mk(map[string]any{"uid": "u1", "did": "d1", "c_ip": "198.51.100.4"}), "u1"},
		{"没有 uid 用 did", mk(map[string]any{"did": "d1", "c_ip": "198.51.100.4"}), "d1"},
		{"都没有用 c_ip", mk(map[string]any{"c_ip": "198.51.100.4"}), "198.51.100.4"},
		{"空串不算", mk(map[string]any{"uid": "", "did": "d1"}), "d1"},
		{"非字符串不算", mk(map[string]any{"uid": 42, "did": "d1"}), "d1"},
		{"一个都没有则留空", mk(map[string]any{"page": "/x"}), ""},
	}
	for _, c := range cases {
		if got := partitionKey(c.e); got != c.want {
			t.Errorf("%s: 得到 %q,要 %q", c.name, got, c.want)
		}
	}
}

func TestNewKafkaRejectsIncompleteConfig(t *testing.T) {
	// 缺配置时立刻失败,而不是连上去之后才发现没有主题
	if _, err := NewKafka(KafkaOptions{Topic: "t"}); err == nil {
		t.Error("缺 brokers 应当报错")
	}
	if _, err := NewKafka(KafkaOptions{Brokers: "127.0.0.1:9092"}); err == nil {
		t.Error("缺 topic 应当报错")
	}
}
