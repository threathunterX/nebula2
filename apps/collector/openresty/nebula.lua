-- 星云 OpenResty 埋点。
--
-- 在网关侧把 HTTP 请求转成 HTTP_DYNAMIC 事件,批量投给采集器。1.x 的 sniffer
-- 承担过这个角色。
--
-- # 装在哪一层
--
-- 用 `log_by_lua_block` —— 响应发出**之后**才执行,因此:
--   * 拿得到状态码与响应大小(access 阶段还没有)
--   * 埋点本身的耗时不计入用户可见的延迟
--   * 埋点失败不会影响请求 —— 这一条最重要:**风控埋点永远不该让业务请求失败**
--
-- # 敏感字段
--
-- 事件模型把 `cookie` / `uri_query` / `c_body` / `s_body` 标为 `sensitive`,
-- 采集器会在入口处脱敏。但**本模块根本不采集它们** —— 让数据先出网关再在下游擦掉,
-- 中间每一跳(日志、代理、抓包)都可能留下副本。少采一个字段,比多做一次脱敏可靠。
--
-- `uid` / `did` 由业务系统通过请求头显式提供(见 `M.setup` 的 uid_header),
-- 网关不去猜。
--
-- # 投递失败怎么办
--
-- 批量缓冲 + 定时 flush。缓冲满时**丢最旧的一批并计数**,而不是阻塞请求或无限增长 ——
-- 网关内存是硬约束,而阻塞请求会把风控的故障变成业务的故障。
-- 丢弃计数通过 `M.stats()` 暴露,配合 `/nebula-status` 端点可被监控抓走。

local cjson = require "cjson.safe"

local M = {}

local conf = {
    endpoint = nil,          -- 采集器的 /v2/events 地址
    token = nil,             -- NEBULA_COLLECTOR_TOKEN
    batch_size = 100,        -- 攒够这么多条就发
    flush_interval = 1,      -- 秒,到点就发(即使没攒够)
    max_buffer = 10000,      -- 缓冲上限,超出丢最旧的
    timeout = 2000,          -- 毫秒
    -- endpoint 解析出来的三段,setup 时填好,避免每次发送都解析一遍 URL
    _host = nil, _port = nil, _path = nil,
    uid_header = "X-User-Id",
    did_header = "X-Device-Id",
    sid_header = "X-Session-Id",
    platform_header = "X-Platform",
}

-- 缓冲与计数放在共享内存里 —— OpenResty 每个 worker 是独立的 Lua VM,
-- 用模块级变量的话统计数字会按 worker 分裂,而运维看到的应当是整个网关的。
local BUF = "nebula_buffer"
local STATS = "nebula_stats"

local function incr(key, n)
    local dict = ngx.shared[STATS]
    if dict then
        dict:incr(key, n or 1, 0)
    end
end

--- 取客户端真实 IP。
--
-- 优先 X-Forwarded-For 的**第一跳**:网关前面通常还有 LB,`remote_addr` 是 LB 的地址。
-- 但 XFF 是客户端可伪造的 —— 只有在网关确实位于可信代理之后时才该用它。
-- `trust_xff` 默认关,要显式打开:默认信任会让攻击者随便声明自己的 IP,
-- 而 IP 是风控最核心的主体维度之一。
local function client_ip()
    if conf.trust_xff then
        local xff = ngx.var.http_x_forwarded_for
        if xff then
            local first = xff:match("^%s*([^,%s]+)")
            if first then
                return first
            end
        end
    end
    return ngx.var.remote_addr
end

