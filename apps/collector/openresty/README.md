# OpenResty 埋点

在网关侧把 HTTP 请求转成 `HTTP_DYNAMIC` 事件,批量投给采集器。1.x 的 `sniffer`
承担过这个角色。

## 装法

```bash
# 1. 放模块
cp nebula.lua /usr/local/openresty/nebula/

# 2. 参照 nginx.conf.example 改配置,三处必需:
#      lua_shared_dict nebula_buffer / nebula_stats
#      lua_package_path 指向模块目录
#      env NEBULA_ENDPOINT; env NEBULA_COLLECTOR_TOKEN;
#
# 3. 起
NEBULA_ENDPOINT=http://collector:8088/v2/events \
NEBULA_COLLECTOR_TOKEN=$TOKEN openresty -p /path/to/prefix
```

> **`env` 那两行是必需的。** nginx 默认不把环境变量传给 worker 进程,不声明的话
> `os.getenv` 一律返回 nil,而表现是「埋点静默不生效」——
> 只有 error_log 里一行提示。这条是实跑时撞出来的。

## 装在 log 阶段,不是 access 阶段

```nginx
log_by_lua_block { require("nebula").log() }
```

响应发出**之后**才执行,因此:

- 拿得到状态码与响应大小(access 阶段还没有)
- 埋点耗时不计入用户可见的延迟
- **埋点失败不会影响业务请求** —— 这一条最重要:风控埋点永远不该让业务请求失败

实测验证过:采集器整个挂掉时,业务请求仍然 200,失败计数照常上涨。

## 敏感字段:根本不采

事件模型把 `cookie` / `uri_query` / `c_body` / `s_body` 标为 `sensitive`,采集器会在
入口脱敏。但**本模块根本不采集它们**。

让数据先出网关再在下游擦掉,中间每一跳(日志、代理、抓包)都可能留下副本。
**少采一个字段,比多做一次脱敏可靠。**

实测:请求带 `Cookie: session=SECRET_COOKIE` 与 `?token=SECRET_QUERY`,
采集器落盘的 11 条事件里两个值都**一次都没出现**。

`uid` / `did` / `sid` 由业务系统通过请求头显式提供,网关不去猜 —— 猜错的代价是把
两个人的行为算成一个人。

## X-Forwarded-For 默认不信任

```lua
require("nebula").setup{ trust_xff = false }   -- 默认
```

网关前面还有 LB 时 `remote_addr` 是 LB 的地址,这时才该打开 `trust_xff`。
**默认信任会让攻击者随便声明自己的 IP**,而 IP 是风控最核心的主体维度之一。

## 缓冲与丢弃

批量缓冲 + 每秒 flush。缓冲满时**丢最旧的一批并计数**,不阻塞请求也不无限增长 ——
网关内存是硬约束,而阻塞请求会把风控的故障变成业务的故障。

投递失败**不重新入队**:重试会在采集器不可用时把缓冲撑爆,而缓冲爆了之后丢的是
**新**事件。宁可丢这一批并让计数涨起来 —— 计数可监控,静默积压不可监控。

```bash
curl http://gateway/nebula-status
# {"queued":11,"sent":11,"failed":0,"dropped":0,"pending":0,"errors":0,"encode_errors":0}
```

> `/nebula-status` **不要暴露到可信网络之外** —— 它会告诉外面这个网关的流量量级。

## 不依赖 lua-resty-http

用 `ngx.socket.tcp` 手写了一个最简单的 POST。

`lua-resty-http` **不是 OpenResty 自带的**(镜像的 `lualib/resty/` 下没有 `http.lua`,
要用 `opm` 单独装)—— 这一条是实跑时撞出来的,初稿注释里写的「自带」是错的。

让部署方为一个埋点脚本再装一个 Lua 库是实打实的负担,而这里只需要固定方法、固定内容
类型、不需要重定向 / chunked / 连接复用的一次 POST。与采集器不引入 client_golang
是同一个判断。

## 验证方式

用官方镜像跑的真实 OpenResty(1.31.1.1),不是纸面推演:

```bash
docker run -d --name gw --network <net> -p 18080:8080 \
  -v "$PWD/nebula.lua:/usr/local/openresty/nebula/nebula.lua:ro" \
  -v "$PWD/nginx.conf.example:/usr/local/openresty/nginx/conf/nginx.conf:ro" \
  -e NEBULA_ENDPOINT=http://collector:8088/v2/events \
  -e NEBULA_COLLECTOR_TOKEN=$TOKEN openresty/openresty:alpine
```

覆盖到的:事件产出与字段完整、敏感字段未外泄、令牌错时记 failed(HTTP 401)、
采集器不可用时业务请求不受影响。
