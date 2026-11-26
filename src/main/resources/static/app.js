'use strict';

const $ = (id) => document.getElementById(id);
const state = { seats: [], passengers: [], lastEventId: 0, qrTimer: null };

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
    const msg = data && data.error ? data.error : `HTTP ${res.status}`;
    throw new Error(msg);
  }
  return data;
}

function toast(msg, isErr = false) {
  const el = $('toast');
  el.textContent = msg;
  el.className = 'toast' + (isErr ? ' err' : '');
  el.hidden = false;
  clearTimeout(el._t);
  el._t = setTimeout(() => { el.hidden = true; }, 4000);
}

function fmt(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return isNaN(d) ? iso : d.toLocaleString('zh-CN', { hour12: false });
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

$('logout').onclick = async () => {
  await api('/api/auth/logout', { method: 'POST' });
  location.reload();
};

async function enterApp() {
  $('login-view').hidden = true;
  $('app-view').hidden = false;
  $('q-date').value = new Date(Date.now() + 86400000).toISOString().slice(0, 10);
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
    const loginEl = $('login-state');
    loginEl.textContent = me.loggedIn12306 ? '已登录 12306' : '未登录 12306';
    loginEl.className = 'pill ' + (me.loggedIn12306 ? 'on' : 'off');

    const gateEl = $('gate-state');
    gateEl.textContent = gate.state === 'HOLD'
      ? '闸门 已暂停'
      : `今日 ${gate.created}/${gate.limit} 单`;
    gateEl.className = 'pill ' + (gate.state === 'HOLD' ? 'bad' : (gate.canSubmit ? 'on' : 'warn'));
    gateEl.title = gate.reason || '';

    $('auto-booking').checked = !!booking.enabled;
  } catch (e) { /* 会话失效时忽略 */ }
}

$('auto-booking').onchange = async (e) => {
  const enabled = e.target.checked;
  if (enabled && !confirm('开启后，命中余票会自动提交订单（只到待支付，不会付款）。确定开启？')) {
    e.target.checked = false;
    return;
  }
  try {
    const r = await api('/api/booking', { method: 'POST', body: JSON.stringify({ enabled }) });
    toast(r.enabled ? '已开启自动下单' : '已切换为监控模式');
    refreshStatus();
  } catch (err) {
    e.target.checked = !enabled;
    toast(err.message, true);
  }
};

/* ---------------- 扫码登录 ---------------- */

$('qr-start').onclick = async () => {
  try {
    const qr = await api('/api/auth/qr', { method: 'POST' });
    $('qr-box').hidden = false;
    $('qr-img').src = 'data:image/png;base64,' + qr.imageBase64;
    $('qr-msg').textContent = '请用 12306 App 扫码';
    clearInterval(state.qrTimer);
    state.qrTimer = setInterval(() => pollQr(qr.uuid), 2000);
  } catch (e) { toast(e.message, true); }
};

async function pollQr(uuid) {
  try {
    const r = await api('/api/auth/qr/' + encodeURIComponent(uuid));
    $('qr-msg').textContent = r.message || r.state;
    if (r.state === 'LOGGED_IN') {
      clearInterval(state.qrTimer);
      $('qr-box').hidden = true;
      toast('12306 登录成功');
      refreshStatus();
      loadPassengers();
    } else if (r.state === 'EXPIRED' || r.state === 'FAILED') {
      clearInterval(state.qrTimer);
    }
  } catch (e) { clearInterval(state.qrTimer); toast(e.message, true); }
}

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
      method: 'POST', body: JSON.stringify({
        username: $('sms-user').value.trim(),
        password: $('sms-pass').value,
        smsCode: $('sms-code').value.trim(),
      }),
    });
    $('sms-pass').value = '';
    $('sms-code').value = '';
    toast('12306 登录成功' + (r.username ? '：' + r.username : ''));
    refreshStatus();
    loadPassengers();
  } catch (e) { $('sms-msg').textContent = e.message; }
};

$('cookie-import').onclick = async () => {
  try {
    const r = await api('/api/auth/cookie', {
      method: 'POST', body: JSON.stringify({ cookieHeader: $('cookie-input').value }),
    });
    toast(`已导入 ${r.imported} 个 Cookie`);
    refreshStatus();
  } catch (e) { toast(e.message, true); }
};

/* ---------------- 乘车人 ---------------- */

$('pax-sync').onclick = async () => {
  try {
    const r = await api('/api/passengers/sync', { method: 'POST' });
    toast(`已同步 ${r.synced} 位乘车人`);
    await loadPassengers();
  } catch (e) { toast(e.message, true); }
};

async function loadPassengers() {
  state.passengers = await api('/api/passengers');
  $('pax-count').textContent = state.passengers.length ? `已同步 ${state.passengers.length} 人` : '还没同步乘车人';
  $('pax-box').innerHTML = state.passengers.map(p =>
    `<label class="chip"><input type="checkbox" value="${p.id}"> ${p.nameMasked} ${p.idNoMasked}</label>`
  ).join('') || '<span class="muted">先点「同步乘车人」</span>';
}

/* ---------------- 查余票 ---------------- */

$('q-run').onclick = async () => {
  $('q-msg').textContent = '查询中…';
  $('q-result').innerHTML = '';
  try {
    const r = await api('/api/query', {
      method: 'POST',
      body: JSON.stringify({ date: $('q-date').value, from: $('q-from').value.trim(), to: $('q-to').value.trim() }),
    });
    $('q-msg').textContent = `共 ${r.total} 趟，显示 ${r.count} 趟`;
    renderTrains(r.trains);
  } catch (e) {
    $('q-msg').textContent = e.message;
  }
};

