/* ============================================================
   SuperAutoStudy 客户端 —— 前端逻辑（原生 JS，零依赖）
   ============================================================ */
'use strict';

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));

/* ---------------- 工具 ---------------- */
function esc(v) {
  return String(v == null ? '' : v)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function fmtDur(sec) {
  if (sec == null || isNaN(sec)) return '—';
  sec = Math.max(0, Math.round(Number(sec)));
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
  const mm = String(m).padStart(2, '0'), ss = String(s).padStart(2, '0');
  return h > 0 ? `${h}:${mm}:${ss}` : `${m}:${ss}`;
}

async function api(path, opts = {}) {
  const init = { method: opts.method || 'GET' };
  if (opts.body !== undefined) {
    init.headers = { 'Content-Type': 'application/json' };
    init.body = JSON.stringify(opts.body);
  }
  const res = await fetch(path, init);
  let data = {};
  try { data = await res.json(); } catch (e) { /* 非 JSON 响应 */ }
  return data;
}

let toastTimer = null;
function toast(msg, kind) {
  const el = $('#toast');
  el.textContent = msg;
  el.className = 'toast' + (kind ? ' ' + kind : '');
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, 6000);
}

function openModal(title, html) {
  $('#modal-title').textContent = title;
  $('#modal-body').innerHTML = html;
  $('#modal').classList.remove('hidden');
}
function closeModal() {
  $('#modal').classList.add('hidden');
  pointsToken++;   // 让正在轮询的任务点请求作废
}

/* ---------------- 全局状态 ---------------- */
const S = {
  data: null,
  view: 'overview',
  logFilter: '',
  logOnlyErr: false,
  logAutoScroll: true,
  enroll: { courses: null, picked: new Set(), loading: false },
  points: {},          // courseId -> {loading, data, error, at}
  // 待确认测验：items 为后端原始行，answers 缓存用户在界面上的改动
  // （pid -> {qid: answer}），不直接写回 items，免得重绘把用户输入冲掉。
  quiz: { items: null, loading: false, error: '', answers: {}, ai: null, submitting: {} },
  busy: false,
};

const POLL_MS = 2000;

/* ---------------- 轮询 ---------------- */
async function poll() {
  if (S.busy) return;
  try {
    const d = await api('/api/state');
    if (d && d.services) {
      const first = !S.data;
      S.data = d;
      renderTopbar(d);
      renderQuizBadge(d);
      // 首次拿到数据时任何视图都要重绘（否则直接落在 #settings 会一直显示「正在连接」）；
      // 之后只有总览/课程随轮询重绘，设置/选课/日志保留用户输入与滚动位置。
      if (first || S.view === 'overview' || S.view === 'courses') render();
      // 日志 / 设置保留用户的输入、勾选与滚动位，只做局部刷新
      else if (S.view === 'logs') renderLogs(d, true);
      else if (S.view === 'settings') renderUpdate(d);
    }
  } catch (e) {
    /* 服务刚起/刚停时会失败，忽略 */
  }
}

/* ---------------- 顶栏 ---------------- */
function renderTopbar(d) {
  const acc = d.account;
  const starOn = acc && acc.logged_in;
  const items = [
    // 注意：类名是 dot-ok / dot-err（见 tokens.css），不是 ok / err —— 写错的话
    // class 会变成 "dot ok"，两个类都不存在，圆点永远是灰的。
    { name: 'MySQL', on: d.services.mysql, dot: d.services.mysql ? 'dot-ok' : 'dot-err' },
    { name: '后端', on: d.services.backend, dot: d.services.backend ? 'dot-ok' : 'dot-err' },
    {
      name: starOn ? `超星 ${acc.account}` : '超星 未登录',
      on: !!starOn,
      dot: starOn ? 'dot-ok' : 'dot-err',
    },
  ];
  $('#svc').innerHTML = items.map(it => `
    <span class="svc-item ${it.on ? 'on' : ''}">
      <span class="dot ${it.dot}"></span>${esc(it.name)}
    </span>`).join('');
}

/* 待确认小红点：轮询里更新，不随视图重绘，用户切到别的页也能看到还有几份没处理 */
function renderQuizBadge(d) {
  const el = $('#quiz-badge');
  if (!el) return;
  const n = d.quizPending || 0;
  el.hidden = !n;
  el.textContent = n > 99 ? '99+' : String(n);
}

/* ---------------- 视图分派 ---------------- */
function render() {
  const d = S.data;
  const v = $('#view');
  if (!d) { v.innerHTML = `<div class="view-inner"><div class="empty">正在连接本地服务…</div></div>`; return; }

  if (S.view === 'overview') v.innerHTML = viewOverview(d);
  else if (S.view === 'courses') v.innerHTML = viewCourses(d);
  else if (S.view === 'quiz') v.innerHTML = viewQuiz(d);
  else if (S.view === 'enroll') v.innerHTML = viewEnroll(d);
  else if (S.view === 'settings') v.innerHTML = viewSettings(d);
  else if (S.view === 'logs') v.innerHTML = viewLogsShell(d);

  if (S.view === 'logs') renderLogs(d, false);
  if (S.view === 'settings') { renderUpdate(d); renderAiConfig(); }
  if (S.view === 'enroll') ensureEnrollCourses(d);
  if (S.view === 'quiz') ensureQuizLoaded(d);
}

// 已经登录过（库里有 cookie）就不该逼用户再输一次密码才看得到课程。
// 这里用只读的 /api/courses（不触网登录），拉不到才需要走「登录并拉取课程」。
let enrollFetching = false;
async function ensureEnrollCourses(d) {
  if (enrollFetching || S.enroll.courses !== null || S.enroll.loading) return;
  if (!d.account || !d.account.logged_in) return;
  enrollFetching = true;
  S.enroll.loading = true;
  render();
  try {
    const r = await api('/api/courses');
    if (r && r.code === 200 && Array.isArray(r.data)) {
      S.enroll.courses = r.data;
    } else if (r && r.code === 401) {
      S.enroll.courses = null;   // 没登录态，等用户手动登录
    } else {
      S.enroll.courses = [];
    }
  } catch (e) {
    S.enroll.courses = [];
  } finally {
    S.enroll.loading = false;
    enrollFetching = false;
    render();
  }
}

/* ---------------- 总览 ---------------- */
function viewOverview(d) {
  const st = d.stats;
  const svcOk = d.services.mysql && d.services.backend;

  let liveHtml;
  if (!d.services.backend) {
    liveHtml = `<div class="empty">后端未运行，点右上角「启动服务」开始刷课</div>`;
  } else if (!d.live || !d.live.length) {
    liveHtml = `<div class="empty">当前没有正在刷的视频（可能都在排队或已完成）</div>`;
  } else {
    liveHtml = `<div class="live-list">` + d.live.map(v => `
      <div class="live-card">
        <div class="live-head">
          <span class="live-thread">${esc(v.thread)}</span>
          <span class="live-course">${esc(v.course)}</span>
        </div>
        <div class="live-job">${esc(v.job)}</div>
        <div class="bar-row">
          <div class="bar"><i style="width:${Math.min(100, v.percent)}%"></i></div>
          <span class="bar-pct">${v.percent.toFixed(1)}%</span>
        </div>
        <div class="live-meta">播放位置 ${fmtDur(v.position)} / ${fmtDur(v.duration)}
          · 已进行 ${fmtDur(v.elapsed)}${v.round > 1 ? ` · 第 ${v.round} 遍` : ''}</div>

      </div>`).join('') + `</div>`;
  }

  const recent = (d.recentVideos || []).slice().reverse();
  const recentHtml = recent.length ? `<div class="job-list">` + recent.map(r => `
      <div class="job-row">
        <svg class="job-mark ${/计分$/.test(r.result) ? 'ok' : 'no'}" viewBox="0 0 24 24" fill="none"
             stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          ${/计分$/.test(r.result) ? '<path d="M20 6L9 17l-5-5"/>' : '<path d="M12 8v5M12 17h.01"/><circle cx="12" cy="12" r="9"/>'}
        </svg>
        <div class="job-name">${esc(r.job)}</div>
        <div class="job-chapter">${esc(r.course)}</div>
        <div class="job-type">${esc(r.result)}</div>
      </div>`).join('') + `</div>` : `<div class="empty">暂无记录</div>`;

  return `<div class="view-inner">
    <div class="stats">
      <div class="stat"><div class="stat-label">课程总数</div><div class="stat-value">${st.total}</div></div>
      <div class="stat"><div class="stat-label">进行中</div><div class="stat-value run">${st.running}</div></div>
      <div class="stat"><div class="stat-label">已完成</div><div class="stat-value ok">${st.done}</div></div>
      <div class="stat"><div class="stat-label">异常</div><div class="stat-value err">${st.failed}</div></div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">正在刷的视频</span>
        <span class="faint" style="font-size:var(--t-xs)">实时</span>
      </div>
      ${liveHtml}
    </div>

    <div class="panel">
      <div class="panel-head"><span class="panel-title">最近完成的视频</span></div>
      ${recentHtml}
    </div>

    <div class="panel">
      <div class="panel-head"><span class="panel-title">运行环境</span></div>
      <dl class="kv">
        <dt>服务状态</dt><dd>${svcOk ? '正常' : '未就绪'}</dd>
        <dt>部署目录</dt><dd>${esc(d.services.root)}</dd>
        <dt>日志文件</dt><dd>${esc(d.services.log)}</dd>
        <dt>刷课方式</dt><dd>单课串行 · 真实播放节奏（不倍速）· 断点续刷开启</dd>
        <dt>防休眠</dt><dd>${d.settings.keep_awake
          ? (d.keep_awake && d.keep_awake.active ? '已开启 · 生效中' : '已开启')
          : '已关闭'}</dd>
      </dl>
    </div>
  </div>`;
}

