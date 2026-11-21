"""HTML for the demo SUT. Semantic markup first (labels, roles, headings), data-testid as stable fallback."""

LAYOUT = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>{{title}} · QA Shop Admin</title>
<style>
 body{font-family:system-ui,sans-serif;margin:0;background:#f6f7f9;color:#1d2433}
 header{display:flex;gap:16px;align-items:center;padding:12px 24px;background:#1d2433;color:#fff}
 header a{color:#cfe0ff;text-decoration:none} header .spacer{flex:1}
 main{max-width:960px;margin:24px auto;padding:0 16px}
 form{display:grid;gap:10px;max-width:420px} label{display:grid;gap:4px;font-weight:600}
 input,select{padding:8px;font:inherit} button{padding:8px 14px;font:inherit;cursor:pointer}
 table{width:100%;border-collapse:collapse;background:#fff} th,td{padding:8px;border-bottom:1px solid #ddd;text-align:left}
 [role=alert]{color:#a40000} [role=status]{color:#0a6b2b} [hidden]{display:none!important}
</style>
<script>
const TOKEN_KEY = 'authToken';
function token() { return localStorage.getItem(TOKEN_KEY); }
async function api(path, opts = {}) {
  opts.headers = Object.assign({'Authorization': 'Bearer ' + token()}, opts.headers || {});
  if (opts.json !== undefined) { opts.body = JSON.stringify(opts.json); opts.headers['Content-Type'] = 'application/json'; }
  const r = await fetch(path, opts);
  if (r.status === 401 && location.pathname !== '/login') { logout(); }
  return r;
}
function logout() { localStorage.removeItem(TOKEN_KEY); location.href = '/login'; }
function errorText(body) {
  if (!body) return 'Unexpected error';
  if (Array.isArray(body.detail)) return body.detail.map(d => d.msg.replace('Value error, ', '')).join('; ');
  return body.detail || 'Unexpected error';
}
async function guard() {
  if (location.pathname === '/login') return null;
  if (!token()) { location.href = '/login'; return null; }
  const r = await api('/api/me');
  if (!r.ok) return null;
  const me = await r.json();
  document.getElementById('app-header').hidden = false;
  document.querySelector('[data-testid=signed-in-user]').textContent = me.displayName;
  return me;
}
</script>
</head>
<body>
<header data-testid="app-header" id="app-header" hidden>
  <strong>QA Shop Admin</strong>
  <nav aria-label="Main"><a href="/dashboard">Dashboard</a> · <a href="/users">Users</a> · <a href="/payments/new">Payments</a> · <a href="/files">Files</a></nav>
  <span class="spacer"></span>
  <span data-testid="signed-in-user"></span>
  <button type="button" data-testid="logout" onclick="logout()">Log out</button>
</header>
<main>{{content}}</main>
</body></html>"""

LOGIN = """<h1>Sign in</h1>
<form id="login-form" novalidate>
  <label>Username <input name="username" autocomplete="username" required></label>
  <label>Password <input name="password" type="password" autocomplete="current-password" required></label>
  <button type="submit">Sign in</button>
  <div role="alert" data-testid="login-error" hidden></div>
</form>
<script>
document.getElementById('login-form').addEventListener('submit', async e => {
  e.preventDefault();
  const f = new FormData(e.target), err = document.querySelector('[data-testid=login-error]');
  err.hidden = true;
  if (!f.get('username') || !f.get('password')) { err.textContent = 'Username and password are required'; err.hidden = false; return; }
  const r = await fetch('/api/auth/login', {method: 'POST', headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({username: f.get('username'), password: f.get('password')})});
  if (!r.ok) { err.textContent = 'Invalid username or password'; err.hidden = false; return; }
  localStorage.setItem('authToken', (await r.json()).token);
  location.href = '/dashboard';
});
</script>"""

DASHBOARD = """<h1 data-testid="welcome">Loading…</h1>
<p>Registered users: <strong data-testid="user-count">–</strong></p>
<div role="alert" data-testid="load-error" hidden>Could not load dashboard data. Please retry.</div>
<script>
(async () => {
  const me = await guard(); if (!me) return;
  document.querySelector('[data-testid=welcome]').textContent = 'Welcome, ' + me.displayName;
  try {
    const r = await api('/api/users?size=1');
    if (!r.ok) throw new Error(r.status);
    document.querySelector('[data-testid=user-count]').textContent = (await r.json()).total;
  } catch (e) { document.querySelector('[data-testid=load-error]').hidden = false; }
})();
</script>"""

USERS = """<h1>User management</h1>
<div style="display:flex;gap:8px;margin-bottom:12px">
  <input type="search" aria-label="Search users" placeholder="Search by name or email" data-testid="user-search">
  <button type="button" id="add-user">Add user</button>
</div>
<table data-testid="users-table" aria-label="Users">
  <thead><tr><th>Name</th><th>Email</th><th>Job</th><th>Actions</th></tr></thead><tbody></tbody>
</table>
<p data-testid="users-empty" hidden>No users found</p>
<dialog id="user-dialog" aria-labelledby="dlg-title">
  <h2 id="dlg-title">Add user</h2>
  <form id="user-form" method="dialog" novalidate>
    <label>Name <input name="name" required></label>
    <label>Email <input name="email" type="email" required></label>
    <label>Job <input name="job"></label>
    <div role="alert" data-testid="user-form-error" hidden></div>
    <div><button type="submit">Save</button> <button type="button" id="cancel-user">Cancel</button></div>
  </form>
</dialog>
<script>
const dlg = document.getElementById('user-dialog');
async function load(q = '') {
  const r = await api('/api/users?size=100&q=' + encodeURIComponent(q)); const body = await r.json();
  const tbody = document.querySelector('[data-testid=users-table] tbody'); tbody.innerHTML = '';
  for (const u of body.data) {
    const tr = document.createElement('tr');
    tr.innerHTML = `<td></td><td></td><td></td><td><a href="/users/${u.id}">View</a> <button type="button">Delete</button></td>`;
    tr.children[0].textContent = u.name; tr.children[1].textContent = u.email; tr.children[2].textContent = u.job;
    tr.querySelector('button').setAttribute('aria-label', 'Delete ' + u.name);
    tr.querySelector('button').onclick = async () => {
      if (!confirm('Delete ' + u.name + '?')) return;
      await api('/api/users/' + u.id, {method: 'DELETE'}); load(document.querySelector('[data-testid=user-search]').value);
    };
    tbody.appendChild(tr);
  }
  document.querySelector('[data-testid=users-empty]').hidden = body.data.length > 0;
}
document.querySelector('[data-testid=user-search]').addEventListener('input', e => load(e.target.value));
document.getElementById('add-user').onclick = () => { document.getElementById('user-form').reset(); dlg.showModal(); };
document.getElementById('cancel-user').onclick = () => dlg.close();
document.getElementById('user-form').addEventListener('submit', async e => {
  e.preventDefault(); const f = new FormData(e.target), err = document.querySelector('[data-testid=user-form-error]');
  const r = await api('/api/users', {method: 'POST', json: {name: f.get('name'), email: f.get('email'), job: f.get('job')}});
  if (!r.ok) { err.textContent = errorText(await r.json()); err.hidden = false; return; }
  err.hidden = true; dlg.close(); load(document.querySelector('[data-testid=user-search]').value);
});
guard().then(me => me && load());
</script>"""

PROFILE = """<h1>User profile</h1>
<dl>
  <dt>Name</dt><dd data-testid="user-name"></dd>
  <dt>Email</dt><dd data-testid="user-email"></dd>
  <dt>Job</dt><dd data-testid="user-job"></dd>
</dl>
<h2>Edit profile</h2>
<form id="profile-form" novalidate>
  <label>Name <input name="name"></label>
  <label>Job <input name="job"></label>
  <button type="submit">Save changes</button>
  <div role="status" data-testid="save-status"></div>
</form>
<script>
const uid = '{{uid}}';
function render(u) {
  document.querySelector('[data-testid=user-name]').textContent = u.name;
  document.querySelector('[data-testid=user-email]').textContent = u.email;
  document.querySelector('[data-testid=user-job]').textContent = u.job;
  const f = document.getElementById('profile-form'); f.name.value = u.name; f.job.value = u.job;
}
let current;
guard().then(async me => { if (!me) return; const r = await api('/api/users/' + uid); current = await r.json(); render(current); });
document.getElementById('profile-form').addEventListener('submit', async e => {
  e.preventDefault(); const f = e.target;
  const r = await api('/api/users/' + uid, {method: 'PUT', json: {name: f.name.value, email: current.email, job: f.job.value}});
  if (r.ok) { current = await r.json(); render(current); document.querySelector('[data-testid=save-status]').textContent = 'Profile saved'; }
  else { document.querySelector('[data-testid=save-status]').textContent = 'Save failed'; }
});
</script>"""

PAYMENT = """<h1>New payment</h1>
<form id="payment-form" novalidate>
  <label>Cardholder name <input name="cardholder" autocomplete="cc-name"></label>
  <label>Card number <input name="cardNumber" inputmode="numeric" autocomplete="cc-number"></label>
  <label>Expiry (MM/YY) <input name="expiry" placeholder="MM/YY" autocomplete="cc-exp"></label>
  <label>CVV <input name="cvv" inputmode="numeric" autocomplete="cc-csc"></label>
  <label>Amount <input name="amount" inputmode="decimal"></label>
  <label for="currency">Currency</label> <select id="currency" name="currency"><option>USD</option><option>EUR</option><option>GBP</option><option>INR</option></select>
  <button type="submit">Pay now</button>
</form>
<div role="status" data-testid="payment-success" hidden></div>
<div role="alert" data-testid="payment-error" hidden></div>
<script>
guard();
document.getElementById('payment-form').addEventListener('submit', async e => {
  e.preventDefault(); const f = new FormData(e.target);
  const ok = document.querySelector('[data-testid=payment-success]'), bad = document.querySelector('[data-testid=payment-error]');
  ok.hidden = bad.hidden = true;
  const r = await api('/api/payments', {method: 'POST', json: {cardholder: f.get('cardholder'), cardNumber: f.get('cardNumber'),
    expiry: f.get('expiry'), cvv: f.get('cvv'), amount: Number(f.get('amount')), currency: f.get('currency')}});
  const body = await r.json();
  if (r.status === 201) { ok.textContent = 'Payment approved. Reference ' + body.reference; ok.hidden = false; }
  else if (r.status === 402) { bad.textContent = 'Payment declined by issuer'; bad.hidden = false; }
  else { bad.textContent = 'Payment rejected: ' + errorText(body); bad.hidden = false; }
});
</script>"""

FILES = """<h1>Files</h1>
<form id="upload-form">
  <label>Choose file <input type="file" name="file"></label>
  <button type="submit">Upload</button>
</form>
<p role="status" data-testid="upload-result"></p>
<button type="button" id="download">Download users report</button>
<script>
guard();
document.getElementById('upload-form').addEventListener('submit', async e => {
  e.preventDefault();
  const r = await api('/api/files', {method: 'POST', body: new FormData(e.target)}); const b = await r.json();
  document.querySelector('[data-testid=upload-result]').textContent = `Uploaded ${b.filename} (${b.size} bytes)`;
});
document.getElementById('download').onclick = async () => {
  const r = await api('/api/reports/users.csv'); const blob = await r.blob();
  const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = 'users-report.csv';
  document.body.appendChild(a); a.click(); a.remove();
};
</script>"""

PAGES = {"layout": LAYOUT, "login": LOGIN, "dashboard": DASHBOARD, "users": USERS, "profile": PROFILE,
         "payment": PAYMENT, "files": FILES}
