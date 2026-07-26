import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import { Badge, ErrorBox, Loading, Panel } from '../components';
import { useAsync } from '../hooks';

const STATUSES = ['inedit', 'test', 'online', 'outline'];

export default function StrategyDetail() {
  const { name = '' } = useParams();
  const detail = useAsync(() => api.strategy(name), [name]);
  const revisions = useAsync(() => api.revisions(name), [name]);

  const [draft, setDraft] = useState<string | null>(null);
  const [note, setNote] = useState('');
  const [msg, setMsg] = useState<string | null>(null);
  const [err, setErr] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const d = detail.data;
  const def = d?.definition;
  // 行版本,不是 definition.version(那是领域模型版本 "2.0")
  const version = d?.version ?? 0;
  const status = d?.status ?? '';

  async function save() {
    setBusy(true);
    setErr(null);
    setMsg(null);
    try {
      const parsed = JSON.parse(draft!);
      const r = await api.saveStrategy(name, parsed, version, note || '通过控制台修改');
      setMsg(`已保存,当前 v${r.version}`);
      setDraft(null);
      setNote('');
      detail.reload();
      revisions.reload();
    } catch (e) {
      // 409 是版本冲突,不是请求出错 —— 提示必须让人知道该重新拉取而不是重试
      if (e instanceof ApiError && e.status === 409) {
        setErr(new Error(`${e.message}\n\n别人在你编辑期间改过这条策略。请刷新页面拿到最新版本后重新修改。`));
      } else if (e instanceof SyntaxError) {
        setErr(new Error(`JSON 解析失败:${e.message}`));
      } else {
        setErr(e);
      }
    } finally {
      setBusy(false);
    }
  }

  async function changeStatus(next: string) {
    setBusy(true);
    setErr(null);
    setMsg(null);
    try {
      await api.setStatus(name, next);
      setMsg(`状态已改为 ${next}`);
      detail.reload();
    } catch (e) {
      setErr(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <p className="note"><Link to="/strategies">← 返回策略列表</Link></p>
      <h2>{name}</h2>
      <p className="lede">
        {status && <Badge kind={status}>{status}</Badge>} {version > 0 && <span className="mono">v{version}</span>}
      </p>

      {msg && <div className="ok">{msg}</div>}
      <ErrorBox error={err ?? detail.error} />

      {detail.loading ? <Loading /> : def && (
        <>
          <Panel title="状态">
            <div className="filters">
              {STATUSES.map((s) => (
                <button key={s} className={s === status ? '' : 'ghost'} disabled={busy || s === status}
                        onClick={() => changeStatus(s)}>
                  {s}
                </button>
              ))}
            </div>
            <p className="note">
              <code>test</code> 会照常计算并产出告警,但不参与线上决策。
              <b>不要从 <code>inedit</code> 直接跳到 <code>online</code></b> —— test 阶段的意义是用真实流量
              验证命中量,跳过它等于拿线上业务做实验。
            </p>
          </Panel>

          <Panel title="定义">
            {draft === null ? (
              <>
                <pre className="mono" style={{ margin: 0, maxHeight: 420, overflow: 'auto' }}>
                  {JSON.stringify(def, null, 2)}
                </pre>
                <p style={{ marginBottom: 0 }}>
                  <button className="ghost" onClick={() => setDraft(JSON.stringify(def, null, 2))}>编辑</button>
                </p>
              </>
            ) : (
              <>
                <textarea rows={22} value={draft} onChange={(e) => setDraft(e.target.value)} />
                <div className="filters" style={{ marginTop: 12 }}>
                  <label style={{ flex: 1 }}>
                    变更说明
                    <input value={note} onChange={(e) => setNote(e.target.value)}
                           placeholder="例如:阈值 100 调到 50" />
                  </label>
                  <button disabled={busy} onClick={save}>{busy ? '保存中…' : '保存'}</button>
                  <button className="ghost" disabled={busy} onClick={() => { setDraft(null); setErr(null); }}>取消</button>
                </div>
                <p className="note">
                  保存时带上你读到的版本号 <span className="mono">v{version}</span>。若期间有人改过,
                  会返回冲突而不是静默覆盖。服务端会按 schema 与引用完整性校验,
                  不通过时一次列出全部问题。
                </p>
              </>
            )}
          </Panel>

          <Panel title="修订历史">
            {revisions.loading ? <Loading /> : (revisions.data?.revisions.length ?? 0) === 0 ? (
              <p className="note">还没有修订记录。历史从本条策略第一次经控制台保存时开始记录。</p>
            ) : (
              <table>
                <thead><tr><th>版本</th><th>状态</th><th>修改者</th><th>说明</th><th>时间</th></tr></thead>
                <tbody>
                  {revisions.data!.revisions.map((r) => (
                    <tr key={r.version}>
                      <td className="mono">v{r.version}</td>
                      <td><Badge kind={r.status}>{r.status}</Badge></td>
                      <td>{r.changed_by}</td>
                      <td>{r.change_note || '—'}</td>
                      <td className="mono">{r.changed_at}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            <p className="note">回滚 = 把某个旧版本重新提交一次,因此回滚本身也会产生新版本,历史只增不改。</p>
          </Panel>
        </>
      )}
    </>
  );
}
