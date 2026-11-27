'use strict';

const $ = (id) => document.getElementById(id);
const state = {
  seats: [],
  passengers: [],
  origin: null,
  dest: null,
  dates: [],
  trains: [],
  picked: new Set(),
  lastEventId: 0,
  qr: null,
  qrTimer: null,
};

async function api(path, options = {}) {
  const res = await fetch(path, {
    credentials: 'same-origin',
    headers: options.body ? { 'Content-Type': 'application/json' } : {},
    ...options,
  });
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (!res.ok) {
    throw new Error(data && data.error ? data.error : `HTTP ${res.status}`);
  }
  return data;
}

function toast(msg, isErr = false) {
  const el = $('toast');
  el.textContent = msg;
  el.className = 'toast' + (isErr ? ' err' : '');
  el.hidden = false;
  clearTimeout(el._t);
  el._t = setTimeout(() => { el.hidden = true; }, 4500);
}

function fmt(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return isNaN(d) ? iso : d.toLocaleString('zh-CN', { hour12: false });
}

function todayStr(offsetDays = 0) {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function escapeHtml(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

/* ---------------- 登录 ---------------- */

$('admin-login').onclick = async () => {
  $('admin-err').textContent = '';
  try {
    await api('/api/auth/admin', { method: 'POST', body: JSON.stringify({ password: $('admin-pwd').value }) });
    await enterApp();
  } catch (e) {
    $('admin-err').textContent = e.message;
  }
};
$('admin-pwd').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('admin-login').click(); });
$('logout').onclick = async () => { await api('/api/auth/logout', { method: 'POST' }); location.reload(); };

async function enterApp() {
  $('login-view').hidden = true;
  $('app-view').hidden = false;

  $('date-input').min = todayStr(0);
  $('date-input').max = todayStr(14);
  $('date-input').value = todayStr(1);
  addDate();

  await Promise.all([loadMeta(), refreshStatus(), loadPassengers(), loadTasks(), loadOrders(), loadSettings()]);
  startLogs();
  setInterval(refreshStatus, 5000);
  setInterval(loadTasks, 5000);
}

/* ---------------- 状态 ---------------- */

async function refreshStatus() {
  try {
    const [me, gate, booking] = await Promise.all([
      api('/api/auth/me'), api('/api/gate'), api('/api/booking'),
    ]);
    const el = $('login-state');
    el.textContent = me.loggedIn12306 ? '已登录 12306' : '未登录 12306';
    el.className = 'pill ' + (me.loggedIn12306 ? 'on' : 'off');
    $('login-card').hidden = !!me.loggedIn12306;

    const gateEl = $('gate-state');
    gateEl.textContent = gate.state === 'HOLD' ? '已暂停，待处理订单' : `今日 ${gate.created} 单`;
    gateEl.className = 'pill ' + (gate.state === 'HOLD' ? 'bad' : 'ok');
    gateEl.title = gate.reason || '';

    $('auto-booking').checked = !!booking.enabled;
  } catch (e) { /* 会话失效忽略 */ }
}

$('auto-booking').onchange = async (e) => {
  const enabled = e.target.checked;
  if (enabled && !confirm('开启后，命中余票会自动提交订单（只到待支付，不会付款）。确定？')) {
    e.target.checked = false;
    return;
  }
  try {
    await api('/api/booking', { method: 'POST', body: JSON.stringify({ enabled }) });
    toast(enabled ? '已开启自动下单' : '已切换为监控模式');
    refreshStatus();
  } catch (err) {
    e.target.checked = !enabled;
    toast(err.message, true);
  }
};

/* ---------------- 扫码 / 短信 ---------------- */

$('qr-start').onclick = () => startQr();

async function startQr() {
  clearInterval(state.qrTimer);
  try {
    const qr = await api('/api/auth/qr', { method: 'POST' });
    state.qr = { uuid: qr.uuid, deadline: Date.now() + 45_000 };
    $('qr-box').hidden = false;
    $('qr-img').src = 'data:image/png;base64,' + qr.imageBase64;
    $('sms-box').hidden = true;
    tickQr();
    state.qrTimer = setInterval(async () => {
      await pollQr();
      tickQr();
    }, 2000);
  } catch (e) {
    toast(e.message, true);
  }
}

