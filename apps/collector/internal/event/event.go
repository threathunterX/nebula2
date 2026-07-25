// Package event 定义业务事件模型与单继承链。
//
// 事件模型的权威定义在 packages/domain-schema/event-model.schema.json,
// 出厂资产在 seeds/events/。本包在运行时加载这些定义,用于:
//   - 校验事件字段
//   - 解析继承链(ACCOUNT_LOGIN 的父事件是 HTTP_DYNAMIC)
//   - 驱动按字段敏感级别执行脱敏
package event

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// Sensitivity 字段敏感级别,取值与 domain-schema 的 enums.json 一致。
type Sensitivity string

const (
	SensPublic    Sensitivity = "public"
	SensInternal  Sensitivity = "internal"
	SensPII       Sensitivity = "pii"
	SensSensitive Sensitivity = "sensitive"
)

// Masking 采集端脱敏方式。
type Masking string

const (
	MaskNone    Masking = "none"
	MaskDrop    Masking = "drop"
	MaskHash    Masking = "hash"
	MaskPartial Masking = "partial"
	MaskRegex   Masking = "regex"
)

// Property 事件字段定义。
type Property struct {
	Name           string      `json:"name"`
	Type           string      `json:"type"`
	Subtype        string      `json:"subtype,omitempty"`
	VisibleName    string      `json:"visible_name"`
	Remark         string      `json:"remark,omitempty"`
	Sensitivity    Sensitivity `json:"sensitivity,omitempty"`
	Masking        Masking     `json:"masking,omitempty"`
	MaskingPattern string      `json:"masking_pattern,omitempty"`
}

// EffectiveSensitivity 未声明时按 internal 处理(与 schema 的 default 一致)。
func (p Property) EffectiveSensitivity() Sensitivity {
	if p.Sensitivity == "" {
		return SensInternal
	}
	return p.Sensitivity
}

// EffectiveMasking 未声明时按 none 处理。
func (p Property) EffectiveMasking() Masking {
	if p.Masking == "" {
		return MaskNone
	}
	return p.Masking
}

type sourceRef struct {
	App  string `json:"app"`
	Name string `json:"name"`
}

// Model 一个事件类型的定义。
type Model struct {
	App         string      `json:"app"`
	Name        string      `json:"name"`
	VisibleName string      `json:"visible_name"`
	Remark      string      `json:"remark,omitempty"`
	Type        string      `json:"type"`
	Version     string      `json:"version"`
	Source      []sourceRef `json:"source"`
	Properties  []Property  `json:"properties"`
}

// Registry 全部事件模型,支持继承链解析与字段合并。
type Registry struct {
	models map[string]*Model
	fields map[string]map[string]Property // 合并父事件后的完整字段集,惰性构建
}

// NewRegistry 从内存中的模型列表构建。
func NewRegistry(models []*Model) *Registry {
	r := &Registry{
		models: make(map[string]*Model, len(models)),
		fields: make(map[string]map[string]Property, len(models)),
	}
	for _, m := range models {
		r.models[m.Name] = m
	}
	return r
}

// LoadRegistry 从 seeds/events 目录加载。
func LoadRegistry(dir string) (*Registry, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, fmt.Errorf("读取事件模型目录 %s: %w", dir, err)
	}
	var models []*Model
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") || e.Name() == "index.json" {
			continue
		}
		b, err := os.ReadFile(filepath.Join(dir, e.Name()))
		if err != nil {
			return nil, fmt.Errorf("读取 %s: %w", e.Name(), err)
		}
		var m Model
		if err := json.Unmarshal(b, &m); err != nil {
			return nil, fmt.Errorf("解析 %s: %w", e.Name(), err)
		}
		models = append(models, &m)
	}
	if len(models) == 0 {
		return nil, fmt.Errorf("目录 %s 中没有事件模型", dir)
	}
	return NewRegistry(models), nil
}

