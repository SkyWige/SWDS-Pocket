/* SWDS офлайн — приложение саб-сервера SWDS Pocket.
   Работает с локальным сервером телефона (/local/api/...): данные лежат на телефоне,
   правки помечаются «не отправлено» и уходят на основной сервер при появлении связи. */
(function () {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const today = () => new Date().toISOString().slice(0, 10);
  const S = { tab: "tasks", state: null, list: [], q: "", edit: null };

  async function api(method, path, body) {
    const r = await fetch("/local/api" + path, { method, headers: body ? { "Content-Type": "application/json" } : {}, body: body ? JSON.stringify(body) : undefined });
    const j = await r.json().catch(() => ({ ok: false, error: "HTTP " + r.status }));
    if (!r.ok || j.ok === false) throw new Error(j.error || "Ошибка");
    return j;
  }
  function toast(t) { const el = $("toast"); el.textContent = t; el.classList.add("on"); clearTimeout(toast.t); toast.t = setTimeout(() => el.classList.remove("on"), 2200); }

  /* Описание наборов: как показывать и какие поля редактировать */
  const DS = {
    tasks: { title: "Задачи", icon: "✅", done: (d) => d.status === "done", toggle: (d) => ({ status: d.status === "done" ? "pending" : "done", completed_at: d.status === "done" ? null : new Date().toISOString().slice(0, 19).replace("T", " ") }),
      line: (d) => d.title, sub: (d) => [d.due_date ? "📅 " + d.due_date + (d.due_time ? " " + d.due_time : "") : "", d.priority > 1 ? "❗" : ""].filter(Boolean).join(" · "),
      group: (d) => d.status === "done" ? "Сделано" : !d.due_date ? "Без срока" : d.due_date < today() ? "Просрочено" : d.due_date === today() ? "Сегодня" : "Дальше",
      order: ["Просрочено", "Сегодня", "Дальше", "Без срока", "Сделано"],
      fields: [["title", "Задача", "text"], ["due_date", "Срок", "date"], ["due_time", "Время", "time"], ["priority", "Важность", "select", [[0, "обычная"], [1, "повыше"], [2, "важно"], [3, "срочно"]]], ["description", "Описание", "textarea"]],
      blank: () => ({ title: "", status: "pending", priority: 0, due_date: today() }) },
    shopping: { title: "Покупки", icon: "🛒", done: (d) => !!+d.done, toggle: (d) => ({ done: +d.done ? 0 : 1, done_at: +d.done ? null : new Date().toISOString().slice(0, 19).replace("T", " ") }),
      line: (d) => d.title + (d.qty ? " · " + d.qty + (d.unit ? " " + d.unit : "") : ""), sub: (d) => d.note || "",
      group: (d) => +d.done ? "Куплено" : (d.list || "Список"), fields: [["title", "Что купить", "text"], ["qty", "Сколько", "number"], ["unit", "Ед.", "text"], ["list", "Список", "text"], ["note", "Заметка", "text"]],
      blank: () => ({ title: "", done: 0 }) },
    calendar: { title: "Календарь", icon: "📅", line: (d) => (d.icon || "📌") + " " + d.title, sub: (d) => d.event_date + (d.event_time ? " " + d.event_time : "") + (d.note ? " · " + d.note : ""),
      group: (d) => d.event_date < today() && !+d.yearly ? "Прошло" : d.event_date === today() ? "Сегодня" : "Впереди", order: ["Сегодня", "Впереди", "Прошло"],
      sort: (a, b) => String(a.data.event_date).localeCompare(String(b.data.event_date)),
      fields: [["title", "Событие", "text"], ["event_date", "Дата", "date"], ["event_time", "Время", "time"], ["note", "Заметка", "text"]],
      blank: () => ({ title: "", event_date: today(), kind: "event", icon: "📌" }) },
    notes: { title: "Заметки", icon: "📝", line: (d) => (+d.pinned ? "📌 " : "") + (d.title || "Без названия"), sub: (d) => String(d.body || "").slice(0, 140),
      sort: (a, b) => String(b.data.updated_at || "").localeCompare(String(a.data.updated_at || "")),
      fields: [["title", "Заголовок", "text"], ["body", "Текст", "textarea"]], blank: () => ({ title: "", body: "", tags: [] }),
      beforeSave: (d) => Object.assign(d, { updated_at: new Date().toISOString().slice(0, 19) }) },
    wallet: { title: "Траты", icon: "💳", line: (d) => (d.note || d.category || (d.type === "income" ? "Доход" : "Расход")), sub: (d) => [d.tx_date, d.category, d.account].filter(Boolean).join(" · "),
      amount: (d) => (d.type === "income" ? "+" : "−") + (+d.amount || 0).toLocaleString("ru-RU"), sort: (a, b) => String(b.data.tx_date).localeCompare(String(a.data.tx_date)),
      fields: [["amount", "Сумма", "number"], ["type", "Тип", "select", [["expense", "расход"], ["income", "доход"]]], ["note", "На что", "text"], ["category", "Категория", "text"], ["tx_date", "Дата", "date"]],
      blank: () => ({ type: "expense", amount: "", tx_date: today() }), addOnly: true },
  };

  async function loadState() {
    try {
      const st = S.state = await api("GET", "/state");
      $("node-name").textContent = st.node_name || "SWDS на телефоне";
      const ago = st.last_sync ? Math.round((Date.now() / 1000 - st.last_sync) / 60) : null;
      $("sync-line").textContent = (st.server_online ? "🟢 " + (st.server_name || "сервер на связи") : "⚪ офлайн — всё сохранится и уйдёт позже")
        + (ago != null ? ` · синхр. ${ago < 1 ? "только что" : ago < 60 ? ago + " мин назад" : Math.round(ago / 60) + " ч назад"}` : "")
        + (st.dirty ? ` · не отправлено: ${st.dirty}` : "") + ` · уровень ${st.level}`;
      const tabs = (st.datasets || []).filter((d) => DS[d.name]).map((d) => `<button data-t="${d.name}" class="${d.name === S.tab ? "on" : ""}">${DS[d.name].icon} ${DS[d.name].title}<span class="n">${d.count || ""}</span></button>`);
      if (st.media) tabs.push(`<button data-t="media" class="${S.tab === "media" ? "on" : ""}">🎵 На телефоне<span class="n">${st.media}</span></button>`);
      if ((st.modules || []).length) tabs.push(`<button data-t="mods" class="${S.tab === "mods" ? "on" : ""}">🧩 Модули</button>`);
      $("tabs").innerHTML = tabs.join("") || '<span style="color:var(--muted);padding:8px">Данные появятся после первой синхронизации</span>';
      if (!DS[S.tab] && S.tab !== "mods" && S.tab !== "media" && st.datasets && st.datasets[0]) S.tab = st.datasets[0].name;
    } catch (e) { $("sync-line").textContent = "⚠ " + e.message; }
  }

  async function loadList() {
    if (S.tab === "mods") return renderMods();
    if (S.tab === "media") return renderMedia();
    const r = await api("GET", "/records/" + S.tab);
    S.list = r.records || [];
    render();
  }
  // скачанная музыка и видео — играют прямо с телефона, без сети
  async function renderMedia() {
    $("fab").style.display = "none";
    const r = await api("GET", "/media");
    const fmt = (s) => { s = Math.round(s || 0); return s ? Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0") : ""; };
    $("main").innerHTML = `<div id="mplayer" style="position:sticky;top:112px;z-index:3;margin-bottom:10px"></div>` + (r.items.length ? r.items.map((m, i) => `<div class="item" data-mi="${i}">
        <span style="font-size:22px">${m.media_type === "video" ? "🎬" : "🎵"}</span><div class="tx"><div class="t">${esc(m.title || m.filename)}</div>
        <div class="s">${esc([m.artist, m.album, fmt(m.duration), (m.size / 1048576).toFixed(1) + " МБ"].filter(Boolean).join(" · "))}</div></div></div>`).join("")
      : `<div class="empty"><div class="i">🎵</div>Скачайте музыку или видео в медиатеке SWDS: ⋮ → «На телефон»</div>`);
    let cur = -1;
    const play = (i) => {
      const m = r.items[i]; if (!m) return; cur = i;
      const tag = m.media_type === "video" ? "video" : "audio";
      $("mplayer").innerHTML = `<div class="item" style="flex-direction:column;align-items:stretch"><b>${esc(m.title || m.filename)}</b>
        <${tag} id="mp-el" src="/local/media/${m.id}" controls autoplay playsinline style="width:100%;margin-top:8px${tag === "video" ? ";max-height:40vh;background:#000;border-radius:10px" : ""}"></${tag}></div>`;
      const el = $("mp-el");
      el.onended = () => play(cur + 1 < r.items.length ? cur + 1 : -1);
      if ("mediaSession" in navigator) { try { navigator.mediaSession.metadata = new MediaMetadata({ title: m.title || m.filename, artist: m.artist || "" });
        navigator.mediaSession.setActionHandler("nexttrack", () => play(cur + 1)); navigator.mediaSession.setActionHandler("previoustrack", () => play(Math.max(0, cur - 1))); } catch (e) { /* */ } }
    };
    $("main").onclick = (e) => { const it = e.target.closest("[data-mi]"); if (it) play(+it.dataset.mi); };
  }
  function renderMods() {
    $("fab").style.display = "none";
    $("main").innerHTML = `<div class="mods">${(S.state.modules || []).map((m) => `<a href="/modules/${encodeURIComponent(m.id)}/index.html"><span style="font-size:26px">${esc(m.icon)}</span><b>${esc(m.name)}</b><small>${esc(m.description || "")}</small></a>`).join("")}</div>`;
  }
  function render() {
    const ds = DS[S.tab];
    $("fab").style.display = "";
    const q = S.q.toLowerCase();
    let list = S.list.filter((x) => !x.deleted && (!q || JSON.stringify(x.data).toLowerCase().includes(q)));
    if (ds.sort) list.sort(ds.sort);
    const box = [`<input class="search" id="q" placeholder="Поиск…" value="${esc(S.q)}">`];
    if (!list.length) box.push(`<div class="empty"><div class="i">${ds.icon}</div>${q ? "Ничего не нашлось" : "Пока пусто — нажмите ＋"}</div>`);
    const groups = {};
    list.forEach((x) => { const g = ds.group ? ds.group(x.data) : ""; (groups[g] = groups[g] || []).push(x); });
    const order = ds.order || Object.keys(groups);
    Object.keys(groups).forEach((g) => { if (!order.includes(g)) order.push(g); });
    order.filter((g) => groups[g]).forEach((g) => {
      if (g) box.push(`<div class="grp">${esc(g)} · ${groups[g].length}</div>`);
      groups[g].forEach((x) => {
        const d = x.data, done = ds.done ? ds.done(d) : false;
        box.push(`<div class="item${done ? " done" : ""}" data-uid="${esc(x.uid)}">${ds.toggle ? `<button class="chk" data-toggle="1">${done ? "✓" : ""}</button>` : ""}
          <div class="tx"><div class="t">${esc(ds.line(d))}</div>${ds.sub(d) ? `<div class="s">${esc(ds.sub(d))}</div>` : ""}</div>
          ${ds.amount ? `<span class="amt">${esc(ds.amount(d))}</span>` : ""}${x.dirty ? '<span class="dot" title="ещё не отправлено на сервер"></span>' : ""}</div>`);
      });
    });
    $("main").innerHTML = box.join("");
    const qi = $("q");
    qi.oninput = () => { S.q = qi.value; const pos = qi.selectionStart; render(); const n = $("q"); n.focus(); n.setSelectionRange(pos, pos); };
  }

  $("main").addEventListener("click", async (e) => {
    const it = e.target.closest("[data-uid]"); if (!it) return;
    const rec = S.list.find((x) => x.uid === it.dataset.uid); if (!rec) return;
    const ds = DS[S.tab];
    if (e.target.closest("[data-toggle]")) {
      await api("POST", "/records/" + S.tab, { uid: rec.uid, data: Object.assign({}, rec.data, ds.toggle(rec.data)) });
      return refresh();
    }
    openSheet(rec);
  });
  function openSheet(rec) {
    const ds = DS[S.tab];
    if (rec && ds.addOnly) { toast("Траты меняются в «Кошельке» на сервере"); return; }
    S.edit = rec || { uid: null, data: ds.blank() };
    $("sheet-h").textContent = (rec ? "Изменить" : "Добавить") + " · " + ds.title;
    $("sheet-body").innerHTML = ds.fields.map(([k, label, type, opts]) => {
      const v = S.edit.data[k] ?? "";
      if (type === "textarea") return `<div class="f"><label>${label}</label><textarea data-k="${k}">${esc(v)}</textarea></div>`;
      if (type === "select") return `<div class="f"><label>${label}</label><select data-k="${k}">${opts.map(([ov, ol]) => `<option value="${esc(ov)}"${String(ov) === String(v) ? " selected" : ""}>${esc(ol)}</option>`).join("")}</select></div>`;
      return `<div class="f"><label>${label}</label><input data-k="${k}" type="${type}" value="${esc(v)}"${type === "number" ? ' step="any" inputmode="decimal"' : ""}></div>`;
    }).join("");
    $("sheet-del").style.display = rec ? "" : "none";
    $("sheet").classList.add("on");
    setTimeout(() => { const f = $("sheet-body").querySelector("[data-k]"); if (f) f.focus(); }, 60);
  }
  $("sheet-cancel").onclick = () => $("sheet").classList.remove("on");
  $("sheet").onclick = (e) => { if (e.target === $("sheet")) $("sheet").classList.remove("on"); };
  $("sheet-save").onclick = async () => {
    const ds = DS[S.tab], d = Object.assign({}, S.edit.data);
    $("sheet-body").querySelectorAll("[data-k]").forEach((el) => { const k = el.dataset.k; d[k] = el.type === "number" ? (el.value === "" ? null : +el.value) : el.value; });
    if (!String(d.title ?? d.amount ?? "").trim()) { toast("Заполните первое поле"); return; }
    if (ds.beforeSave) ds.beforeSave(d);
    await api("POST", "/records/" + S.tab, { uid: S.edit.uid, data: d });
    $("sheet").classList.remove("on"); toast("Сохранено на телефоне"); refresh();
  };
  $("sheet-del").onclick = async () => {
    if (!confirm("Удалить?")) return;
    await api("DELETE", "/records/" + S.tab + "/" + encodeURIComponent(S.edit.uid));
    $("sheet").classList.remove("on"); refresh();
  };
  $("fab").onclick = () => openSheet(null);
  $("tabs").onclick = (e) => { const b = e.target.closest("[data-t]"); if (!b) return; S.tab = b.dataset.t; S.q = ""; [...$("tabs").children].forEach((x) => x.classList.toggle("on", x === b)); loadList(); };
  $("btn-sync").onclick = async () => {
    $("btn-sync").classList.add("spin");
    try { const r = await api("POST", "/sync"); toast(r.message || "Синхронизировано"); } catch (e) { toast("⚠ " + e.message); }
    $("btn-sync").classList.remove("spin"); refresh();
  };
  async function refresh() { await loadState(); await loadList(); }
  refresh();
  setInterval(loadState, 30000);
})();