/* ---------------- 课程队列 ---------------- */
function viewCourses(d) {
  if (!d.queue.length) {
    return `<div class="view-inner"><div class="panel"><div class="empty">
      队列里还没有课程。去「登录 / 选课」把课程加进来。
    </div></div></div>`;
  }
  const rows = d.queue.map(c => {
    const barCls = c.tag === 'ok' ? 'ok' : c.tag === 'err' ? 'err' : c.tag === 'idle' ? 'idle' : '';
    const tagCls = c.tag === 'ok' ? 'tag-ok' : c.tag === 'err' ? 'tag-err'
                 : c.tag === 'run' ? 'tag-accent' : 'tag';
    const job = c.currentJob || (c.error ? c.error : (c.remark || ''));
    const resumeParts = [];
    if (c.tag !== 'ok') {
      if (c.chapter != null) resumeParts.push(`第 ${(c.chapter || 0) + 1} 章`);
      if (c.failedChapterCount) resumeParts.push(`失败 ${c.failedChapterCount} 章`);
      if (c.resumeVideoSeconds) resumeParts.push(`续播 ${fmtDur(c.resumeVideoSeconds)}`);
    }
    const resumeText = resumeParts.join(' · ');
    return `<div class="course-row">
      <div>
        <div class="course-name" title="${esc(c.courseName)}">${esc(c.courseName || '—')}</div>
        <div class="course-sub">${esc(c.courseId)} · 优先级 ${c.priority}${resumeText ? ` · ${esc(resumeText)}` : ''}</div>
      </div>
      <div><span class="tag ${tagCls}">${esc(c.statusText)}</span></div>
      <div class="bar-row">
        <div class="bar ${barCls}"><i style="width:${c.progress}%"></i></div>
        <span class="bar-pct">${c.progress.toFixed(1)}%</span>
      </div>
      <div class="course-job" title="${esc(job)}${c.chapter != null ? ` · 章节索引 ${c.chapter}` : ''}">
        ${esc(job || '—')}
      </div>
      <div class="course-ops">
        <button class="btn btn-ghost" data-act="points" data-id="${esc(c.courseId)}"
                data-name="${esc(c.courseName)}">任务点</button>
        <button class="btn btn-ghost" data-act="prio" data-id="${esc(c.id)}"
                data-prio="${c.priority}">优先级</button>
        ${(c.retryLimitReached || c.tag === 'err')
          ? `<button class="btn btn-ghost" data-act="row-reset" data-id="${esc(c.id)}">续刷</button>` : ''}
        <button class="btn btn-danger" data-act="del" data-id="${esc(c.id)}"
                data-name="${esc(c.courseName)}">移除</button>
      </div>
    </div>`;
  }).join('');

  return `<div class="view-inner">
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">课程队列 · ${d.queue.length} 门</span>
        <span class="faint" style="font-size:var(--t-xs)">每 2 秒刷新</span>
      </div>
      <div class="course-list">${rows}</div>
    </div>
    <div class="hint">
      提示：删除队列行是<b>唯一能真正停掉一门课</b>的方式（把状态改成「异常」会被启动自愈拉回来）。
      删除后正在执行的那一轮仍会跑完，要立刻停只能重启后端。
    </div>
  </div>`;
}

/* ---------------- 登录 / 选课 ---------------- */
function viewEnroll(d) {
  const acc = d.account;
  const picked = S.enroll.picked;
  let listHtml;

  if (S.enroll.loading) {
    listHtml = `<div class="empty">正在从超星拉取课程列表…</div>`;
  } else if (S.enroll.courses === null) {
    listHtml = `<div class="empty">登录后会在这里列出你账号下的所有课程</div>`;
  } else if (!S.enroll.courses.length) {
    listHtml = `<div class="empty">账号下没有查到课程</div>`;
  } else {
    const queued = new Map(d.queue.map(c => [String(c.courseId), c]));
    listHtml = `<div class="check-list">` + S.enroll.courses.map(c => {
      const cid = String(c.courseId);
      const q = queued.get(cid);
      const done = !!(q && q.tag === 'ok');
      const locked = !!q && !done;
      return `<label class="check-item">
        <input type="checkbox" data-act="pick" data-id="${esc(cid)}"
               ${picked.has(cid) ? 'checked' : ''} ${locked ? 'disabled' : ''}>
        <span class="check-title">${esc(c.title || '(无标题)')}</span>
        <span class="check-sub">${esc(cid)}${q ? (done ? ' · 已完成，可重新复核' : ' · 已在队列') : ''}</span>
      </label>`;
    }).join('') + `</div>`;
  }

  const pickedCount = picked.size;

  return `<div class="view-inner">
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">超星登录</span>
        ${acc && acc.logged_in
          ? `<span class="tag tag-ok">已登录 ${esc(acc.account)}</span>`
          : `<span class="tag tag-err">未登录</span>`}
      </div>
      <div class="form-grid">
        <div class="form-2">
          <div>
            <label class="label" for="in-acc">账号</label>
            <input class="field" id="in-acc" placeholder="超星手机号 / 学号"
                   value="${esc(acc && acc.account ? acc.account : '')}">
          </div>
          <div>
            <label class="label" for="in-pwd">密码</label>
            <input class="field" id="in-pwd" type="password" placeholder="超星密码">
          </div>
        </div>
        <div class="row">
          <button class="btn btn-primary" data-act="login">登录并拉取课程</button>
          <span class="hint">登录会真的去超星验证一次，别频繁点。</span>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">选择要刷的课程</span>
        <span class="row">
          <span class="faint" style="font-size:var(--t-xs)">已选 ${pickedCount}</span>
          <button class="btn btn-primary" data-act="enroll" ${pickedCount ? '' : 'disabled'}>
            加入队列
          </button>
        </span>
      </div>
      ${listHtml}
    </div>

    <div class="hint">
      加入队列是纯本地操作（只写数据库，不碰超星），加完后会自动触发一次队列扫描。
      工具只处理视频、文档、阅读和超链接，其他任务点类型一律跳过。
    </div>
  </div>`;
}

