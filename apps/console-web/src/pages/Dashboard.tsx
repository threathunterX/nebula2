import { api } from '../api';
import { ErrorBox, Loading, Panel, Empty } from '../components';
import { useAsync, defaultRange } from '../hooks';

export default function Dashboard() {
  const stats = useAsync(() => api.stats(), []);
  const range = defaultRange();
  const trend = useAsync(() => api.trend({ ...range, include_test: true }), []);

  return (
    <>
      <h2>概览</h2>
      <p className="lede">内置风控资产与最近 24 小时的告警趋势。</p>
      <ErrorBox error={stats.error} />

      {stats.loading ? <Loading /> : stats.data && (
        <div className="cards">
          <Card n={stats.data.events} l="事件模型" />
          <Card n={stats.data.variables} l="统计变量" />
          <Card n={stats.data.strategies} l="策略模板" />
          <Card n={stats.data.tags} l="风险标签" />
          <Card n={stats.data.strategies_requiring_config} l="需配置才生效" warn />
          <Card n={stats.data.pii_variables} l="含个人标识的变量" />
          <Card n={stats.data.metadata_version} l="元数据版本" />
        </div>
      )}

      <Panel title="告警趋势(最近 24 小时,含 test 状态策略)">
        <ErrorBox error={trend.error} />
        {trend.loading ? <Loading /> : <Trend buckets={trend.data?.buckets ?? []} />}
      </Panel>
    </>
  );
}

function Card({ n, l, warn }: { n: number; l: string; warn?: boolean }) {
  return (
    <div className="card">
      <div className="n" style={warn && n > 0 ? { color: 'var(--danger)' } : undefined}>{n}</div>
      <div className="l">{l}</div>
    </div>
  );
}

function Trend({ buckets }: { buckets: { hour: string; notices: string | number }[] }) {
  if (buckets.length === 0) {
    return (
      <Empty>
        这段时间没有告警。<br />
        接入初期为 0 是正常的 —— 内置策略以 <code>test</code> 状态分发,阈值来自
        1.x 当年某个站点的经验值,需要按你自己的流量校准。先确认事件进得来、引擎在算,
        再怀疑策略。
      </Empty>
    );
  }
  // 按小时合并(同一小时可能有多条策略/场景的分桶)
  const byHour = new Map<string, number>();
  for (const b of buckets) {
    byHour.set(b.hour, (byHour.get(b.hour) ?? 0) + Number(b.notices));
  }
  const rows = [...byHour.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  const max = Math.max(...rows.map((r) => r[1]), 1);
  return (
    <>
      <div className="bars">
        {rows.map(([h, n]) => (
          <div key={h} className="b" style={{ height: `${(n / max) * 100}%` }} title={`${h} —— ${n} 条`} />
        ))}
      </div>
      <p className="note" style={{ marginTop: 8 }}>
        {rows.length} 个小时分桶,峰值 {max} 条。数据来自物化视图维护的 <code>notices_hourly</code>,
        不扫明细表。
      </p>
    </>
  );
}
