import { useEffect, useState } from 'react';

/** 最小的数据获取 hook。不引入 react-query —— 这个界面的请求形态非常简单。 */
export function useAsync<T>(fn: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError(null);
    fn()
      .then((d) => alive && setData(d))
      .catch((e) => alive && setError(e))
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);

  return { data, error, loading, reload: () => setTick((t) => t + 1) };
}

/** 默认时间范围:最近 24 小时。告警接口强制要求范围且不超过 90 天。 */
export function defaultRange() {
  const to = new Date();
  const from = new Date(to.getTime() - 24 * 3600 * 1000);
  return { from: iso(from), to: iso(to) };
}

export function iso(d: Date) {
  return d.toISOString().slice(0, 19) + 'Z';
}