/* ---------------- 设置 ---------------- */
function viewSettings(d) {
  const s = d.settings;

  return `<div class="view-inner">
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">运行选项</span>
        <span class="hint">保存后立即生效</span>
      </div>

      <div class="setting-row">
        <div>
          <div class="setting-name">运行时防止电脑休眠</div>
          <div class="setting-desc">
            客户端开着的时候持续告诉 Windows「有程序在干活」，系统不会自动休眠、睡眠或关机
            （屏幕照样会自动关闭，不影响省电）。<br>
            适合挂着刷课时无人值守；关掉后系统恢复默认电源策略。
            ${d.keep_awake && d.keep_awake.supported === false
              ? '<br><b style="color:var(--err)">当前环境不支持（仅 Windows 有效）</b>'
                + (d.keep_awake.last_err ? ' <span style="color:var(--err);font-size:var(--t-xs)">' + String(d.keep_awake.last_err).slice(0, 120) + '</span>' : '')
              : ''}
          </div>
        </div>
        <div class="setting-input">
          <label class="check-item" style="padding-left:0">
            <input type="checkbox" id="set-keep-awake" data-act="keep-awake"
                   ${s.keep_awake ? 'checked' : ''}>
            <span class="check-title" style="font-size:var(--t-sm)">启用防休眠</span>
            <span class="check-sub">${d.keep_awake && d.keep_awake.active ? '生效中' : '已关闭'}</span>
          </label>
        </div>
      </div>

      <div class="row" style="margin-top:var(--s4)">
        <button class="btn btn-primary" data-act="save-settings">保存设置</button>
      </div>
    </div>

    <div class="panel" style="margin-top:var(--s4)">
      <div class="panel-head">
        <span class="panel-title">AI 作答（章节测验）</span>
        <span id="ai-status"></span>
      </div>

      <div id="ai-config-body">
        <div class="form-grid">
          <div class="form-2">
            <div>
              <label class="label" for="ai-base-url">接口地址</label>
              <input class="field" id="ai-base-url" placeholder="https://api.siliconflow.cn/v1">
            </div>
            <div>
              <label class="label" for="ai-model">模型</label>
              <input class="field" id="ai-model" placeholder="Qwen/Qwen2.5-7B-Instruct">
            </div>
          </div>
          <div class="form-2">
            <div>
              <label class="label" for="ai-key">API Key</label>
              <input class="field" id="ai-key" type="password" placeholder="sk-...">
            </div>
            <div>
              <label class="label" for="ai-timeout">超时（秒）</label>
              <input class="field" id="ai-timeout" type="number" min="10" max="300" value="90">
            </div>
          </div>
          <div style="margin-top:var(--s3)">
            <label class="label" for="ai-answer-mode">答题策略</label>
            <select class="field" id="ai-answer-mode">
              <option value="confirm">全部人工确认（默认）</option>
              <option value="auto_high">自动提交 · 全部 high</option>
              <option value="auto_medium">自动提交 · ≥ medium</option>
              <option value="auto_any">自动提交 · 能答就交</option>
              <option value="off">关闭答题</option>
            </select>
            <div class="hint" id="ai-mode-desc" style="margin-top:var(--s2);line-height:1.7"></div>
          </div>
        </div>

        <div class="hint" style="margin-top:var(--s3);line-height:1.8">
          任何 OpenAI 兼容接口都能用：硅基流动 / DeepSeek / 通义 / 智谱 / 本地 Ollama。
          硅基流动就填 <span class="mono">https://api.siliconflow.cn/v1</span> +
          <span class="mono">Qwen/Qwen2.5-7B-Instruct</span>，一份 5 题的卷子大约 700 token，
          一个学期的测验也就几分钱。<br>
          保存后立即生效，不用重启后端。这里填的 key 只存在本机
          <span class="mono" id="ai-path">app/ai-config.json</span>。
        </div>

        <div class="row" style="margin-top:var(--s3)">
          <button class="btn btn-primary" data-act="save-ai">保存 AI 配置</button>
          <button class="btn btn-ghost" data-act="test-ai">测一下能不能用</button>
        </div>
      </div>
    </div>

    <div class="panel" style="margin-top:var(--s4)">
      <div class="panel-head">
        <span class="panel-title">版本与更新</span>
        <span class="hint">旧文件备份在 <span class="mono">_update_backup_*</span> 目录，出错可回退</span>
      </div>
      <div id="update-body"></div>
    </div>
  </div>`;
}

/* ---------------- 待确认测验 ---------------- */
// 刷课线程抓到章节测验就落一条待确认记录（status=1），抓崩了落 status=5。
// 这里只负责「让人看一眼再交」—— 一次提交就定分，没有后悔药，
// 所以界面上刻意没有「全部自动提交」这种东西。
const QZ_TYPE_NAME = { single: '单选', multiple: '多选', judge: '判断', fill: '填空', short: '简答' };

async function ensureQuizLoaded() {
  if (S.quiz.loading || S.quiz.items !== null) return;
  loadQuiz();
}

async function loadQuiz() {
  if (S.quiz.loading) return;
  S.quiz.loading = true;
  S.quiz.error = '';
  render();
  try {
    const [r, ai] = await Promise.all([api('/api/quiz?limit=30'), api('/api/ai-config')]);
    if (ai && ai.ok) S.quiz.ai = ai.config;
    if (r && r.code === 200 && Array.isArray(r.data)) {
      S.quiz.items = r.data;
    } else {
      S.quiz.items = [];
      S.quiz.error = (r && (r.error || r.msg)) || '读取待确认列表失败';
    }
  } catch (e) {
    S.quiz.items = [];
    S.quiz.error = String(e);
  } finally {
    S.quiz.loading = false;
    render();
  }
}

function quizQ(pid, qid) {
  const p = (S.quiz.items || []).find(x => String(x.id) === String(pid));
  if (!p) return null;
  return (p.questions || []).find(q => String(q.id) === String(qid)) || null;
}

// 用户在界面上改的答案单独存在 S.quiz.answers 里，不写回 items ——
// 否则每次重绘都会把「AI 建议」和「最终答案」搅在一起，分不清哪个是模型给的。
//
// manual 的题不能拿 suggestion 当默认值：那正是校验没通过、被否掉的答案，
// 预选上等于把人往坑里带，必须留空让人自己选。
function quizAnswer(pid, q) {
  const box = S.quiz.answers[pid];
  if (box && Object.prototype.hasOwnProperty.call(box, q.id)) return box[q.id];
  if (q.answer) return String(q.answer);
  return q.manual ? '' : String(q.suggestion || '');
}

function setQuizAnswer(pid, qid, val) {
  if (!S.quiz.answers[pid]) S.quiz.answers[pid] = {};
  S.quiz.answers[pid][qid] = val;
}

// 判断题的选项是 true. 对 / false. 错，value 必须原样用 true/false；
// 选择题取首字母当 key，把 "A. 16" 里的 "A. " 剥掉当正文。
function quizOptList(q) {
  if (q.type === 'judge') {
    return [{ value: 'true', key: '对', text: '' }, { value: 'false', key: '错', text: '' }];
  }
  return (q.options || []).map(o => {
    const s = String(o).trim();
    const m = s.match(/^([A-Za-z])[\s\.\、\．\)]*([\s\S]*)$/);
    if (m) return { value: m[1].toUpperCase(), key: m[1].toUpperCase(), text: m[2].trim() };
    return { value: s, key: s.slice(0, 1), text: s };
  }).filter(o => o.value);
}

function paintQuizOpts(pid, qid) {
  const q = quizQ(pid, qid);
  if (!q) return;
  const judge = q.type === 'judge';
  const raw = String(quizAnswer(pid, q)).trim();
  const cur = judge ? raw.toLowerCase() : raw.toUpperCase();
  $$('.qz-opt').forEach(b => {
    if (b.dataset.pid !== String(pid) || b.dataset.qid !== String(qid)) return;
    b.classList.toggle('on', judge ? cur === b.dataset.letter : cur.includes(b.dataset.letter));
  });
}

