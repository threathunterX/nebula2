import { useState } from 'react';
import { NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { api, clearCredentials, isAuthenticated, setCredentials } from './api';
import { ErrorBox } from './components';
import Dashboard from './pages/Dashboard';
import Strategies from './pages/Strategies';
import StrategyDetail from './pages/StrategyDetail';
import Alerts from './pages/Alerts';
import Variables from './pages/Variables';

export default function App() {
  const [authed, setAuthed] = useState(isAuthenticated());
  if (!authed) return <Login onDone={() => setAuthed(true)} />;
  return (
    <div className="layout">
      <aside className="sidebar">
        <h1>星云 Nebula</h1>
        <div className="sub">风控控制台</div>
        <nav>
          <NavLink to="/" end>概览</NavLink>
          <NavLink to="/strategies">策略</NavLink>
          <NavLink to="/alerts">告警</NavLink>
          <NavLink to="/variables">变量</NavLink>
        </nav>
        <div className="foot">
          <button
            className="ghost"
            onClick={() => {
              clearCredentials();
              setAuthed(false);
            }}
          >
            退出
          </button>
        </div>
      </aside>
      <main className="main">
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/strategies" element={<Strategies />} />
          <Route path="/strategies/:name" element={<StrategyDetail />} />
          <Route path="/alerts" element={<Alerts />} />
          <Route path="/variables" element={<Variables />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  );
}

function Login({ onDone }: { onDone: () => void }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setCredentials(username, password);
    try {
      // 用一个最轻的已认证接口验证凭据,而不是"先让进去再说" ——
      // 后者会让口令错误直到第一次真正操作时才暴露。
      await api.stats();
      onDone();
    } catch (err) {
      clearCredentials();
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="login" onSubmit={submit}>
      <h1>星云 Nebula</h1>
      <p>风控控制台</p>
      <ErrorBox error={error} />
      <label>
        用户名
        <input value={username} onChange={(e) => setUsername(e.target.value)} autoFocus autoComplete="username" />
      </label>
      <label>
        口令
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
      </label>
      <button disabled={busy || !username || !password}>{busy ? '验证中…' : '登录'}</button>
      <p className="note" style={{ marginTop: 14 }}>
        首次启动的管理员口令只在控制面日志中打印一次。凭据仅保存在内存中,刷新页面需要重新登录。
      </p>
    </form>
  );
}
