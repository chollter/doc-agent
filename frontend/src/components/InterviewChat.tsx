import { useCallback, useEffect, useRef, useState } from 'react';
import { Loader2, MessageSquare, Send, X } from 'lucide-react';
import { analysisApi, type MessageDto } from '../api/analysis';

const INTERVIEW_START_PROMPT =
  '请根据这份简历进行模拟面试。你是面试官，请从简历中最值得深挖的技术点开始，提第一个面试问题。每次只问一个问题，等我回答后再追问。';

interface ChatMessage {
  role: 'USER' | 'ASSISTANT';
  content: string;
}

/**
 * 面试模拟对话组件——基于 follow-up API 实现多轮对话。
 * 进入时自动发送开场白，用户回答后 Agent 追问，直到用户点击"结束面试"。
 */
export default function InterviewChat({ runId, onEnd }: {
  runId: string;
  onEnd: () => void;
}) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [waiting, setWaiting] = useState(false);
  const [started, setStarted] = useState(false);
  const pollRef = useRef<number | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  /** 滚动到底部 */
  const scrollToBottom = useCallback(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, []);

  /** 轮询等待 run 完成 */
  const pollUntilDone = useCallback(async () => {
    const poll = () => {
      analysisApi.getRun(runId).then((detail) => {
        if (detail.status === 'COMPLETED') {
          if (pollRef.current) window.clearInterval(pollRef.current);
          pollRef.current = null;
          // 拉取最新消息
          analysisApi.getMessages(runId).then((msgs) => {
            const chatMsgs: ChatMessage[] = msgs.map((m: MessageDto) => ({
              role: m.role as 'USER' | 'ASSISTANT',
              content: m.content,
            }));
            setMessages(chatMsgs);
            setWaiting(false);
            setTimeout(scrollToBottom, 50);
            // 聚焦输入框
            inputRef.current?.focus();
          }).catch(() => {
            setWaiting(false);
          });
        } else if (detail.status === 'FAILED') {
          if (pollRef.current) window.clearInterval(pollRef.current);
          pollRef.current = null;
          setWaiting(false);
        }
      }).catch(() => { /* 轮询失败忽略 */ });
    };

    poll();
    pollRef.current = window.setInterval(poll, 2000);
  }, [runId, scrollToBottom]);

  /** 发送消息 */
  const sendMessage = useCallback(async (text: string) => {
    if (!text.trim() || waiting) return;
    setWaiting(true);
    setInput('');
    try {
      await analysisApi.followUp(runId, text.trim());
      pollUntilDone();
    } catch {
      setWaiting(false);
    }
  }, [runId, waiting, pollUntilDone]);

  /** 首次挂载：自动发送开场白 */
  useEffect(() => {
    if (!started) {
      setStarted(true);
      sendMessage(INTERVIEW_START_PROMPT);
    }
    return () => {
      if (pollRef.current) window.clearInterval(pollRef.current);
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** 提交用户回答 */
  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!input.trim() || waiting) return;
    sendMessage(input);
  };

  return (
    <div className="flex flex-col rounded-2xl border border-sky-200 bg-white shadow-sm" style={{ maxHeight: '500px' }}>
      {/* 头部 */}
      <div className="flex items-center justify-between border-b border-sky-100 px-4 py-3">
        <div className="flex items-center gap-2">
          <MessageSquare size={16} className="text-sky-500" />
          <span className="text-sm font-semibold text-slate-700">面试模拟</span>
          {waiting && <Loader2 size={14} className="animate-spin text-sky-400" />}
        </div>
        <button
          type="button"
          onClick={onEnd}
          className="flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-slate-500 hover:bg-slate-100"
        >
          <X size={14} />
          结束面试
        </button>
      </div>

      {/* 消息区 */}
      <div ref={scrollRef} className="flex-1 space-y-3 overflow-y-auto p-4" style={{ minHeight: '200px' }}>
        {messages.length === 0 && waiting && (
          <div className="flex items-center justify-center py-8 text-sm text-slate-400">
            <Loader2 size={16} className="mr-2 animate-spin" />
            面试官正在准备问题...
          </div>
        )}
        {messages.map((msg, i) => (
          <div key={i} className={`flex ${msg.role === 'USER' ? 'justify-end' : 'justify-start'}`}>
            <div
              className={`max-w-[85%] rounded-2xl px-4 py-2.5 text-sm leading-6 whitespace-pre-wrap ${
                msg.role === 'USER'
                  ? 'bg-sky-600 text-white'
                  : 'bg-slate-100 text-slate-700'
              }`}
            >
              {msg.content}
            </div>
          </div>
        ))}
        {waiting && messages.length > 0 && messages[messages.length - 1]?.role === 'USER' && (
          <div className="flex justify-start">
            <div className="rounded-2xl bg-slate-100 px-4 py-2.5 text-sm text-slate-400">
              面试官思考中...
            </div>
          </div>
        )}
      </div>

      {/* 输入区 */}
      <form onSubmit={handleSubmit} className="border-t border-sky-100 p-3">
        <div className="flex gap-2">
          <input
            ref={inputRef}
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            disabled={waiting}
            placeholder={waiting ? '等待面试官...' : '输入你的回答...'}
            className="flex-1 rounded-xl border border-slate-200 px-3 py-2 text-sm outline-none focus:border-sky-400 disabled:bg-slate-50"
          />
          <button
            type="submit"
            disabled={!input.trim() || waiting}
            className="flex items-center gap-1 rounded-xl bg-sky-600 px-3 py-2 text-sm font-medium text-white transition-colors hover:bg-sky-700 disabled:cursor-not-allowed disabled:bg-slate-300"
          >
            <Send size={14} />
          </button>
        </div>
      </form>
    </div>
  );
}