function viewQuiz(d) {
  const st = S.quiz;

  if (st.loading && st.items === null) {
    return `<div class="view-inner"><div class="panel"><div class="empty">正在读取待确认的测验…</div></div></div>`;
  }
  if (st.error) {
    return `<div class="view-inner"><div class="panel"><div class="empty">
      ${esc(st.error)}
      <button class="btn" data-act="quiz-reload" style="margin-top:var(--s3)">重试</button>
    </div></div></div>`;
  }

  const items = st.items || [];
  if (!items.length) {
    return `<div class="view-inner"><div class="panel">
      <div class="panel-head">
        <span class="panel-title">待确认的章节测验</span>
        <button class="btn btn-ghost btn-sm" data-act="quiz-reload">刷新</button>
      </div>
      <div class="empty">
        现在没有要处理的测验
        <span class="hint">刷课碰到章节测验会自动抓题、让 AI 先出一份答案，抓到的都会列在这里等你过一眼。</span>
      </div>
    </div></div>`;
  }

  const ai = st.ai || {};
  const warn = ai.has_key ? '' : `<div class="panel" style="border-color:var(--warn)">
    <div class="panel-head" style="border-bottom-color:var(--warn-dim)">
      <span class="panel-title" style="color:var(--warn)">AI 还没配 key</span>
    </div>
    <div class="hint">
      下面这些卷子的答案要么是空的，要么标了「需人工」。
      去「设置 → AI 作答」填一个 key 并保存，之后新抓到的测验才会自动出答案。
    </div>
  </div>`;

  return `<div class="view-inner">
    ${warn}
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">待确认的章节测验 · ${items.length} 份</span>
        <span class="row" style="gap:var(--s2)">
          <span class="faint" style="font-size:var(--t-xs)">AI 只给建议，交之前请自己看一遍</span>
          <button class="btn btn-ghost btn-sm" data-act="quiz-reload">刷新</button>
        </span>
      </div>
      <div class="hint">
        提交后不能撤销、不能重做，答案立刻计分。拿不准的题直接在选项上改，改完再交。
      </div>
    </div>
    ${items.map(p => viewQuizPaper(p)).join('')}
  </div>`;
}

function viewQuizPaper(p) {
  const qs = p.questions || [];
  const broken = p.status === 5;
  const manual = qs.filter(q => q.manual).length;

  const tags = [broken
    ? '<span class="tag tag-err">抓题失败</span>'
    : '<span class="tag tag-accent">待确认</span>'];
  if (manual) tags.push(`<span class="tag tag-err">${manual} 题需人工</span>`);

  const body = qs.length
    ? qs.map((q, i) => viewQuizQuestion(p, q, i)).join('')
    : `<div class="empty" style="padding:var(--s5) 0">
         ${esc(p.error_message || '这份卷子没解析出题目')}
         <span class="hint">抓到的原始页面存在 app/quiz-dump/ 下</span>
       </div>`;

  const ops = qs.length
    ? `<div class="qz-actions" style="margin-top:var(--s4)">
         <button class="btn btn-primary" data-act="quiz-submit" data-id="${p.id}">
           提交这份（${qs.length} 题）
         </button>
         <button class="btn btn-danger" data-act="quiz-skip" data-id="${p.id}">跳过</button>
       </div>`
    : `<div class="qz-actions" style="margin-top:var(--s4)">
         <button class="btn" data-act="quiz-skip" data-id="${p.id}">跳过这条</button>
       </div>`;

  return `<div class="panel qz-paper" data-pid="${p.id}">
    <div class="panel-head">
      <span class="panel-title">${esc(p.course_name || '—')} · ${esc(p.title || '章节测验')}</span>
      <span class="row" style="gap:var(--s1)">${tags.join('')}</span>
    </div>
    ${body}
    ${ops}
  </div>`;
}

function viewQuizQuestion(p, q, idx) {
  const opts = quizOptList(q);
  const judge = q.type === 'judge';
  const raw = String(quizAnswer(p.id, q)).trim();
  const cur = judge ? raw.toLowerCase() : raw.toUpperCase();
  const typeName = QZ_TYPE_NAME[q.type] || q.type || '';

  let control;
  if (opts.length) {
    control = `<div class="qz-opts">` + opts.map(o => {
      const on = judge ? cur === o.value : cur.includes(o.value);
      return `<button class="qz-opt ${on ? 'on' : ''}" data-act="qz-pick"
                data-pid="${p.id}" data-qid="${esc(q.id)}" data-letter="${esc(o.value)}">
        <span class="qz-opt-key">${esc(o.key)}</span>
        ${o.text ? `<span class="qz-opt-text">${esc(o.text)}</span>` : ''}
      </button>`;
    }).join('') + `</div>`;
  } else {
    control = `<div class="qz-opts">
      <input class="field" data-act="qz-fill" data-pid="${p.id}" data-qid="${esc(q.id)}"
             value="${esc(raw)}"
             placeholder="${q.type === 'short' ? '写一段简答' : '填答案；多个空用 ||| 分隔'}">
    </div>`;
  }

  const aiBits = [];
  if (q.suggestion) aiBits.push(`AI 建议 <b>${esc(q.suggestion)}</b>`);
  if (q.confidence) {
    aiBits.push(`<span class="qz-conf qz-conf-${esc(q.confidence)}">${esc(q.confidence)}</span>`);
  }
  if (q.reason) aiBits.push(esc(q.reason));
  if (q.note) aiBits.push(`<span class="qz-note">${esc(q.note)}</span>`);
  const aiLine = aiBits.length ? `<div class="qz-ai">${aiBits.join(' · ')}</div>` : '';

  return `<div class="qz-q ${q.manual ? 'is-manual' : ''}">
    <div class="qz-head">
      <span class="qz-no">${idx + 1}.</span>
      <span class="qz-title">
        ${typeName ? `<span class="tag" style="margin-right:6px">${esc(typeName)}</span>` : ''}
        ${esc(q.title)}
      </span>
    </div>
    ${control}
    ${aiLine}
  </div>`;
}

function paintQuizBusy(pid, busy) {
  $$('.qz-paper').forEach(el => {
    if (el.dataset.pid !== String(pid)) return;
    $$('button', el).forEach(b => { b.disabled = busy; });
  });
}

async function doQuizSubmit(pid) {
  const p = (S.quiz.items || []).find(x => String(x.id) === String(pid));
  if (!p) return;
  const qs = p.questions || [];
  if (!qs.length) { toast('这份没有题目，直接跳过吧', 'err'); return; }

  const answers = {};
  const missing = [];
  qs.forEach((q, i) => {
    const a = String(quizAnswer(pid, q)).trim();
    if (!a) missing.push(i + 1);
    answers[q.id] = a;
  });
  if (missing.length) {
    toast(`第 ${missing.join('、')} 题还没有答案`, 'err');
    return;
  }

  const manual = qs.filter(q => q.manual).length;
  const msg = `提交「${p.course_name || ''} · ${p.title || '章节测验'}」？\n\n`
    + `共 ${qs.length} 题${manual ? `，其中 ${manual} 题 AI 没把握` : ''}。\n\n`
    + '提交后不能撤销、不能重做，答案会立刻计分。';
  if (!confirm(msg)) return;

  paintQuizBusy(pid, true);
  const r = await api(`/api/quiz/${pid}/submit`, { method: 'POST', body: { answers } });
  const ok = !!(r && ((r.data && r.data.ok) || r.ok === true || r.code === 200));
  const why = (r && ((r.data && r.data.message) || r.msg || r.error)) || '未知错误';
  toast(ok ? '已提交' : '提交失败：' + why, ok ? 'ok' : 'err');
  if (ok) {
    S.quiz.items = (S.quiz.items || []).filter(x => String(x.id) !== String(pid));
    delete S.quiz.answers[pid];
    render();
    poll();
  } else {
    paintQuizBusy(pid, false);
  }
}

async function doQuizSkip(pid) {
  if (!confirm('跳过这份测验？\n\n跳过后不会再出现在这个列表里，你随时可以去超星网页上自己补做。')) return;
  paintQuizBusy(pid, true);
  const r = await api(`/api/quiz/${pid}/skip`, { method: 'POST', body: { reason: '界面跳过' } });
  const ok = !!(r && (r.code === 200 || r.ok === true));
  toast(ok ? '已跳过' : '跳过失败：' + ((r && (r.msg || r.error)) || '未知错误'), ok ? 'ok' : 'err');
  if (ok) {
    S.quiz.items = (S.quiz.items || []).filter(x => String(x.id) !== String(pid));
    delete S.quiz.answers[pid];
    render();
    poll();
  } else {
    paintQuizBusy(pid, false);
  }
}

