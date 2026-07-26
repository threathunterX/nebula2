import { useState } from 'react';
import { api } from '../api';
import { Badge, Empty, ErrorBox, Loading } from '../components';
import { useAsync } from '../hooks';

const MODULES = ['', 'base', 'realtime', 'slot', 'profile'];
const SENS = ['', 'public', 'internal', 'pii', 'sensitive'];

export default function Variables() {
  const [module, setModule] = useState('');
  const [sensitivity, setSensitivity] = useState('');
  const [q, setQ] = useState('');
  const { data, error, loading } = useAsync(
    () => api.variables({ module: module || undefined, sensitivity: sensitivity || undefined, limit: 500 }),
    [module, sensitivity],
  );
  const rows = (data ?? []).filter((v) => !q || v.name.includes(q));

  return (
    <>
      <h2>变量</h2>
      <p className="lede">
        统计特征的定义。变量目前<b>只读</b> —— 改变量会改变已有告警的语义,
        需要配套的迁移机制,尚未实现。
      </p>

      <div className="filters">
        <label>
          模块
          <select value={module} onChange={(e) => setModule(e.target.value)}>
            {MODULES.map((m) => <option key={m} value={m}>{m || '全部'}</option>)}
          </select>
        </label>
        <label>
          敏感级别
          <select value={sensitivity} onChange={(e) => setSensitivity(e.target.value)}>
            {SENS.map((s) => <option key={s} value={s}>{s || '全部'}</option>)}
          </select>
        </label>
        <label>搜索<input value={q} onChange={(e) => setQ(e.target.value)} placeholder="按名称过滤" /></label>
      </div>

      <ErrorBox error={error} />
      {loading ? <Loading /> : rows.length === 0 ? <Empty>没有符合条件的变量。</Empty> : (
        <div className="panel">
          <table>
            <thead><tr><th>名称</th><th>模块</th><th>维度</th><th>状态</th><th>敏感级别</th></tr></thead>
            <tbody>
              {rows.map((v) => (
                <tr key={v.name}>
                  <td className="mono">{v.name}</td>
                  <td>{v.module}</td>
                  <td>{v.dimension || '—'}</td>
                  <td>{v.status}</td>
                  <td>
                    {v.sensitivity === 'pii' || v.sensitivity === 'sensitive'
                      ? <Badge kind="cfg">{v.sensitivity}</Badge>
                      : <span className="note">{v.sensitivity}</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="note">共 {rows.length} 个。标红的含个人标识,存储层需加密保护。</p>
    </>
  );
}
