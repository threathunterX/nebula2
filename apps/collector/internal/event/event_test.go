package event

import (
	"encoding/json"
	"path/filepath"
	"testing"
)

func loadSeeds(t *testing.T) *Registry {
	t.Helper()
	r, err := LoadRegistry(filepath.Join("..", "..", "..", "..", "seeds", "events"))
	if err != nil {
		t.Fatalf("加载事件模型: %v", err)
	}
	return r
}

func TestLoadSeedRegistry(t *testing.T) {
	r := loadSeeds(t)
	if n := len(r.Names()); n != 17 {
		t.Errorf("期望 17 个内置事件模型,实际 %d", n)
	}
}

func TestInheritanceChain(t *testing.T) {
	r := loadSeeds(t)
	chain := r.Chain("ACCOUNT_LOGIN")
	if len(chain) != 2 || chain[0] != "ACCOUNT_LOGIN" || chain[1] != "HTTP_DYNAMIC" {
		t.Errorf("期望 [ACCOUNT_LOGIN HTTP_DYNAMIC],实际 %v", chain)
	}
	if !r.IsA("ACCOUNT_LOGIN", "HTTP_DYNAMIC") {
		t.Error("登录事件应可视为动态请求事件")
	}
	if r.IsA("HTTP_DYNAMIC", "ACCOUNT_LOGIN") {
		t.Error("继承是单向的")
	}
}

func TestRootEventSelfReferenceIsNotACycle(t *testing.T) {
	// 根事件的 source 按 1.x 惯例指向自身,解析时必须能终止
	r := loadSeeds(t)
	chain := r.Chain("HTTP_DYNAMIC")
	if len(chain) != 1 {
		t.Errorf("根事件的链应只有自身,实际 %v", chain)
	}
}

func TestFieldsMergeParentProperties(t *testing.T) {
	r := loadSeeds(t)
	f := r.Fields("ACCOUNT_LOGIN")
	if _, ok := f["c_ip"]; !ok {
		t.Error("应继承父事件的 c_ip")
	}
	if _, ok := f["result"]; !ok {
		t.Error("应含自身的增量字段 result")
	}
	if len(f) < 30 {
		t.Errorf("合并后字段数应不少于父事件的 30 个,实际 %d", len(f))
	}
}

func TestSeedFieldsAreClassified(t *testing.T) {
	// 隐私要求:全部字段必须标注敏感级别,不允许依赖缺省
	r := loadSeeds(t)
	missing := 0
	for _, name := range r.Names() {
		m, _ := r.Get(name)
		for _, p := range m.Properties {
			if p.Sensitivity == "" {
				t.Logf("未标注: %s.%s", name, p.Name)
				missing++
			}
		}
	}
	if missing > 0 {
		t.Errorf("有 %d 个字段未标注敏感级别,见 docs/security/privacy.md", missing)
	}
}

func TestSensitiveFieldsDeclareMasking(t *testing.T) {
	// schema 约束的运行时复核:sensitive 必须声明脱敏方式
	r := loadSeeds(t)
	for _, name := range r.Names() {
		m, _ := r.Get(name)
		for _, p := range m.Properties {
			if p.EffectiveSensitivity() == SensSensitive && p.EffectiveMasking() == MaskNone {
				t.Errorf("%s.%s 标注为 sensitive 但未声明脱敏方式", name, p.Name)
			}
		}
	}
}

func TestEventJSONRoundTrip(t *testing.T) {
	raw := `{"name":"ACCOUNT_LOGIN","timestamp":1784944800000,"c_ip":"198.51.100.1","result":"F","status":401}`
	var e Event
	if err := json.Unmarshal([]byte(raw), &e); err != nil {
		t.Fatal(err)
	}
	if e.Name != "ACCOUNT_LOGIN" || e.Timestamp != 1784944800000 {
		t.Errorf("顶层字段解析错误: %+v", e)
	}
	if e.GetString("c_ip") != "198.51.100.1" {
		t.Errorf("业务字段应进 Values,实际 %v", e.Values)
	}

	// 序列化后业务字段应平铺回顶层,与 1.x 线上格式一致
	b, err := json.Marshal(e)
	if err != nil {
		t.Fatal(err)
	}
	var back map[string]any
	if err := json.Unmarshal(b, &back); err != nil {
		t.Fatal(err)
	}
	for _, k := range []string{"name", "timestamp", "c_ip", "result", "status"} {
		if _, ok := back[k]; !ok {
			t.Errorf("序列化后缺少字段 %s", k)
		}
	}
}

func TestTimestampAcceptsStringForm(t *testing.T) {
	// 部分日志源把时间戳写成字符串,应容错
	var e Event
	if err := json.Unmarshal([]byte(`{"name":"X","timestamp":"1784944800000"}`), &e); err != nil {
		t.Fatal(err)
	}
	if e.Timestamp != 1784944800000 {
		t.Errorf("字符串形式的时间戳应被解析,实际 %d", e.Timestamp)
	}
}