// Names 全部事件类型名,已排序。
func (r *Registry) Names() []string {
	out := make([]string, 0, len(r.models))
	for n := range r.models {
		out = append(out, n)
	}
	sort.Strings(out)
	return out
}

// Get 取事件定义。
func (r *Registry) Get(name string) (*Model, bool) {
	m, ok := r.models[name]
	return m, ok
}

// Chain 事件自身 + 全部祖先,由近及远。
//
// 根事件(HTTP_DYNAMIC)的 source 按 1.x 惯例指向自身,到此为止 —— 这不是环。
func (r *Registry) Chain(name string) []string {
	var out []string
	seen := map[string]bool{}
	cur := name
	for cur != "" && !seen[cur] {
		seen[cur] = true
		out = append(out, cur)
		m, ok := r.models[cur]
		if !ok || len(m.Source) == 0 {
			break
		}
		parent := m.Source[0].Name
		if parent == cur {
			break // 根事件
		}
		cur = parent
	}
	return out
}

// IsA 事件 name 是否可视为 target(自身或其祖先)。
//
// 这条语义至关重要:定义在父事件上的变量与策略必须能被子事件触发。
// 参见 docs/reference/operators.md。
func (r *Registry) IsA(name, target string) bool {
	for _, n := range r.Chain(name) {
		if n == target {
			return true
		}
	}
	return false
}

// Fields 合并父事件后的完整字段集。存储时只保存增量字段,运行时需要合并。
func (r *Registry) Fields(name string) map[string]Property {
	if f, ok := r.fields[name]; ok {
		return f
	}
	out := map[string]Property{}
	chain := r.Chain(name)
	// 由远及近合并,子事件的同名字段覆盖父事件
	for i := len(chain) - 1; i >= 0; i-- {
		m, ok := r.models[chain[i]]
		if !ok {
			continue
		}
		for _, p := range m.Properties {
			out[p.Name] = p
		}
	}
	r.fields[name] = out
	return out
}

// Event 一条标准化的业务事件。
type Event struct {
	Name      string         `json:"name"`
	Timestamp int64          `json:"timestamp"` // 毫秒
	ID        string         `json:"id,omitempty"`
	PID       string         `json:"pid,omitempty"`
	Values    map[string]any `json:"-"`
}

// MarshalJSON 把 Values 平铺到顶层,与 1.x 的线上格式保持一致。
func (e Event) MarshalJSON() ([]byte, error) {
	out := make(map[string]any, len(e.Values)+4)
	for k, v := range e.Values {
		out[k] = v
	}
	out["name"] = e.Name
	out["timestamp"] = e.Timestamp
	if e.ID != "" {
		out["id"] = e.ID
	}
	if e.PID != "" {
		out["pid"] = e.PID
	}
	return json.Marshal(out)
}

// UnmarshalJSON 顶层字段之外的一律进 Values。
func (e *Event) UnmarshalJSON(b []byte) error {
	var raw map[string]any
	if err := json.Unmarshal(b, &raw); err != nil {
		return err
	}
	e.Values = make(map[string]any, len(raw))
	for k, v := range raw {
		switch k {
		case "name":
			if s, ok := v.(string); ok {
				e.Name = s
			}
		case "timestamp":
			switch t := v.(type) {
			case float64:
				e.Timestamp = int64(t)
			case string:
				// 容错:部分日志源把时间戳写成字符串
				var n int64
				if _, err := fmt.Sscanf(t, "%d", &n); err == nil {
					e.Timestamp = n
				}
			}
		case "id":
			if s, ok := v.(string); ok {
				e.ID = s
			}
		case "pid":
			if s, ok := v.(string); ok {
				e.PID = s
			}
		default:
			e.Values[k] = v
		}
	}
	return nil
}

// GetString 取字符串字段,不存在或类型不符返回空串。
func (e *Event) GetString(field string) string {
	if v, ok := e.Values[field]; ok {
		if s, ok := v.(string); ok {
			return s
		}
	}
	return ""
}
