package driver

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"strconv"
	"strings"
	"time"

	"github.com/threathunterX/nebula2/apps/collector/internal/event"
)

// syslog 接入。1.x 的 sniffer 承担过这个角色。
//
// # 支持的格式
//
// RFC 3164(BSD syslog,仍是绝大多数网络设备的默认)与 RFC 5424。两者靠首部形态
// 区分:5424 在优先级之后紧跟版本号 `1`,3164 紧跟的是月份缩写。
//
// # 消息体怎么变成事件
//
// 优先按 JSON 解析 —— 现代设备与 Zeek、Suricata 这类工具都能配成 JSON 输出,
// 那时字段直接可用。解析不了就退化成一条带 `message` 字段的事件,并保留 syslog
// 首部里的 facility / severity / hostname / tag。
//
// **不做正则提取。** 各家设备的文本格式互不相同,内置一套正则等于承诺维护它们;
// 而写错一个正则的表现是「某类日志的某个字段永远为空」,不会报错。需要结构化的
// 场景,正确做法是把设备配成 JSON 输出,或在前面放一层 syslog 转换。
//
// # UDP 的取舍
//
// syslog 的默认传输是 UDP,它会丢包且不保证顺序。这对风控是有影响的:丢的那条
// 可能正是要命中策略的那条。TCP 更可靠,但很多网络设备只支持 UDP。
// 两者都实现,由部署方选;README 里写明 UDP 会丢。
type syslogDriver struct {
	network          string // "udp" | "tcp"
	addr             string
	defaultEventName string
}

// NewSyslog 监听 syslog。network 取 udp 或 tcp。
func NewSyslog(network, addr, defaultEventName string) Driver {
	return &syslogDriver{network: network, addr: addr, defaultEventName: defaultEventName}
}

func (d *syslogDriver) Name() string { return "syslog/" + d.network }

func (d *syslogDriver) Run(ctx context.Context, out chan<- *event.Event) error {
	switch d.network {
	case "udp":
		return d.runUDP(ctx, out)
	case "tcp":
		return d.runTCP(ctx, out)
	default:
		return fmt.Errorf("syslog 不支持的传输 %q(取 udp 或 tcp)", d.network)
	}
}

func (d *syslogDriver) runUDP(ctx context.Context, out chan<- *event.Event) error {
	addr, err := net.ResolveUDPAddr("udp", d.addr)
	if err != nil {
		return err
	}
	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		return err
	}
	defer conn.Close()
	go func() { <-ctx.Done(); _ = conn.Close() }()

	// RFC 5426 建议接收方至少支持 2048 字节;实际部署里更长的并不少见,
	// 取 64 KiB —— UDP 数据报本身的上限就在这个量级,再大也收不到
	buf := make([]byte, 64*1024)
	for {
		n, _, err := conn.ReadFromUDP(buf)
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		e := parseSyslog(string(buf[:n]), d.defaultEventName)
		if e == nil {
			continue
		}
		select {
		case out <- e:
		case <-ctx.Done():
			return nil
		}
	}
}

func (d *syslogDriver) runTCP(ctx context.Context, out chan<- *event.Event) error {
	ln, err := net.Listen("tcp", d.addr)
	if err != nil {
		return err
	}
	defer ln.Close()
	go func() { <-ctx.Done(); _ = ln.Close() }()

	for {
		conn, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		// 每个连接一个 goroutine。syslog 的 TCP 连接是长连接,一个慢发送方
		// 不应该挡住其它设备 —— 串行处理时它会。
		go d.serveConn(ctx, conn, out)
	}
}

func (d *syslogDriver) serveConn(ctx context.Context, conn net.Conn, out chan<- *event.Event) {
	defer conn.Close()
	sc := bufio.NewScanner(conn)
	sc.Buffer(make([]byte, 0, 8*1024), 1024*1024)
	for sc.Scan() {
		line := strings.TrimRight(sc.Text(), "\r")
		if line == "" {
			continue
		}
		e := parseSyslog(line, d.defaultEventName)
		if e == nil {
			continue
		}
		select {
		case out <- e:
		case <-ctx.Done():
			return
		}
	}
}

