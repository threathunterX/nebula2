package driver

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"testing"
	"time"

	"github.com/threathunterX/nebula2/apps/collector/internal/event"
)

// syslog 解析属于「写错了不报错」的那类:某个字段解析不出来只会让它永远为空,
// 上游看到的是「这类日志没有这个字段」,不会有任何告警。所以逐格式断言。

func TestParse3164(t *testing.T) {
	e := parseSyslog(`<34>Oct 11 22:14:15 mymachine su[1234]: 'su root' failed for lonvick`, "SYSLOG")
	if e == nil {
		t.Fatal("解析返回 nil")
	}
	// 34 = facility 4(auth)* 8 + severity 2(critical)
	if got := e.Values["syslog_facility"]; got != 4 {
		t.Errorf("facility = %v,要 4", got)
	}
	if got := e.Values["syslog_severity"]; got != 2 {
		t.Errorf("severity = %v,要 2", got)
	}
	if got := e.Values["syslog_hostname"]; got != "mymachine" {
		t.Errorf("hostname = %v,要 mymachine", got)
	}
	if got := e.Values["syslog_tag"]; got != "su" {
		t.Errorf("tag = %v,要 su", got)
	}
	if got := e.Values["syslog_pid"]; got != "1234" {
		t.Errorf("pid = %v,要 1234", got)
	}
	if got := e.Values["message"]; got != "'su root' failed for lonvick" {
		t.Errorf("message = %q", got)
	}
	if e.Name != "SYSLOG" {
		t.Errorf("事件名 = %q,要退回缺省值", e.Name)
	}
}

func TestParse5424(t *testing.T) {
	line := `<165>1 2026-07-26T22:14:15.003Z mymachine.example.com evntslog 8710 ID47 - message here`
	e := parseSyslog(line, "SYSLOG")
	if e == nil {
		t.Fatal("解析返回 nil")
	}
	if got := e.Values["syslog_hostname"]; got != "mymachine.example.com" {
		t.Errorf("hostname = %v", got)
	}
	if got := e.Values["syslog_app"]; got != "evntslog" {
		t.Errorf("app = %v", got)
	}
	if got := e.Values["syslog_msgid"]; got != "ID47" {
		t.Errorf("msgid = %v", got)
	}
	if got := e.Values["message"]; got != "message here" {
		t.Errorf("message = %q", got)
	}
	// 5424 的时间戳带年份与时区,不该像 3164 那样靠猜
	want := time.Date(2026, 7, 26, 22, 14, 15, 3e6, time.UTC).UnixMilli()
	if got := e.Values["syslog_timestamp"]; got != want {
		t.Errorf("timestamp = %v,要 %v", got, want)
	}
}

func TestParse5424StructuredDataSkipped(t *testing.T) {
	line := `<165>1 2026-07-26T22:14:15Z host app - - ` +
		`[exampleSDID@32473 iut="3" eventID="1011"] {"name":"ACCOUNT_LOGIN","uid":"u1"}`
	e := parseSyslog(line, "SYSLOG")
	if e == nil {
		t.Fatal("解析返回 nil")
	}
	// structured-data 要被完整跳过,否则它会被当成消息体的一部分,
	// 导致后面真正的 JSON 解析失败 —— 表现是「发的是 JSON 却成了纯文本」
	if e.Name != "ACCOUNT_LOGIN" {
		t.Errorf("事件名 = %q,要 ACCOUNT_LOGIN(structured-data 没跳干净)", e.Name)
	}
	if got := e.Values["uid"]; got != "u1" {
		t.Errorf("uid = %v", got)
	}
	if got, ok := e.Values["syslog_structured_data"]; !ok {
		t.Error("structured-data 应当保留下来而不是丢掉")
	} else if got != `[exampleSDID@32473 iut="3" eventID="1011"]` {
		t.Errorf("structured_data = %v", got)
	}
}

func TestParseJSONBody(t *testing.T) {
	line := `<134>Oct 11 22:14:15 gw zeek: {"name":"HTTP_DYNAMIC","c_ip":"198.51.100.7","status":200}`
	e := parseSyslog(line, "SYSLOG")
	if e == nil {
		t.Fatal("解析返回 nil")
	}
	if e.Name != "HTTP_DYNAMIC" {
		t.Errorf("事件名 = %q", e.Name)
	}
	if got := e.Values["c_ip"]; got != "198.51.100.7" {
		t.Errorf("c_ip = %v", got)
	}
	// syslog 首部的字段也要留着 —— 它回答「这条是哪台设备发的」
	if got := e.Values["syslog_hostname"]; got != "gw" {
		t.Errorf("hostname = %v", got)
	}
	if _, ok := e.Values["message"]; ok {
		t.Error("JSON 体解析成功时不该再塞一个 message 字段")
	}
}