function tickQr() {
  if (!state.qr) return;
  const left = Math.max(0, Math.round((state.qr.deadline - Date.now()) / 1000));
  $('qr-msg').textContent = left > 0 ? `请用 12306 App 扫码，${left} 秒后自动刷新` : '刷新中…';
  if (left === 0) {
    // 12306 的二维码一分钟内就过期，这里提前重建，用户不用手动点
    state.qr.deadline = Date.now() + 45_000;
    api('/api/auth/qr', { method: 'POST' }).then((qr) => {
      state.qr.uuid = qr.uuid;
      $('qr-img').src = 'data:image/png;base64,' + qr.imageBase64;
    }).catch(() => {});
  }
}

async function pollQr() {
  if (!state.qr) return;
  try {
    const r = await api('/api/auth/qr/' + encodeURIComponent(state.qr.uuid));
    if (r.state === 'LOGGED_IN') {
      clearInterval(state.qrTimer);
      state.qr = null;
      $('qr-box').hidden = true;
      toast('12306 登录成功');
      refreshStatus();
      loadPassengers();
    } else if (r.state === 'EXPIRED' || r.state === 'FAILED') {
      state.qr.deadline = Date.now();
    }
  } catch (e) {
    clearInterval(state.qrTimer);
  }
}

$('sms-toggle').onclick = () => { $('sms-box').hidden = !$('sms-box').hidden; };

$('sms-send').onclick = async () => {
  $('sms-msg').textContent = '';
  try {
    const r = await api('/api/auth/sms/send', {
      method: 'POST', body: JSON.stringify({ username: $('sms-user').value.trim() }),
    });
    $('sms-msg').textContent = r.message || '验证码已发送';
  } catch (e) { $('sms-msg').textContent = e.message; }
};

$('sms-login').onclick = async () => {
  $('sms-msg').textContent = '';
  try {
    const r = await api('/api/auth/sms/login', {
      method: 'POST',
      body: JSON.stringify({
        username: $('sms-user').value.trim(),
        password: $('sms-pass').value,
        smsCode: $('sms-code').value.trim(),
      }),
    });
    $('sms-pass').value = '';
    $('sms-code').value = '';
    toast('登录成功' + (r.username ? '：' + r.username : ''));
    refreshStatus();
    loadPassengers();
  } catch (e) { $('sms-msg').textContent = e.message; }
};

/* ---------------- 车站选择 ---------------- */

function wireStation(input, list, key) {
  let timer = null;
  input.addEventListener('input', () => {
    state[key] = null;
    clearTimeout(timer);
    const q = input.value.trim();
    if (!q) { list.hidden = true; return; }
    timer = setTimeout(async () => {
      try {
        const rows = await api('/api/stations/search?q=' + encodeURIComponent(q) + '&limit=12');
        if (!rows.length) {
          list.innerHTML = '<div class="dropdown-item muted">没有匹配的车站</div>';
        } else {
          list.innerHTML = rows.map((s) =>
            `<div class="dropdown-item" data-code="${escapeHtml(s.code)}" data-name="${escapeHtml(s.name)}">${escapeHtml(s.name)}<span class="muted"> ${escapeHtml(s.code)}</span></div>`
          ).join('');
        }
        list.hidden = false;
      } catch (e) { list.hidden = true; }
    }, 220);
  });
  list.addEventListener('mousedown', (e) => {
    const item = e.target.closest('.dropdown-item');
    if (!item || !item.dataset.code) return;
    state[key] = { code: item.dataset.code, name: item.dataset.name };
    input.value = item.dataset.name;
    list.hidden = true;
  });
  input.addEventListener('blur', () => setTimeout(() => { list.hidden = true; }, 150));
}

wireStation($('from-input'), $('from-list'), 'origin');
wireStation($('to-input'), $('to-list'), 'dest');