/* ---------------- AI 配置（设置页） ---------------- */

// 策略说明。键必须和后端 QuizMode 的取值一致 —— 这边只负责写进
// app/ai-config.json，真正执行的是 Java 那边。
const QUIZ_MODE_DESC = {
  confirm: '抓到题、AI 出完答案就挂到「待确认」，你逐题看过再点提交。最稳，但得有人盯着。',
  auto_high: '每道题都过了校验、且 AI 自评全是 high 才自动交；只要有一道没把握，整份退回「待确认」。',
  auto_medium: '门槛放宽到 medium，那些「大概率对」的也一起交掉。可能有个别错题，但不用天天盯着。',
  auto_any: '只要能答出来就交，包含 AI 自评 low 的题。完全不想管的时候用，错了不负责。',
  off: '遇到章节测验直接跳过，和 v1.0.8 之前一样。「待确认」页会一直是空的。',
};

function renderQuizModeDesc() {
  const sel = $('#ai-answer-mode');
  const box = $('#ai-mode-desc');
  if (!sel || !box) return;
  box.textContent = QUIZ_MODE_DESC[sel.value] || '';
}

// 草稿。设置页一重绘（切标签、页面重载、客户端重启后窗口重开）表单就会被
// 服务端值填回去，用户刚敲进去的地址和模型名当场蒸发，再点保存存回去的还是旧值。
// 所以每敲一下就往 localStorage 存一份，重绘时发现草稿和磁盘不一致就把草稿填回去。
const AI_DRAFT_KEY = 'sas-ai-draft';
const AI_FIELDS = ['ai-base-url', 'ai-model', 'ai-key', 'ai-timeout', 'ai-answer-mode'];

function readAiDraft() {
  try { return JSON.parse(localStorage.getItem(AI_DRAFT_KEY) || 'null'); } catch (e) { return null; }
}
function writeAiDraft() {
  const el = $('#ai-base-url');
  if (!el) return;
  try { localStorage.setItem(AI_DRAFT_KEY, JSON.stringify(collectAiConfig())); } catch (e) { /* 隐私模式写不进去就算了 */ }
}
function clearAiDraft() {
  try { localStorage.removeItem(AI_DRAFT_KEY); } catch (e) { /* 同上 */ }
}
function aiFormFocused() {
  const a = document.activeElement;
  return !!(a && a.id && AI_FIELDS.indexOf(a.id) >= 0);
}

// 草稿和磁盘上的值是否一致。地址末尾斜杠、超时数字类型这些不算差异。
function sameAiConfig(d, c) {
  const norm = s => String(s == null ? '' : s).trim().replace(/\/+$/, '');
  if (norm(d.base_url) !== norm(c.base_url)) return false;
  if (norm(d.model) !== norm(c.model)) return false;
  if (String(d.answer_mode || 'confirm') !== String(c.answer_mode || 'confirm')) return false;
  if (Number(d.timeout_sec || 90) !== Number(c.timeout_sec || 90)) return false;
  const k = String(d.api_key || '');
  if (k && k.indexOf('****') < 0 && k !== String(c.api_key || '')) return false;
  return true;
}

function restoreAiDraft(c) {
  const box = $('#ai-config-body');
  if (!box) return;
  const d = readAiDraft();
  if (!d || sameAiConfig(d, c)) return;
  const put = (id, v) => { const el = $('#' + id); if (el) el.value = v; };
  put('ai-base-url', d.base_url || '');
  put('ai-model', d.model || '');
  put('ai-timeout', d.timeout_sec || 90);
  put('ai-answer-mode', d.answer_mode || 'confirm');
  // 掩码 key 不回填，回填了也存不进去（服务端认得出 **** 表示「不改」）
  if (d.api_key && String(d.api_key).indexOf('****') < 0) put('ai-key', d.api_key);
  renderQuizModeDesc();
  if ($('#ai-draft-tip')) return;
  box.insertAdjacentHTML('afterbegin',
    `<div class="hint" id="ai-draft-tip" style="color:var(--warn);margin-bottom:var(--s2);line-height:1.7">
       上次改的还没保存，已经帮你填回表单了（磁盘上仍是旧配置）。确认无误点「保存 AI 配置」才会写进去。
     </div>`);
}

async function renderAiConfig() {
  const r = await api('/api/ai-config');
  const box = $('#ai-config-body');
  if (!box) return;
  if (!r || !r.ok) {
    box.insertAdjacentHTML('afterbegin',
      `<div class="hint" style="color:var(--err);margin-bottom:var(--s2)">读取配置失败：${esc((r && r.error) || '未知错误')}</div>`);
    return;
  }
  const c = r.config || {};
  S.quiz.ai = c;
  // 光标正在某个输入框里就别覆盖 —— 这时候填值等于把用户敲了一半的地址抹掉
  if (aiFormFocused()) return;
  const put = (id, v) => { const el = $('#' + id); if (el) el.value = v; };
  put('ai-base-url', c.base_url || '');
  put('ai-model', c.model || '');
  put('ai-key', c.api_key || '');
  put('ai-timeout', c.timeout_sec || 90);
  put('ai-answer-mode', c.answer_mode || 'confirm');
  renderQuizModeDesc();
  restoreAiDraft(c);
  const st = $('#ai-status');
  if (st) {
    st.innerHTML = c.has_key
      ? `<span class="tag tag-ok">已配置</span>`
      : `<span class="tag tag-err">未配置</span>`;
  }
  const p = $('#ai-path');
  if (p && c.path) p.textContent = c.path;
}

function collectAiConfig() {
  const val = id => (($('#' + id) || {}).value || '').trim();
  return {
    base_url: val('ai-base-url'),
    model: val('ai-model'),
    api_key: val('ai-key'),
    timeout_sec: Number(val('ai-timeout')) || 90,
    answer_mode: val('ai-answer-mode') || 'confirm',
  };
}

async function doSaveAiConfig() {
  const r = await api('/api/ai-config', { method: 'POST', body: collectAiConfig() });
  if (!r || !r.ok) { toast('保存失败：' + ((r && r.error) || '未知错误'), 'err'); return; }
  S.quiz.ai = r.config;
  clearAiDraft();
  const tip = $('#ai-draft-tip');
  if (tip) tip.remove();
  toast(r.config.has_key ? 'AI 配置已保存，下份测验就会用它' : '已保存，但 api_key 还是空的', r.config.has_key ? 'ok' : 'err');
  renderAiConfig();
}

async function doTestAiConfig() {
  const btn = $('[data-act="test-ai"]');
  if (btn) { btn.disabled = true; btn.textContent = '正在测…'; }
  const r = await api('/api/ai-config/test', { method: 'POST', body: collectAiConfig() });
  if (btn) { btn.disabled = false; btn.textContent = '测一下能不能用'; }
  if (r && r.ok) toast(`通了：${r.ms}ms，模型回「${r.reply}」`, 'ok');
  else toast('不通：' + ((r && r.error) || '未知错误'), 'err');
}

/* ---------------- 日志 ---------------- */
function viewLogsShell() {
  return `<div class="view-inner">
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">后端日志</span>
        <span class="faint" style="font-size:var(--t-xs)" id="log-count"></span>
      </div>
      <div class="log-toolbar">
        <input class="field" id="log-filter" placeholder="过滤关键词（课程名 / 任务名 / 关键词）"
               value="${esc(S.logFilter)}">
        <label class="row" style="gap:6px;font-size:var(--t-sm);color:var(--fg-1)">
          <input type="checkbox" id="log-only-err" ${S.logOnlyErr ? 'checked' : ''}
                 style="accent-color:var(--err)">只看告警
        </label>
        <label class="row" style="gap:6px;font-size:var(--t-sm);color:var(--fg-1)">
          <input type="checkbox" id="log-auto" ${S.logAutoScroll ? 'checked' : ''}
                 style="accent-color:var(--accent)">自动滚动
        </label>
      </div>
      <div class="log-box" id="log-box"></div>
    </div>
  </div>`;
}

