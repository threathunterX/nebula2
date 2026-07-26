package metrics

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// 渲染出来的东西必须能被 Prometheus 解析。这里不引入解析库(为它多一个依赖不值),
// 改为断言格式契约本身:每个指标要有 HELP 与 TYPE,值行要能被拆成「名字 值」。
func render(t *testing.T, s Snapshot) string {
	t.Helper()
	rec := httptest.NewRecorder()
	Handler(func() Snapshot { return s }, time.Now().Add(-90*time.Second))(
		rec, httptest.NewRequest(http.MethodGet, "/metrics", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("状态码 %d", rec.Code)
	}
	return rec.Body.String()
}

func TestEveryMetricHasHelpAndType(t *testing.T) {
	body := render(t, Snapshot{Received: 5, MaskApplied: map[string]int64{"password": 3}})
	for _, line := range strings.Split(body, "\n") {
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		name := strings.SplitN(line, "{", 2)[0]
		name = strings.SplitN(name, " ", 2)[0]
		// 缺 TYPE 时 Prometheus 会按 untyped 处理,rate() 之类的函数就用不了 ——
		// 而这不会报错,只是查询结果为空
		if !strings.Contains(body, "# TYPE "+name+" ") {
			t.Errorf("指标 %s 缺少 TYPE 声明", name)
		}
		if !strings.Contains(body, "# HELP "+name+" ") {
			t.Errorf("指标 %s 缺少 HELP 说明", name)
		}
	}
}

func TestCountersReflectSnapshot(t *testing.T) {
	body := render(t, Snapshot{Received: 13, Emitted: 12, Dropped: 1, WriteErrs: 2, MaskErrors: 3})
	for _, want := range []string{
		"nebula_collector_events_received_total 13",
		"nebula_collector_events_emitted_total 12",
		"nebula_collector_events_dropped_total 1",
		"nebula_collector_write_errors_total 2",
		"nebula_collector_mask_errors_total 3",
	} {
		if !strings.Contains(body, want) {
			t.Errorf("缺少 %q\n%s", want, body)
		}
	}
}

func TestMaskAppliedIsSortedAndQuoted(t *testing.T) {
	body := render(t, Snapshot{MaskApplied: map[string]int64{"uid": 1, "cookie": 2, "password": 3}})
	// 顺序稳定,否则每次抓取的输出都不同,diff 没法用
	iCookie := strings.Index(body, `field="cookie"`)
	iPassword := strings.Index(body, `field="password"`)
	iUID := strings.Index(body, `field="uid"`)
	if !(iCookie < iPassword && iPassword < iUID) {
		t.Errorf("字段未按字典序输出:\n%s", body)
	}
}

func TestUptimeIsGauge(t *testing.T) {
	body := render(t, Snapshot{})
	if !strings.Contains(body, "# TYPE nebula_collector_uptime_seconds gauge") {
		t.Error("uptime 应当是 gauge 而不是 counter —— counter 只增不减,而进程重启后它会归零")
	}
	if !strings.Contains(body, "nebula_collector_uptime_seconds 90") {
		t.Errorf("uptime 计算不对:\n%s", body)
	}
}

func TestEmptySnapshotStillRenders(t *testing.T) {
	// 没有任何数据时也要输出计数为 0 的行,而不是省略 —— 省略会让监控端
	// 分不清「值是 0」和「这个实例挂了」
	body := render(t, Snapshot{})
	if !strings.Contains(body, "nebula_collector_events_received_total 0") {
		t.Errorf("空快照应输出 0 而不是省略:\n%s", body)
	}
}