/* ---------------- 日期 ---------------- */

function addDate() {
  const value = $('date-input').value;
  if (!value) { toast('先选个日期', true); return; }
  if (value < todayStr(0)) { toast('不能选过去的日期', true); return; }
  if (value > todayStr(14)) { toast('12306 只放 15 天内的票', true); return; }
  if (state.dates.includes(value)) { toast('这个日期已经加过了', true); return; }
  if (state.dates.length >= 5) { toast('最多 5 个日期', true); return; }
  state.dates.push(value);
  renderDates();
}

function renderDates() {
  $('date-chips').innerHTML = state.dates.map((d) =>
    `<span class="chip">${escapeHtml(d)}<button type="button" data-date="${escapeHtml(d)}" class="chip-x">×</button></span>`
  ).join('');
}

$('btn-add-date').onclick = addDate;
$('date-chips').addEventListener('click', (e) => {
  const btn = e.target.closest('.chip-x');
  if (!btn) return;
  state.dates = state.dates.filter((d) => d !== btn.dataset.date);
  renderDates();
});

/* ---------------- 查余票 ---------------- */

$('btn-query').onclick = async () => {
  if (!state.origin || !state.dest) { toast('先从下拉里选出发站和到达站', true); return; }
  if (!state.dates.length) { toast('至少加一个乘车日期', true); return; }
  $('query-msg').textContent = '查询中…';
  $('train-table').innerHTML = '';
  try {
    const r = await api('/api/query', {
      method: 'POST',
      body: JSON.stringify({ date: state.dates[0], from: state.origin.code, to: state.dest.code }),
    });
    state.trains = r.trains;
    state.picked.clear();
    $('query-msg').textContent = `${state.dates[0]} 共 ${r.total} 趟，显示 ${r.count} 趟`;
    renderTrains();
  } catch (e) {
    $('query-msg').textContent = e.message;
  }
};

function renderTrains() {
  if (!state.trains.length) {
    $('train-table').innerHTML = '<p class="muted">没有查到车次</p>';
    $('train-count').textContent = '';
    return;
  }
  const rows = state.trains.map((t, i) => {
    const seats = Object.entries(t.seats).map(([name, s]) => {
      const price = s.price ? `¥${s.price}` : '';
      const count = s.count > 0 ? `${s.count}张` : (s.raw || '无');
      return `<span class="seat ${s.available ? 'ok' : 'off'}">${escapeHtml(name)} ${escapeHtml(count)}${price ? ' · ' + price : ''}</span>`;
    }).join('');
    return `<tr>
      <td><input type="checkbox" class="train-pick" data-idx="${i}"></td>
      <td class="mono">${escapeHtml(t.trainNo)}</td>
      <td>${escapeHtml(t.startTime)}<div class="muted">${escapeHtml(t.fromName)}</div></td>
      <td>${escapeHtml(t.arriveTime)}<div class="muted">${escapeHtml(t.toName)}</div></td>
      <td class="muted">${escapeHtml(t.duration)}</td>
      <td><div class="seats">${seats || '<span class="muted">无</span>'}</div></td>
    </tr>`;
  }).join('');
  $('train-table').innerHTML = `<table class="trains">
    <thead><tr><th></th><th>车次</th><th>出发</th><th>到达</th><th>历时</th><th>席别 / 余票 / 票价</th></tr></thead>
    <tbody>${rows}</tbody></table>`;
  $('train-count').textContent = `已选 ${state.picked.size} 趟`;
}

$('train-table').addEventListener('change', (e) => {
  const box = e.target.closest('.train-pick');
  if (!box) return;
  const train = state.trains[Number(box.dataset.idx)];
  if (box.checked) state.picked.add(train.trainNo); else state.picked.delete(train.trainNo);
  $('train-count').textContent = `已选 ${state.picked.size} 趟`;
});

/* ---------------- 乘客 / 席别 ---------------- */

