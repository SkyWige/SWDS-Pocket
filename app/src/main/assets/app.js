/* Общие функции локальных страниц SWDS Pocket: мост к Kotlin (window.Pocket), тема, мелочи. */
(function () {
  const waiters = {};
  let n = 0;
  const listeners = {};
  window.__pocketCb = (id, r) => { const f = waiters[id]; delete waiters[id]; if (f) f(r); };
  window.__pocketEvent = (ev, data) => (listeners[ev] || []).forEach(f => f(data));

  const P = {
    available: typeof window.Pocket !== "undefined",
    state() {
      if (!this.available) return { css: {}, hello: {}, perms: {}, extra: {}, servers: [], sync_rules: [] };
      try { return JSON.parse(window.Pocket.state()); } catch (e) { return {}; }
    },
    call(method, args) {
      return new Promise(res => {
        if (!this.available) return res({ ok: false, error: "Нет моста приложения" });
        const id = "c" + (++n);
        waiters[id] = res;
        window.Pocket.call(id, method, JSON.stringify(args || {}));
      });
    },
    on(ev, f) { (listeners[ev] = listeners[ev] || []).push(f); },
  };
  window.P = P;

  window.applyTheme = function (css) {
    if (!css) return;
    const r = document.documentElement.style;
    for (const k of ["bg", "surface", "text", "muted", "accent", "topbar"]) if (css[k]) r.setProperty("--" + k, css[k]);
    document.documentElement.style.colorScheme = css.dark ? "dark" : "light";
  };
  window.$ = (id) => document.getElementById(id);
  window.esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  window.toast = function (msg) {
    const t = document.createElement("div");
    t.className = "toast"; t.textContent = msg;
    document.body.appendChild(t);
    setTimeout(() => t.remove(), 2800);
  };
  window.human = function (b) {
    if (b == null || b < 0) return "";
    if (b < 1024) return b + " Б";
    if (b < 1048576) return Math.round(b / 1024) + " КБ";
    if (b < 1073741824) return (b / 1048576).toFixed(1) + " МБ";
    return (b / 1073741824).toFixed(2) + " ГБ";
  };
  document.addEventListener("DOMContentLoaded", () => applyTheme(P.state().css));
})();
