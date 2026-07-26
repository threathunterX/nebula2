import { useState } from 'react';
import { api, type CreatedUser, type IssuedToken } from '../api';
import { Badge, Empty, ErrorBox, Loading } from '../components';
import { useAsync } from '../hooks';

const ROLES = ['ADMIN', 'OPERATOR', 'VIEWER'];
const SCOPES = ['checkRisk', 'metadata:read'];

/**
 * 账号与服务令牌管理。
 *
 * 这个页面处理的东西有一个共同特点:**明文只出现一次**。建账号返回的口令、签发令牌
 * 返回的令牌串,服务端都只存哈希,响应之后再也拿不到。所以这里的呈现有几条硬约束:
 *
 * - 明文只渲染在一个**必须手动关闭**的框里,不自动消失 —— 自动消失意味着有人转头
 *   去接杯水回来就丢了,只能重新签发。
 * - 不写进 localStorage、不进 URL、不放进任何会被上报的地方。整个前端都遵守这条
 *   (见 `api.ts` 的说明),这里只是最容易破例的地方。
 * - 关闭前明确写出「关掉就没有了」,而不是让人自己推断。
 */
export default function Accounts() {
  const me = useAsync(() => api.me(), []);
  const isAdmin = (me.data?.roles ?? []).includes('ADMIN');

  if (me.loading) return <Loading />;
  if (!isAdmin) {
    return (
      <>
        <h2>账号与令牌</h2>
        <Empty>
          当前角色是 {(me.data?.roles ?? []).join(' / ') || '未知'},账号与令牌管理仅管理员可用。
        </Empty>
      </>
    );
  }

  return (
    <>
      <h2>账号与令牌</h2>
      <p className="lede">
        人类账号走 HTTP Basic,服务走令牌 —— 两者不通用:人类账号<b>调不了</b>
        <code>/checkRisk</code>,服务令牌<b>调不了</b>管理接口。
      </p>
      <Users self={me.data?.username ?? ''} />
      <Tokens />
    </>
  );
}

// ---------------------------------------------------------------- 账号