function renderLogs(d, incremental) {
  const box = $('#log-box');
  if (!box) return;
  const filter = S.logFilter.toLowerCase();
  const lines = (d.logs || []).filter(ln => {
    if (S.logOnlyErr && !/ERROR|WARN|风控|9010|退避|被拒|异常|失败/.test(ln)) return false;
    if (filter && !ln.toLowerCase().includes(filter)) return false;
    return true;
  });

  $('#log-count').textContent = `${lines.length} / ${(d.logs || []).length} 行`;

  const atBottom = box.scrollTop + box.clientHeight >= box.scrollHeight - 30;
  box.innerHTML = lines.map(ln => {
    let cls = '';
    if (/ERROR|风控|9010|被拒|失败|异常/.test(ln)) cls = 'hl-err';
    else if (/WARN|退避|未刷完|未计分/.test(ln)) cls = 'hl-warn';
    else if (/计分|已完成|Started|running/.test(ln)) cls = 'hl-ok';
    else if (/^\s*$|^--/.test(ln)) cls = 'hl-dim';
    let text = esc(ln);
    if (filter) {
      const i = ln.toLowerCase().indexOf(filter);
      if (i >= 0) {
        text = esc(ln.slice(0, i)) + '<mark>' + esc(ln.slice(i, i + filter.length)) + '</mark>' + esc(ln.slice(i + filter.length));
      }
    }
    return `<span class="log-line ${cls}">${text || '&nbsp;'}</span>`;
  }).join('');

  if (S.logAutoScroll && (atBottom || !incremental)) box.scrollTop = box.scrollHeight;
}

/* ---------------- 版本与更新 ----------------
   设置页不随轮询整体重绘（要保留用户输入），所以这里单独刷新：
   viewSettings 只画外壳 <div id="update-body">，内容每次 poll 由 renderUpdate 重画。 */

function fmtBytes(n) {
  n = Number(n) || 0;
  if (n >= 1048576) return (n / 1048576).toFixed(1) + ' MB';
  if (n >= 1024) return (n / 1024).toFixed(0) + ' KB';
  return n + ' B';
}

function fmtAgo(ts) {
  if (!ts) return '';
  const s = Math.max(0, Math.round(Date.now() / 1000 - Number(ts)));
  if (s < 60) return '刚刚';
  if (s < 3600) return Math.floor(s / 60) + ' 分钟前';
  if (s < 86400) return Math.floor(s / 3600) + ' 小时前';
  return Math.floor(s / 86400) + ' 天前';
}