function renderTrains(trains) {
  if (!trains.length) {
    $('q-result').innerHTML = '<p class="muted">没有查到车次</p>';
    return;
  }
  const rows = trains.map((t) => {
    const seats = Object.entries(t.seats).map(([name, s]) => {
      const price = s.price ? `¥${s.price}` : '';
      const count = s.count > 0 ? `${s.count} 张` : (s.raw || '无');
      return `<span class="seat ${s.available ? 'ok' : 'off'}">${escapeHtml(name)} ${escapeHtml(count)}${price ? ' · ' + price : ''}</span>`;
    }).join('');
    return `<tr>
      <td class="mono">${escapeHtml(t.trainNo)}</td>
      <td>${escapeHtml(t.startTime)}<div class="muted">${escapeHtml(t.fromName)}</div></td>
      <td>${escapeHtml(t.arriveTime)}<div class="muted">${escapeHtml(t.toName)}</div></td>
      <td class="muted">${escapeHtml(t.duration)}</td>
      <td><div class="seats">${seats || '<span class="muted">无席别信息</span>'}</div></td>
    </tr>`;
  }).join('');
  $('q-result').innerHTML = `<table class="trains">
    <thead><tr><th>车次</th><th>出发</th><th>到达</th><th>历时</th><th>席别 / 余票 / 票价</th></tr></thead>
    <tbody>${rows}</tbody></table>`;
}

/* ---------------- 任务 ---------------- */

async function loadMeta() {
  try {
    const meta = await api('/api/meta');
    state.seats = meta.seats || [];
    $('seat-box').innerHTML = state.seats.map((s, i) =>
      `<label class="chip"><input type="checkbox" value="${s}" ${i === 0 ? 'checked' : ''}> ${s}</label>`
    ).join('');
  } catch (e) { /* 忽略 */ }
}

async function loadTasks() {
  const tasks = await api('/api/tasks');
  if (!tasks.length) {
    $('tasks').innerHTML = '<span class="muted">还没有任务</span>';
    return;
  }
  $('tasks').innerHTML = tasks.map(t => `
    <div class="task">
      <div>
        <strong>${escapeHtml(t.name)}</strong>
        <span class="pill ${statusClass(t.status)}">${t.status}</span>
        <div class="meta">
          ${escapeHtml(t.fromName)} → ${escapeHtml(t.toName)} ·
          ${t.dates.join(', ')} · ${t.seatTypes.join('/')} ·
          车次 ${t.trains.length ? t.trains.join(',') : '不限'}
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
    if (btn.dataset.act === 'start') {
      await api(`/api/tasks/${id}/start`, { method: 'POST' });
      toast('任务已启动');
    } else if (btn.dataset.act === 'stop') {
      await api(`/api/tasks/${id}/stop`, { method: 'POST' });
      toast('任务已停止');
    } else {
      if (!confirm('删除任务？')) return;
      await api(`/api/tasks/${id}`, { method: 'DELETE' });
    }
    loadTasks();
  } catch (err) { toast(err.message, true); }
};

$('task-toggle').onclick = () => { $('task-form').hidden = !$('task-form').hidden; };
$('task-cancel').onclick = () => { $('task-form').hidden = true; };

$('task-form').onsubmit = async (e) => {
  e.preventDefault();
  const f = e.target;
  const seats = [...$('seat-box').querySelectorAll('input:checked')].map(i => i.value);
  const pax = [...$('pax-box').querySelectorAll('input:checked')].map(i => Number(i.value));
  if (!seats.length) { toast('至少选一个席别', true); return; }
  if (!pax.length) { toast('至少选一个乘车人', true); return; }
  const startAt = f.startAt.value ? new Date(f.startAt.value).toISOString() : '';
  const body = {
    name: f.name.value,
    mode: f.mode.value,
    from: f.from.value.trim(),
    to: f.to.value.trim(),
    dates: f.dates.value.split(',').map(s => s.trim()).filter(Boolean),
    trains: f.trains.value.split(',').map(s => s.trim()).filter(Boolean),
    seatTypes: seats,
    passengerIds: pax,
    intervalMs: Number(f.intervalMs.value),
    startAt,
  };
  try {
    await api('/api/tasks', { method: 'POST', body: JSON.stringify(body) });
    f.reset();
    $('task-form').hidden = true;
    toast('任务已创建');
    loadTasks();
  } catch (err) { toast(err.message, true); }
};

/* ---------------- 日志 ---------------- */

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

/* ---------------- 订单 ---------------- */

async function loadOrders() {
  const orders = await api('/api/orders');
  $('orders').innerHTML = orders.length
    ? orders.map(o => `<div class="task"><div><strong>${escapeHtml(o.orderNo)}</strong>
        <span class="pill ${o.status === 'PENDING_PAY' ? 'warn' : 'on'}">${o.status}</span>
        <div class="meta">${escapeHtml(o.trainNo || '')} ${escapeHtml(o.seatType || '')} · ${fmt(o.createdAt)}</div>
      </div></div>`).join('')
    : '<span class="muted">还没有订单</span>';
}

/* ---------------- 设置 ---------------- */

async function loadSettings() {
  const s = await api('/api/settings');
  $('set-limit').value = s.dailyOrderLimit;
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
    s.dailyOrderLimit = Number($('set-limit').value);
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

/* ---------------- 工具 ---------------- */

function escapeHtml(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

api('/api/auth/me').then(enterApp).catch(() => {});
