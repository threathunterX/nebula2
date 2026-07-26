import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import { Badge, ErrorBox, Loading, Panel } from '../components';
import { useAsync } from '../hooks';
import { deriveOptions, missing, type Options, type StrategySchema } from '../schema';
import StrategyForm from './StrategyForm';

const STATUSES = ['inedit', 'test', 'online', 'outline'];

export default function StrategyDetail() {
  const { name = '' } = useParams();
  const detail = useAsync(() => api.strategy(name), [name]);
  const revisions = useAsync(() => api.revisions(name), [name]);
  const schema = useAsync(() => api.schema('strategy'), []);

  // 编辑中的定义。表单与 JSON 视图共用同一份草稿 —— 分开存的话切换视图会丢掉
  // 刚才的修改,而且不会有任何提示。
  const [draft, setDraft] = useState<Record<string, unknown> | null>(null);
  const [view, setView] = useState<'form' | 'json'>('form');
  // JSON 视图下的文本单独存:打字过程中它经常不是合法 JSON,只有解析成功时才
  // 同步回 draft。若直接绑 draft,每打一个字符都会解析失败并把草稿清掉。
  const [jsonText, setJsonText] = useState('');
  const [jsonError, setJsonError] = useState<string | null>(null);
  const [note, setNote] = useState('');
  const [msg, setMsg] = useState<string | null>(null);
  const [err, setErr] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const d = detail.data;
  const def = d?.definition;
  // 行版本,不是 definition.version(那是领域模型版本 "2.0")
  const version = d?.version ?? 0;
  const status = d?.status ?? '';

  const rawSchema = (schema.data ?? {}) as StrategySchema;
  const derived = deriveOptions(rawSchema);
  const lacking = missing(derived);

  function startEdit() {
    setDraft(structuredClone(def) as Record<string, unknown>);
    setJsonText(JSON.stringify(def, null, 2));
    setJsonError(null);
    setErr(null);
  }

  function cancelEdit() {
    setDraft(null);
    setJsonError(null);
    setErr(null);
  }

  /** 表单改动:draft 是权威,顺带刷新 JSON 文本,这样切过去看到的是最新的。 */
  function onFormChange(next: Record<string, unknown>) {
    setDraft(next);
    setJsonText(JSON.stringify(next, null, 2));
    setJsonError(null);
  }

  /** JSON 改动:文本是权威,解析成功才同步回 draft。 */
  function onJsonChange(text: string) {
    setJsonText(text);
    try {
      const parsed = JSON.parse(text);
      if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
        setJsonError('策略定义必须是一个 JSON 对象');
        return;
      }
      setDraft(parsed as Record<string, unknown>);
      setJsonError(null);
    } catch (e) {
      setJsonError(e instanceof Error ? e.message : String(e));
    }
  }

  async function save() {
    setBusy(true);
    setErr(null);
    setMsg(null);
    try {
      const r = await api.saveStrategy(name, draft!, version, note || '通过控制台修改');
      setMsg(`已保存,当前 v${r.version}`);
      setDraft(null);
      setNote('');
      detail.reload();
      revisions.reload();
    } catch (e) {
      // 409 是版本冲突,不是请求出错 —— 提示必须让人知道该重新拉取而不是重试
      if (e instanceof ApiError && e.status === 409) {
        setErr(new Error(`${e.message}\n\n别人在你编辑期间改过这条策略。请刷新页面拿到最新版本后重新修改。`));
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
                  <button className="ghost" onClick={startEdit}>编辑</button>
                </p>
              </>
            ) : (
              <>
                <div className="tabs">
                  <button className={view === 'form' ? '' : 'ghost'} onClick={() => setView('form')}>表单</button>
                  <button className={view === 'json' ? '' : 'ghost'} onClick={() => setView('json')}>JSON</button>
                  {jsonError && <span className="note">JSON 未解析:{jsonError}</span>}
                </div>

                {view === 'form' ? (
                  schema.loading ? <Loading what="载入 schema" />
                  : schema.error ? (
                    <>
                      <ErrorBox error={schema.error} />
                      {/* 取不到 schema 时不用硬编码的取值兜底 —— 那等于把「前端另抄
                          一份」偷偷放回来,而且只在拉取失败时生效,最难发现 */}
                      <p className="note">拿不到领域 schema,表单无法确定各字段的合法取值。请用 JSON 视图编辑。</p>
                    </>
                  ) : lacking.length > 0 ? (
                    <p className="note">
                      schema 里读不到这些取值:{lacking.join('、')}。表单不猜,请用 JSON 视图编辑。
                    </p>
                  ) : (
                    <StrategyForm
                      value={draft}
                      schema={rawSchema}
                      options={derived as Options}
                      onChange={onFormChange}
                    />
                  )
                ) : (
                  <textarea rows={22} className="mono" value={jsonText}
                            onChange={(e) => onJsonChange(e.target.value)} />
                )}

                <div className="filters" style={{ marginTop: 12 }}>
                  <label style={{ flex: 1 }}>
                    变更说明
                    <input value={note} onChange={(e) => setNote(e.target.value)}
                           placeholder="例如:阈值 100 调到 50" />
                  </label>
                  <button disabled={busy || jsonError !== null} onClick={save}>
                    {busy ? '保存中…' : '保存'}
                  </button>
                  <button className="ghost" disabled={busy} onClick={cancelEdit}>取消</button>
                </div>
                <p className="note">
                  表单只改它渲染的字段,其余原样保留 —— 包括表单不认识的字段(如 <code>source_1x</code>、
                  <code>action.handlers</code>)。保存时带上你读到的版本号 <span className="mono">v{version}</span>,
                  若期间有人改过会返回冲突而不是静默覆盖。服务端按 schema 与引用完整性校验,
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
