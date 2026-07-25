// Package mask 实现采集端脱敏。
//
// 这是隐私设计的第一道也是最关键的一道关口:脱敏发生在数据**离开客户网络
// 边界之前**,下游所有组件都不再接触原文。即使后续任何一环被攻破,泄露的
// 也只是脱敏后的数据。
//
// 规范见 docs/security/privacy.md。
package mask

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"regexp"
	"strings"
	"sync"

	"github.com/threathunterX/nebula2/apps/collector/internal/event"
)

// Redacted 被丢弃字段的占位值。保留占位而不是删除键,是为了让下游能区分
// 「字段不存在」与「字段存在但已脱敏」—— 前者可能是采集缺陷,后者是预期行为。
const Redacted = "<REDACTED>"

// Rule 单个字段的脱敏规则。
type Rule struct {
	Action  event.Masking `json:"action"`
	Pattern string        `json:"pattern,omitempty"` // action=regex 时的匹配模式
	Replace string        `json:"replace,omitempty"` // action=regex 时的替换串,支持 $1
	Keep    KeepSpec      `json:"keep,omitempty"`    // action=partial 时保留的字符数
	KeyRef  string        `json:"key_ref,omitempty"` // action=hash 时的密钥来源,如 env:NEBULA_HMAC_KEY
}

// KeepSpec partial 脱敏保留的首尾字符数,如手机号 138****8000 为 {3, 4}。
type KeepSpec struct {
	Prefix int `json:"prefix"`
	Suffix int `json:"suffix"`
}

// Config 脱敏配置。
type Config struct {
	// Fields 按字段名指定规则,优先级高于按敏感级别推导。
	Fields map[string]Rule `json:"fields,omitempty"`
	// BySensitivity 按敏感级别的默认规则。未指定时使用 DefaultBySensitivity。
	BySensitivity map[event.Sensitivity]Rule `json:"by_sensitivity,omitempty"`
	// HMACKey HMAC 密钥。生产环境应从环境变量或密钥管理注入,不写在配置文件里。
	HMACKey string `json:"-"`
}

// DefaultBySensitivity 采集端的默认规则。
//
// 这里有一个容易搞混、但必须分清的界限:
//
//	sensitive —— 敏感个人信息(口令、证件号、请求体、Cookie)。
//	             在采集端**就地脱敏,原文不出客户网络边界**。
//	pii       —— 个人信息(IP、账号、设备号)。
//	             采集端**保持原值**,保护发生在存储层(HMAC 或加密列)。
//
// 为什么 pii 不在采集端哈希:风控引擎需要这些字段的原值才能工作 —— IP 要做
// 地理定位与信誉查询,账号与设备号要做跨维度关联。在采集端哈希会直接打断
// 风控能力,而收益为零:数据仍然在你自己的系统内,真正的风险在于**落库**
// 之后的长期留存与访问,那正是存储层保护要解决的问题。
//
// 放宽默认规则需要显式配置,且该配置项本身会被记入审计日志。
// 完整设计见 docs/security/privacy.md。
func DefaultBySensitivity() map[event.Sensitivity]Rule {
	return map[event.Sensitivity]Rule{
		event.SensSensitive: {Action: event.MaskDrop},
		event.SensPII:       {Action: event.MaskNone},
		event.SensInternal:  {Action: event.MaskNone},
		event.SensPublic:    {Action: event.MaskNone},
	}
}

// DefaultFieldRules 对若干高危字段的出厂规则。
//
// 这些字段极易携带口令、令牌、证件号:即便事件模型里没有标注敏感级别,
// 也必须默认脱敏。宁可误脱也不能漏脱。
func DefaultFieldRules() map[string]Rule {
	sensitiveQuery := `(?i)(password|passwd|pwd|token|secret|id_card|idcard|mobile|phone|card_no|cardno|cvv)=([^&]*)`
	return map[string]Rule{
		"cookie": {Action: event.MaskDrop},
		"c_body": {Action: event.MaskRegex, Pattern: sensitiveQuery, Replace: "$1=" + Redacted},
		"s_body": {Action: event.MaskDrop},
		"uri_query": {Action: event.MaskRegex, Pattern: sensitiveQuery, Replace: "$1=" + Redacted},
		"password":  {Action: event.MaskDrop},
	}
}

// Masker 对事件执行脱敏。并发安全。
type Masker struct {
	cfg      Config
	registry *event.Registry
	key      []byte

	mu       sync.RWMutex
	compiled map[string]*regexp.Regexp

	// Stats 脱敏计数,用于可观测性:规则命中率异常往往意味着采集侧出了问题。
	Stats *Stats
}

// Stats 脱敏统计。
type Stats struct {
	mu      sync.Mutex
	Applied map[string]int64 // 字段 -> 脱敏次数
	Errors  int64
}

func newStats() *Stats { return &Stats{Applied: map[string]int64{}} }

func (s *Stats) inc(field string) {
	s.mu.Lock()
	s.Applied[field]++
	s.mu.Unlock()
}

// Snapshot 返回统计快照。
func (s *Stats) Snapshot() map[string]int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make(map[string]int64, len(s.Applied))
	for k, v := range s.Applied {
		out[k] = v
	}
	return out
}