$('pax-sync').onclick = async () => {
  try {
    const r = await api('/api/passengers/sync', { method: 'POST' });
    toast(`已同步 ${r.synced} 位乘车人`);
    await loadPassengers();
  } catch (e) { toast(e.message, true); }
};

async function loadPassengers() {
  state.passengers = await api('/api/passengers');
  $('pax-box').innerHTML = state.passengers.length
    ? state.passengers.map((p) =>
      `<label class="chip"><input type="checkbox" value="${p.id}"> ${escapeHtml(p.nameMasked)} ${escapeHtml(p.idNoMasked)}</label>`
    ).join('')
    : '<span class="muted">还没有乘客，先点「同步乘车人」</span>';
}

async function loadMeta() {
  const meta = await api('/api/meta');
  state.seats = meta.seats || [];
  $('seat-box').innerHTML = state.seats.map((s) =>
    `<label class="chip"><input type="checkbox" value="${escapeHtml(s)}" ${s === '二等座' ? 'checked' : ''}> ${escapeHtml(s)}</label>`
  ).join('');
}

/* ---------------- 开始抢票 ---------------- */

$('btn-start').onclick = async () => {
  if (!state.origin || !state.dest) { toast('先选出发站和到达站', true); return; }
  if (!state.dates.length) { toast('至少加一个日期', true); return; }
  if (!state.picked.size) { toast('先在车次表里勾几趟车', true); return; }
  const seats = [...$('seat-box').querySelectorAll('input:checked')].map((i) => i.value);
  if (!seats.length) { toast('至少选一个席别', true); return; }
  const pax = [...$('pax-box').querySelectorAll('input:checked')].map((i) => Number(i.value));
  if (!pax.length) { toast('至少选一位乘客', true); return; }

  const startAt = $('start-at').value ? new Date($('start-at').value).toISOString() : '';
  const body = {
    mode: 'GRAB',
    from: state.origin.code,
    to: state.dest.code,
    dates: state.dates,
    trains: [...state.picked],
    seatTypes: seats,
    passengerIds: pax,
    intervalMs: Number($('interval').value) || 1000,
    startAt,
  };
  $('start-msg').textContent = '创建任务…';
  try {
    const task = await api('/api/tasks', { method: 'POST', body: JSON.stringify(body) });
    await api('/api/tasks/' + task.id + '/start', { method: 'POST' });
    $('start-msg').textContent = '已开始，看下面的日志';
    toast('抢票任务已启动');
    loadTasks();
  } catch (e) {
    $('start-msg').textContent = '';
    toast(e.message, true);
  }
};

$('btn-stop').onclick = async () => {
  try {
    const tasks = await api('/api/tasks');
    await Promise.all(tasks.filter((t) => t.running).map((t) =>
      api('/api/tasks/' + t.id + '/stop', { method: 'POST' })));
    toast('已停止');
    loadTasks();
  } catch (e) { toast(e.message, true); }
};

/* ---------------- 任务列表 ---------------- */

async function loadTasks() {
  const tasks = await api('/api/tasks');
  if (!tasks.length) {
    $('tasks').innerHTML = '<span class="muted">还没有任务</span>';
    return;
  }
  $('tasks').innerHTML = tasks.map((t) => `
    <div class="task">
      <div>
        <strong>${escapeHtml(t.name)}</strong>
        <span class="pill ${statusClass(t.status)}">${t.status}</span>
        <div class="meta">
          ${escapeHtml(t.fromName)} → ${escapeHtml(t.toName)} ·
          ${t.dates.map(escapeHtml).join('、')} ·
          ${t.seatTypes.map(escapeHtml).join('/')} ·
          ${t.trains.length ? t.trains.map(escapeHtml).join(',') : '车次不限'}
          ${t.startAt ? ' · 开抢 ' + fmt(t.startAt) : ''}
        </div>
      </div>
      <div class="row">
        <button class="small" data-act="start" data-id="${t.id}">启动</button>
        <button class="small ghost" data-act="stop" data-id="${t.id}">停止</button>
        <button class="small ghost" data-act="del" data-id="${t.id}">删除</button>
      </div>
    </div>`).join('');
}

