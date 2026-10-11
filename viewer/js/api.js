// Transport to the FlowLens engine.
//  - Browser mode: the page is served by the engine, so plain same-origin fetch().
//  - Desktop mode (Tauri): the Rust shell owns the engine process and its secret token;
//    the UI calls it through IPC (`engine_request`) and never sees the token or port.

const tauri = window.__TAURI__;
export const isDesktop = !!(tauri && tauri.core);

export function invoke(cmd, args) {
  if (!isDesktop) return Promise.reject(new Error('desktop only'));
  return tauri.core.invoke(cmd, args);
}

async function request(method, path, body) {
  let status, text;
  if (isDesktop) {
    const r = await invoke('engine_request', { method, path, body: body === undefined ? null : JSON.stringify(body) });
    status = r.status;
    text = r.body;
  } else {
    const r = await fetch(path, {
      method,
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      cache: 'no-store',
    });
    status = r.status;
    text = await r.text();
  }
  let data;
  try { data = text ? JSON.parse(text) : {}; } catch { data = { error: text || `HTTP ${status}` }; }
  if (status >= 400) {
    const e = new Error(data.error || `HTTP ${status}`);
    e.status = status;
    throw e;
  }
  return data;
}

export const api = {
  info: () => request('GET', '/api/info'),
  startJob: opts => request('POST', '/api/jobs', opts),
  job: id => request('GET', `/api/jobs/${encodeURIComponent(id)}`),
  cancel: id => request('DELETE', `/api/jobs/${encodeURIComponent(id)}`),
  pick: () => request('POST', '/api/pick', {}),
  fingerprint: opts => request('POST', '/api/fingerprint', opts),
  diff: (base, current, label) => request('POST', '/api/diff', { base, current, label }),
};

/** Run an analysis job, reporting progress; resolves with the graph (or null if cancelled). */
export async function runJob(opts, onProgress, cancelSignal) {
  const { id } = await api.startJob(opts);
  let cancelled = false;
  cancelSignal.cancel = async () => { cancelled = true; try { await api.cancel(id); } catch { /* finished already */ } };
  for (;;) {
    const s = await api.job(id);
    if (s.state === 'done') return s.graph;
    if (s.state === 'error') throw new Error(s.error || 'analysis failed');
    if (s.state === 'cancelled' || cancelled && s.state !== 'running') return null;
    onProgress(s);
    await new Promise(r => setTimeout(r, 200));
  }
}

/** Is the engine reachable at all? (browser mode opened as a file, or engine crashed) */
export async function engineInfo() {
  try { return await api.info(); } catch { return null; }
}