function renderUpdate(d) {
  const box = $('#update-body');
  if (!box) return;
  const u = d.update || {};
  const info = u.info || null;
  const prog = u.progress || null;

  // updater.py 缺失/挂了：更新模块没加载，只能引导去发布页手动下载
  if (!u.available) {
    box.innerHTML = `<div class="update-msg err">更新模块未加载（${esc(u.reason || '未知原因')}），
      请到 GitHub Releases 页面手动下载安装包。</div>`;
    return;
  }

  let html = `<div class="setting-row">
    <div>
      <div class="setting-name">当前版本 <span class="mono">v${esc(u.current || '?')}</span></div>
      <div class="setting-desc">
        更新来源 <span class="mono">${esc((info && info.repo) || 'GitHub')}</span>${
          u.last_check ? ' · 上次检查 ' + esc(fmtAgo(u.last_check)) : ' · 尚未检查'}
      </div>
    </div>
    <div class="setting-input" style="align-items:flex-end">
      <button class="btn" data-act="update-check"
              ${(u.checking || u.applying) ? 'disabled' : ''}>${
                u.checking ? '检查中…' : '检查更新'}</button>
    </div>
  </div>`;

  // ---- 状态行 ----
  if (u.checking) {
    html += `<div class="update-msg">正在检查更新…</div>`;
  } else if (u.applying) {
    html += `<div class="update-msg warn">正在更新，客户端会自动重启，请勿关闭窗口…</div>`;
  } else if (u.error) {
    html += `<div class="update-msg err">${esc(u.error)}</div>`;
  } else if (info && info.has_update) {
    html += `<div class="update-msg accent">发现新版本 <b class="mono">v${esc(info.latest)}</b>${
      info.published ? '（发布于 ' + esc(String(info.published).slice(0, 10)) + '）' : ''}</div>`;
  } else if (info && info.same_version) {
    html += `<div class="update-msg ok">已是最新版本 <span class="mono">v${esc(info.latest)}</span></div>`;
  } else if (info) {
    html += `<div class="update-msg">当前版本比发布页上的最新版（<span class="mono">v${
      esc(info.latest)}</span>）还新</div>`;
  }

  // ---- 下载源顺序（只在有更新时提，平时是噪音）----
  if (info && info.has_update) {
    const ms = (info.mirrors || []).map(m => String(m)
      .replace(/^https?:\/\//, '').replace(/\{url\}$/, '').replace(/\/$/, ''));
    html += `<div class="update-hint" style="margin-top:0">下载顺序：官方 GitHub${
      ms.length ? ' → ' + esc(ms.join(' → ')) : '（未配置镜像，只用官方）'}</div>`;
  }

  // ---- 新版本说明 ----
  if (info && info.has_update && info.notes && String(info.notes).trim()) {
    html += `<div class="update-notes">
      <div class="update-notes-head">v${esc(info.latest)} 更新内容</div>
      <div class="update-notes-body">${esc(String(info.notes).trim())}</div>
    </div>`;
  }

  // ---- 更新按钮（有新版且没在更新中才给） ----
  if (info && info.has_update && !u.applying && !u.checking) {
    html += `<div class="update-actions">`;
    if (info.client_asset) {
      html += `<button class="btn btn-primary" data-act="update-apply" data-mode="client"
        title="只替换客户端代码文件，后端与正在刷的课不受影响">
        更新客户端（约 ${esc(fmtBytes(info.client_asset.size))}）</button>`;
    }
    if (info.setup_asset) {
      html += `<button class="btn" data-act="update-apply" data-mode="full"
        title="完整安装包，安装期间刷课会暂停约一分钟">
        完整安装包（约 ${esc(fmtBytes(info.setup_asset.size))}）</button>`;
    }
    if (!info.client_asset && !info.setup_asset) {
      html += `<a class="btn" href="https://github.com/${esc(info.repo)}/releases/latest"
        target="_blank" rel="noreferrer">打开发布页手动下载</a>`;
    }
    html += `</div>
    <div class="update-hint">
      「更新客户端」只覆盖 client/ 下的代码，后端 jar 和正在刷的课完全不受影响；
      「完整安装包」会更新全部组件，安装过程中刷课暂停约一分钟，装完回界面点「启动服务」即可。
    </div>`;
  }

  // ---- 进度条 ----
  if (prog && prog.phase) {
    const pct = prog.total > 0 ? Math.min(100, prog.done / prog.total * 100) : 0;
    const detail = prog.total > 0
      ? `${esc(fmtBytes(prog.done))} / ${esc(fmtBytes(prog.total))}（${pct.toFixed(0)}%）`
      : (prog.done ? esc(fmtBytes(prog.done)) : '');
    html += `<div class="update-progress">
      <div class="bar${prog.total > 0 ? '' : ' indet'}"><i style="width:${
        prog.total > 0 ? pct.toFixed(1) : 35}%"></i></div>
      <div class="update-progress-text"><b>${esc(prog.phase)}</b>${detail ? ' · ' + detail : ''}</div>
    </div>`;
  }

  box.innerHTML = html;
}

/* ---------------- 任务点详情 ---------------- */

// 后端 /api/points 是「异步扫描」：首次调用只触发扫描并立刻返回
// scanning=true，之后轮询拿 scannedChapters/totalChapters 画进度。
// 一门课几十上百章，全量扫要 1~2 分钟，所以必须给用户看得见的进度。
let pointsToken = 0;

async function showPoints(courseId, courseName, force) {
  pointsToken++;
  const myToken = pointsToken;
  openModal(courseName || '任务点', pointsLoadingHtml(0, 0, force));
  await pollPoints(courseId, courseName, !!force, myToken);
}

function pointsLoadingHtml(done, total, scanning) {
  const pct = total > 0 ? Math.min(100, done / total * 100) : 0;
  return `<div class="empty" style="padding:var(--s5) var(--s4)">
    <div style="margin-bottom:var(--s3)">${scanning ? '正在从超星拉取任务点清单…' : '准备中…'}</div>
    <div class="bar" style="width:min(420px,100%);margin:0 auto"><i style="width:${pct.toFixed(1)}%"></i></div>
    <div class="faint mono" style="margin-top:var(--s3);font-size:var(--t-xs)">
      ${total > 0 ? `已扫描 ${done} / ${total} 章` : '正在获取章节列表…'}
    </div>
    <div class="faint" style="margin-top:var(--s2);font-size:var(--t-xs)">
      首次扫描需要一两分钟，完成后 5 分钟内重复查看会秒开
    </div>
  </div>`;
}

async function pollPoints(courseId, courseName, force, myToken) {
  const q = `/api/points?courseId=${encodeURIComponent(courseId)}${force ? '&refresh=true' : ''}`;
  const r = await api(q);
  if (myToken !== pointsToken) return;          // 用户已经关了/换了弹层

  if (!r || r.code !== 200 || !r.data) {
    $('#modal-body').innerHTML = pointsErrorHtml(
      (r && (r.msg || r.error)) || '未知错误', courseId, courseName);
    return;
  }
  const d = r.data;

  if (d.error) {
    $('#modal-body').innerHTML = pointsErrorHtml(d.error, courseId, courseName);
    return;
  }
  if (d.scanning) {
    $('#modal-body').innerHTML = pointsLoadingHtml(d.scannedChapters || 0, d.totalChapters || 0, true);
    setTimeout(() => pollPoints(courseId, courseName, false, myToken), 1200);
    return;
  }
  renderPoints(d, courseId, courseName);
}

function pointsErrorHtml(msg, courseId, courseName) {
  return `<div class="empty">扫描失败：${esc(msg)}<br>
    <span class="faint" style="font-size:var(--t-xs)">若是超星风控（9010），请等几分钟再试</span></div>
    <div class="row" style="justify-content:center;margin-top:var(--s4)">
      <button class="btn" data-act="points-refresh" data-id="${esc(courseId)}" data-name="${esc(courseName)}">重试</button>
    </div>`;
}

function renderPoints(d, courseId, courseName) {
  const jobs = d.jobs || [];
  if (!jobs.length) {
    $('#modal-body').innerHTML = `<div class="empty">
      没有拉到任务点（可能都已完成，或超星返回了占位页）
      <div class="row" style="justify-content:center;margin-top:var(--s4)">
        <button class="btn" data-act="points-refresh" data-id="${esc(courseId)}" data-name="${esc(courseName)}">重新扫描</button>
      </div></div>`;
    return;
  }

  // 按章节分组
  const groups = new Map();
  jobs.forEach(j => {
    const k = j.chapter || '(未分章)';
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k).push(j);
  });

  const doneCount = jobs.filter(j => j.passed).length;
  const videoCount = jobs.filter(j => j.type === 'video').length;
  const videoDone = jobs.filter(j => j.type === 'video' && j.passed).length;

  let html = `<div class="points-head">
    <span class="tag tag-accent">任务点 ${doneCount}/${jobs.length}</span>
    <span class="tag">视频 ${videoDone}/${videoCount}</span>
    <span class="faint mono" style="font-size:var(--t-xs)">已扫 ${d.scannedChapters}/${d.totalChapters} 章</span>
    <button class="btn btn-sm" style="margin-left:auto" data-act="points-refresh"
      data-id="${esc(courseId)}" data-name="${esc(courseName)}">重新扫描</button>
  </div>`;

  for (const [chapter, list] of groups) {
    const chDone = list.filter(j => j.passed).length;
    html += `<div style="margin-top:var(--s4)">
      <div class="chapter-head">
        <span class="setting-name" style="font-size:var(--t-sm)">${esc(chapter)}</span>
        <span class="faint mono" style="font-size:var(--t-xs)">${chDone}/${list.length}</span>
      </div>
      <div class="job-list">`;
    for (const j of list) {
      const icon = j.passed
        ? `<svg class="job-mark ok" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6L9 17l-5-5"/></svg>`
        : `<svg class="job-mark no" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/></svg>`;
      const played = (j.playTime || 0) / 1000;
      const pct = (j.duration && played) ? Math.min(100, played / j.duration * 100) : 0;
      const durTxt = j.duration
        ? `${fmtDur(played)} / ${fmtDur(j.duration)}`
        : (played ? fmtDur(played) : '—');
      html += `<div class="job-row${j.passed ? ' is-done' : ''}">
        ${icon}
        <div style="min-width:0">
          <div class="job-name" title="${esc(j.name)}">${esc(j.name || j.jobId || '—')}</div>
          ${pct > 0 ? `<div class="bar" style="margin-top:4px"><i style="width:${pct.toFixed(1)}%"></i></div>` : ''}
        </div>
        <div class="job-type">${esc(j.type || '')}</div>
        <div class="job-dur mono">${durTxt}</div>
      </div>`;
    }
    html += `</div></div>`;
  }
  $('#modal-body').innerHTML = html;
}

/* ---------------- 动作 ---------------- */
async function doStart() {
  S.busy = true;
  toast('正在启动 MySQL 与后端…');
  const r = await api('/api/service/start', { method: 'POST' });
  if (r.ok && (!r.result || r.result === 'ready' || r.result === 'already')) {
    toast((r.messages || []).join(' ｜ ') || '已启动', 'ok');
  } else {
    const parts = [
      r.errorCode || '',
      r.reason || (r.messages || []).join(' ｜ ') || '启动失败',
      r.nextStep ? ('下一步：' + r.nextStep) : '',
      r.logPath ? ('日志：' + r.logPath) : '',
    ].filter(Boolean);
    toast(parts.join(' ｜ '), 'err');
  }
  S.busy = false;
  poll();
}

async function doStop() {
  if (!confirm('确定要停止后端和 MySQL 吗？\n正在刷的任务会被中断。')) return;
  S.busy = true;
  toast('正在停止…');
  const r = await api('/api/service/stop', { method: 'POST' });
  toast((r.messages || []).join(' ｜ ') || '已停止', 'ok');
  S.busy = false;
  poll();
}

async function doLogin() {
  const account = ($('#in-acc') || {}).value || '';
  const password = ($('#in-pwd') || {}).value || '';
  if (!account || !password) { toast('账号和密码都要填', 'err'); return; }
  S.enroll.loading = true;
  S.enroll.courses = null;
  if (S.view === 'enroll') render();
  const r = await api('/api/login', { method: 'POST', body: { account, password } });
  S.enroll.loading = false;
  if (r.ok) {
    S.enroll.courses = r.courses || [];
    S.enroll.picked = new Set();
    toast(`登录成功，拉到 ${S.enroll.courses.length} 门课程`, 'ok');
  } else {
    S.enroll.courses = [];
    toast('登录失败：' + (r.error || '未知错误'), 'err');
  }
  if (S.view === 'enroll') render();
}

async function doEnroll() {
  const picked = Array.from(S.enroll.picked);
  if (!picked.length) { toast('先勾选课程', 'err'); return; }
  const map = new Map((S.enroll.courses || []).map(c => [String(c.courseId), c]));
  let ok = 0, fail = 0;
  for (const cid of picked) {
    const c = map.get(cid) || {};
    const r = await api('/api/enroll', { method: 'POST', body: { courseId: cid, courseName: c.title || '' } });
    if (r.ok) ok++; else fail++;
  }
  toast(`加入队列：成功 ${ok} 门${fail ? `，失败 ${fail} 门` : ''}`, fail ? 'err' : 'ok');
  S.enroll.picked = new Set();
  if (S.view === 'enroll') render();
  poll();
}

async function doRemove(id, name) {
  if (!confirm(`确定把「${name}」移出队列吗？\n\n这是唯一能真正停掉这门课的方式。`)) return;
  const r = await api('/api/course/' + encodeURIComponent(id), { method: 'DELETE' });
  toast(r.ok ? '已移出队列' : '操作失败', r.ok ? 'ok' : 'err');
  poll();
}

async function doPriority(id, cur) {
  const v = prompt('设置优先级（1 = 最高）：', cur || 1);
  if (v === null) return;
  const n = parseInt(v, 10);
  if (!n || n < 1 || n > 10) { toast('优先级要在 1~10 之间', 'err'); return; }
  const r = await api('/api/course/' + encodeURIComponent(id), { method: 'PATCH', body: { priority: n } });
  toast(r.ok ? '优先级已更新（下一次排队生效）' : (r.error || '操作失败'), r.ok ? 'ok' : 'err');
  poll();
}

async function doRowReset() {
  const r = await api('/api/queue/reset', { method: 'POST' });
  const msgs = (r && r.messages) ? r.messages.join(' ｜ ') : ((r && r.error) || '续刷失败');
  toast(msgs, r.ok ? 'ok' : 'err');
  poll();
}

function collectSettings() {
  return {
    keep_awake: !!($('#set-keep-awake') || {}).checked,
  };
}

/* 防休眠开关：客户端本地行为，改完立即生效，不用重启后端 */
async function toggleKeepAwake(el) {
  const on = !!el.checked;
  const r = await api('/api/settings', { method: 'POST', body: { keep_awake: on } });
  toast(on ? '已开启防休眠（立即生效）' : '已关闭防休眠', r.ok ? 'ok' : 'err');
  if (!r.ok) el.checked = !on;
  const sub = el.parentElement && el.parentElement.querySelector('.check-sub');
  if (sub) sub.textContent = on ? '生效中' : '已关闭';
  poll();
}

async function doSaveSettings() {
  const r = await api('/api/settings', { method: 'POST', body: collectSettings() });
  if (!r.ok) { toast('保存失败', 'err'); return; }
  toast('设置已保存并生效', 'ok');
  poll();
}

/* ---------------- 在线更新 ---------------- */
async function doUpdateCheck() {
  const r = await api('/api/update/check?force=1');
  if (r && r.ok) toast('检查更新完成', 'ok');
  else toast('检查失败：' + ((r && r.error) || '网络错误'), 'err');
  poll();
}

async function doUpdateApply(mode) {
  const desc = mode === 'client'
    ? '只更新客户端代码（几百 KB），后端和正在刷的课不受影响'
    : '下载完整安装包并覆盖安装（百 MB 级），安装期间刷课会暂停约一分钟';
  if (!confirm('确定现在安装更新吗？\n\n' + desc +
               '\n\n下载并校验通过后，客户端会自动重启。')) return;
  const r = await api('/api/update/apply', { method: 'POST', body: { mode: mode } });
  if (r && r.ok) toast('更新已开始，正在下载…', 'ok');
  else toast('启动更新失败：' + ((r && r.error) || '未知错误'), 'err');
  poll();
}

/* ---------------- 事件 ---------------- */
document.addEventListener('click', async (e) => {
  const tab = e.target.closest('.tab');
  if (tab) {
    S.view = tab.dataset.view;
    location.hash = S.view;
    $$('.tab').forEach(t => t.classList.toggle('is-active', t === tab));
    render();
    return;
  }
  if (e.target.id === 'modal-close' || e.target.id === 'modal') { closeModal(); return; }

  const el = e.target.closest('[data-act]');
  if (!el) return;
  const act = el.dataset.act;

  if (act === 'points') return showPoints(el.dataset.id, el.dataset.name);
  if (act === 'points-refresh') return showPoints(el.dataset.id, el.dataset.name, true);
  if (act === 'del') return doRemove(el.dataset.id, el.dataset.name);
  if (act === 'prio') return doPriority(el.dataset.id, el.dataset.prio);
  if (act === 'row-reset') return doRowReset();
  if (act === 'login') return doLogin();
  if (act === 'enroll') return doEnroll();
  if (act === 'save-settings') return doSaveSettings();
  if (act === 'update-check') return doUpdateCheck();
  if (act === 'update-apply') return doUpdateApply(el.dataset.mode);

  // ---- 待确认测验
  if (act === 'qz-pick') {
    const pid = el.dataset.pid, qid = el.dataset.qid;
    const q = quizQ(pid, qid);
    if (!q) return;
    const letter = el.dataset.letter;
    if (q.type === 'multiple') {
      // 多选是开关：再点一次取消，最后按字母序拼起来交给后端
      const picked = String(quizAnswer(pid, q)).toUpperCase()
        .split('').filter(c => /[A-Z]/.test(c));
      const i = picked.indexOf(letter);
      if (i >= 0) picked.splice(i, 1); else picked.push(letter);
      picked.sort();
      setQuizAnswer(pid, qid, picked.join(''));
    } else {
      setQuizAnswer(pid, qid, letter);
    }
    paintQuizOpts(pid, qid);
    return;
  }
  if (act === 'quiz-reload') { S.quiz.items = null; return loadQuiz(); }
  if (act === 'quiz-submit') return doQuizSubmit(el.dataset.id);
  if (act === 'quiz-skip') return doQuizSkip(el.dataset.id);

  // ---- AI 配置
  if (act === 'save-ai') return doSaveAiConfig();
  if (act === 'test-ai') return doTestAiConfig();
});

document.addEventListener('change', (e) => {
  const el = e.target;
  if (el.dataset && el.dataset.act === 'pick') {
    const id = el.dataset.id;
    if (el.checked) S.enroll.picked.add(id); else S.enroll.picked.delete(id);
    render();
  }
  if (el.dataset && el.dataset.act === 'keep-awake') {
    toggleKeepAwake(el);
  }
  if (el.id === 'log-only-err') { S.logOnlyErr = el.checked; renderLogs(S.data, false); }
  if (el.id === 'log-auto') { S.logAutoScroll = el.checked; }
  if (el.id === 'ai-answer-mode') { renderQuizModeDesc(); writeAiDraft(); }
});

document.addEventListener('input', (e) => {
  if (e.target.id === 'log-filter') {
    S.logFilter = e.target.value;
    renderLogs(S.data, false);
    return;
  }
  // AI 配置：边敲边存草稿，页面被重绘/重开也不丢
  if (e.target.id && AI_FIELDS.indexOf(e.target.id) >= 0) { writeAiDraft(); return; }
  // 填空/简答：只记进 state，不重绘 —— 一重绘输入框就失焦，没法连续打字
  const el = e.target;
  if (el.dataset && el.dataset.act === 'qz-fill') {
    setQuizAnswer(el.dataset.pid, el.dataset.qid, el.value);
  }
});

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') closeModal();
});

$('#btn-start').addEventListener('click', doStart);
$('#btn-stop').addEventListener('click', doStop);
$('#btn-rescan').addEventListener('click', async () => {
  const r = await api('/api/queue/rescan', { method: 'POST' });
  toast((r.messages || [r.error]).join(' ｜ '), r.ok ? 'ok' : 'err');
  poll();
});
$('#btn-reset').addEventListener('click', async () => {
  const r = await api('/api/queue/reset', { method: 'POST' });
  toast((r.messages || [r.error]).join(' ｜ '), r.ok ? 'ok' : 'err');
  poll();
});
$('#btn-open').addEventListener('click', () => {
  window.open('http://localhost:3030/', '_blank');
});

/* ---------------- 启动 ---------------- */
const VIEWS = ['overview', 'courses', 'quiz', 'enroll', 'settings', 'logs'];
function applyHash() {
  const v = (location.hash || '').replace('#', '');
  if (VIEWS.includes(v) && v !== S.view) {
    S.view = v;
    $$('.tab').forEach(t => t.classList.toggle('is-active', t.dataset.view === v));
    render();
  }
}
window.addEventListener('hashchange', applyHash);
applyHash();
poll();
setInterval(poll, POLL_MS);
