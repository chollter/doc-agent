import { Link, Outlet, useLocation } from 'react-router-dom';
import { motion } from 'framer-motion';
import { FileText, Sparkles } from 'lucide-react';

export default function Layout() {
  const location = useLocation();
  return (
    <div className="flex min-h-screen bg-gradient-to-br from-slate-50 to-indigo-50">
      <aside className="fixed left-0 top-0 z-50 flex h-screen w-64 flex-col border-r border-slate-100 bg-white">
        <div className="border-b border-slate-100 p-6">
          <Link to="/summary" className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-primary-500 to-primary-600 text-white shadow-lg shadow-primary-500/30">
              <Sparkles className="h-5 w-5" />
            </div>
            <div>
              <span className="block text-lg font-bold tracking-tight text-slate-800">Agent Workspace</span>
              <span className="text-xs text-slate-400">文档分析工作台</span>
            </div>
          </Link>
        </div>
        <nav className="flex-1 p-4">
          <Link to="/summary" className={`flex items-center gap-3 rounded-xl px-3 py-3 ${location.pathname === '/summary' ? 'bg-primary-50 text-primary-600' : 'text-slate-600 hover:bg-slate-50'}`}>
            <div className="rounded-lg bg-primary-100 p-2 text-primary-600"><FileText className="h-5 w-5" /></div>
            <div><span className="block text-sm font-semibold">资料总结</span><span className="text-xs text-slate-400">上传资料并生成报告</span></div>
          </Link>
        </nav>
        <div className="border-t border-slate-100 p-4"><div className="rounded-xl bg-gradient-to-r from-primary-50 to-indigo-50 px-3 py-2"><p className="text-xs font-medium text-primary-600">Document Summary Skill</p><p className="mt-0.5 text-xs text-slate-400">Agent Workspace v0.1</p></div></div>
      </aside>
      <main className="ml-64 min-h-screen flex-1 overflow-y-auto p-10">
        <motion.div key={location.pathname} initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.3 }}><Outlet /></motion.div>
      </main>
    </div>
  );
}