function Users({ self }: { self: string }) {
  const { data, error, loading, reload } = useAsync(() => api.users(), []);
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState<CreatedUser | null>(null);
  const [busy, setBusy] = useState('');
  const [opError, setOpError] = useState<unknown>(null);

  async function run(what: string, fn: () => Promise<unknown>) {
    setBusy(what);
    setOpError(null);
    try {
      await fn();
      reload();
    } catch (e) {
      setOpError(e);
    } finally {
      setBusy('');
    }
  }

  const rows = data?.users ?? [];

  return (
    <section className="panel" style={{ marginBottom: 20 }}>
      <h3>
        账号
        <button className="ghost" style={{ float: 'right' }} onClick={() => setCreating((v) => !v)}>
          {creating ? '取消' : '新建账号'}
        </button>
      </h3>
      <div className="body">
        <ErrorBox error={error} />
        <ErrorBox error={opError} />
        {created && <SecretOnce
          title={`账号 ${created.username} 的初始口令`}
          secret={created.password}
          hint="交付本人后请让其立即更换。关掉这个框之后口令就再也取不回来了,只能重置。"
          onClose={() => setCreated(null)}
        />}
        {creating && <CreateUser
          onCreated={(u) => { setCreated(u); setCreating(false); reload(); }}
        />}

        {loading ? <Loading /> : rows.length === 0 ? <Empty>还没有任何账号。</Empty> : (
          <table>
            <thead>
              <tr><th>账号</th><th>显示名</th><th>角色</th><th>状态</th><th>创建时间</th><th /></tr>
            </thead>
            <tbody>
              {rows.map((u) => (
                <tr key={u.username}>
                  <td className="mono">{u.username}{u.username === self && <span className="note"> (你)</span>}</td>
                  <td>{u.display_name}</td>
                  <td>{u.roles.map((r) => <Badge key={r} kind="cfg">{r}</Badge>)}</td>
                  <td>{u.enabled ? '启用' : <span className="note">已停用</span>}</td>
                  <td className="note">{u.created_at}</td>
                  <td style={{ whiteSpace: 'nowrap' }}>
                    <button
                      className="ghost"
                      disabled={busy !== ''}
                      onClick={() => run('reset', async () => {
                        const r = await api.resetPassword(u.username);
                        setCreated(r);
                      })}
                    >
                      重置口令
                    </button>
                    {/* 停用自己会让自己再也登不进来。服务端也拒绝这个操作,
                        这里禁用按钮只是别让人先点了才知道。 */}
                    <button
                      className="ghost"
                      disabled={busy !== '' || u.username === self}
                      title={u.username === self ? '不能停用自己的账号' : ''}
                      onClick={() => run('toggle', () => api.setUserEnabled(u.username, !u.enabled))}
                    >
                      {u.enabled ? '停用' : '启用'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <p className="note">
          停用而不是删除:账号名会出现在审计日志里,删掉行会让那些记录指向一个查不到的人。
        </p>
      </div>
    </section>
  );
}

function CreateUser({ onCreated }: { onCreated: (u: CreatedUser) => void }) {
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [roles, setRoles] = useState<string[]>(['VIEWER']);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onCreated(await api.createUser(username, displayName || username, roles));
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="filters" style={{ alignItems: 'flex-end' }}>
      <ErrorBox error={error} />
      <label>
        账号
        <input value={username} onChange={(e) => setUsername(e.target.value)} placeholder="3-32 位小写" autoFocus />
      </label>
      <label>
        显示名
        <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} placeholder="留空则同账号" />
      </label>
      <label>
        角色
        <span>
          {ROLES.map((r) => (
            <label key={r} className="inline">
              <input
                type="checkbox"
                checked={roles.includes(r)}
                onChange={(e) => setRoles((prev) => e.target.checked ? [...prev, r] : prev.filter((x) => x !== r))}
              />
              {r}
            </label>
          ))}
        </span>
      </label>
      <button disabled={busy || !username || roles.length === 0}>{busy ? '创建中…' : '创建'}</button>
      <p className="note">
        口令由服务端生成 —— 不接受调用方指定,否则它会出现在请求体、代理日志和 shell 历史里。
      </p>
    </form>
  );
}

// ---------------------------------------------------------------- 令牌

function Tokens() {
  const { data, error, loading, reload } = useAsync(() => api.tokens(), []);
  const [issuing, setIssuing] = useState(false);
  const [issued, setIssued] = useState<IssuedToken | null>(null);
  const [opError, setOpError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const rows = data?.tokens ?? [];

  return (
    <section className="panel">
      <h3>
        服务令牌
        <button className="ghost" style={{ float: 'right' }} onClick={() => setIssuing((v) => !v)}>
          {issuing ? '取消' : '签发令牌'}
        </button>
      </h3>
      <div className="body">
        <ErrorBox error={error} />
        <ErrorBox error={opError} />
        {issued && <SecretOnce
          title={`令牌 ${issued.token_id}`}
          secret={issued.token}
          hint="立即保存。库里只有哈希,关掉这个框之后无法再取回,丢失只能重新签发。"
          onClose={() => setIssued(null)}
        />}
        {issuing && <IssueToken onIssued={(t) => { setIssued(t); setIssuing(false); reload(); }} />}

        {loading ? <Loading /> : rows.length === 0 ? <Empty>还没有签发过任何服务令牌。</Empty> : (
          <table>
            <thead>
              <tr>
                <th>令牌 ID</th><th>用途</th><th>作用域</th><th>来源网段</th>
                <th>状态</th><th>最近使用</th><th />
              </tr>
            </thead>
            <tbody>
              {rows.map((t) => (
                <tr key={t.token_id}>
                  <td className="mono">{t.token_id}</td>
                  <td>{t.description || <span className="note">—</span>}</td>
                  <td>{t.scopes.map((s) => <Badge key={s} kind="cfg">{s}</Badge>)}</td>
                  <td className="mono">
                    {t.allowed_cidrs.length === 0
                      ? <span className="note">不限</span>
                      : t.allowed_cidrs.join(', ')}
                  </td>
                  <td>{t.enabled ? '有效' : <span className="note">已吊销</span>}</td>
                  {/* 最有用的一列:回答「这个令牌还有人在用吗」。清理陈旧令牌时
                      唯一能依据的事实,空值意味着签发之后从未被使用过。 */}
                  <td className="note">{t.last_used_at || '从未使用'}</td>
                  <td>
                    {t.enabled && (
                      <button
                        className="ghost"
                        disabled={busy}
                        onClick={async () => {
                          if (!confirm(`吊销令牌 ${t.token_id}?使用它的服务会立即开始收到 401。`)) return;
                          setBusy(true);
                          setOpError(null);
                          try {
                            await api.revokeToken(t.token_id);
                            reload();
                          } catch (e) {
                            setOpError(e);
                          } finally {
                            setBusy(false);
                          }
                        }}
                      >
                        吊销
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <p className="note">
          令牌与来源网段是<b>与</b>的关系,不是或 —— 令牌对但来源不在网段内,同样拒绝。
        </p>
      </div>
    </section>
  );
}

function IssueToken({ onIssued }: { onIssued: (t: IssuedToken) => void }) {
  const [description, setDescription] = useState('');
  const [scopes, setScopes] = useState<string[]>(['checkRisk']);
  const [cidrs, setCidrs] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const list = cidrs.split(',').map((c) => c.trim()).filter(Boolean);
      onIssued(await api.issueToken(description, scopes, list));
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="filters" style={{ alignItems: 'flex-end' }}>
      <ErrorBox error={error} />
      <label>
        用途
        <input value={description} onChange={(e) => setDescription(e.target.value)} placeholder="例如:订单系统" autoFocus />
      </label>
      <label>
        作用域
        <span>
          {SCOPES.map((s) => (
            <label key={s} className="inline">
              <input
                type="checkbox"
                checked={scopes.includes(s)}
                onChange={(e) => setScopes((prev) => e.target.checked ? [...prev, s] : prev.filter((x) => x !== s))}
              />
              {s}
            </label>
          ))}
        </span>
      </label>
      <label>
        来源网段
        <input value={cidrs} onChange={(e) => setCidrs(e.target.value)} placeholder="10.1.0.0/16, 逗号分隔;留空不限" />
      </label>
      <button disabled={busy || scopes.length === 0}>{busy ? '签发中…' : '签发'}</button>
    </form>
  );
}

// ---------------------------------------------------------------- 只出现一次的明文

/**
 * 展示一段之后再也拿不到的明文。
 *
 * 不做自动隐藏、不做倒计时 —— 这类"贴心"设计在这里是有害的:它会在人还没保存好的
 * 时候把唯一一份明文收走。只能手动关闭,并且关闭前把后果写清楚。
 */
function SecretOnce({
  title, secret, hint, onClose,
}: { title: string; secret: string; hint: string; onClose: () => void }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="secret-once">
      <strong>{title}</strong>
      <code className="secret">{secret}</code>
      <div>
        <button
          className="ghost"
          onClick={async () => {
            try {
              await navigator.clipboard.writeText(secret);
              setCopied(true);
            } catch {
              // 非 HTTPS 或未授权时剪贴板不可用 —— 明文就在上面,手动选中即可,
              // 所以这里不当作错误弹出来打扰人
              setCopied(false);
            }
          }}
        >
          {copied ? '已复制' : '复制'}
        </button>
        <button className="ghost" onClick={onClose}>我已保存,关闭</button>
      </div>
      <p className="note">{hint}</p>
    </div>
  );
}