// parseSyslog 把一行 syslog 转成事件。无法解析时返回 nil。
func parseSyslog(raw, defaultEventName string) *event.Event {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil
	}

	values := map[string]any{}
	rest := raw

	// 优先级 <PRI>:facility*8 + severity
	if strings.HasPrefix(rest, "<") {
		if end := strings.Index(rest, ">"); end > 1 && end <= 4 {
			if pri, err := strconv.Atoi(rest[1:end]); err == nil && pri >= 0 && pri < 192 {
				values["syslog_facility"] = pri / 8
				values["syslog_severity"] = pri % 8
				rest = rest[end+1:]
			}
		}
	}

	// RFC 5424 在 PRI 之后紧跟版本号与空格
	if strings.HasPrefix(rest, "1 ") {
		rest = parse5424(rest[2:], values)
	} else {
		rest = parse3164(rest, values)
	}

	e := &event.Event{Values: values}

	// 消息体是 JSON 时展开它 —— 设备能配成 JSON 输出时字段直接可用,
	// 这是唯一不用猜格式就能拿到结构化字段的路径
	if body := strings.TrimSpace(rest); strings.HasPrefix(body, "{") {
		var fields map[string]any
		if err := json.Unmarshal([]byte(body), &fields); err == nil {
			for k, v := range fields {
				// syslog 首部解析出的字段优先级更低:消息体是设备自己写的,
				// 更可能是使用方真正关心的那一份
				values[k] = v
			}
			if n, ok := fields["name"].(string); ok && n != "" {
				e.Name = n
			}
			if ts, ok := fields["timestamp"].(float64); ok && ts > 0 {
				e.Timestamp = int64(ts)
			}
		} else {
			values["message"] = body
		}
	} else if body != "" {
		values["message"] = body
	}

	normalize(e, defaultEventName)
	return e
}

// parse3164 解析 BSD syslog 首部,返回剩余的消息体。
//
// 形态:`Jan  2 15:04:05 hostname tag[pid]: message`
// 解析不出来时**原样把整行当消息体**,而不是丢弃 —— 格式不合规范的设备很常见,
// 丢掉意味着那台设备的日志静默消失。
func parse3164(rest string, values map[string]any) string {
	const layout = "Jan _2 15:04:05"
	if len(rest) < len(layout) {
		return rest
	}
	stamp := rest[:len(layout)]
	if t, err := time.Parse(layout, stamp); err == nil {
		// RFC 3164 的时间戳不带年份。补当前年份 —— 跨年那几小时会错一年,
		// 这是格式本身的缺陷,没有正确解法;真正在意时间精度的场景应当用 5424。
		values["syslog_timestamp"] = time.Date(time.Now().Year(), t.Month(), t.Day(),
			t.Hour(), t.Minute(), t.Second(), 0, time.Local).UnixMilli()
		rest = strings.TrimSpace(rest[len(layout):])
	}

	// hostname
	if i := strings.IndexByte(rest, ' '); i > 0 {
		values["syslog_hostname"] = rest[:i]
		rest = rest[i+1:]
	}
	// tag[pid]:
	if i := strings.IndexByte(rest, ':'); i > 0 && i < 64 {
		tag := rest[:i]
		if j := strings.IndexByte(tag, '['); j > 0 {
			if k := strings.IndexByte(tag[j:], ']'); k > 0 {
				values["syslog_pid"] = tag[j+1 : j+k]
			}
			tag = tag[:j]
		}
		values["syslog_tag"] = tag
		rest = strings.TrimSpace(rest[i+1:])
	}
	return rest
}

// parse5424 解析 RFC 5424 首部,返回剩余的消息体。
//
// 形态:`TIMESTAMP HOSTNAME APP-NAME PROCID MSGID STRUCTURED-DATA MSG`
// 各字段以空格分隔,缺失用 `-` 表示。
func parse5424(rest string, values map[string]any) string {
	fields := []string{"syslog_timestamp_raw", "syslog_hostname", "syslog_app",
		"syslog_pid", "syslog_msgid"}
	for _, name := range fields {
		i := strings.IndexByte(rest, ' ')
		if i < 0 {
			return ""
		}
		v := rest[:i]
		rest = rest[i+1:]
		if v == "-" {
			continue
		}
		if name == "syslog_timestamp_raw" {
			// 5424 用 RFC 3339,带年份与时区 —— 不需要 3164 那样猜年份
			if t, err := time.Parse(time.RFC3339, v); err == nil {
				values["syslog_timestamp"] = t.UnixMilli()
				continue
			}
		}
		values[name] = v
	}

	// STRUCTURED-DATA:`-` 或一串 `[id k="v" ...]`。这里只跳过它,不解析 ——
	// 它的转义规则(`]` `"` `\` 都要转义)自成一套,而实际使用中绝大多数设备
	// 发的是 `-`。需要它的场景应当把内容放进 JSON 消息体。
	rest = strings.TrimSpace(rest)
	if strings.HasPrefix(rest, "-") {
		rest = strings.TrimSpace(rest[1:])
	} else if strings.HasPrefix(rest, "[") {
		depth, esc, end := 0, false, -1
		for i, c := range rest {
			switch {
			case esc:
				esc = false
			case c == '\\':
				esc = true
			case c == '[':
				depth++
			case c == ']':
				depth--
				if depth == 0 {
					end = i
				}
			}
			if depth == 0 && end == i && (i+1 >= len(rest) || rest[i+1] != '[') {
				break
			}
		}
		if end >= 0 {
			values["syslog_structured_data"] = rest[:end+1]
			rest = strings.TrimSpace(rest[end+1:])
		}
	}
	// 5424 允许消息体带 UTF-8 BOM,去掉它 —— 留着会让 JSON 解析失败,
	// 而表现是「明明发的是 JSON 却被当成纯文本」
	return strings.TrimPrefix(rest, "\ufeff")
}
