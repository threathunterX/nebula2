package mask

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/threathunterX/nebula2/apps/collector/internal/event"
)

func newMasker(t *testing.T, cfg Config, reg *event.Registry) *Masker {
	t.Helper()
	if cfg.HMACKey == "" {
		cfg.HMACKey = "test-key-not-for-production"
	}
	m, err := New(cfg, reg)
	if err != nil {
		t.Fatalf("构建脱敏器失败: %v", err)
	}
	return m
}

func TestDefaultRulesDropHighRiskFields(t *testing.T) {
	m := newMasker(t, Config{}, nil)
	e := &event.Event{Name: "ACCOUNT_LOGIN", Values: map[string]any{
		"cookie": "session=abc; token=xyz",
		"s_body": `{"user":"alice"}`,
		"c_ip":   "198.51.100.1",
	}}
	m.Apply(e)

	if e.Values["cookie"] != Redacted {
		t.Errorf("cookie 应被丢弃,实际 %v", e.Values["cookie"])
	}
	if e.Values["s_body"] != Redacted {
		t.Errorf("s_body 应被丢弃,实际 %v", e.Values["s_body"])
	}
	if e.Values["c_ip"] != "198.51.100.1" {
		t.Errorf("未标注敏感级别的字段不应被改动,实际 %v", e.Values["c_ip"])
	}
}

func TestRegexMaskingKeepsStructure(t *testing.T) {
	m := newMasker(t, Config{}, nil)
	e := &event.Event{Name: "ACCOUNT_LOGIN", Values: map[string]any{
		"uri_query": "user=alice&password=hunter2&next=/home",
		"c_body":    "action=login&mobile=13800138000&ok=1",
	}}
	m.Apply(e)

	q := e.Values["uri_query"].(string)
	if strings.Contains(q, "hunter2") {
		t.Errorf("口令应被脱敏: %s", q)
	}
	if !strings.Contains(q, "user=alice") || !strings.Contains(q, "next=/home") {
		t.Errorf("非敏感参数应保留,便于排查: %s", q)
	}
	b := e.Values["c_body"].(string)
	if strings.Contains(b, "13800138000") {
		t.Errorf("手机号应被脱敏: %s", b)
	}
	if !strings.Contains(b, "action=login") {
		t.Errorf("非敏感参数应保留: %s", b)
	}
}

func TestHMACIsDeterministicAndIrreversible(t *testing.T) {
	m := newMasker(t, Config{Fields: map[string]Rule{
		"uid": {Action: event.MaskHash},
	}}, nil)

	e1 := &event.Event{Name: "ACCOUNT_LOGIN", Values: map[string]any{"uid": "alice"}}
	e2 := &event.Event{Name: "ACCOUNT_LOGIN", Values: map[string]any{"uid": "alice"}}
	e3 := &event.Event{Name: "ACCOUNT_LOGIN", Values: map[string]any{"uid": "bob"}}
	m.Apply(e1)
	m.Apply(e2)
	m.Apply(e3)

	// 可比较性:同值同结果 —— 风控靠这个判断「是否与历史一致」
	if e1.Values["uid"] != e2.Values["uid"] {
		t.Error("相同输入应产生相同哈希,否则无法做关联分析")
	}
	if e1.Values["uid"] == e3.Values["uid"] {
		t.Error("不同输入应产生不同哈希")
	}
	// 不可读性
	if strings.Contains(e1.Values["uid"].(string), "alice") {
		t.Errorf("哈希后不应含原文: %v", e1.Values["uid"])
	}
}

func TestHashWithoutKeyFailsLoudly(t *testing.T) {
	_, err := New(Config{Fields: map[string]Rule{"uid": {Action: event.MaskHash}}}, nil)
	if err == nil {
		t.Fatal("缺少 HMAC 密钥时必须启动失败,不能静默降级为明文")
	}
	if !strings.Contains(err.Error(), "NEBULA_HMAC_KEY") {
		t.Errorf("错误信息应指明如何补救,实际: %v", err)
	}
}

