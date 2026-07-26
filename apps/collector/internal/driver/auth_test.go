package driver

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// 认证是「关掉了也不报错」的那类:去掉校验之后所有测试照样通过,接口照样工作,
// 只是任何人都能伪造事件了。所以逐条钉住。

func TestAuthDisabledWhenNoToken(t *testing.T) {
	a := NewAuth("")
	if a.Enabled() {
		t.Fatal("空令牌应当表示不校验")
	}
	r := httptest.NewRequest(http.MethodPost, "/v2/events", nil)
	if !a.Check(r) {
		t.Error("未启用校验时应当放行")
	}
}

func TestAuthAcceptsBothHeaders(t *testing.T) {
	a := NewAuth("s3cret")
	for name, set := range map[string]func(*http.Request){
		"X-Nebula-Token": func(r *http.Request) { r.Header.Set("X-Nebula-Token", "s3cret") },
		"Bearer":         func(r *http.Request) { r.Header.Set("Authorization", "Bearer s3cret") },
	} {
		r := httptest.NewRequest(http.MethodPost, "/v2/events", nil)
		set(r)
		if !a.Check(r) {
			t.Errorf("%s 写法应当通过", name)
		}
	}
}

func TestAuthRejects(t *testing.T) {
	a := NewAuth("s3cret")
	cases := map[string]func(*http.Request){
		"不带任何头":           func(r *http.Request) {},
		"令牌不对":            func(r *http.Request) { r.Header.Set("X-Nebula-Token", "wrong") },
		"空令牌":             func(r *http.Request) { r.Header.Set("X-Nebula-Token", "") },
		"Bearer 但值不对":     func(r *http.Request) { r.Header.Set("Authorization", "Bearer wrong") },
		"Basic 不是 Bearer": func(r *http.Request) { r.Header.Set("Authorization", "Basic s3cret") },
		// 前缀相同的令牌必须被拒 —— 用 HasPrefix 而不是相等比较时它会通过
		"正确令牌的前缀": func(r *http.Request) { r.Header.Set("X-Nebula-Token", "s3cre") },
		"正确令牌加后缀": func(r *http.Request) { r.Header.Set("X-Nebula-Token", "s3cretX") },
	}
	for name, set := range cases {
		r := httptest.NewRequest(http.MethodPost, "/v2/events", nil)
		set(r)
		if a.Check(r) {
			t.Errorf("%s 应当被拒", name)
		}
	}
}

func TestAuthWrapReturns401WithoutReason(t *testing.T) {
	a := NewAuth("s3cret")
	called := false
	h := a.Wrap(func(w http.ResponseWriter, r *http.Request) { called = true })

	w := httptest.NewRecorder()
	h(w, httptest.NewRequest(http.MethodPost, "/v2/events", nil))
	if w.Code != http.StatusUnauthorized {
		t.Errorf("状态码 = %d,要 401", w.Code)
	}
	if called {
		t.Error("校验失败时不该进到业务处理 —— 否则事件已经被收下了")
	}
	// 响应体不能区分「没带令牌」与「令牌不对」,那等于告诉对方猜的方向对不对
	body := w.Body.String()
	for _, leak := range []string{"token", "令牌", "Bearer", "X-Nebula"} {
		if len(body) > 0 && contains(body, leak) {
			t.Errorf("401 响应体泄露了校验细节:%q", body)
		}
	}
}

func TestAuthWrapPassesThrough(t *testing.T) {
	a := NewAuth("s3cret")
	called := false
	h := a.Wrap(func(w http.ResponseWriter, r *http.Request) { called = true })
	r := httptest.NewRequest(http.MethodPost, "/v2/events", nil)
	r.Header.Set("X-Nebula-Token", "s3cret")
	h(httptest.NewRecorder(), r)
	if !called {
		t.Error("令牌正确时应当进到业务处理")
	}
}

func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}
