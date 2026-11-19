export const esc = value => String(value ?? '—').replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
export const money = value => value == null || value === '' || !Number.isFinite(Number(value)) ? '待核实' : `¥${Number(value).toFixed(2)}`;
export const positive = value => /^[1-9]\d*$/.test(String(value)) && Number.isSafeInteger(Number(value));
export const params = (path, values) => path + '?' + new URLSearchParams(values);
export function rows(value) {
  if (!Array.isArray(value)) throw new Error('记录格式不完整，请刷新核对');
  return value;
}
export function table(headers, entries) {
  return `<div class="panel"><table><thead><tr>${headers.map(header => `<th scope="col">${esc(header)}</th>`).join('')}</tr></thead><tbody>${entries.length ? entries.map(row => `<tr>${row.map(cell => `<td>${cell}</td>`).join('')}</tr>`).join('') : `<tr><td colspan="${headers.length}">暂无记录</td></tr>`}</tbody></table></div>`;
}
export function facts(fields) {
  return `<dl class="detail">${fields.map(([label, value]) => `<dt>${esc(label)}</dt><dd>${esc(value)}</dd>`).join('')}</dl>`;
}
export function screen({api,container,current,onSessionLost}) {
  const show = html => { if (current()) container.innerHTML = html; };
  function error(message) {
    if (!current()) return;
    if (!api.tokens && onSessionLost) { onSessionLost(message); return; }
    const note = container.querySelector('[data-error]') ?? document.createElement('p');
    note.dataset.error = '';
    note.className = 'error';
    note.setAttribute('role','alert');
    note.textContent = message;
    if (!note.isConnected) container.prepend(note);
  }
  function listen(selector, event, fn) {
    if (!current()) return;
    container.querySelectorAll(selector).forEach(node => node.addEventListener(event, async e => {
      e.preventDefault();
      if (!current() || !node.isConnected || node.disabled || node.dataset.running || node.dataset.locked) return;
      node.dataset.running = 'true';
      container.querySelector('[data-error]')?.remove();
      if (node.tagName === 'BUTTON') node.disabled = true;
      try { await fn(node, e); }
      catch (failure) { error(failure.message); }
      finally { delete node.dataset.running; if (current() && !node.dataset.locked) node.disabled = false; }
    }));
  }
  async function submit(path, method, body, description) {
    if (!current() || description && !confirm(description)) return false;
    try {
      await api.command(path, method, body);
    } catch (failure) {
      if (failure.uncertain && current()) {
        container.querySelectorAll('form, button:not([data-queue])').forEach(node => { node.dataset.locked = 'true'; if (node.tagName === 'BUTTON') node.disabled = true; });
      }
      throw failure;
    }
    return current();
  }
  return {show,error,listen,submit};
}

// Keep only the original operation payload in browser session storage, scoped to its actor.
export function original(user, operation) {
  const actor = user.staffId ?? user.id;
  if (!positive(actor) || !positive(user.stationId)) throw new Error('当前站长身份待核实，请重新登录');
  const key = `aquaflow:${actor}:${user.stationId}:${operation}`;
  return {
    read: () => JSON.parse(sessionStorage.getItem(key) ?? 'null'),
    save(value) {
      const data = JSON.stringify(value);
      sessionStorage.setItem(key, data);
      if (sessionStorage.getItem(key) !== data) throw new Error('原请求未能保存，尚未提交');
    },
    clear: () => sessionStorage.removeItem(key)
  };
}
