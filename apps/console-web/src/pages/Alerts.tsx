import { useState } from 'react';
import { api } from '../api';
import { Badge, Empty, ErrorBox, Loading } from '../components';
import { useAsync, defaultRange } from '../hooks';

export default function Alerts() {
  const init = defaultRange();
  const [from, setFrom] = useState(init.from);
  const [to, setTo] = useState(init.to);
  const [decision, setDecision] = useState('');
  const [subject, setSubject] = useState('');
  const [includeTest, setIncludeTest] = useState(true);
  const [page, setPage] = useState(0);
  const [query, setQuery] = useState(0);

  const { data, error, loading } = useAsync(
    () => api.alerts({ from, to, decision: decision || undefined, subject: subject || undefined,
                       include_test: includeTest, page, size: 50 }),
    [query, page],
  );

  return (
    <>
      <h2>告警</h2>
      <p className="lede">
        必须带时间范围且不超过 90 天 —— 告警表按天分区,不给范围就是全表扫描。
      </p>

      <div className="filters">
        <label>起(UTC)<input value={from} onChange={(e) => setFrom(e.target.value)} size={22} /></label>
        <label>止(UTC)<input value={to} onChange={(e) => setTo(e.target.value)} size={22} /></label>
        <label>
          处置
          <select value={decision} onChange={(e) => setDecision(e.target.value)}>
            <option value="">全部</option><option value="reject">reject</option>
            <option value="review">review</option><option value="accept">accept</option>
          </select>
        </label>
        <label>主体<input value={subject} onChange={(e) => setSubject(e.target.value)} placeholder="精确匹配" /></label>
        <label>
          含 test
          <input type="checkbox" checked={includeTest} onChange={(e) => setIncludeTest(e.target.checked)} />
        </label>
        <button onClick={() => { setPage(0); setQuery((q) => q + 1); }}>查询</button>
      </div>

      {subject && (
        <p className="note">
          按主体精确查询会单独记入审计 —— 那是「查某个人」,与浏览列表不是一回事。
        </p>
      )}

      <ErrorBox error={error} />
      {loading ? <Loading /> : !data ? null : data.items.length === 0 ? (
        <Empty>
          这个范围内没有告警。<br />
          如果是刚接入,先按[接入指南]的四步倒推:事件进得来吗?脱敏生效了吗?
          引擎在算吗?再怀疑策略 —— 内置阈值来自 1.x 当年某个站点,多半需要按你的流量校准。
        </Empty>
      ) : (
        <>
          {data.subject_masked && (
            <p className="note">
              你的角色是 VIEWER,主体值已掩码显示。需要看原值请用 OPERATOR 或 ADMIN 账号。
            </p>
          )}
          <div className="panel">
            <table>
              <thead>
                <tr><th>时间</th><th>主体</th><th>策略</th><th>处置</th><th>判定依据</th></tr>
              </thead>
              <tbody>
                {data.items.map((a, i) => (
                  <tr key={i}>
                    <td className="mono">{a.notice_time}</td>
                    <td className="mono">{a.check_type}={a.subject_key}</td>
                    <td>
                      {a.strategy_name}
                      {a.is_test === 1 && <> <Badge kind="test">test</Badge></>}
                    </td>
                    <td><Badge kind={a.decision}>{a.decision}</Badge></td>
                    <td className="mono">
                      {Object.entries(a.variable_values ?? {}).map(([k, v]) => (
                        <div key={k}>{k}: {String(v)}</div>
                      ))}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="filters">
            <span className="note">共 {data.total} 条,第 {data.page + 1} 页</span>
            <button className="ghost" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>上一页</button>
            <button className="ghost" disabled={data.items.length < data.size} onClick={() => setPage((p) => p + 1)}>下一页</button>
          </div>
        </>
      )}
    </>
  );
}