function statusClass(s) {
  if (s === 'RUNNING') return 'on';
  if (s === 'HELD' || s === 'NEED_VERIFY') return 'bad';
  if (s === 'DONE') return 'warn';
  return '';
}

$('tasks').onclick = async (e) => {
  const btn = e.target.closest('button[data-act]');
  if (!btn) return;
  const id = btn.dataset.id;
  try {
    if (btn.dataset.act === 'start') await api(`/api/tasks/${id}/start`, { method: 'POST' });
    else if (btn.dataset.act === 'stop') await api(`/api/tasks/${id}/stop`, { method: 'POST' });
    else {
      if (!confirm('删除任务？')) return;
      await api(`/api/tasks/${id}`, { method: 'DELETE' });
    }
    loadTasks();
  } catch (err) { toast(err.message, true); }
};

/* ---------------- 日志 / 订单 ---------------- */

function startLogs() {
  setInterval(async () => {
    try {
      const r = await api(`/api/events?after=${state.lastEventId}&limit=100`);
      if (!r.events.length) return;
      state.lastEventId = r.events[r.events.length - 1].id;
      const box = $('logs');
      for (const ev of r.events) {
        const line = document.createElement('div');
        line.className = 'log-line ' + ev.level;
        line.textContent = `${fmt(ev.createdAt)} [${ev.level}] ${ev.message}`;
        box.appendChild(line);
      }
      while (box.childElementCount > 300) box.removeChild(box.firstChild);
      box.scrollTop = box.scrollHeight;
    } catch (e) { /* 忽略 */ }
  }, 2000);
}

async function loadOrders() {
  const orders = await api('/api/orders');
  $('orders').innerHTML = orders.length
    ? orders.map((o) => `<div class="task"><div><strong>${escapeHtml(o.orderNo)}</strong>
        <span class="pill ${o.status === 'PENDING_PAY' ? 'warn' : 'on'}">${o.status}</span>
        <div class="meta">${escapeHtml(o.trainNo || '')} ${escapeHtml(o.seatType || '')} · ${fmt(o.createdAt)}</div>
      </div></div>`).join('')
    : '<span class="muted">还没有订单</span>';
}

/* ---------------- 设置 ---------------- */

async function loadSettings() {
  const s = await api('/api/settings');
  $('set-interval').value = s.intervalMs;
  $('set-smtp-host').value = s.smtp.host;
  $('set-smtp-port').value = s.smtp.port;
  $('set-smtp-user').value = s.smtp.username;
  $('set-smtp-to').value = s.smtp.to;
  $('set-smtp-pass').value = '';
  $('set-smtp-pass').placeholder = s.smtp.password === '******' ? '已设置，留空不修改' : '';
  $('set-wx-provider').value = s.wechat.provider;
  $('set-wx-key').value = '';
  $('set-wx-key').placeholder = s.wechat.key === '******' ? '已设置，留空不修改' : '';
}

$('save-settings').onclick = async () => {
  try {
    const s = await api('/api/settings');
    s.intervalMs = Number($('set-interval').value);
    s.smtp.host = $('set-smtp-host').value;
    s.smtp.port = Number($('set-smtp-port').value);
    s.smtp.username = $('set-smtp-user').value;
    s.smtp.to = $('set-smtp-to').value;
    if ($('set-smtp-pass').value) s.smtp.password = $('set-smtp-pass').value;
    s.wechat.provider = $('set-wx-provider').value;
    if ($('set-wx-key').value) s.wechat.key = $('set-wx-key').value;
    await api('/api/settings', { method: 'PUT', body: JSON.stringify(s) });
    toast('设置已保存');
    loadSettings();
  } catch (e) { toast(e.message, true); }
};

$('test-notify').onclick = async () => {
  try {
    const r = await api('/api/settings/test-notify', { method: 'POST' });
    toast(r.message || '已发送');
  } catch (e) { toast(e.message, true); }
};

api('/api/auth/me').then(enterApp).catch(() => {});
