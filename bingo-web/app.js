'use strict';

/*
 * 789bingo player pages: login, register, account, KYC. Plain JS, no build step.
 * Every call goes to /api/** on the same origin; dev_server.py proxies it to bingo-gateway.
 */

const TOKEN_KEY = 'bingo.session';
const DEVICE_KEY = 'bingo.device';
const MAX_IMAGE_BYTES = 10 * 1024 * 1024;
const IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp'];
const KYC_POLL_MS = 3000;

// Declared document types of SubmitKycRequest.identityType (OCR runs for 1 and 23).
const ID_TYPES = [
  [23, 'PhilID (National ID)'],
  [1, "Driver's licence"],
  [20, 'Passport'],
  [16, 'UMID'],
  [14, 'SSS ID'],
  [15, 'TIN ID'],
  [2, 'PhilHealth ID'],
  [3, 'Postal ID'],
  [17, "Voter's ID"],
];

// KycPlayerView.status
const KYC_STATE = {
  0: { tone: 'warn', title: 'Verifying your documents', text: 'This usually takes a minute. This page updates by itself.', busy: true },
  1: { tone: 'warn', title: 'Verifying your documents', text: 'This usually takes a minute. This page updates by itself.', busy: true },
  2: { tone: 'ok', title: 'Identity verified', text: 'Thank you. Your account is fully verified.' },
  3: { tone: 'bad', title: 'Verification rejected', text: 'Please check the reasons below and submit again.' },
  4: { tone: 'bad', title: 'Verification could not be completed', text: 'Something went wrong on our side. Please submit again.' },
};

// ------------------------------------------------------------------ session & API

const session = {
  get() {
    try {
      const s = JSON.parse(localStorage.getItem(TOKEN_KEY) || 'null');
      return s && new Date(s.expiresAt) > new Date() ? s : null;
    } catch { return null; }
  },
  set(login) {
    try { localStorage.setItem(TOKEN_KEY, JSON.stringify(login)); } catch { /* private mode: session lives until reload */ }
    memorySession = login;
  },
  clear() {
    try { localStorage.removeItem(TOKEN_KEY); } catch { /* ignore */ }
    memorySession = null;
  },
};
let memorySession = null;
const currentSession = () => session.get() || memorySession;

function deviceId() {
  try {
    let id = localStorage.getItem(DEVICE_KEY);
    if (!id) {
      // crypto.randomUUID needs a secure context: absent when a phone opens http://<LAN IP>
      id = crypto.randomUUID ? crypto.randomUUID()
        : Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) => b.toString(16).padStart(2, '0')).join('');
      localStorage.setItem(DEVICE_KEY, id);
    }
    return id;
  } catch { return 'web-anonymous'; }
}

