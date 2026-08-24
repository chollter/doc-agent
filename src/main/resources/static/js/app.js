(function () {
  'use strict';

  const API = '/api';
  const SESSION_ID = 'sess-web-' + Date.now();
  const USER_ID = 'u-1001';
  let currentRunId = null;
  let currentPendingId = null;
  let currentAnalysis = null;
  let analyzingTimer = null;
  let lastEvalReport = null;

  const $ = (id) => document.getElementById(id);

  function on(id, eventName, handler) {
    const el = $(id);
    if (el) el.addEventListener(eventName, handler);
  }

  const submitForm = $('submitForm');
  const summaryForm = $('summaryForm');
  const supplementSection = $('supplementSection');
  const supplementBtn = $('supplementBtn');
  const clearBtn = $('clearBtn');
  const logFilesInput = $('logFiles');
  const knowledgeForm = $('knowledgeForm');
  const refreshKnowledgeBtn = $('refreshKnowledgeBtn');
  const confirmBtn = $('confirmBtn');
  const rejectBtn = $('rejectBtn');
  const auditBtn = $('auditBtn');
  const llmStatsBtn = $('llmStatsBtn');

  const samples = {
    complete: {
      ticketNo: 'INC-2026-0702-001',
      source: 'Web Console',
      environment: '生产',
      createdAt: '2026-07-02 10:00',
      content: '生产环境 payment-service Pod OOMKilled，从上午 10 点开始多个用户支付失败，内存使用从 200Mi 飙升到 512Mi 后被 kill，错误为 java.lang.OutOfMemoryError: Java heap space'
    },
    ruoyi: {
      ticketNo: 'INC-2026-0702-004',
      source: 'GitHub Issue',
      environment: 'Docker',
      createdAt: '2026-07-02 10:18',
      content: '生产环境 ruoyi-ai 后端服务在 Docker 中运行，管理端访问后端接口出现 500/登录失败，请优先查看 ruoyi-ai.log 中最近的 ERROR 日志，结合 MySQL、Redis、Weaviate、MinIO 依赖判断根因。'
    },
    incomplete: {
      ticketNo: 'INC-2026-0702-002',
      source: 'IM 群反馈',
      environment: '未知',
      createdAt: '2026-07-02 10:25',
      content: '接口报错了'
    },
    consult: {
      ticketNo: 'REQ-2026-0702-003',
      source: 'Web Console',
      environment: '生产',
      createdAt: '2026-07-02 10:40',
      content: '咨询：库存同步任务如何配置重试次数和告警阈值？希望确认是否有现成 SOP，避免同步失败后重复扣减库存。'
    }
  };

  function showToast(msg, isError) {
    const toast = $('toast');
    toast.textContent = msg;
    toast.classList.toggle('error', !!isError);
    toast.classList.remove('hidden');
    setTimeout(() => toast.classList.add('hidden'), 3200);
  }

  async function apiFetch(url, options) {
    const res = await fetch(url, options);
    const body = await res.json().catch(() => null);
    if (!res.ok) {
      const msg = body?.message || body?.error || `请求失败 (${res.status})`;
      throw new Error(msg);
    }
    return body;
  }

  function setLoading(loading) {
    const submitBtn = $('submitBtn');
    if (submitBtn) submitBtn.disabled = loading;
    if (supplementBtn) supplementBtn.disabled = loading;
  }

  function activateTab(name) {
    document.querySelectorAll('.tab').forEach((tab) => {
      tab.classList.toggle('active', tab.dataset.tab === name);
    });
    document.querySelectorAll('.tab-page').forEach((page) => {
      page.classList.toggle('active', page.id === 'tab-' + name);
    });
  }

  function selectSample(kind) {
    const sample = samples[kind];
    if (!sample) return;
    const ticketNo = $('ticketNo');
    const ticketSource = $('ticketSource');
    const ticketEnv = $('ticketEnv');
    const ticketCreatedAt = $('ticketCreatedAt');
    const content = $('content');
    if (ticketNo) ticketNo.value = sample.ticketNo;
    if (ticketSource) ticketSource.value = sample.source;
    if (ticketEnv) ticketEnv.value = sample.environment;
    if (ticketCreatedAt) ticketCreatedAt.value = sample.createdAt;
    if (content) content.value = sample.content;
    document.querySelectorAll('.ticket-item[data-sample]').forEach((item) => {
      item.classList.toggle('active', item.dataset.sample === kind);
    });
    resetResultArea();
  }

  function renderLogFileList() {
    const box = $('logFileList');
    if (!box || !logFilesInput) return;
    const files = Array.from(logFilesInput.files || []);
    if (!files.length) {
      box.innerHTML = '<span>未选择日志文件</span>';
      return;
    }
    box.innerHTML = files.map((file) => {
      const size = file.size >= 1024 ? (file.size / 1024).toFixed(1) + 'KB' : file.size + 'B';
      return '<span class="log-file-chip">' + esc(file.name) + ' · ' + size + '</span>';
    }).join('');
  }

  async function uploadLogFilesIfNeeded() {
    const files = Array.from(logFilesInput.files || []);
    if (!files.length) return null;
    const formData = new FormData();
    files.forEach((file) => formData.append('files', file));
    return apiFetch(`${API}/log-files`, { method: 'POST', body: formData });
  }

  function resetResultArea() {
    currentRunId = null;
    currentPendingId = null;
    currentAnalysis = null;
    ['statusBar', 'messageBox', 'questionsBox', 'executionBox', 'analysisBox', 'opsSummaryBox',
      'opsActions', 'auditBox', 'llmStatsBox', 'confirmBox'].forEach((id) => {
      const el = $(id);
      if (el) el.classList.add('hidden');
    });
    if (supplementSection) supplementSection.classList.add('hidden');
    const emptyState = $('emptyState');
    if (emptyState) {
      emptyState.innerHTML = '<strong>等待分析</strong><p>选择左侧工单或输入新的故障描述。</p>';
      emptyState.classList.remove('hidden');
    }
    const aiBadge = $('aiBadge');
    if (aiBadge) aiBadge.classList.add('hidden');
  }

  function showAnalyzing() {
    stopAnalyzing();
    $('emptyState').innerHTML = '<div class="analyzing-state">' +
      '<div class="analyzing-spinner"></div>' +
      '<p>Agent 正在分析工单</p>' +
      '<p class="analyzing-time" id="analyzingTime">已用时 0.0s</p>' +
      '</div>';
    $('emptyState').classList.remove('hidden');
    ['statusBar', 'messageBox', 'questionsBox', 'executionBox', 'analysisBox', 'opsSummaryBox',
      'opsActions', 'auditBox', 'llmStatsBox', 'confirmBox'].forEach((id) => $(id).classList.add('hidden'));

    const startTime = Date.now();
    analyzingTimer = setInterval(() => {
      const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
      const el = $('analyzingTime');
      if (el) el.textContent = '已用时 ' + elapsed + 's';
    }, 100);
  }

  function stopAnalyzing() {
    if (analyzingTimer) {
      clearInterval(analyzingTimer);
      analyzingTimer = null;
    }
  }

  function renderResponse(data, latency) {
    currentRunId = data.runId;
    currentAnalysis = data.analysis || null;
    $('emptyState').classList.add('hidden');
    $('statusBar').classList.remove('hidden');
    $('opsActions').classList.remove('hidden');
    $('opsSummaryBox').classList.remove('hidden');
    $('opsSummaryBox').innerHTML = renderOpsSummary(null, null);
    $('executionBox').classList.remove('hidden');
    $('executionBox').innerHTML = renderExecutionDetails(data.analysis, null, null);
    $('auditBox').classList.add('hidden');
    $('llmStatsBox').classList.add('hidden');
    $('runIdDisplay').textContent = data.runId;
    $('statusBadge').textContent = data.status;
    $('replyBadge').textContent = data.replyType;

    $('aiBadge').classList.toggle('hidden', !data.aiGenerated);
    renderLatency(latency);
    renderMessage(data.message);
    renderQuestions(data.questions);
    renderAnalysisBox(data.analysis, data.aiGenerated);

    supplementSection.classList.toggle('hidden', data.status !== 'WAIT_USER_INPUT');
    renderHumanConfirm(data);
    loadOpsSummary();
  }

  function renderLatency(latency) {
    let latencyEl = $('latencyTag');
    if (!latencyEl) {
      latencyEl = document.createElement('span');
      latencyEl.id = 'latencyTag';
      latencyEl.className = 'latency-tag';
      $('statusBar').appendChild(latencyEl);
    }
    latencyEl.textContent = latency ? latency + 's' : '';
  }

  function renderMessage(message) {
    const box = $('messageBox');
    if (message) {
      box.textContent = message;
      box.classList.remove('hidden');
    } else {
      box.classList.add('hidden');
    }
  }

  function renderQuestions(questions) {
    const box = $('questionsBox');
    if (questions && questions.length > 0) {
      box.innerHTML = '<h4>需要补充的信息</h4><ol>' +
        questions.map((q) => '<li>' + esc(q) + '</li>').join('') + '</ol>';
      box.classList.remove('hidden');
    } else {
      box.classList.add('hidden');
    }
  }

  function renderAnalysisBox(analysis, aiGenerated) {
    const box = $('analysisBox');
    if (analysis) {
      box.innerHTML = renderAnalysis(analysis, aiGenerated);
      box.classList.remove('hidden');
    } else {
      box.classList.add('hidden');
    }
  }

  function renderHumanConfirm(data) {
    const confirmBox = $('confirmBox');
    if (data.status === 'WAIT_HUMAN_CONFIRM' && data.analysis && data.analysis.humanConfirm) {
      $('confirmReason').textContent = '需要人工确认：' + (data.analysis.humanConfirm.reason || '高优先级工单');
      confirmBox.classList.remove('hidden');
      loadPendingForCurrentRun();
    } else {
      confirmBox.classList.add('hidden');
      currentPendingId = null;
    }
  }

  function renderAnalysis(a, aiGenerated) {
    const t = a.ticket || {};
    const r = a.routing || {};
    const s = a.suggestion || {};
    const h = a.humanConfirm || {};
    const rc = a.rootCause || {};
    const pri = t.priority || '—';
    let html = '';

    html += '<div class="artifact-title">执行产出</div>';
    html += '<div class="analysis-card ticket-summary-card">';
    html += '<div class="card-heading"><h3>结构化工单</h3>';
    if (pri !== '—') html += '<span class="priority priority-' + esc(pri) + '">' + esc(pri) + '</span>';
    html += '</div><dl>';
    if (t.summary) html += row('摘要', t.summary);
    if (t.issueType) html += row('类型', t.issueType);
    if (t.affectedSystem) html += row('系统', t.affectedSystem);
    if (t.affectedModule) html += row('模块', t.affectedModule);
    if (t.impactScope) html += row('影响', t.impactScope);
    html += '</dl></div>';

    if (rc.hypothesis) {
      html += '<div class="analysis-card"><div class="card-heading"><h3>根因假设</h3>';
      html += '<span class="confidence">置信度 ' + (rc.confidence != null ? (rc.confidence * 100).toFixed(0) + '%' : '—') + '</span></div>';
      html += '<p class="root-cause">' + esc(rc.hypothesis) + '</p>';
      if (rc.evidence && rc.evidence.length) {
        html += '<p class="section-label">证据链</p><ul>';
        rc.evidence.forEach((e) => { html += '<li>' + esc(e) + '</li>'; });
        html += '</ul>';
      }
      if (rc.unknowns && rc.unknowns.length) {
        html += '<p class="section-label">待确认项</p><ul>';
        rc.unknowns.forEach((u) => { html += '<li>' + esc(u) + '</li>'; });
        html += '</ul>';
      }
      html += '</div>';
    }

    if (r.primaryTeam) {
      html += '<div class="analysis-card"><h3>路由决策</h3><dl>';
      html += row('主责团队', r.primaryTeam);
      if (r.backupTeams && r.backupTeams.length) html += row('协同团队', r.backupTeams.join('、'));
      if (r.routingReason) html += row('路由依据', r.routingReason);
      html += '</dl></div>';
    }

    if ((s.possibleCauses && s.possibleCauses.length) || (s.actions && s.actions.length)) {
      html += '<div class="analysis-card"><div class="card-heading"><h3>处置建议</h3>';
      if (aiGenerated) html += '<span class="ai-badge">AI</span>';
      html += '</div>';
      if (s.possibleCauses && s.possibleCauses.length) {
        html += '<p class="section-label">可能原因</p><ul>';
        s.possibleCauses.forEach((c) => { html += '<li>' + esc(c) + '</li>'; });
        html += '</ul>';
      }
      if (s.runbookSteps && s.runbookSteps.length) {
        html += '<p class="section-label">处理步骤</p><ol>';
        s.runbookSteps.forEach((c) => { html += '<li>' + esc(c) + '</li>'; });
        html += '</ol>';
      } else if (s.actions && s.actions.length) {
        html += '<p class="section-label">建议动作</p><ul>';
        s.actions.forEach((c) => { html += '<li>' + esc(c) + '</li>'; });
        html += '</ul>';
      }
      if (s.sources && s.sources.length) {
        html += '<p class="section-label">引用来源</p><ul class="source-list">';
        s.sources.forEach((c) => { html += '<li>' + esc(c) + '</li>'; });
        html += '</ul>';
      }
      html += '</div>';
    }

    if (h.required) {
      html += '<div class="human-alert">需要人工确认：' + esc(h.reason || '高优先级工单') + '</div>';
    }
    return html;
  }

  function row(label, value) {
    return '<dt>' + esc(label) + '</dt><dd>' + esc(value) + '</dd>';
  }

  function esc(str) {
    if (str == null) return '';
    const d = document.createElement('div');
    d.textContent = String(str);
    return d.innerHTML;
  }

  async function submitSummary(e) {
    e.preventDefault();
    const file = $('summaryFile')?.files?.[0];
    const result = $('summaryResult');
    if (!file) { showToast('请选择资料文件', true); return; }
    const button = $('summaryBtn');
    button.disabled = true;
    result.classList.remove('hidden');
    result.innerHTML = '<div class="analyzing-state"><div class="analyzing-spinner"></div><p>DocumentSummary Agent 正在执行</p></div>';
    try {
      const body = new FormData();
      body.append('file', file);
      body.append('instruction', $('summaryInstruction')?.value || '');
      const data = await apiFetch(`${API}/summary/runs`, { method: 'POST', body });
      result.innerHTML = renderSummaryResult(data);
      showToast('资料总结完成 · ' + data.executionMode);
    } catch (err) {
      result.innerHTML = '<div class="message-box">' + esc(err.message) + '</div>';
      showToast(err.message, true);
    } finally { button.disabled = false; }
  }

  function renderSummaryResult(data) {
    const list = (items) => (items || []).map((item) => '<li>' + esc(item) + '</li>').join('');
    return '<div class="analysis-card"><div class="card-heading"><h3>总结结果</h3><span class="soft-badge">' + esc(data.executionMode) + '</span></div>' +
      '<p class="root-cause">' + esc(data.summary) + '</p>' +
      '<p class="section-label">关键内容</p><ul>' + list(data.keyPoints) + '</ul>' +
      '<p class="section-label">风险</p><ul>' + list(data.risks) + '</ul>' +
      '<p class="section-label">待办</p><ul>' + list(data.todos) + '</ul>' +
      '<p class="section-label">引用证据</p><ul class="source-list">' + list(data.citations) + '</ul></div>' +
      '<div class="execution-box"><div class="runtime-title">Agent 执行过程</div>' +
      (data.steps || []).map((step) => '<div class="lane-item"><div class="lane-node">' + esc(step.name) + '</div><div class="lane-meta"><span>' + esc(step.status) + '</span><span>' + esc(step.detail) + '</span></div></div>').join('') + '</div>';
  }

  async function submitTicket(e) {
    e.preventDefault();
    const content = $('content').value.trim();
    if (!content) {
      showToast('请输入工单描述', true);
      return;
    }
    setLoading(true);
    const startTime = Date.now();
    showAnalyzing();

    try {
      const logUpload = await uploadLogFilesIfNeeded();
      const ticketNo = $('ticketNo').value.trim();
      const ticketSource = $('ticketSource').value.trim();
      const ticketEnv = $('ticketEnv').value.trim();
      const ticketCreatedAt = $('ticketCreatedAt').value.trim();
      const data = await apiFetch(`${API}/tickets/agent-runs`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          sessionId: SESSION_ID,
          userId: USER_ID,
          title: ticketNo,
          content,
          source: ticketSource || 'WEB',
          metadata: {
            ticketNo,
            source: ticketSource,
            environment: ticketEnv,
            createdAt: ticketCreatedAt
          }
        })
      });
      const latency = ((Date.now() - startTime) / 1000).toFixed(1);
      stopAnalyzing();
      renderResponse(data, latency);
      const uploadMsg = logUpload && logUpload.uploaded ? '，日志 ' + logUpload.uploaded + ' 个已上传' : '';
      showToast((data.status === 'WAIT_USER_INPUT' ? '需要补充信息' : '分析完成') + uploadMsg + ' · ' + latency + 's');
    } catch (err) {
      stopAnalyzing();
      $('emptyState').innerHTML = '<strong>分析失败</strong><p>' + esc(err.message) + '</p>';
      showToast(err.message, true);
    } finally {
      setLoading(false);
    }
  }

  async function supplementMessage() {
    if (!currentRunId) return;
    const content = $('supplementContent').value.trim();
    if (!content) {
      showToast('请输入补充内容', true);
      return;
    }
    setLoading(true);
    const startTime = Date.now();
    showAnalyzing();
    try {
      const data = await apiFetch(`${API}/tickets/agent-runs/${currentRunId}/messages`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content })
      });
      const latency = ((Date.now() - startTime) / 1000).toFixed(1);
      stopAnalyzing();
      renderResponse(data, latency);
      $('supplementContent').value = '';
      showToast('补充完成 · ' + latency + 's');
    } catch (err) {
      stopAnalyzing();
      showToast(err.message, true);
    } finally {
      setLoading(false);
    }
  }

  async function loadPendingForCurrentRun() {
    if (!currentRunId) return;
    try {
      const items = await apiFetch(`${API}/human/pending`);
      const match = items && items.find((p) => p.runId === currentRunId);
      currentPendingId = match ? match.id : null;
    } catch (_) {
      currentPendingId = null;
    }
  }

  async function handleConfirm(confirm) {
    if (!currentPendingId) {
      showToast('未找到待确认项', true);
      return;
    }
    const url = confirm
      ? `${API}/human/pending/${currentPendingId}/confirm`
      : `${API}/human/pending/${currentPendingId}/reject`;
    try {
      const run = await apiFetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ confirmedBy: 'operator-web' })
      });
      $('statusBadge').textContent = run.status;
      $('confirmBox').classList.add('hidden');
      showToast(confirm ? '已确认分派' : '已驳回建议');
    } catch (err) {
      showToast(err.message, true);
    }
  }

  async function loadOpsSummary() {
    if (!currentRunId) return;
    const box = $('opsSummaryBox');
    const executionBox = $('executionBox');
    try {
      const [steps, stats] = await Promise.all([
        apiFetch(`${API}/audit/agent-runs/${currentRunId}`),
        apiFetch(`${API}/audit/agent-runs/${currentRunId}/llm-stats`)
      ]);
      box.innerHTML = renderOpsSummary(steps || [], stats || {});
      executionBox.innerHTML = renderExecutionDetails(currentAnalysis, steps || [], stats || {});
    } catch (err) {
      box.innerHTML = '<p class="hint">运行观测加载失败：' + esc(err.message) + '</p>';
      executionBox.innerHTML = renderExecutionDetails(currentAnalysis, [], {});
    }
  }

  function renderExecutionDetails(analysis, steps, stats) {
    if (!analysis) {
      return '<div class="runtime-title">Agent Runtime</div><p class="hint">等待 Agent 形成执行实例。</p>';
    }
    if (!steps && !stats) {
      return '<div class="runtime-title">Agent Runtime</div><p class="hint">正在加载编排链路、约束策略和运行观测…</p>';
    }
    const stepList = steps || [];
    return '<div class="runtime-board">' +
      renderRuntimeHeader(analysis, stepList, stats || {}) +
      '<div class="runtime-grid">' +
      '<section class="runtime-section orchestration-section">' + renderOrchestration(stepList) + '</section>' +
      '<section class="runtime-section">' + renderConstraintPanel(analysis, stepList, stats || {}) + '</section>' +
      '</div>' +
      '<div class="runtime-grid runtime-grid-bottom">' +
      '<section class="runtime-section">' + renderEvidencePanel(analysis) + '</section>' +
      '<section class="runtime-section">' + renderObservabilityPanel(stepList, stats || {}) + '</section>' +
      '</div>' +
      renderRagSignalPanel(analysis, stepList) +
      '</div>';
  }

  function renderRuntimeHeader(analysis, steps, stats) {
    const t = analysis.ticket || {};
    const h = analysis.humanConfirm || {};
    const totalCalls = stats.totalCalls || 0;
    const failed = stats.failedCalls || 0;
    const successRate = totalCalls > 0 ? Math.round(((stats.successCalls || 0) / totalCalls) * 100) + '%' : '—';
    return '<div class="runtime-header">' +
      runtimeMetric('Run Status', $('statusBadge').textContent || '—', $('replyBadge').textContent || '—') +
      runtimeMetric('Priority Gate', t.priority || '—', h.required ? 'HITL required' : 'Auto review') +
      runtimeMetric('LLM Calls', totalCalls || '—', 'success ' + successRate) +
      runtimeMetric('Fallback', stats.fallbackSignals || 0, failed + ' failed calls') +
      '</div>';
  }

  function runtimeMetric(label, value, meta) {
    return '<div class="runtime-metric"><span>' + esc(label) + '</span><strong>' + esc(value) + '</strong><em>' + esc(meta) + '</em></div>';
  }

  function renderOrchestration(steps) {
    if (!steps.length) return '<div class="runtime-title">编排链路</div><p class="hint">暂无审计步骤。</p>';
    const visible = steps.slice(0, 10);
    let html = '<div class="runtime-title">编排链路</div><div class="orchestration-lanes">';
    visible.forEach((s) => {
      const label = stepLabel(s.stepName);
      const tags = [];
      if (s.llmUsed) tags.push('<span class="mini-tag mini-tag-ai">LLM</span>');
      if (s.toolUsed) tags.push('<span class="mini-tag">' + esc(s.toolUsed) + '</span>');
      if (s.costMs > 0) tags.push('<span class="mini-tag">' + formatMs(s.costMs) + '</span>');
      html += '<div class="lane-item">' +
        '<div class="lane-node">' + esc(label) + '</div>' +
        '<div class="lane-meta"><span>' + esc(s.status) + '</span>' + tags.join('') + '</div>' +
        '</div>';
    });
    if (steps.length > visible.length) {
      html += '<div class="timeline-more">还有 ' + (steps.length - visible.length) + ' 个步骤，可在审计链路查看。</div>';
    }
    return html + '</div>';
  }

  function stepLabel(name) {
    const map = {
      TICKET_EXTRACT: '结构化抽取',
      INFO_GAP_ANALYSIS: '信息缺口判断',
      AGENT_PLAN: '任务规划',
      KNOWLEDGE_SEARCH: '知识检索',
      TOOL_SELECTION: '工具选择',
      EVIDENCE_COLLECTION: '证据收集',
      ROOT_CAUSE_ANALYSIS: '根因分析',
      PRIORITY_EVALUATION: '优先级评估',
      TEAM_ROUTING: '团队路由',
      SUGGESTION_GENERATION: '处置建议',
      WAIT_HUMAN_CONFIRM: '人工确认',
      FINAL: '流程完成'
    };
    return map[name] || name || '执行步骤';
  }

  function renderEvidencePanel(analysis) {
    const rc = analysis.rootCause || {};
    const s = analysis.suggestion || {};
    const groups = groupEvidence(rc.evidence || []);
    let html = '<div class="runtime-title">证据约束</div><div class="evidence-grid">';
    html += evidenceGroup('日志证据', groups.log);
    html += evidenceGroup('运行线索', groups.metric);
    html += evidenceGroup('知识来源', groups.knowledge);
    html += evidenceGroup('待确认项', rc.unknowns || []);
    html += '</div>';
    if (s.sources && s.sources.length) {
      html += '<div class="source-chips">' + s.sources.map((x) => '<span class="source-chip">' + esc(x) + '</span>').join('') + '</div>';
    }
    return html;
  }

  function groupEvidence(items) {
    const groups = { log: [], metric: [], knowledge: [], other: [] };
    items.forEach((item) => {
      const text = String(item || '');
      const lower = text.toLowerCase();
      if (lower.includes('log') || text.includes('日志') || lower.includes('exception') || lower.includes('error')) groups.log.push(text);
      else if (lower.includes('metric') || text.includes('指标') || text.includes('内存') || lower.includes('memory')) groups.metric.push(text);
      else if (lower.includes('runbook') || lower.includes('sop') || lower.includes('incident') || text.includes('知识') || text.includes('案例') || text.includes('历史')) groups.knowledge.push(text);
      else groups.other.push(text);
    });
    groups.knowledge = groups.knowledge.concat(groups.other);
    return groups;
  }

  function evidenceGroup(title, items) {
    const list = (items || []).slice(0, 3);
    if (!list.length) {
      return '<div class="evidence-card muted"><strong>' + esc(title) + '</strong><p>暂无明确证据</p></div>';
    }
    return '<div class="evidence-card"><strong>' + esc(title) + '</strong><ul>' +
      list.map((x) => '<li>' + esc(x) + '</li>').join('') + '</ul></div>';
  }

  function renderConstraintPanel(analysis, steps, stats) {
    const t = analysis.ticket || {};
    const h = analysis.humanConfirm || {};
    const llmSteps = (steps || []).filter((s) => s.llmUsed).length;
    const toolSteps = (steps || []).filter((s) => s.toolUsed).length;
    const fallback = stats.fallbackSignals || 0;
    const failed = stats.failedCalls || 0;
    return '<div class="runtime-title">约束与控制</div><div class="constraint-stack">' +
      constraintRow('Input Gate', t.summary ? 'PASS' : 'WAIT', t.summary ? '已完成结构化抽取' : '等待信息补全') +
      constraintRow('Source Bound', hasSources(analysis) ? 'ON' : 'LOW', hasSources(analysis) ? '建议绑定知识来源' : '知识来源不足') +
      constraintRow('Tool Boundary', toolSteps > 0 ? 'ACTIVE' : 'IDLE', toolSteps + ' 个工具步骤由后端执行') +
      constraintRow('Human Gate', h.required ? 'BLOCKING' : 'OPEN', h.reason || '未触发人工确认') +
      constraintRow('LLM Budget', llmSteps || stats.totalCalls || 0, '调用记录进入 run 级观测') +
      constraintRow('Fallback', fallback || failed ? 'CHECK' : 'CLEAN', fallback + ' 个降级信号，' + failed + ' 个失败调用') +
      '</div>';
  }

  function hasSources(analysis) {
    const s = analysis.suggestion || {};
    return !!(s.sources && s.sources.length);
  }

  function constraintRow(label, state, meta) {
    return '<div class="constraint-row"><span>' + esc(label) + '</span><strong>' + esc(state) + '</strong><em>' + esc(meta) + '</em></div>';
  }

  function renderObservabilityPanel(steps, stats) {
    const totalCostMs = (steps || []).reduce((sum, s) => sum + (Number(s.costMs) || 0), 0);
    const llmStepCount = (steps || []).filter((s) => s.llmUsed).length;
    const toolStepCount = (steps || []).filter((s) => s.toolUsed).length;
    const slowest = getSlowestCall(stats);
    return '<div class="runtime-title">运行观测</div><div class="observability-grid">' +
      observabilityCell('审计步骤', (steps || []).length || '—') +
      observabilityCell('步骤耗时', formatMs(totalCostMs)) +
      observabilityCell('LLM 步骤', llmStepCount) +
      observabilityCell('工具步骤', toolStepCount) +
      observabilityCell('总调用', stats.totalCalls || 0) +
      observabilityCell('最慢调用', slowest ? slowest.callName : '—') +
      '</div>';
  }

  function observabilityCell(label, value) {
    return '<div class="observability-cell"><span>' + esc(label) + '</span><strong>' + esc(value) + '</strong></div>';
  }

  function renderRagSignalPanel(analysis, steps) {
    const signal = buildRagSignal(analysis, steps || []);
    return '<section class="runtime-section rag-signal-panel">' +
      '<div class="runtime-title">RAG 质量信号</div>' +
      '<div class="rag-signal-grid">' +
      ragSignalCard('检索步骤', signal.searchStep ? 'YES' : 'NO', signal.searchStep ? 'KNOWLEDGE_SEARCH 已记录' : '本次未进入知识检索') +
      ragSignalCard('命中来源', signal.hitCount, signal.sourceTypes.length ? signal.sourceTypes.join(' / ') : '暂无来源') +
      ragSignalCard('引用覆盖', signal.coverage + '%', signal.citedEvidence + ' / ' + signal.totalEvidence + ' 条证据带来源') +
      ragSignalCard('约束状态', signal.boundState, signal.boundReason) +
      '</div>' +
      renderRagSamples(signal.sources) +
      '</section>';
  }

  function buildRagSignal(analysis, steps) {
    const rc = analysis.rootCause || {};
    const s = analysis.suggestion || {};
    const sources = (s.sources || []).filter(Boolean);
    const evidence = (rc.evidence || []).filter(Boolean);
    const sourceTypes = [...new Set(sources.map((x) => String(x).split(':')[0]).filter(Boolean))];
    const citedEvidence = evidence.filter((x) => {
      const text = String(x).toLowerCase();
      return text.includes('runbook') || text.includes('sop') || text.includes('incident') ||
        text.includes('redmine') || text.includes('error_code') || text.includes('案例') ||
        text.includes('知识') || text.includes('历史');
    }).length;
    const totalEvidence = evidence.length || 0;
    const coverage = totalEvidence > 0 ? Math.round((citedEvidence / totalEvidence) * 100) : 0;
    const searchStep = steps.some((x) => x.stepName === 'KNOWLEDGE_SEARCH' || String(x.toolUsed || '').toLowerCase().includes('knowledge'));
    const boundState = sources.length >= 3 ? 'STRONG' : sources.length > 0 ? 'PARTIAL' : 'LOW';
    const boundReason = sources.length >= 3
      ? '多来源约束，适合支撑建议'
      : sources.length > 0
        ? '已有来源，但覆盖仍可增强'
        : '无来源，结果应保守展示';
    return {
      searchStep,
      sources,
      sourceTypes,
      hitCount: sources.length,
      citedEvidence,
      totalEvidence,
      coverage,
      boundState,
      boundReason
    };
  }

  function ragSignalCard(label, value, meta) {
    return '<div class="rag-signal-card"><span>' + esc(label) + '</span><strong>' + esc(value) + '</strong><em>' + esc(meta) + '</em></div>';
  }

  function renderRagSamples(sources) {
    if (!sources || !sources.length) return '';
    return '<div class="rag-source-strip">' +
      sources.slice(0, 6).map((x) => '<span>' + esc(x) + '</span>').join('') +
      '</div>';
  }

  function renderOpsSummary(steps, stats) {
    if (!steps && !stats) {
      return '<div class="ops-title">运行概览</div><p class="hint">正在加载审计与调用统计…</p>';
    }
    const stepList = steps || [];
    const totalCostMs = stepList.reduce((sum, s) => sum + (Number(s.costMs) || 0), 0);
    const llmStepCount = stepList.filter((s) => s.llmUsed).length;
    const toolStepCount = stepList.filter((s) => s.toolUsed).length;
    const totalCalls = stats.totalCalls || 0;
    const successRate = totalCalls > 0 ? Math.round(((stats.successCalls || 0) / totalCalls) * 100) + '%' : '—';
    const slowest = getSlowestCall(stats);

    let html = '<div class="ops-title">运行概览</div>';
    html += '<div class="summary-grid">' +
      summaryCard('审计步骤', stepList.length || '—', '链路可回放') +
      summaryCard('LLM 调用', totalCalls || '—', '成功率 ' + successRate) +
      summaryCard('LLM 耗时', formatMs(stats.totalDurationMs), (stats.failedCalls || 0) + ' 次失败') +
      summaryCard('步骤累计', formatMs(totalCostMs), llmStepCount + ' 个 AI 步骤') +
      summaryCard('工具步骤', toolStepCount || 0, '日志 / 历史 / 知识') +
      summaryCard('降级信号', stats.fallbackSignals || 0, slowest ? '最慢 ' + slowest.callName : '无慢调用') +
      '</div>';
    if (slowest) {
      html += '<div class="ops-note">最慢 LLM 调用：<code>' + esc(slowest.callName) + '</code>，'
        + formatMs(slowest.maxDurationMs || slowest.totalDurationMs) + '。</div>';
    }
    return html;
  }

  function getSlowestCall(stats) {
    const entries = Object.entries(stats?.byCallName || {});
    if (!entries.length) return null;
    return entries
      .map(([callName, item]) => ({ callName, ...item }))
      .sort((a, b) => (b.maxDurationMs || 0) - (a.maxDurationMs || 0))[0];
  }

  function summaryCard(label, value, meta) {
    return '<div class="summary-card"><span>' + esc(label) + '</span><strong>' + esc(value) + '</strong><em>' + esc(meta) + '</em></div>';
  }

  function formatMs(ms) {
    const n = Number(ms) || 0;
    if (n <= 0) return '—';
    if (n >= 1000) return (n / 1000).toFixed(n >= 10000 ? 1 : 2) + 's';
    return n + 'ms';
  }

  async function loadAudit() {
    if (!currentRunId) {
      showToast('请先提交一条工单', true);
      return;
    }
    const box = $('auditBox');
    auditBtn.disabled = true;
    box.classList.toggle('hidden');
    if (box.classList.contains('hidden')) {
      auditBtn.disabled = false;
      return;
    }
    box.innerHTML = '<p class="hint">加载审计链路中…</p>';
    try {
      const steps = await apiFetch(`${API}/audit/agent-runs/${currentRunId}`);
      box.innerHTML = renderAudit(steps || []);
    } catch (err) {
      box.innerHTML = '<p class="eval-fail">' + esc(err.message) + '</p>';
      showToast(err.message, true);
    } finally {
      auditBtn.disabled = false;
    }
  }

  function renderAudit(steps) {
    if (!steps.length) return '<p class="hint">暂无审计步骤</p>';
    let html = '<div class="ops-title">审计链路</div><div class="audit-list">';
    steps.forEach((s, idx) => {
      const tags = [];
      if (s.llmUsed) tags.push('<span class="mini-tag mini-tag-ai">LLM</span>');
      if (s.toolUsed) tags.push('<span class="mini-tag">' + esc(s.toolUsed) + '</span>');
      if (s.costMs != null && s.costMs > 0) tags.push('<span class="mini-tag">' + s.costMs + 'ms</span>');
      html += '<div class="audit-item">' +
        '<div class="audit-main"><span class="audit-index">' + (idx + 1) + '</span>' +
        '<span class="audit-name">' + esc(s.stepName) + '</span>' +
        '<span class="audit-status">' + esc(s.status) + '</span></div>' +
        '<div class="audit-tags">' + tags.join('') + '</div>' +
        (s.outputSnapshot ? '<div class="audit-output">' + esc(s.outputSnapshot) + '</div>' : '') +
        '</div>';
    });
    return html + '</div>';
  }

  async function loadLlmStats() {
    if (!currentRunId) {
      showToast('请先提交一条工单', true);
      return;
    }
    const box = $('llmStatsBox');
    llmStatsBtn.disabled = true;
    box.classList.toggle('hidden');
    if (box.classList.contains('hidden')) {
      llmStatsBtn.disabled = false;
      return;
    }
    box.innerHTML = '<p class="hint">加载 LLM 调用统计中…</p>';
    try {
      const stats = await apiFetch(`${API}/audit/agent-runs/${currentRunId}/llm-stats`);
      box.innerHTML = renderLlmStats(stats);
    } catch (err) {
      box.innerHTML = '<p class="eval-fail">' + esc(err.message) + '</p>';
      showToast(err.message, true);
    } finally {
      llmStatsBtn.disabled = false;
    }
  }

  function renderLlmStats(stats) {
    const total = stats || {};
    let html = '<div class="ops-title">LLM 调用统计</div>';
    html += '<div class="stats-grid">' +
      statCard('总调用', total.totalCalls || 0) +
      statCard('成功', total.successCalls || 0) +
      statCard('失败', total.failedCalls || 0) +
      statCard('降级信号', total.fallbackSignals || 0) +
      statCard('总耗时', formatMs(total.totalDurationMs)) +
      '</div>';
    const entries = Object.entries(total.byCallName || {});
    if (entries.length) {
      const maxDuration = Math.max(...entries.map(([, x]) => x.maxDurationMs || 0));
      html += '<div class="ops-table-wrap"><table class="ops-table"><thead><tr>' +
        '<th>调用点</th><th>次数</th><th>成功</th><th>失败</th><th>总耗时</th><th>最大耗时</th>' +
        '</tr></thead><tbody>';
      entries.forEach(([name, item]) => {
        const isSlow = (item.maxDurationMs || 0) === maxDuration;
        html += '<tr><td>' + esc(name) + (isSlow ? ' <span class="mini-tag mini-tag-warn">最慢</span>' : '') + '</td>' +
          '<td>' + (item.totalCalls || 0) + '</td>' +
          '<td>' + (item.successCalls || 0) + '</td>' +
          '<td>' + (item.failedCalls || 0) + '</td>' +
          '<td>' + formatMs(item.totalDurationMs) + '</td>' +
          '<td>' + formatMs(item.maxDurationMs) + '</td></tr>';
      });
      html += '</tbody></table></div>';
    }
    return html;
  }

  function statCard(label, value) {
    return '<div class="stat-card"><span>' + esc(label) + '</span><strong>' + esc(value) + '</strong></div>';
  }

  async function uploadKnowledge(e) {
    e.preventDefault();
    const file = $('knowledgeFile').files[0];
    if (!file) {
      showToast('请选择知识文档', true);
      return;
    }
    $('knowledgeUploadBtn').disabled = true;
    try {
      const formData = new FormData();
      formData.append('file', file);
      appendFormValue(formData, 'title', $('knowledgeTitle').value);
      appendFormValue(formData, 'systemName', $('knowledgeSystem').value);
      appendFormValue(formData, 'moduleName', $('knowledgeModule').value);
      appendFormValue(formData, 'tags', $('knowledgeTags').value);
      const result = await apiFetch(`${API}/knowledge/documents`, { method: 'POST', body: formData });
      knowledgeForm.reset();
      await loadKnowledge();
      showToast('已导入 ' + result.chunks + ' 个知识片段' + (result.vectorIndexed ? '，并写入向量索引' : ''));
    } catch (err) {
      showToast(err.message, true);
    } finally {
      $('knowledgeUploadBtn').disabled = false;
    }
  }

  function appendFormValue(formData, key, value) {
    const trimmed = value == null ? '' : value.trim();
    if (trimmed) formData.append(key, trimmed);
  }

  async function loadKnowledge() {
    const list = $('knowledgeList');
    try {
      const docs = await apiFetch(`${API}/knowledge/documents?limit=10`);
      if (!docs || docs.length === 0) {
        list.innerHTML = '<li class="knowledge-empty">暂无导入记录（启动时自带种子知识，未计入此列表）</li>';
        return;
      }
      list.innerHTML = docs.map((doc) => {
        const meta = [doc.systemName, doc.moduleName].filter(Boolean).join(' / ');
        return '<li class="knowledge-item">' +
          '<div><strong>' + esc(doc.title) + '</strong>' +
          (doc.sourceType ? '<span class="knowledge-source">' + esc(doc.sourceType) + '</span></div>' : '</div>') +
          (meta ? '<div class="step-meta">' + esc(meta) + '</div>' : '') +
          (doc.summary ? '<div class="knowledge-summary">' + esc(doc.summary) + '</div>' : '') +
          '</li>';
      }).join('');
    } catch (err) {
      list.innerHTML = '<li class="knowledge-empty">加载失败</li>';
    }
  }

  async function runEval() {
    const btn = $('runEvalBtn');
    const box = $('evalResult');
    const suite = $('evalSuite') ? $('evalSuite').value : 'full';
    btn.disabled = true;
    box.innerHTML = '<p class="hint">运行 ' + evalSuiteLabel(suite) + ' 中，逐条调用真实 LLM…</p>';
    try {
      const report = await apiFetch(`${API}/evals/run?suite=${encodeURIComponent(suite)}`, { method: 'POST' });
      lastEvalReport = report;
      const passClass = report.failed === 0 ? 'eval-pass' : 'eval-fail';
      let html = renderEvalOutputFile(report);
      html += '<p class="' + passClass + '">' + esc(evalSuiteLabel(suite)) + '：通过 ' + report.passed + ' / ' + report.total + '</p>';
      if (report.groups && report.groups.length) {
        html += '<div class="eval-groups">';
        report.groups.forEach((group) => {
          const groupClass = group.failed === 0 ? 'eval-pass' : 'eval-fail';
          html += '<div class="eval-group-item"><span>' + esc(group.name) + '</span>' +
            '<span class="' + groupClass + '">' + group.passed + ' / ' + group.total + '</span></div>';
        });
        html += '</div>';
      }
      html += renderEvalMetrics(report.metricsSummary);
      if (report.failures && report.failures.length) {
        html += '<p class="hint">失败项：</p><ul class="eval-failures">';
        report.failures.forEach((f) => {
          const caseId = failureCaseId(f);
          const caseResult = findEvalCaseResult(report, caseId);
          html += '<li class="eval-fail">' + esc(f);
          if (caseResult && caseResult.runId) {
            html += renderEvalFailureActions(caseId, caseResult.runId);
          }
          html += '</li>';
        });
        html += '</ul>';
      }
      box.innerHTML = html;
      bindEvalDebugActions(box);
      showToast(report.failed === 0 ? 'Eval 全部通过' : 'Eval 存在 ' + report.failed + ' 个失败', report.failed > 0);
    } catch (err) {
      box.innerHTML = '<p class="eval-fail">' + esc(err.message) + '</p>';
      showToast(err.message, true);
    } finally {
      btn.disabled = false;
    }
  }

  function renderEvalFailureActions(caseId, runId) {
    const safeCaseId = esc(caseId);
    const safeRunId = esc(runId);
    return '<div class="eval-failure-meta">runId: <code>' + safeRunId + '</code></div>' +
      '<div class="eval-debug-actions">' +
      '<button type="button" class="btn btn-ghost btn-xs" data-eval-action="rerun-case" data-case-id="' + safeCaseId + '">单 case 重跑</button>' +
      '<button type="button" class="btn btn-ghost btn-xs" data-eval-action="show-audit" data-run-id="' + safeRunId + '">展开审计</button>' +
      '<button type="button" class="btn btn-ghost btn-xs" data-eval-action="show-llm" data-run-id="' + safeRunId + '">LLM 统计</button>' +
      '</div>' +
      '<div class="eval-debug-output hidden" data-eval-output="' + safeRunId + '"></div>';
  }

  function bindEvalDebugActions(container) {
    container.querySelectorAll('[data-eval-action]').forEach((btn) => {
      btn.addEventListener('click', async () => {
        const action = btn.dataset.evalAction;
        const runId = btn.dataset.runId;
        const caseId = btn.dataset.caseId;
        btn.disabled = true;
        try {
          if (action === 'rerun-case') {
            await rerunEvalCase(container, caseId);
          } else if (action === 'show-audit') {
            await showEvalAudit(container, runId);
          } else if (action === 'show-llm') {
            await showEvalLlmStats(container, runId);
          }
        } catch (err) {
          showToast(err.message, true);
        } finally {
          btn.disabled = false;
        }
      });
    });
  }

  async function rerunEvalCase(container, caseId) {
    const output = ensureEvalDebugOutput(container, 'case-' + caseId);
    output.classList.remove('hidden');
    output.innerHTML = '<p class="hint">正在重跑 ' + esc(caseId) + '…</p>';
    const report = await apiFetch(`${API}/evals/run-case?caseId=${encodeURIComponent(caseId)}`, { method: 'POST' });
    output.innerHTML = renderSingleCaseEvalReport(report);
    bindEvalDebugActions(output);
  }

  async function runEvalCaseFromInput() {
    const btn = $('runEvalCaseBtn');
    const box = $('evalResult');
    const input = $('evalCaseId');
    const caseId = input ? input.value.trim() : '';
    if (!caseId) {
      showToast('请输入 caseId', true);
      return;
    }
    btn.disabled = true;
    box.innerHTML = '<p class="hint">运行单 case：' + esc(caseId) + '…</p>';
    try {
      const report = await apiFetch(`${API}/evals/run-case?caseId=${encodeURIComponent(caseId)}`, { method: 'POST' });
      lastEvalReport = report;
      box.innerHTML = renderSingleCaseEvalReport(report);
      bindEvalDebugActions(box);
      showToast(report.failed === 0 ? '单 case 通过' : '单 case 失败', report.failed > 0);
    } catch (err) {
      box.innerHTML = '<p class="eval-fail">' + esc(err.message) + '</p>';
      showToast(err.message, true);
    } finally {
      btn.disabled = false;
    }
  }

  async function exportCurrentEvalReport() {
    const btn = $('exportEvalReportBtn');
    const box = $('evalResult');
    if (!lastEvalReport) {
      showToast('请先运行 Eval 或单 case，再导出当前结果', true);
      return;
    }
    btn.disabled = true;
    try {
      const report = await apiFetch(`${API}/evals/export`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          report: lastEvalReport,
          outputPath: readEvalOutputPath() || null
        })
      });
      lastEvalReport = report;
      const fileHtml = renderEvalOutputFile(report);
      if (fileHtml) {
        box.insertAdjacentHTML('afterbegin', fileHtml);
      }
      showToast('当前 Eval 结果已导出');
    } catch (err) {
      showToast(err.message, true);
    } finally {
      btn.disabled = false;
    }
  }

  function readEvalOutputPath() {
    const input = $('evalOutputPath');
    return input ? input.value.trim() : '';
  }

  function renderEvalOutputFile(report) {
    if (!report || !report.outputFile) return '';
    return '<div class="eval-output-file">已写入：<code>' + esc(report.outputFile) + '</code></div>';
  }

  async function showEvalAudit(container, runId) {
    const output = ensureEvalDebugOutput(container, runId);
    output.classList.remove('hidden');
    output.innerHTML = '<p class="hint">加载审计链路中…</p>';
    const steps = await apiFetch(`${API}/audit/agent-runs/${encodeURIComponent(runId)}`);
    output.innerHTML = renderAudit(steps || []);
  }

  async function showEvalLlmStats(container, runId) {
    const output = ensureEvalDebugOutput(container, runId);
    output.classList.remove('hidden');
    output.innerHTML = '<p class="hint">加载 LLM 统计中…</p>';
    const stats = await apiFetch(`${API}/audit/agent-runs/${encodeURIComponent(runId)}/llm-stats`);
    output.innerHTML = renderLlmStats(stats);
  }

  async function loadEvalAuditByRunId() {
    const btn = $('loadEvalAuditBtn');
    const box = $('evalResult');
    const runId = readEvalAuditRunId();
    if (!runId) return;
    btn.disabled = true;
    box.innerHTML = '<p class="hint">加载 runId=' + esc(runId) + ' 的审计链路中…</p>';
    try {
      const steps = await apiFetch(`${API}/audit/agent-runs/${encodeURIComponent(runId)}`);
      box.innerHTML = '<div class="eval-run-inspect"><div class="eval-failure-meta">runId: <code>' + esc(runId) + '</code></div>' +
        renderAudit(steps || []) + '</div>';
      showToast('审计链路已加载');
    } catch (err) {
      box.innerHTML = '<p class="eval-fail">' + esc(err.message) + '</p>';
      showToast(err.message, true);
    } finally {
      btn.disabled = false;
    }
  }

  async function loadEvalLlmByRunId() {
    const btn = $('loadEvalLlmBtn');
    const box = $('evalResult');
    const runId = readEvalAuditRunId();
    if (!runId) return;
    btn.disabled = true;
    box.innerHTML = '<p class="hint">加载 runId=' + esc(runId) + ' 的 LLM 统计中…</p>';
    try {
      const stats = await apiFetch(`${API}/audit/agent-runs/${encodeURIComponent(runId)}/llm-stats`);
      box.innerHTML = '<div class="eval-run-inspect"><div class="eval-failure-meta">runId: <code>' + esc(runId) + '</code></div>' +
        renderLlmStats(stats) + '</div>';
      showToast('LLM 统计已加载');
    } catch (err) {
      box.innerHTML = '<p class="eval-fail">' + esc(err.message) + '</p>';
      showToast(err.message, true);
    } finally {
      btn.disabled = false;
    }
  }

  function readEvalAuditRunId() {
    const input = $('evalAuditRunId');
    const runId = input ? input.value.trim() : '';
    if (!runId) {
      showToast('请输入 runId', true);
      return '';
    }
    return runId;
  }

  function ensureEvalDebugOutput(container, key) {
    let output = container.querySelector('[data-eval-output="' + cssEscape(key) + '"]');
    if (!output) {
      output = document.createElement('div');
      output.className = 'eval-debug-output';
      output.dataset.evalOutput = key;
      container.appendChild(output);
    }
    return output;
  }

  function renderSingleCaseEvalReport(report) {
    const result = report.caseResults && report.caseResults.length ? report.caseResults[0] : null;
    const passClass = report.failed === 0 ? 'eval-pass' : 'eval-fail';
    let html = '<div class="eval-single-case">' + renderEvalOutputFile(report) +
      '<p class="' + passClass + '">单 case：通过 ' + report.passed + ' / ' + report.total + '</p>';
    if (result) {
      html += '<div class="eval-failure-meta">caseId: <code>' + esc(result.caseId) + '</code> · runId: <code>' + esc(result.runId) + '</code></div>';
      html += '<div class="eval-debug-actions">' +
        '<button type="button" class="btn btn-ghost btn-xs" data-eval-action="show-audit" data-run-id="' + esc(result.runId) + '">展开审计</button>' +
        '<button type="button" class="btn btn-ghost btn-xs" data-eval-action="show-llm" data-run-id="' + esc(result.runId) + '">LLM 统计</button>' +
        '</div><div class="eval-debug-output hidden" data-eval-output="' + esc(result.runId) + '"></div>';
      if (result.assertions && result.assertions.length) {
        html += '<div class="eval-assertions">';
        result.assertions.forEach((item) => {
          const cls = item.passed ? 'eval-pass' : 'eval-fail';
          html += '<div class="eval-assertion ' + cls + '"><strong>' + esc(item.name) + '</strong>' +
            '<span>expected=' + esc(item.expected) + '</span>' +
            '<span>actual=' + esc(item.actual) + '</span></div>';
        });
        html += '</div>';
      }
    }
    if (report.failures && report.failures.length) {
      html += '<ul class="eval-failures">' + report.failures.map((f) => '<li class="eval-fail">' + esc(f) + '</li>').join('') + '</ul>';
    }
    return html + '</div>';
  }

  function cssEscape(value) {
    return String(value).replace(/\\/g, '\\\\').replace(/"/g, '\\"');
  }

  function failureCaseId(failureText) {
    if (!failureText) return '';
    const idx = failureText.indexOf(' failed');
    return idx > 0 ? failureText.slice(0, idx) : '';
  }

  function findEvalCaseResult(report, caseId) {
    if (!report || !report.caseResults || !caseId) return null;
    return report.caseResults.find((item) => item.caseId === caseId) || null;
  }

  function evalSuiteLabel(suite) {
    const labels = {
      smoke: '快速评测',
      core: '核心评测',
      full: '完整评测'
    };
    return labels[suite] || '完整评测';
  }

  function renderEvalMetrics(metrics) {
    if (!metrics) return '';
    const tokenTotal = (metrics.totalPromptTokens || 0) + (metrics.totalCompletionTokens || 0);
    const avgCalls = metrics.totalCases ? ((metrics.totalLlmCalls || 0) / metrics.totalCases).toFixed(1) : '—';
    const avgTokens = metrics.totalCases ? Math.round(tokenTotal / metrics.totalCases) : '—';
    let html = '<div class="eval-metrics">';
    html += '<div class="ops-title">评测指标</div>';
    html += '<div class="eval-metric-grid">' +
      evalMetricCard('结构通过率', percent(metrics.passRate), metrics.passedCases + ' / ' + metrics.totalCases) +
      evalMetricCard('工具成功率', percent(metrics.toolSuccessRate), (metrics.successfulToolCalls || 0) + ' / ' + (metrics.totalToolCalls || 0)) +
      evalMetricCard('平均耗时', formatMs(metrics.averageRunCostMs), 'P95 ' + formatMs(metrics.p95RunCostMs)) +
      evalMetricCard('RAG 覆盖', metrics.ragSearchCases || 0, (metrics.ragDegradedCases || 0) + ' 个降级') +
      evalMetricCard('人工确认', metrics.humanConfirmCases || 0, '追问 ' + (metrics.followUpCases || 0)) +
      evalMetricCard('LLM 调用', metrics.totalLlmCalls || 0, '失败 ' + (metrics.failedLlmCalls || 0)) +
      evalMetricCard('Token 总量', tokenTotal || 0, '输入 ' + (metrics.totalPromptTokens || 0) + ' / 输出 ' + (metrics.totalCompletionTokens || 0)) +
      evalMetricCard('分析闭环', metrics.analyzedCases || 0, '进入根因/路由链路') +
      evalMetricCard('平均调用', avgCalls, 'LLM calls / case') +
      evalMetricCard('平均 Token', avgTokens, 'tokens / case') +
      '</div>';
    const groups = Object.entries(metrics.casesByScenarioType || {});
    if (groups.length) {
      html += '<div class="eval-scenario-strip">' +
        groups.map(([name, count]) => '<span>' + esc(name) + ' · ' + count + '</span>').join('') +
        '</div>';
    }
    return html + '</div>';
  }

  function evalMetricCard(label, value, meta) {
    return '<div class="eval-metric-card"><span>' + esc(label) + '</span><strong>' + esc(value) + '</strong><em>' + esc(meta) + '</em></div>';
  }

  function percent(value) {
    const n = Number(value);
    if (!Number.isFinite(n)) return '—';
    return n.toFixed(n % 1 === 0 ? 0 : 1) + '%';
  }

  function bindTicketSamples() {
    document.querySelectorAll('.ticket-item[data-sample]').forEach((item) => {
      item.addEventListener('click', () => selectSample(item.dataset.sample));
    });
  }

  function init() {
    document.querySelectorAll('.tab').forEach((tab) => {
      tab.addEventListener('click', () => activateTab(tab.dataset.tab));
    });
    on('summaryForm', 'submit', submitSummary);
  on('submitForm', 'submit', submitTicket);
    on('supplementBtn', 'click', supplementMessage);
    bindTicketSamples();
    on('clearBtn', 'click', clearTicketForm);
    on('logFiles', 'change', renderLogFileList);
    on('confirmBtn', 'click', () => handleConfirm(true));
    on('rejectBtn', 'click', () => handleConfirm(false));
    on('auditBtn', 'click', loadAudit);
    on('llmStatsBtn', 'click', loadLlmStats);
    on('knowledgeForm', 'submit', uploadKnowledge);
    on('refreshKnowledgeBtn', 'click', loadKnowledge);
    on('runEvalBtn', 'click', runEval);
    on('runEvalCaseBtn', 'click', runEvalCaseFromInput);
    on('exportEvalReportBtn', 'click', exportCurrentEvalReport);
    on('loadEvalAuditBtn', 'click', loadEvalAuditByRunId);
    on('loadEvalLlmBtn', 'click', loadEvalLlmByRunId);

    selectSample('complete');
    renderLogFileList();
    loadKnowledge();
  }

  function clearTicketForm() {
    $('ticketNo').value = '';
    $('ticketSource').value = '';
    $('ticketEnv').value = '';
    $('ticketCreatedAt').value = '';
    $('content').value = '';
    if (logFilesInput) logFilesInput.value = '';
    renderLogFileList();
    resetResultArea();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
