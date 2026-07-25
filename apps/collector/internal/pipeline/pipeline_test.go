package pipeline

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/threathunterX/nebula2/apps/collector/internal/driver"
	"github.com/threathunterX/nebula2/apps/collector/internal/event"
	"github.com/threathunterX/nebula2/apps/collector/internal/mask"
	"github.com/threathunterX/nebula2/apps/collector/internal/sink"
)

func seedsDir(t *testing.T) string {
	t.Helper()
	return filepath.Join("..", "..", "..", "..", "seeds", "events")
}

func runPipeline(t *testing.T, input string, strict bool) ([]map[string]any, *Pipeline) {
	t.Helper()
	dir := t.TempDir()
	inPath := filepath.Join(dir, "in.jsonl")
	outPath := filepath.Join(dir, "out.jsonl")
	if err := os.WriteFile(inPath, []byte(input), 0o600); err != nil {
		t.Fatal(err)
	}

	reg, err := event.LoadRegistry(seedsDir(t))
	if err != nil {
		t.Fatal(err)
	}
	m, err := mask.New(mask.Config{HMACKey: "test-key"}, reg)
	if err != nil {
		t.Fatal(err)
	}
	sk, err := sink.NewFile(outPath)
	if err != nil {
		t.Fatal(err)
	}
	p := New(driver.NewFile(inPath, "HTTP_DYNAMIC"), m, sk, reg)
	p.StrictEventName = strict
	if err := p.Run(context.Background()); err != nil {
		t.Fatalf("流水线运行失败: %v", err)
	}

	b, err := os.ReadFile(outPath)
	if err != nil {
		t.Fatal(err)
	}
	var out []map[string]any
	for _, line := range strings.Split(strings.TrimSpace(string(b)), "\n") {
		if line == "" {
			continue
		}
		var d map[string]any
		if err := json.Unmarshal([]byte(line), &d); err != nil {
			t.Fatalf("输出不是合法 JSON: %v", err)
		}
		out = append(out, d)
	}
	return out, p
}

func TestPipelineMasksBeforeWriting(t *testing.T) {
	// 核心不变式:任何敏感原文都不得出现在输出中
	in := `{"name":"ACCOUNT_REGISTRATION","timestamp":1784944800000,"c_ip":"198.51.100.1","uid":"alice","password":"hunter2","captcha":"8821","user_name":"张三","result":"T"}
`
	out, p := runPipeline(t, in, false)
	if len(out) != 1 {
		t.Fatalf("期望 1 条输出,实际 %d", len(out))
	}
	raw, _ := json.Marshal(out[0])
	for _, secret := range []string{"hunter2", "8821", "张三"} {
		if strings.Contains(string(raw), secret) {
			t.Errorf("敏感原文泄漏到输出: %s", secret)
		}
	}
	// pii 保持原值,风控引擎需要
	if out[0]["c_ip"] != "198.51.100.1" || out[0]["uid"] != "alice" {
		t.Errorf("pii 字段在采集端应保持原值,实际 %v", out[0])
	}
	if p.Stats.Emitted.Load() != 1 {
		t.Errorf("应输出 1 条,实际 %d", p.Stats.Emitted.Load())
	}
}

func TestMalformedLinesAreSkippedNotFatal(t *testing.T) {
	in := `{"name":"HTTP_DYNAMIC","timestamp":1,"c_ip":"198.51.100.1"}
这不是 JSON
{"name":"HTTP_DYNAMIC","timestamp":2,"c_ip":"198.51.100.2"}
`
	out, _ := runPipeline(t, in, false)
	if len(out) != 2 {
		t.Errorf("坏行应被跳过而不是中断整条流水线,实际输出 %d 条", len(out))
	}
}

func TestStrictModeDropsUnknownEventTypes(t *testing.T) {
	in := `{"name":"HTTP_DYNAMIC","timestamp":1,"c_ip":"198.51.100.1"}
{"name":"NOT_A_REAL_EVENT","timestamp":2,"c_ip":"198.51.100.2"}
`
	out, p := runPipeline(t, in, true)
	if len(out) != 1 {
		t.Errorf("strict 模式应丢弃未知事件类型,实际输出 %d 条", len(out))
	}
	if p.Stats.Dropped.Load() != 1 {
		t.Errorf("应记录 1 条丢弃,实际 %d", p.Stats.Dropped.Load())
	}
}

func TestNonStrictModePassesUnknownButCounts(t *testing.T) {
	in := `{"name":"NOT_A_REAL_EVENT","timestamp":1,"c_ip":"198.51.100.1"}
`
	out, p := runPipeline(t, in, false)
	if len(out) != 1 {
		t.Errorf("非 strict 模式应放行未知事件,便于接入初期排查,实际 %d 条", len(out))
	}
	if p.Stats.Dropped.Load() != 0 {
		t.Errorf("非 strict 模式不应丢弃,实际丢弃 %d", p.Stats.Dropped.Load())
	}
}

func TestMissingTimestampGetsFilled(t *testing.T) {
	in := `{"name":"HTTP_DYNAMIC","c_ip":"198.51.100.1"}
`
	out, _ := runPipeline(t, in, false)
	ts, ok := out[0]["timestamp"].(float64)
	if !ok || ts <= 0 {
		t.Errorf("缺失的时间戳应被补上,实际 %v", out[0]["timestamp"])
	}
}