func TestParseMalformedNotDropped(t *testing.T) {
	// 不合规范的设备很常见。丢掉意味着那台设备的日志静默消失 ——
	// 而「静默消失」正是这个项目反复要避免的失败方式。
	for _, raw := range []string{
		"完全不符合 syslog 格式的一行",
		"<999>这个优先级越界",
		"<13>没有时间戳直接是正文",
	} {
		e := parseSyslog(raw, "SYSLOG")
		if e == nil {
			t.Errorf("%q 被丢弃了", raw)
			continue
		}
		if e.Values["message"] == nil || e.Values["message"] == "" {
			t.Errorf("%q 的正文没有保留:%v", raw, e.Values)
		}
	}
}

func TestParseEmptyReturnsNil(t *testing.T) {
	for _, raw := range []string{"", "   ", "\n"} {
		if e := parseSyslog(raw, "SYSLOG"); e != nil {
			t.Errorf("%q 应当返回 nil,得到 %v", raw, e.Values)
		}
	}
}

func TestParseFillsTimestamp(t *testing.T) {
	// 事件时间戳必须有值 —— 下游按事件时间分窗,0 会让这条事件落到 1970 年
	e := parseSyslog("<13>没有任何时间信息", "SYSLOG")
	if e.Timestamp <= 0 {
		t.Errorf("timestamp = %d,要填成当前时间", e.Timestamp)
	}
}

func TestSyslogUDPEndToEnd(t *testing.T) {
	d := NewSyslog("udp", "127.0.0.1:0", "SYSLOG")
	// 端口 0 让内核选,但这样测试拿不到实际端口。改用显式监听后取地址。
	conn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := conn.LocalAddr().String()
	_ = conn.Close()

	d = NewSyslog("udp", addr, "SYSLOG")
	out := make(chan *event.Event, 4)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx, out) }()

	// 等监听就绪。没有就绪信号时只能重试发送 —— UDP 不会因为对端没起来而报错,
	// 所以这里用「收到才算数」而不是「发出去就算数」
	var got *event.Event
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) && got == nil {
		c, err := net.Dial("udp", addr)
		if err != nil {
			t.Fatal(err)
		}
		_, _ = fmt.Fprint(c, `<134>Oct 11 22:14:15 gw app: {"name":"ACCOUNT_LOGIN","uid":"u9"}`)
		_ = c.Close()
		select {
		case got = <-out:
		case <-time.After(100 * time.Millisecond):
		}
	}
	if got == nil {
		t.Fatal("3 秒内没收到事件")
	}
	if got.Name != "ACCOUNT_LOGIN" || got.Values["uid"] != "u9" {
		t.Errorf("收到的事件不对:%v", got.Values)
	}
}

func TestSyslogTCPEndToEnd(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := ln.Addr().String()
	_ = ln.Close()

	d := NewSyslog("tcp", addr, "SYSLOG")
	out := make(chan *event.Event, 4)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = d.Run(ctx, out) }()

	var c net.Conn
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if c, err = net.Dial("tcp", addr); err == nil {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if c == nil {
		t.Fatal("连不上 TCP 监听")
	}
	defer c.Close()
	// 一个连接上发两条 —— TCP 的 syslog 是长连接,按行切分必须正确
	_, _ = fmt.Fprint(c, "<13>1 2026-07-26T00:00:00Z h a - - - {\"name\":\"A\"}\n"+
		"<13>1 2026-07-26T00:00:01Z h a - - - {\"name\":\"B\"}\n")

	names := []string{}
	for i := 0; i < 2; i++ {
		select {
		case e := <-out:
			names = append(names, e.Name)
		case <-time.After(3 * time.Second):
			t.Fatalf("只收到 %d 条:%v", len(names), names)
		}
	}
	if names[0] != "A" || names[1] != "B" {
		t.Errorf("收到 %v,要 [A B] —— 长连接上的多条消息要逐行切开", names)
	}
}

func TestSyslogRejectsUnknownNetwork(t *testing.T) {
	d := NewSyslog("sctp", "127.0.0.1:0", "SYSLOG")
	err := d.Run(context.Background(), make(chan *event.Event, 1))
	if err == nil {
		t.Fatal("不支持的传输应当报错而不是静默什么都不做")
	}
}

func TestSyslogJSONBodyOverridesHeaderFields(t *testing.T) {
	// 消息体里的字段与首部同名时,以消息体为准 —— 那是设备自己写的,
	// 更可能是使用方真正关心的那一份
	line := `<134>Oct 11 22:14:15 gw app: {"syslog_hostname":"real-host"}`
	e := parseSyslog(line, "SYSLOG")
	if got := e.Values["syslog_hostname"]; got != "real-host" {
		t.Errorf("hostname = %v,要以消息体为准", got)
	}
}

func TestSyslogEventMarshals(t *testing.T) {
	// 解析出来的事件要能被下游序列化 —— Values 里混入不可序列化的东西会在
	// 写出时才炸,而那时已经离解析很远了
	e := parseSyslog(`<134>Oct 11 22:14:15 gw app: {"a":1}`, "SYSLOG")
	if _, err := json.Marshal(e); err != nil {
		t.Fatalf("序列化失败: %v", err)
	}
}