--- 把当前请求转成一条事件。
local function build_event()
    local h = ngx.req.get_headers()
    return {
        name = "HTTP_DYNAMIC",
        -- 毫秒。ngx.now() 返回带小数的秒,精度取决于 nginx 的时间缓存
        timestamp = math.floor(ngx.now() * 1000),
        c_ip = client_ip(),
        c_port = tonumber(ngx.var.remote_port),
        s_ip = ngx.var.server_addr,
        s_port = tonumber(ngx.var.server_port),
        host = ngx.var.host,
        uri_stem = ngx.var.uri,
        page = ngx.var.uri,
        method = ngx.req.get_method(),
        status = tonumber(ngx.var.status),
        referer = h["referer"],
        useragent = h["user-agent"],
        s_bytes = tonumber(ngx.var.bytes_sent),
        c_bytes = tonumber(ngx.var.request_length),
        c_type = h["content-type"],
        s_type = ngx.var.sent_http_content_type,
        -- 业务系统显式提供的身份。网关不去猜 —— 猜错的代价是把两个人的行为算成一个人。
        uid = h[conf.uid_header:lower()],
        did = h[conf.did_header:lower()],
        sid = h[conf.sid_header:lower()],
        platform = h[conf.platform_header:lower()],
        -- cookie / uri_query / c_body / s_body 是 sensitive,**故意不采集**。
        -- 见文件头的说明。
    }
end

--- 拆 endpoint。只认 http://host[:port]/path —— 采集器不做 TLS 终结,
--- 需要 TLS 时在它前面放代理(与控制面同一条规则)。
local function parse_endpoint(url)
    local host, port, path = url:match("^http://([^:/]+):(%d+)(/.*)$")
    if not host then
        host, path = url:match("^http://([^:/]+)(/.*)$")
        port = 80
    end
    if not host then
        return nil, nil, nil, "endpoint 应形如 http://host[:port]/v2/events,实际: " .. url
    end
    return host, tonumber(port), path
end

