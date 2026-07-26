package driver

import (
	"crypto/subtle"
	"net/http"
	"strings"
)

// Auth 接入口的共享令牌校验。
//
// # 为什么要有
//
// 采集器的 HTTP 入口此前**没有任何认证**:能连到端口的人都可以伪造事件。
// 威胁模型把它列为名单投毒最直接的入口 —— 攻击者用受害者的账号名造一批假行为,
// 就能把那个账号刷进黑名单;或者反过来灌大量噪声,把真实信号稀释掉。
// 两种都不需要任何凭据。
//
// # 为什么是共享令牌而不是 mTLS 或每客户端一个身份
//
// 采集器的调用方是**业务系统的埋点 SDK 或旁路进程**,不是人。它们数量少、部署在
// 同一组织内、生命周期与业务发版绑定。给每个客户端签发独立身份意味着采集器要维护
// 一份身份存储 —— 而采集器的依赖面刻意保持很小,且做成一个可以随业务一起打包的
// 单二进制。共享令牌配合网段限制,在这个部署形态下是相称的。
//
// 需要按客户端区分身份与吊销时,正确做法是在前面放一个反向代理做 mTLS,
// 而不是把身份体系塞进采集器。README 与威胁模型里都写明了这一点。
//
// # 空令牌 = 不校验,且必须显式选择
//
// 没配令牌时不启用校验,否则升级会打断所有现有部署。但**启动时会打印一行警告**,
// 而不是静默放行 —— 「忘了配」和「明确决定不配」在日志里必须能区分开。
type Auth struct {
	// token 为空表示不校验。
	token []byte
}

// NewAuth 构造校验器。token 为空时不校验。
func NewAuth(token string) *Auth {
	if token == "" {
		return &Auth{}
	}
	return &Auth{token: []byte(token)}
}

// Enabled 是否启用了校验。供启动时打印状态。
func (a *Auth) Enabled() bool { return len(a.token) > 0 }

// Check 校验一个请求。
//
// 接受两种写法:`Authorization: Bearer <token>` 与 `X-Nebula-Token: <token>`。
// 前者是通用约定,后者便于那些不方便设置 Authorization 头的旁路采集脚本。
func (a *Auth) Check(r *http.Request) bool {
	if !a.Enabled() {
		return true
	}
	presented := r.Header.Get("X-Nebula-Token")
	if presented == "" {
		if v := r.Header.Get("Authorization"); strings.HasPrefix(v, "Bearer ") {
			presented = strings.TrimPrefix(v, "Bearer ")
		}
	}
	// 定长比较。按字节短路的比较会让攻击者通过响应耗时逐位试出令牌 ——
	// 令牌是高熵随机串时这个攻击并不现实,但正确写法与错误写法的代价一样,
	// 没有理由选错的那个。
	return subtle.ConstantTimeCompare([]byte(presented), a.token) == 1
}

// Wrap 给 handler 套上校验。校验失败返回 401 且**不说明原因** ——
// 区分「没带令牌」与「令牌不对」等于告诉对方它猜的方向对不对。
func (a *Auth) Wrap(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !a.Check(r) {
			http.Error(w, "未认证", http.StatusUnauthorized)
			return
		}
		next(w, r)
	}
}
