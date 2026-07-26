import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import { Badge, Empty, ErrorBox, Loading } from '../components';
import { useAsync } from '../hooks';

const CATEGORIES = ['', 'ACCOUNT', 'ORDER', 'VISITOR', 'TRANSACTION', 'MARKETING', 'OTHER'];
const STATUSES = ['', 'inedit', 'test', 'online', 'outline'];

export default function Strategies() {
  const [category, setCategory] = useState('');
  const [status, setStatus] = useState('');
  const [q, setQ] = useState('');
  const { data, error, loading } = useAsync(
    () => api.strategies({ category: category || undefined, status: status || undefined, limit: 500 }),
    [category, status],
  );

  const rows = (data ?? []).filter(
    (s) => !q || s.name.includes(q) || s.visible_name.includes(q),
  );

  return (
    <>
      <h2>策略</h2>
      <p className="lede">
        内置 170 条模板。标记 <Badge kind="cfg">需配置</Badge> 的策略含占位符,
        不替换就不会正常工作 —— 其中一部分会恒真、命中所有主体。
      </p>

      <div className="filters">
        <label>
          分类
          <select value={category} onChange={(e) => setCategory(e.target.value)}>
            {CATEGORIES.map((c) => <option key={c} value={c}>{c || '全部'}</option>)}
          </select>
        </label>
        <label>
          状态
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            {STATUSES.map((s) => <option key={s} value={s}>{s || '全部'}</option>)}
          </select>
        </label>
        <label>
          搜索
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="按名称过滤" />
        </label>
      </div>

      <ErrorBox error={error} />
      {loading ? <Loading /> : rows.length === 0 ? (
        <Empty>没有符合条件的策略。</Empty>
      ) : (
        <div className="panel">
          <table>
            <thead>
              <tr>
                <th>名称</th><th>分类</th><th>状态</th><th>风险分</th><th>标签</th><th>版本</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((s) => (
                <tr key={s.name}>
                  <td>
                    <Link to={`/strategies/${encodeURIComponent(s.name)}`}>{s.name}</Link>
                    {s.requires_config && <> <Badge kind="cfg">需配置</Badge></>}
                  </td>
                  <td>{s.category}</td>
                  <td><Badge kind={s.status}>{s.status}</Badge></td>
                  <td>{s.score}</td>
                  <td className="note">{s.tags.join('、') || '—'}</td>
                  <td className="mono">v{s.version}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="note">共 {rows.length} 条</p>
    </>
  );
}
