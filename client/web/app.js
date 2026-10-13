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
      // 首次拿到数据时任何视图都要重绘（否则直接落在 #settings 会一直显示「正在连接」）；
      // 之后只有总览/课程随轮询重绘，设置/选课/日志保留用户输入与滚动位置。
      if (first || S.view === 'overview' || S.view === 'courses') render();
      else if (S.view === 'logs') renderLogs(d, true);
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

/* ---------------- 视图分派 ---------------- */
function render() {
  const d = S.data;
  const v = $('#view');
  if (!d) { v.innerHTML = `<div class="view-inner"><div class="empty">正在连接本地服务…</div></div>`; return; }

  if (S.view === 'overview') v.innerHTML = viewOverview(d);
  else if (S.view === 'courses') v.innerHTML = viewCourses(d);
  else if (S.view === 'enroll') v.innerHTML = viewEnroll(d);
  else if (S.view === 'settings') v.innerHTML = viewSettings(d);
  else if (S.view === 'logs') v.innerHTML = viewLogsShell(d);

  if (S.view === 'logs') renderLogs(d, false);
  if (S.view === 'enroll') ensureEnrollCourses(d);
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
        <dt>上报节奏</dt><dd>${d.settings.video_report_pace_ms} ms / 条</dd>
        <dt>并发线程</dt><dd>${d.settings.core_size}</dd>
        <dt>跳过任务点</dt><dd>${esc((d.settings.skip_job_types || []).join(', ') || '（无）')}</dd>
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
    return `<div class="course-row">
      <div>
        <div class="course-name" title="${esc(c.courseName)}">${esc(c.courseName || '—')}</div>
        <div class="course-sub">${esc(c.courseId)} · 优先级 ${c.priority}</div>
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
    const inQueue = new Set(d.queue.map(c => String(c.courseId)));
    listHtml = `<div class="check-list">` + S.enroll.courses.map(c => {
      const cid = String(c.courseId);
      const already = inQueue.has(cid);
      return `<label class="check-item">
        <input type="checkbox" data-act="pick" data-id="${esc(cid)}"
               ${picked.has(cid) ? 'checked' : ''} ${already ? 'disabled' : ''}>
        <span class="check-title">${esc(c.title || '(无标题)')}</span>
        <span class="check-sub">${esc(cid)}${already ? ' · 已在队列' : ''}</span>
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
      「不要做题」等行为由「设置」里的跳过规则控制。
    </div>
  </div>`;
}

/* ---------------- 设置 ---------------- */
function viewSettings(d) {
  const s = d.settings;
  const types = ['workid', 'document', 'read', 'hyperlink'];
  const typeNames = { workid: '做题 / 章节测验', document: '文档', read: '阅读', hyperlink: '超链接' };

  return `<div class="view-inner">
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">刷课参数</span>
        <span class="hint">保存后需要重启后端才生效</span>
      </div>

      <div class="setting-row">
        <div>
          <div class="setting-name">视频上报间隔</div>
          <div class="setting-desc">两条上报之间真实等多久（毫秒）。<b>不要低于 30000</b> —— 实测 10 秒一条会触发超星账号级风控。</div>
        </div>
        <div class="setting-input">
          <input class="field mono" id="set-pace" type="number" min="3000" step="1000"
                 value="${s.video_report_pace_ms}">
        </div>
      </div>

      <div class="setting-row">
        <div>
          <div class="setting-name">并发线程数</div>
          <div class="setting-desc">同时刷几门课。视频上报本身是全局排队节流的，这个值只决定同时扫描几门课。</div>
        </div>
        <div class="setting-input">
          <input class="field mono" id="set-core" type="number" min="1" max="10" value="${s.core_size}">
        </div>
      </div>

      <div class="setting-row">
        <div>
          <div class="setting-name">单视频最大重复遍数</div>
          <div class="setting-desc">超星偶尔要第二遍才落分。越大越慢，2 一般够用。</div>
        </div>
        <div class="setting-input">
          <input class="field mono" id="set-rounds" type="number" min="1" max="5" value="${s.video_max_rounds}">
        </div>
      </div>

      <div class="setting-row">
        <div>
          <div class="setting-name">全局跳过的任务点类型</div>
          <div class="setting-desc">对所有课程生效。勾上「做题 / 章节测验」就是「都不要做题」。</div>
        </div>
        <div class="setting-input">
          ${types.map(t => `
            <label class="check-item" style="padding-left:0">
              <input type="checkbox" data-act="skip" data-type="${t}"
                     ${(s.skip_job_types || []).includes(t) ? 'checked' : ''}>
              <span class="check-title" style="font-size:var(--t-sm)">${esc(typeNames[t])}</span>
              <span class="check-sub">${t}</span>
            </label>`).join('')}
        </div>
      </div>

      <div class="setting-row">
        <div>
          <div class="setting-name">只刷视频的课程 ID</div>
          <div class="setting-desc">每行一个 courseId。命中这些课程时，除视频外的任务点全部跳过。</div>
        </div>
        <div class="setting-input">
          <textarea class="field mono" id="set-video-only" rows="4"
                    placeholder="每行一个 courseId，例如 268045661">${esc((s.video_only_course_ids || []).join('\n'))}</textarea>
        </div>
      </div>

      <div class="row" style="margin-top:var(--s4)">
        <button class="btn btn-primary" data-act="save-settings">保存设置</button>
        <button class="btn" data-act="restart-backend">保存并重启后端</button>
      </div>
    </div>
  </div>`;
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
  toast((r.messages || []).join(' ｜ ') || '已启动', 'ok');
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
  toast(r.ok ? '优先级已更新（重启后端后重新排序）' : '操作失败', r.ok ? 'ok' : 'err');
  poll();
}

function collectSettings() {
  const skip = $$('input[data-act="skip"]').filter(i => i.checked).map(i => i.dataset.type);
  const videoOnly = ($('#set-video-only') || {}).value || '';
  return {
    video_report_pace_ms: parseInt(($('#set-pace') || {}).value || '60000', 10),
    core_size: parseInt(($('#set-core') || {}).value || '3', 10),
    video_max_rounds: parseInt(($('#set-rounds') || {}).value || '2', 10),
    skip_job_types: skip,
    video_only_course_ids: videoOnly.split(/[\s,]+/).map(s => s.trim()).filter(Boolean),
  };
}

async function doSaveSettings(restart) {
  const r = await api('/api/settings', { method: 'POST', body: collectSettings() });
  if (!r.ok) { toast('保存失败', 'err'); return; }
  if (restart) {
    toast('设置已保存，正在重启后端…');
    S.busy = true;
    await api('/api/service/stop', { method: 'POST' });
    const r2 = await api('/api/service/start', { method: 'POST' });
    toast((r2.messages || []).join(' ｜ ') || '已重启', 'ok');
    S.busy = false;
  } else {
    toast('设置已保存（重启后端后生效）', 'ok');
  }
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
  if (act === 'login') return doLogin();
  if (act === 'enroll') return doEnroll();
  if (act === 'save-settings') return doSaveSettings(false);
  if (act === 'restart-backend') return doSaveSettings(true);
});

document.addEventListener('change', (e) => {
  const el = e.target;
  if (el.dataset && el.dataset.act === 'pick') {
    const id = el.dataset.id;
    if (el.checked) S.enroll.picked.add(id); else S.enroll.picked.delete(id);
    render();
  }
  if (el.id === 'log-only-err') { S.logOnlyErr = el.checked; renderLogs(S.data, false); }
  if (el.id === 'log-auto') { S.logAutoScroll = el.checked; }
});

document.addEventListener('input', (e) => {
  if (e.target.id === 'log-filter') {
    S.logFilter = e.target.value;
    renderLogs(S.data, false);
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
const VIEWS = ['overview', 'courses', 'enroll', 'settings', 'logs'];
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
