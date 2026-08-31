import { NavLink, Outlet } from 'react-router-dom';
import { FileSearch, History, Sparkles, GitCompareArrows } from 'lucide-react';

const navItems = [
  { to: '/', label: '分析工作台', icon: FileSearch },
  { to: '/history', label: '历史记录', icon: History },
  { to: '/calibration', label: '校准工作台', icon: GitCompareArrows },
];

export default function Layout() {
  return (
    <div className="flex min-h-screen bg-slate-50">
      <aside className="flex w-60 shrink-0 flex-col border-r border-slate-200 bg-white">
        <div className="flex items-center gap-2 px-5 py-5">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-indigo-600 text-white">
            <Sparkles size={18} />
          </div>
          <div>
            <div className="text-base font-bold text-slate-800">DocAgent</div>
            <div className="text-xs text-slate-400">文档分析 Agent</div>
          </div>
        </div>
        <nav className="flex flex-col gap-1 px-3">
          {navItems.map(({ to, label, icon: Icon }) => (
            <NavLink
              key={to}
              to={to}
              end={to === '/'}
              className={({ isActive }) =>
                `flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm transition-colors ${
                  isActive
                    ? 'bg-indigo-50 font-semibold text-indigo-700'
                    : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
                }`
              }
            >
              <Icon size={17} />
              {label}
            </NavLink>
          ))}
        </nav>
        <div className="mt-auto px-5 py-4 text-xs leading-5 text-slate-400">
          ReAct 工具循环 · 全链路 Trace
          <br />
          三级降级 · 引用可溯源
        </div>
      </aside>
      <main className="min-w-0 flex-1">
        <Outlet />
      </main>
    </div>
  );
}
