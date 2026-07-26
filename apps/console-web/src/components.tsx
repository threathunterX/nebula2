import type { ReactNode } from 'react';

export function Badge({ kind, children }: { kind: string; children: ReactNode }) {
  return <span className={`badge ${kind}`}>{children}</span>;
}

export function ErrorBox({ error }: { error: unknown }) {
  if (!error) return null;
  return <div className="err">{error instanceof Error ? error.message : String(error)}</div>;
}

export function Loading({ what = '加载中' }: { what?: string }) {
  return <p className="note">{what}……</p>;
}

/** 空状态要说清楚"为什么空",而不是只显示"暂无数据" —— 后者让人分不清是没数据还是没接通。 */
export function Empty({ children }: { children: ReactNode }) {
  return <p className="note">{children}</p>;
}

export function Panel({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="panel">
      <h3>{title}</h3>
      <div className="body">{children}</div>
    </section>
  );
}