// New 构建脱敏器。registry 可为 nil,此时只按字段名规则脱敏。
func New(cfg Config, registry *event.Registry) (*Masker, error) {
	if cfg.BySensitivity == nil {
		cfg.BySensitivity = DefaultBySensitivity()
	}
	merged := DefaultFieldRules()
	for k, v := range cfg.Fields {
		merged[k] = v // 显式配置覆盖出厂规则
	}
	cfg.Fields = merged

	m := &Masker{
		cfg:      cfg,
		registry: registry,
		key:      []byte(cfg.HMACKey),
		compiled: map[string]*regexp.Regexp{},
		Stats:    newStats(),
	}
	// 预编译全部正则,配置错误在启动时暴露而不是运行时
	for field, r := range cfg.Fields {
		if r.Action != event.MaskRegex {
			continue
		}
		if r.Pattern == "" {
			return nil, fmt.Errorf("字段 %s 的脱敏规则为 regex 但未提供 pattern", field)
		}
		re, err := regexp.Compile(r.Pattern)
		if err != nil {
			return nil, fmt.Errorf("字段 %s 的脱敏正则无法编译: %w", field, err)
		}
		m.compiled[field] = re
	}
	if len(m.key) == 0 {
		// 没有密钥时 hash 规则无法执行 —— 必须显式失败,不能静默降级为明文
		for field, r := range cfg.Fields {
			if r.Action == event.MaskHash {
				return nil, fmt.Errorf("字段 %s 需要 HMAC 脱敏,但未提供密钥(设置 NEBULA_HMAC_KEY)", field)
			}
		}
	}
	return m, nil
}

// Apply 就地脱敏一条事件。
func (m *Masker) Apply(e *event.Event) {
	if e == nil || e.Values == nil {
		return
	}
	var fields map[string]event.Property
	if m.registry != nil {
		fields = m.registry.Fields(e.Name)
	}

	for name, val := range e.Values {
		rule, ok := m.ruleFor(name, fields)
		if !ok || rule.Action == event.MaskNone {
			continue
		}
		s, isStr := val.(string)
		if !isStr {
			// 非字符串字段只支持 drop —— 对数值做哈希或截断没有意义
			if rule.Action == event.MaskDrop {
				e.Values[name] = Redacted
				m.Stats.inc(name)
			}
			continue
		}
		if s == "" {
			continue
		}
		out, changed := m.applyRule(name, rule, s)
		if changed {
			e.Values[name] = out
			m.Stats.inc(name)
		}
	}
}

// ruleFor 解析某字段应用哪条规则:字段名规则优先,其次按事件模型的敏感级别。
func (m *Masker) ruleFor(name string, fields map[string]event.Property) (Rule, bool) {
	if r, ok := m.cfg.Fields[name]; ok {
		return r, true
	}
	if fields != nil {
		if p, ok := fields[name]; ok {
			// 字段自身声明了脱敏方式则优先
			if p.EffectiveMasking() != event.MaskNone {
				return Rule{Action: p.EffectiveMasking(), Pattern: p.MaskingPattern}, true
			}
			if r, ok := m.cfg.BySensitivity[p.EffectiveSensitivity()]; ok {
				return r, true
			}
		}
	}
	return Rule{}, false
}

func (m *Masker) applyRule(field string, r Rule, s string) (string, bool) {
	switch r.Action {
	case event.MaskDrop:
		return Redacted, true

	case event.MaskHash:
		return m.hmac(s), true

	case event.MaskPartial:
		return partial(s, r.Keep), true

	case event.MaskRegex:
		m.mu.RLock()
		re := m.compiled[field]
		m.mu.RUnlock()
		if re == nil {
			return s, false
		}
		repl := r.Replace
		if repl == "" {
			repl = Redacted
		}
		out := re.ReplaceAllString(s, repl)
		return out, out != s

	default:
		return s, false
	}
}

// hmac 用 HMAC-SHA256 派生一个不可逆但可比较的标识。
//
// 保留了可比较性(能判断「这次登录的 IP 与历史是否一致」),去掉了可读性
// (无法从库中还原原值)—— 这正是风控场景需要的性质。
func (m *Masker) hmac(s string) string {
	h := hmac.New(sha256.New, m.key)
	h.Write([]byte(s))
	// 取前 16 字节即 128 位,碰撞概率可忽略,同时把存储开销减半
	return "h:" + hex.EncodeToString(h.Sum(nil)[:16])
}

// partial 保留首尾若干字符,中间以 * 填充,如 13800138000 -> 138****8000。
func partial(s string, keep KeepSpec) string {
	runes := []rune(s)
	n := len(runes)
	p, sfx := keep.Prefix, keep.Suffix
	if p < 0 {
		p = 0
	}
	if sfx < 0 {
		sfx = 0
	}
	if p == 0 && sfx == 0 {
		p, sfx = 3, 4 // 缺省按手机号的习惯
	}
	if p+sfx >= n {
		// 太短则整体掩码,避免「保留」等于「没脱敏」
		return strings.Repeat("*", n)
	}
	return string(runes[:p]) + strings.Repeat("*", n-p-sfx) + string(runes[n-sfx:])
}