class ApiError extends Error {
  constructor(message, status, code) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

/** Calls /api/**, unwraps Result<T>; throws ApiError with the server's message. */
async function api(path, { method = 'GET', body, form } = {}) {
  const headers = { 'X-Device-Id': deviceId() };
  const s = currentSession();
  if (s) headers.Authorization = `Bearer ${s.token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  let response;
  try {
    response = await fetch(path, { method, headers, body: form ?? (body !== undefined ? JSON.stringify(body) : undefined) });
  } catch {
    throw new ApiError('Cannot reach the server. Is the gateway running?', 0);
  }
  let result = null;
  try { result = await response.json(); } catch { /* non-JSON error page */ }

  if (response.status === 401) {
    session.clear();
    renderNav();
    throw new ApiError(result?.message && result.message !== 'unauthorized' ? result.message : 'Please log in again.', 401, result?.code);
  }
  if (!response.ok || !result || result.code !== 0) {
    throw new ApiError(result?.message || `Request failed (${response.status})`, response.status, result?.code);
  }
  return result.data;
}

// ------------------------------------------------------------------ tiny helpers

const view = document.getElementById('view');

function esc(value) {
  return String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function toast(message) {
  const el = document.getElementById('toast');
  el.textContent = message;
  el.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { el.hidden = true; }, 3500);
}

function go(hash) {
  if (location.hash === hash) route(); else location.hash = hash;
}

function setBusy(button, busy, label) {
  button.disabled = busy;
  if (busy) {
    button.dataset.label = button.textContent;
    button.textContent = label;
  } else if (button.dataset.label) {
    button.textContent = button.dataset.label;
  }
}

function showFormError(form, message) {
  let box = form.querySelector('.alert');
  if (!message) { box?.remove(); return; }
  if (!box) {
    box = document.createElement('div');
    box.className = 'alert';
    box.setAttribute('role', 'alert');
    form.prepend(box);
  }
  box.textContent = message;
}

/** Native constraint validation, with the message under each field. */
function validate(form, extra = () => ({})) {
  let ok = true;
  const custom = extra();
  for (const input of form.querySelectorAll('input, select')) {
    const message = custom[input.name]
      || (input.validity.valueMissing ? 'Required.' : input.checkValidity() ? '' : input.dataset.error || input.validationMessage);
    const holder = input.closest('label')?.querySelector('.field-error');
    input.setAttribute('aria-invalid', message ? 'true' : 'false');
    if (holder) holder.textContent = message;
    if (message) ok = false;
  }
  return ok;
}

function fmtTime(iso) {
  return iso ? new Date(iso).toLocaleString() : '—';
}

// ------------------------------------------------------------------ nav & router

let pollTimer = null;

function renderNav() {
  const s = currentSession();
  const page = location.hash.split('?')[0] || '#/';
  const link = (href, text) => `<a href="${href}" class="${page === href ? 'active' : ''}">${text}</a>`;
  document.getElementById('nav').innerHTML = s
    ? `${link('#/account', 'Account')}${link('#/kyc', 'Verify identity')}<button type="button" id="logout">Log out</button>`
    : `${link('#/login', 'Log in')}${link('#/register', 'Register')}`;
  document.getElementById('logout')?.addEventListener('click', logout);
}

async function logout() {
  try { await api('/api/user/logout', { method: 'POST' }); } catch { /* the session is dropped locally anyway */ }
  session.clear();
  toast('Logged out');
  go('#/login');
}

const routes = {
  '#/login': { page: loginPage, guest: true },
  '#/register': { page: registerPage, guest: true },
  '#/account': { page: accountPage, auth: true },
  '#/kyc': { page: kycPage, auth: true, wide: true },
};

function route() {
  clearTimeout(pollTimer);
  const path = location.hash.split('?')[0];
  const signedIn = !!currentSession();
  let r = routes[path];
  if (!r) return go(signedIn ? '#/account' : '#/login');
  if (r.auth && !signedIn) return go('#/login');
  if (r.guest && signedIn) return go('#/account');
  view.classList.toggle('wide', !!r.wide);
  renderNav();
  r.page();
}

window.addEventListener('hashchange', route);

// ------------------------------------------------------------------ login

function loginPage() {
  view.innerHTML = `
    <div class="card">
      <h1>Welcome back</h1>
      <p class="sub">Log in to your 789bingo account.</p>
      <form id="login" novalidate>
        <label><span class="cap">Username</span>
          <input name="username" autocomplete="username" required maxlength="32" autofocus>
          <span class="field-error"></span>
        </label>
        <label><span class="cap">Password</span>
          <input name="password" type="password" autocomplete="current-password" required maxlength="128">
          <span class="field-error"></span>
        </label>
        <button class="btn" type="submit">Log in</button>
      </form>
      <p class="alt">New here? <a href="#/register">Create an account</a></p>
    </div>`;

  const form = view.querySelector('#login');
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    showFormError(form, null);
    if (!validate(form)) return;
    const button = form.querySelector('button');
    setBusy(button, true, 'Logging in…');
    try {
      const data = Object.fromEntries(new FormData(form));
      await login(data.username.trim(), data.password);
      go('#/account');
    } catch (err) {
      showFormError(form, err.message);
    } finally {
      setBusy(button, false);
    }
  });
}

async function login(username, password) {
  const result = await api('/api/user/login', { method: 'POST', body: { username, password } });
  session.set(result);
}

// ------------------------------------------------------------------ register

function registerPage() {
  const maxBirth = new Date();
  maxBirth.setFullYear(maxBirth.getFullYear() - 21);
  view.innerHTML = `
    <div class="card">
      <h1>Create your account</h1>
      <p class="sub">You must be at least 21 years old to play.</p>
      <form id="register" novalidate>
        <label><span class="cap">Username <span class="hint">4–32 letters, digits or _</span></span>
          <input name="username" autocomplete="username" required pattern="[A-Za-z0-9_]{4,32}"
                 data-error="Use 4–32 letters, digits or underscores." autofocus>
          <span class="field-error"></span>
        </label>
        <div class="row">
          <label><span class="cap">Password <span class="hint">8–64 characters</span></span>
            <input name="password" type="password" autocomplete="new-password" required minlength="8" maxlength="64"
                   data-error="Use 8–64 characters.">
            <span class="field-error"></span>
          </label>
          <label><span class="cap">Confirm password</span>
            <input name="confirm" type="password" autocomplete="new-password" required>
            <span class="field-error"></span>
          </label>
        </div>
        <label><span class="cap">Email</span>
          <input name="email" type="email" autocomplete="email" required maxlength="254" data-error="Enter a valid email address.">
          <span class="field-error"></span>
        </label>
        <label><span class="cap">Mobile number <span class="hint">with country code, e.g. +639171234567</span></span>
          <input name="phone" type="tel" autocomplete="tel" required value="+63" pattern="\\+[1-9][0-9]{6,14}"
                 data-error="Use the international format, e.g. +639171234567.">
          <span class="field-error"></span>
        </label>
        <div class="row">
          <label><span class="cap">Date of birth</span>
            <input name="dateOfBirth" type="date" required max="${maxBirth.toISOString().slice(0, 10)}"
                   data-error="You must be at least 21 years old.">
            <span class="field-error"></span>
          </label>
          <label><span class="cap">Country</span>
            <select name="countryCode" required>
              <option value="PH" selected>Philippines</option>
            </select>
            <span class="field-error"></span>
          </label>
        </div>
        <label><span class="cap">Currency</span>
          <select name="currency" required>
            <option value="PHP" selected>PHP — Philippine peso</option>
          </select>
          <span class="field-error"></span>
        </label>
        <button class="btn" type="submit">Create account</button>
      </form>
      <p class="alt">Already have an account? <a href="#/login">Log in</a></p>
    </div>`;

  const form = view.querySelector('#register');
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    showFormError(form, null);
    const data = Object.fromEntries(new FormData(form));
    const ok = validate(form, () => ({ confirm: data.confirm && data.confirm !== data.password ? 'Passwords do not match.' : '' }));
    if (!ok) return;
    const button = form.querySelector('button');
    setBusy(button, true, 'Creating account…');
    try {
      const { confirm, ...request } = data;
      request.username = request.username.trim();
      request.email = request.email.trim();
      await api('/api/user/register', { method: 'POST', body: request });
      await login(request.username, request.password);
      toast('Account created. Next: verify your identity.');
      go('#/kyc');
    } catch (err) {
      showFormError(form, err.message);
    } finally {
      setBusy(button, false);
    }
  });
}

// ------------------------------------------------------------------ account

async function accountPage() {
  view.innerHTML = '<div class="card"><div class="spinner" aria-label="Loading"></div></div>';
  let me;
  try {
    me = await api('/api/user/me');
  } catch (err) {
    if (err.status === 401) return go('#/login');
    view.innerHTML = `<div class="card"><div class="alert">${esc(err.message)}</div></div>`;
    return;
  }
  const kycTone = { VERIFIED: 'ok', PENDING: 'warn', REJECTED: 'bad' }[me.kycStatus] || '';
  const yes = (v) => `<span class="badge ${v ? 'ok' : 'bad'}">${v ? 'Yes' : 'No'}</span>`;
  view.innerHTML = `
    <div class="card">
      <h1>Hi, ${esc(me.username)}</h1>
      <p class="sub">Player ID ${esc(me.userId)} · ${esc(me.defaultCurrency)}</p>
      <dl class="facts">
        <dt>Email</dt><dd>${esc(me.email)}</dd>
        <dt>Mobile</dt><dd>${esc(me.phone)}</dd>
        <dt>Date of birth</dt><dd>${esc(me.dateOfBirth)}</dd>
        <dt>Account</dt><dd><span class="badge ${me.status === 'ACTIVE' ? 'ok' : 'bad'}">${esc(me.status)}</span></dd>
        <dt>Identity</dt><dd><span class="badge ${kycTone}">${esc(me.kycStatus)}</span></dd>
      </dl>
    </div>
    <div class="card">
      <h2>What you can do</h2>
      <dl class="facts">
        <dt>Play</dt><dd>${yes(me.canPlay)}</dd>
        <dt>Deposit</dt><dd>${yes(me.canDeposit)}</dd>
        <dt>Withdraw</dt><dd>${yes(me.canWithdraw)}</dd>
        ${me.restriction ? `<dt>Restriction</dt><dd>${esc(me.restriction)}</dd>` : ''}
        ${me.selfExcludedUntil ? `<dt>Self-excluded until</dt><dd>${esc(fmtTime(me.selfExcludedUntil))}</dd>` : ''}
      </dl>
      ${me.kycStatus !== 'VERIFIED' ? '<div class="actions"><a class="btn" href="#/kyc">Verify my identity</a></div>' : ''}
    </div>`;
}

// ------------------------------------------------------------------ KYC

async function kycPage() {
  view.innerHTML = '<div class="card"><div class="spinner" aria-label="Loading"></div></div>';
  let latest;
  try {
    latest = await api('/api/kyc/submissions/latest');
  } catch (err) {
    if (err.status === 401) return go('#/login');
    view.innerHTML = `<div class="card"><div class="alert">${esc(err.message)}</div></div>`;
    return;
  }
  renderKyc(latest);
}

function statusBanner(latest) {
  if (!latest) return '';
  const s = KYC_STATE[latest.status] || KYC_STATE[4];
  const reasons = latest.reasons?.length
    ? `<ul>${latest.reasons.map((r) => `<li>${esc(r)}</li>`).join('')}</ul>` : '';
  return `
    <div class="status-banner ${s.tone}">
      ${s.busy ? '<div class="spinner"></div>' : ''}
      <div>
        <strong>${s.title}</strong>
        <span>${s.text}</span>
        ${reasons}
        <div class="sub" style="margin:6px 0 0">Submitted ${esc(fmtTime(latest.createdAt))}${latest.completedAt ? ` · completed ${esc(fmtTime(latest.completedAt))}` : ''}</div>
      </div>
    </div>`;
}

function renderKyc(latest) {
  const state = latest ? latest.status : null;
  const inProgress = state === 0 || state === 1;
  const verified = state === 2;

  view.innerHTML = `
    <div class="card">
      <h1>Verify your identity</h1>
      <p class="sub">Philippine regulations require us to check a government ID and a selfie before you can deposit and play.</p>
      ${statusBanner(latest)}
      ${inProgress || verified ? '' : kycForm()}
      ${verified ? '<div class="actions"><a class="btn" href="#/account">Back to my account</a></div>' : ''}
    </div>`;

  if (inProgress) {
    pollTimer = setTimeout(async () => {
      try { renderKyc(await api('/api/kyc/submissions/latest')); } catch (err) { if (err.status === 401) go('#/login'); }
    }, KYC_POLL_MS);
    return;
  }
  if (!verified) bindKycForm();
}

function kycForm() {
  return `
    <form id="kyc" novalidate>
      <label><span class="cap">ID type</span>
        <select name="identityType" required>
          ${ID_TYPES.map(([v, t]) => `<option value="${v}">${esc(t)}</option>`).join('')}
        </select>
        <span class="field-error"></span>
      </label>
      <div class="uploads">
        ${dropZone('idFront', 'Front of ID', true)}
        ${dropZone('idBack', 'Back of ID', false)}
        ${dropZone('selfie', 'Selfie holding your ID', true)}
      </div>
      <p class="sub" style="margin:0">JPEG, PNG or WebP, up to 10 MB each. Make sure all text is readable and your face is not covered.</p>
      <button class="btn" type="submit">Submit for verification</button>
    </form>`;
}

function dropZone(name, text, required) {
  return `
    <label class="drop ${required ? 'required' : ''}" data-slot="${name}">
      <span>${text}<br><small>Tap to choose</small></span>
      <input type="file" name="${name}" accept="${IMAGE_TYPES.join(',')}" capture="${name === 'selfie' ? 'user' : 'environment'}">
    </label>`;
}

function bindKycForm() {
  const form = view.querySelector('#kyc');
  const files = {};

  for (const input of form.querySelectorAll('input[type=file]')) {
    input.addEventListener('change', () => {
      const file = input.files[0];
      const zone = input.closest('.drop');
      zone.querySelector('img')?.remove();
      zone.querySelector('.tag')?.remove();
      delete files[input.name];
      if (!file) return;
      if (!IMAGE_TYPES.includes(file.type)) { toast('Please choose a JPEG, PNG or WebP image.'); input.value = ''; return; }
      if (file.size > MAX_IMAGE_BYTES) { toast('That image is larger than 10 MB.'); input.value = ''; return; }
      files[input.name] = file;
      const img = document.createElement('img');
      img.alt = '';
      img.src = URL.createObjectURL(file);
      const tag = document.createElement('span');
      tag.className = 'tag';
      tag.textContent = `${(file.size / 1024 / 1024).toFixed(1)} MB`;
      zone.append(img, tag);
    });
  }

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    showFormError(form, null);
    if (!files.idFront || !files.selfie) {
      showFormError(form, 'Please add the front of your ID and a selfie.');
      return;
    }
    const button = form.querySelector('button');
    try {
      setBusy(button, true, 'Uploading front of ID…');
      const idFront = await upload(files.idFront);
      let idBack = null;
      if (files.idBack) {
        button.textContent = 'Uploading back of ID…';
        idBack = await upload(files.idBack);
      }
      button.textContent = 'Uploading selfie…';
      const selfie = await upload(files.selfie);
      button.textContent = 'Submitting…';
      const submitted = await api('/api/kyc/submissions', {
        method: 'POST',
        body: {
          identityType: Number(form.identityType.value),
          // object keys, not the signed URLs: they never expire and do not depend on the storage host
          idFrontUrl: idFront.key,
          idBackUrl: idBack ? idBack.key : null,
          selfieUrl: selfie.key,
        },
      });
      renderKyc(submitted);
    } catch (err) {
      if (err.status === 401) return go('#/login');
      showFormError(form, err.message);
      setBusy(button, false);
    }
  });
}

function upload(file) {
  const form = new FormData();
  form.append('file', file, file.name);
  return api('/api/kyc/images', { method: 'POST', form });
}

// ------------------------------------------------------------------ start

route();