func TestPartialMasking(t *testing.T) {
	m := newMasker(t, Config{Fields: map[string]Rule{
		"mobile": {Action: event.MaskPartial, Keep: KeepSpec{Prefix: 3, Suffix: 4}},
		"short":  {Action: event.MaskPartial, Keep: KeepSpec{Prefix: 3, Suffix: 4}},
	}}, nil)
	e := &event.Event{Name: "X", Values: map[string]any{
		"mobile": "13800138000",
		"short":  "abc",
	}}
	m.Apply(e)

	if got := e.Values["mobile"]; got != "138****8000" {
		t.Errorf("期望 138****8000,实际 %v", got)
	}
	// 过短的值整体掩码 —— 否则「保留首尾」等于没脱敏
	if got := e.Values["short"]; got != "***" {
		t.Errorf("过短的值应整体掩码,实际 %v", got)
	}
}

func TestSensitivityDrivenMasking(t *testing.T) {
	// 用真实的事件模型驱动:字段声明为 sensitive 时必须脱敏,即便没有按字段名配规则
	var models []*event.Model
	if err := json.Unmarshal([]byte(`[
	  {"app":"nebula","name":"HTTP_DYNAMIC","visible_name":"动态请求","type":"base","version":"1.0",
	   "source":[{"app":"nebula","name":"HTTP_DYNAMIC"}],
	   "properties":[
	     {"name":"c_ip","type":"string","visible_name":"客户端IP","sensitivity":"pii"},
	     {"name":"secret_field","type":"string","visible_name":"密文","sensitivity":"sensitive","masking":"drop"},
	     {"name":"status","type":"long","visible_name":"状态码","sensitivity":"public"}
	   ]}
	]`), &models); err != nil {
		t.Fatal(err)
	}
	reg := event.NewRegistry(models)
	m := newMasker(t, Config{}, reg)

	e := &event.Event{Name: "HTTP_DYNAMIC", Values: map[string]any{
		"c_ip":         "198.51.100.1",
		"secret_field": "top-secret",
		"status":       float64(200),
	}}
	m.Apply(e)

	if v := e.Values["c_ip"].(string); v != "198.51.100.1" {
		// pii 在采集端保持原值:引擎需要原始 IP 做地理定位与关联分析,
		// 保护发生在存储层。见 mask.DefaultBySensitivity 的说明。
		t.Errorf("标注为 pii 的字段在采集端应保持原值,实际 %v", v)
	}
	if e.Values["secret_field"] != Redacted {
		t.Errorf("标注为 sensitive 的字段必须丢弃,实际 %v", e.Values["secret_field"])
	}
	if e.Values["status"] != float64(200) {
		t.Errorf("public 字段不应被改动,实际 %v", e.Values["status"])
	}
}

func TestInvalidRegexFailsAtStartup(t *testing.T) {
	_, err := New(Config{Fields: map[string]Rule{
		"x": {Action: event.MaskRegex, Pattern: "([unclosed"},
	}, HMACKey: "k"}, nil)
	if err == nil {
		t.Fatal("非法正则必须在启动时报错,而不是运行时静默失效")
	}
}

func TestNonStringFieldsOnlySupportDrop(t *testing.T) {
	m := newMasker(t, Config{Fields: map[string]Rule{
		"status": {Action: event.MaskHash},
		"bytes":  {Action: event.MaskDrop},
	}}, nil)
	e := &event.Event{Name: "X", Values: map[string]any{
		"status": float64(200),
		"bytes":  float64(1024),
	}}
	m.Apply(e)

	if e.Values["status"] != float64(200) {
		t.Errorf("数值字段做哈希无意义,应保持不变,实际 %v", e.Values["status"])
	}
	if e.Values["bytes"] != Redacted {
		t.Errorf("数值字段的 drop 应生效,实际 %v", e.Values["bytes"])
	}
}

func TestStatsRecordApplications(t *testing.T) {
	m := newMasker(t, Config{}, nil)
	for i := 0; i < 3; i++ {
		m.Apply(&event.Event{Name: "X", Values: map[string]any{"cookie": "a=b"}})
	}
	if got := m.Stats.Snapshot()["cookie"]; got != 3 {
		t.Errorf("应记录 3 次脱敏,实际 %d", got)
	}
}