--- 把一批事件发给采集器。返回 ok, err。
---
--- **用 ngx.socket.tcp 手写 HTTP,不依赖 lua-resty-http。** 那个库
--- **不是 OpenResty 自带的**(镜像里 lualib/resty 下没有 http.lua,要 opm 单独装)——
--- 这是实跑时撞出来的,初稿的注释写着「自带」是错的。
---
--- 让部署方为一个埋点脚本再装一个 Lua 库是实打实的负担,而这里只需要一个最简单的
--- POST:固定方法、固定内容类型、不需要重定向 / chunked / keep-alive 复用。
--- 几十行标准库就够,与采集器不引入 client_golang 是同一个判断。
---
--- 只在 timer 上下文里调用 —— cosocket 在 log_by_lua 阶段不可用。
local function send(batch)
    local body, err = cjson.encode(batch)
    if not body then
        return false, "序列化失败: " .. tostring(err)
    end

    local sock = ngx.socket.tcp()
    sock:settimeout(conf.timeout)
    local ok, cerr = sock:connect(conf._host, conf._port)
    if not ok then
        return false, "连接 " .. tostring(conf._host) .. ":" .. tostring(conf._port)
            .. " 失败: " .. tostring(cerr)
    end

    local req = {
        "POST ", conf._path, " HTTP/1.1\r\n",
        "Host: ", conf._host, ":", conf._port, "\r\n",
        "Content-Type: application/json\r\n",
        "Content-Length: ", #body, "\r\n",
        -- 不复用连接:埋点是低频批量,连接复用省下的往返远不如它带来的状态复杂度值钱
        "Connection: close\r\n",
    }
    if conf.token then
        req[#req + 1] = "X-Nebula-Token: " .. conf.token .. "\r\n"
    end
    req[#req + 1] = "\r\n"
    req[#req + 1] = body

    local _, serr = sock:send(table.concat(req))
    if serr then
        sock:close()
        return false, "发送失败: " .. serr
    end

    local line, rerr = sock:receive("*l")
    if not line then
        sock:close()
        return false, "读响应失败: " .. tostring(rerr)
    end
    sock:close()

    local status = tonumber(line:match("^HTTP/%d%.%d (%d+)"))
    if not status then
        return false, "响应行无法解析: " .. line
    end
    -- 采集器接受后返回 202
    if status < 200 or status >= 300 then
        return false, "HTTP " .. status
    end
    return true
end

--- 把缓冲里的事件发走。由定时器与「攒够一批」两处调用。
function M.flush()
    local dict = ngx.shared[BUF]
    if not dict then
        return
    end
    local batch = {}
    for _ = 1, conf.batch_size do
        local item = dict:lpop("q")
        if not item then
            break
        end
        local e = cjson.decode(item)
        if e then
            batch[#batch + 1] = e
        end
    end
    if #batch == 0 then
        return
    end
    local ok, err = send(batch)
    if ok then
        incr("sent", #batch)
    else
        incr("failed", #batch)
        -- 不重新入队:重试会在采集器不可用时把缓冲撑爆,而缓冲爆了之后丢的是**新**事件。
        -- 宁可丢这一批并让计数涨起来 —— 计数是可监控的,静默积压不是。
        ngx.log(ngx.ERR, "nebula: 投递失败,丢弃 ", #batch, " 条: ", tostring(err))
    end
end

--- 记录一条事件。在 log_by_lua 阶段调用。
function M.log()
    local dict = ngx.shared[BUF]
    if not dict then
        ngx.log(ngx.ERR, "nebula: 未声明 lua_shared_dict ", BUF)
        return
    end
    local e = build_event()
    local encoded = cjson.encode(e)
    if not encoded then
        incr("encode_errors")
        return
    end

    local len, err = dict:rpush("q", encoded)
    if not len then
        -- 共享内存满。丢最旧的一批腾地方 —— 网关内存是硬约束,
        -- 而阻塞请求会把风控的故障变成业务的故障。
        if err == "no memory" then
            for _ = 1, conf.batch_size do
                if not dict:lpop("q") then break end
            end
            incr("dropped", conf.batch_size)
            dict:rpush("q", encoded)
        else
            incr("errors")
        end
        return
    end

    incr("queued")
    if len >= conf.batch_size then
        -- 不在请求上下文里同步发 —— 用 timer 挪到后台,埋点耗时不占用户的时间
        local ok, terr = ngx.timer.at(0, function() M.flush() end)
        if not ok then
            ngx.log(ngx.ERR, "nebula: 创建 flush timer 失败: ", terr)
        end
    end
end

--- 运行统计。供 /nebula-status 暴露给监控。
function M.stats()
    local dict = ngx.shared[STATS]
    local buf = ngx.shared[BUF]
    local out = {
        queued = 0, sent = 0, failed = 0, dropped = 0,
        errors = 0, encode_errors = 0,
        pending = buf and buf:llen("q") or 0,
    }
    if dict then
        for k in pairs(out) do
            if k ~= "pending" then
                out[k] = dict:get(k) or 0
            end
        end
    end
    return out
end

--- 初始化。在 init_worker_by_lua 阶段调用。
--
-- @param opts.endpoint  采集器地址,如 http://collector:8088/v2/events(必填)
-- @param opts.token     共享令牌,对应采集器的 NEBULA_COLLECTOR_TOKEN
-- @param opts.trust_xff 是否信任 X-Forwarded-For。**默认关**
function M.setup(opts)
    for k, v in pairs(opts or {}) do
        conf[k] = v
    end
    if not conf.endpoint then
        ngx.log(ngx.ERR, "nebula: 未配置 endpoint,埋点不会生效"
            .. "(注意:nginx 默认不把环境变量传给 worker,需要在配置顶层声明 env)")
        return
    end
    local host, port, path, perr = parse_endpoint(conf.endpoint)
    if not host then
        ngx.log(ngx.ERR, "nebula: ", perr)
        return
    end
    conf._host, conf._port, conf._path = host, port, path
    -- 每个 worker 各起一个定时器。攒不满一批的低流量场景靠它把事件发出去 ——
    -- 没有它的话,流量停下来时最后那批会一直留在缓冲里。
    local function tick()
        M.flush()
        local ok, err = ngx.timer.at(conf.flush_interval, tick)
        if not ok then
            ngx.log(ngx.ERR, "nebula: 续期 flush timer 失败: ", err)
        end
    end
    local ok, err = ngx.timer.at(conf.flush_interval, tick)
    if not ok then
        ngx.log(ngx.ERR, "nebula: 创建 flush timer 失败: ", err)
    end
end

return M
