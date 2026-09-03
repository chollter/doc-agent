/** 时间格式化：历史列表与详情统一展示。 */
export function formatTime(iso: string | null | undefined): string {
  if (!iso) return '-';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

/** 运行耗时（秒，保留1位）。 */
export function durationSeconds(from: string | null, to: string | null | undefined): string {
  if (!from) return '-';
  const end = to ? new Date(to).getTime() : Date.now();
  const sec = (end - new Date(from).getTime()) / 1000;
  return sec >= 60 ? `${Math.floor(sec / 60)}m${Math.round(sec % 60)}s` : `${sec.toFixed(1)}s`;
}
