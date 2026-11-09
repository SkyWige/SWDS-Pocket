/* SWDS Reader — читалка книг и документов. Одна и та же в браузере (модуль «Библиотека») и в SWDS Pocket.
   SWDSReader.start(rootEl, {book, pack, marks, src, initial})
   src — источник данных (веб: сервер, Pocket: файлы на телефоне):
     chapter(n) → Promise<html>        asset(rel) → url            saveProgress({progress, locator, ts, seconds, chars})
     saveMark(mark) → Promise          search(q) → Promise<[{ch, snip}]>   back()
     pdfUrl? fileUrl? openFile?()      speak?(text, onEnd) / stopSpeak?()   (свой синтез речи в Pocket)  */
(function () {
"use strict";

var FONTS = {
  serif: {label: "Georgia", stack: 'Georgia, "PT Serif", "Times New Roman", serif'},
  literata: {label: "Literata", stack: '"Literata", "Bookerly", "Iowan Old Style", Georgia, serif'},
  sans: {label: "Без засечек", stack: 'system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", sans-serif'},
  humanist: {label: "Verdana", stack: 'Verdana, "DejaVu Sans", Tahoma, sans-serif'},
  pt: {label: "PT Sans", stack: '"PT Sans", "Trebuchet MS", sans-serif'},
  mono: {label: "Моно", stack: 'ui-monospace, "JetBrains Mono", Consolas, monospace'}
};
var THEMES = [["light", "#fbfaf7", "#1d1d1f", "Светлая"], ["sepia", "#f4ecd8", "#3b2f1e", "Сепия"], ["paper", "#eceae4", "#26251f", "Бумага"],
  ["green", "#dfeedd", "#1d3020", "Зелёная"], ["dark", "#1b1d22", "#d9d6cf", "Тёмная"], ["night", "#000000", "#b9b4a8", "Ночь"]];
var DEF = {theme: "light", font: "serif", fs: 19, lh: 1.62, margin: 48, maxw: 720, align: "justify", mode: "paged", indent: false,
  spread: true, dim: 0, pdfInvert: true, rate: 1, autoTheme: false};
var COLORS = ["yellow", "green", "blue", "pink"];
var LS = "swds.reader.v1";

function $(sel, el) { return (el || document).querySelector(sel); }
function $$(sel, el) { return Array.prototype.slice.call((el || document).querySelectorAll(sel)); }
function esc(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return {"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"}[c]; }); }
function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }
function uid() { return Date.now().toString(36) + Math.random().toString(36).slice(2, 8); }
function fmtMin(m) { m = Math.round(m); if (m < 1) return "< 1 мин"; if (m < 60) return m + " мин"; return Math.floor(m / 60) + " ч " + (m % 60) + " мин"; }
function debounce(fn, ms) { var t; return function () { var a = arguments, s = this; clearTimeout(t); t = setTimeout(function () { fn.apply(s, a); }, ms); }; }

function loadSettings() { try { return Object.assign({}, DEF, JSON.parse(localStorage.getItem(LS) || "{}")); } catch (e) { return Object.assign({}, DEF); } }
function saveSettings(s) { try { localStorage.setItem(LS, JSON.stringify(s)); } catch (e) {} }

function start(root, o) {
  var book = o.book || {}, pack = o.pack || {}, src = o.src;
  var chapters = pack.chapters || [], toc = pack.toc || [], kind = pack.kind || book.kind || "flow";
  var S = loadSettings();
  var marks = (o.marks || []).filter(function (m) { return !m.deleted; });
  var st = {ch: 0, pos: 0, page: 0, pages: 1, loading: false, cache: {}, readSec: 0, lastAct: Date.now(), savedOverall: null,
    speed: 1100, pdf: null, tts: false, sheet: 0, findQ: ""};
  var cum = [], total = 0;
  chapters.forEach(function (c, i) { cum[i] = total; total += Math.max(1, c.chars || 0); });
  var title = (book.title || pack.meta && pack.meta.title || "Книга");

  // ── каркас ──
  root.className = "rd-root";
  root.setAttribute("data-kind", kind);
  root.innerHTML =
    '<div class="rd-view" id="rd-view"><div class="rd-scroll" id="rd-scroll"><div class="rd-pagebox" id="rd-box"><div class="rd-content" id="rd-content"></div></div></div>' +
    '<div class="rd-loading" id="rd-loading">Открываю…</div></div>' +
    '<div class="rd-bm-flag" id="rd-flag"></div>' +
    '<div class="rd-status" id="rd-status"><span id="rd-st-l"></span><span id="rd-st-r"></span></div>' +
    '<div class="rd-top"><div class="rd-topin">' +
      '<button class="rd-ib" id="rd-back" title="Назад к библиотеке">←</button>' +
      '<button class="rd-ib" id="rd-tocb" title="Оглавление, закладки, цитаты (T)">☰</button>' +
      '<div class="rd-ttl"><b id="rd-title"></b><small id="rd-chap"></small></div>' +
      '<button class="rd-ib" id="rd-findb" title="Поиск по книге (/)">⌕</button>' +
      '<button class="rd-ib" id="rd-bmb" title="Закладка (B)">🔖</button>' +
      '<button class="rd-ib" id="rd-ttsb" title="Читать вслух">🔊</button>' +
      '<button class="rd-ib" id="rd-setb" title="Вид: шрифт, тема, поля">Aa</button>' +
      '<button class="rd-ib rd-hide-m" id="rd-fsb" title="Полный экран (F)">⛶</button>' +
    '</div></div>' +
    '<div class="rd-bot"><div class="rd-tts-bar"><button class="rd-btn" id="rd-tts-stop">⏹ Стоп</button><button class="rd-btn" id="rd-tts-pause">⏸</button>' +
      '<span style="flex:1"></span><span style="font-size:12px;color:var(--muted)">Скорость</span><input type="range" id="rd-tts-rate" min="0.6" max="2" step="0.1" style="width:110px"></div>' +
      '<div class="rd-sheets" id="rd-sheets" hidden></div>' +
      '<div class="rd-progress"><span id="rd-pct">0%</span><input type="range" id="rd-slider" min="0" max="1000" value="0"><span id="rd-left"></span></div>' +
      '<div class="rd-bot-row"><span id="rd-pageinfo"></span><span id="rd-booktime"></span></div></div>' +
    '<div class="rd-dim" id="rd-dimbg"></div>' +
    '<div class="rd-sheet rd-side" id="rd-side"><div class="rd-tabs"><button data-t="toc" class="on">Оглавление</button><button data-t="bm">Закладки</button><button data-t="q">Цитаты</button></div><div class="rd-list" id="rd-sidelist"></div></div>' +
    '<div class="rd-sheet rd-side" id="rd-find"><div class="rd-search"><input id="rd-q" placeholder="Найти в книге…" enterkeyhint="search"><button class="rd-ib" id="rd-qgo">⌕</button></div><div class="rd-list" id="rd-hits"><div class="rd-empty">Слово или фраза — найду во всей книге</div></div></div>' +
    '<div class="rd-sheet rd-pop" id="rd-set"></div>' +
    '<div class="rd-selbar" id="rd-selbar"><button data-a="quote">❝ Цитата</button>' + COLORS.map(function (c) { return '<button class="dot" data-a="hl" data-c="' + c + '" style="background:var(--hl-' + c + ')" title="Выделить"></button>'; }).join("") +
      '<button data-a="note">✎ Заметка</button><button data-a="notes" title="Сохранить в заметки SWDS">📝</button><button data-a="copy" title="Копировать">⧉</button><button data-a="find" title="Найти в книге">⌕</button></div>' +
    '<div class="rd-note-pop" id="rd-notepop"></div>' +
    '<div class="rd-lightbox" id="rd-lb"><img alt=""></div>' +
    '<div class="rd-dimmer" id="rd-dimmer"></div>' +
    '<div class="rd-toast" id="rd-toast"></div>';
  var view = $("#rd-view", root), scroller = $("#rd-scroll", root), box = $("#rd-box", root), content = $("#rd-content", root);
  $("#rd-title", root).textContent = title;
  document.title = title + " — SWDS";

  function toast(t) { var e = $("#rd-toast", root); e.textContent = t; e.classList.add("on"); clearTimeout(e._t); e._t = setTimeout(function () { e.classList.remove("on"); }, 2200); }

  // ── настройки вида ──
  function applySettings() {
    var autoDark = S.autoTheme && window.matchMedia && matchMedia("(prefers-color-scheme: dark)").matches;
    root.setAttribute("data-theme", autoDark ? (S.theme === "night" ? "night" : "dark") : S.theme);
    var mode = (kind === "table" || kind === "pdf") ? "scroll" : S.mode;
    root.setAttribute("data-mode", mode);
    root.classList.toggle("indent", !!S.indent);
    root.classList.toggle("pdf-invert", !!S.pdfInvert && /dark|night/.test(root.getAttribute("data-theme")));
    var r = root.style;
    r.setProperty("--font", (FONTS[S.font] || FONTS.serif).stack);
    r.setProperty("--fs", S.fs + "px"); r.setProperty("--lh", S.lh); r.setProperty("--margin", (window.innerWidth < 640 ? Math.min(S.margin, 28) : S.margin) + "px");
    r.setProperty("--maxw", S.maxw + "px"); r.setProperty("--align", S.align);
    $("#rd-dimmer", root).style.opacity = (S.dim || 0) / 100;
    if (src.onTheme) { try { src.onTheme(getComputedStyle(root).getPropertyValue("--bg").trim()); } catch (e) {} }
    if (pack.meta && pack.meta.lang) content.setAttribute("lang", pack.meta.lang);
    else content.setAttribute("lang", "ru");
    saveSettings(S);
  }
  function mode() { return root.getAttribute("data-mode"); }

  // ── главы ──
  function rewrite(el) {
    $$("img", el).forEach(function (img) {
      var s = img.getAttribute("src") || "";
      if (/^assets\//.test(s)) img.setAttribute("src", src.asset(s));
      img.addEventListener("load", relayoutSoon, {once: true});
    });
  }
  function getChapter(n) {
    if (st.cache[n] != null) return Promise.resolve(st.cache[n]);
    return Promise.resolve(src.chapter(n)).then(function (h) { st.cache[n] = h || ""; return st.cache[n]; });
  }
  function loadChapter(n, pos, opts) {
    opts = opts || {};
    n = clamp(n, 0, Math.max(0, chapters.length - 1));
    st.loading = true;
    $("#rd-loading", root).style.display = "";
    return getChapter(n).then(function (html) {
      st.ch = n;
      content.innerHTML = html;
      rewrite(content);
      var next = chapters[n + 1];
      if (kind !== "table") {
        var end = document.createElement("div");
        end.className = "rd-chend";
        end.innerHTML = next ? '<button data-next="1">Дальше: ' + esc(next.title || ("Часть " + (n + 2))) + ' →</button><small>Глава ' + (n + 1) + " из " + chapters.length + "</small>"
          : '<button data-done="1">✓ Книга прочитана</button><small>Конец</small>';
        content.appendChild(end);
      }
      paintMarks();
      if (st.findQ) paintFind(st.findQ);
      $("#rd-chap", root).textContent = chapterTitle(n);
      $("#rd-loading", root).style.display = "none";
      layout();
      if (opts.anchor) { gotoAnchor(opts.anchor); }
      else if (opts.offset != null) { gotoOffset(opts.offset); }
      else gotoPos(pos || 0);
      st.loading = false;
      renderSheets();
      prefetch(n + 1);
      // картинки догружаются — пересчитать страницы
      setTimeout(relayout, 350);
    }).catch(function (e) {
      $("#rd-loading", root).textContent = "Не удалось открыть главу: " + (e && e.message || e);
      st.loading = false;
    });
  }
  function prefetch(n) { if (n < chapters.length && st.cache[n] == null) setTimeout(function () { getChapter(n).catch(function () {}); }, 600); }
  function chapterTitle(n) {
    var best = null;
    toc.forEach(function (t) { if (t.ch === n && (!best || t.level < best.level)) best = t; });
    return (best && best.title) || (chapters[n] && chapters[n].title) || "";
  }

  // ── раскладка страниц ──
  var gap = 60;
  function layout() {
    if (mode() !== "paged") {
      content.style.columnWidth = content.style.columnGap = content.style.width = "";
      box.style.position = "static"; box.style.overflow = "visible";
      st.pages = 1; return;
    }
    box.style.position = ""; box.style.overflow = "";
    var W = box.clientWidth, spread = S.spread && W >= 860 && kind === "flow";
    gap = Math.max(40, parseInt(getComputedStyle(root).getPropertyValue("--margin")) * 1.4 || 60);
    var colW = spread ? (W - gap) / 2 : W;
    content.style.width = W + "px";
    content.style.columnWidth = colW + "px";
    content.style.columnGap = gap + "px";
    st.step = W + gap;
    st.pages = Math.max(1, Math.round((content.scrollWidth + gap) / st.step));
  }
  var relayoutSoon = debounce(relayout, 120);
  function relayout() {
    var p = st.pos;
    layout();
    gotoPos(p, true);
  }
  function setPage(p) {
    st.page = clamp(p, 0, st.pages - 1);
    content.scrollLeft = st.page * st.step;
    st.pos = st.pages > 1 ? st.page / (st.pages - 1) : 0;
    if (st.page === st.pages - 1 && st.pages > 1) st.pos = 1;
    onMove();
  }
  function gotoPos(pos, silent) {
    pos = clamp(pos || 0, 0, 1);
    if (mode() === "paged") {
      var p = Math.round(pos * (st.pages - 1));
      st.page = clamp(p, 0, st.pages - 1);
      content.scrollLeft = st.page * st.step;
      st.pos = pos;
    } else {
      var max = scroller.scrollHeight - scroller.clientHeight;
      scroller.scrollTop = pos * max;
      st.pos = pos;
    }
    if (!silent) onMove(); else updateUI();
  }
  function revealEl(el) {
    if (!el) return;
    if (mode() === "paged") {
      var x = el.getBoundingClientRect().left - content.getBoundingClientRect().left + content.scrollLeft;
      setPage(Math.floor((x + 2) / st.step));
    } else {
      scroller.scrollTop = el.getBoundingClientRect().top - scroller.getBoundingClientRect().top + scroller.scrollTop - 90;
      readScrollPos(); onMove();
    }
  }
  function gotoAnchor(a) { var el = content.querySelector('[id="' + a.replace(/"/g, "") + '"]'); if (el) revealEl(el); else gotoPos(0); }
  function readScrollPos() { var max = scroller.scrollHeight - scroller.clientHeight; st.pos = max > 0 ? clamp(scroller.scrollTop / max, 0, 1) : 0; }

  function next() {
    if (st.loading) return;
    if (mode() === "paged") {
      if (st.page < st.pages - 1) return setPage(st.page + 1);
    } else {
      var max = scroller.scrollHeight - scroller.clientHeight;
      if (scroller.scrollTop < max - 4) { scroller.scrollTop += scroller.clientHeight * 0.9; return; }
    }
    if (st.ch < chapters.length - 1) loadChapter(st.ch + 1, 0);
  }
  function prev() {
    if (st.loading) return;
    if (mode() === "paged") {
      if (st.page > 0) return setPage(st.page - 1);
    } else if (scroller.scrollTop > 4) { scroller.scrollTop -= scroller.clientHeight * 0.9; return; }
    if (st.ch > 0) loadChapter(st.ch - 1, 1);
  }

  // ── прогресс ──
  function overall() {
    if (kind === "pdf" && st.pdf) return st.pdf.pages > 1 ? (st.pdf.cur - 1) / (st.pdf.pages - 1) : 0;
    if (!chapters.length) return 0;
    var c = chapters[st.ch] || {chars: 1};
    return clamp((cum[st.ch] + st.pos * Math.max(1, c.chars || 0)) / total, 0, 1);
  }
  function locator() { return kind === "pdf" && st.pdf ? "p:" + st.pdf.cur : st.ch + ":" + st.pos.toFixed(5); }
  function fromOverall(v) {
    var target = v * total, n = 0;
    for (var i = 0; i < chapters.length; i++) if (cum[i] <= target) n = i;
    var c = Math.max(1, chapters[n].chars || 0);
    return {ch: n, pos: clamp((target - cum[n]) / c, 0, 1)};
  }
  function updateUI() {
    var ov = overall();
    $("#rd-pct", root).textContent = Math.round(ov * 100) + "%";
    if (!sliderDrag) $("#rd-slider", root).value = Math.round(ov * 1000);
    var c = chapters[st.ch] || {chars: 0};
    var leftCh = (1 - st.pos) * (c.chars || 0) / st.speed, leftBook = (1 - ov) * total / st.speed;
    var pi = mode() === "paged" ? "стр. " + (st.page + 1) + " из " + st.pages : Math.round(st.pos * 100) + "% главы";
    if (kind === "pdf" && st.pdf) pi = "стр. " + st.pdf.cur + " из " + st.pdf.pages;
    $("#rd-pageinfo", root).textContent = (chapters.length > 1 && kind !== "pdf" ? "Глава " + (st.ch + 1) + "/" + chapters.length + " · " : "") + pi;
    $("#rd-left", root).textContent = kind === "pdf" ? "" : fmtMin(leftCh) + " до конца главы";
    $("#rd-booktime", root).textContent = kind === "pdf" || !total ? "" : "до конца книги ≈ " + fmtMin(leftBook);
    $("#rd-st-l", root).textContent = kind === "pdf" ? "" : chapterTitle(st.ch);
    $("#rd-st-r", root).textContent = (mode() === "paged" ? (st.page + 1) + "/" + st.pages + " · " : "") + Math.round(ov * 100) + "%";
    var bm = currentBookmark();
    $("#rd-flag", root).classList.toggle("on", !!bm);
    $("#rd-bmb", root).classList.toggle("on", !!bm);
  }
  var lastSavedOv = null;
  function onMove() {
    updateUI();
    saveSoon();
  }
  var saveSoon = debounce(function () { save(false); }, 1200);
  function save(final) {
    var ov = overall();
    var chars = lastSavedOv == null ? 0 : Math.max(0, Math.round((ov - lastSavedOv) * total));
    if (chars > 40000) chars = 0;   // прыжок по книге — не чтение
    lastSavedOv = ov;
    var secs = Math.round(st.readSec); st.readSec = 0;
    if (secs > 20 && chars > 200) st.speed = clamp(0.7 * st.speed + 0.3 * (chars / (secs / 60)), 400, 3500);
    try { src.saveProgress({progress: ov, locator: locator(), ts: Date.now() / 1000, seconds: secs, chars: chars, final: !!final}); } catch (e) {}
  }
  // активное время чтения: страница видна и человек что-то делал последние 2 минуты (или слушает)
  setInterval(function () {
    if (document.visibilityState === "visible" && (Date.now() - st.lastAct < 120000 || st.tts)) st.readSec += 5;
  }, 5000);
  ["pointerdown", "keydown", "wheel", "touchstart"].forEach(function (e) { root.addEventListener(e, function () { st.lastAct = Date.now(); }, {passive: true}); });
  document.addEventListener("visibilitychange", function () { if (document.visibilityState === "hidden") save(true); });
  window.addEventListener("pagehide", function () { save(true); });

  // ── закладки и цитаты ──
  function chMarks(n) { return marks.filter(function (m) { return m.ch === n && !m.deleted; }); }
  function currentBookmark() {
    var lo, hi;
    if (mode() === "paged") { lo = st.pages > 1 ? (st.page - 0.5) / (st.pages - 1) : 0; hi = st.pages > 1 ? (st.page + 0.5) / (st.pages - 1) : 1; }
    else { lo = st.pos - 0.03; hi = st.pos + 0.03; }
    return marks.find(function (m) { return m.kind === "bookmark" && !m.deleted && m.ch === st.ch && m.pos >= lo && m.pos <= hi; });
  }
  function visibleText() {
    try {
      var r = box.getBoundingClientRect(), x = r.left + 12, y = (mode() === "paged" ? r.top : 90) + 14;
      var rng = document.caretRangeFromPoint ? document.caretRangeFromPoint(x, y) : null;
      var t = rng && rng.startContainer && rng.startContainer.textContent || "";
      t = (t.slice(rng ? rng.startOffset : 0) || content.textContent.slice(0, 200)).trim();
      if (rng && rng.startOffset > 0 && /\S/.test((rng.startContainer.textContent || "").charAt(rng.startOffset - 1))) t = t.replace(/^\S*\s+/, "");
      return t.slice(0, 140);
    } catch (e) { return ""; }
  }
  function toggleBookmark() {
    var bm = currentBookmark();
    if (bm) { bm.deleted = 1; bm.updated = Date.now() / 1000; persistMark(bm); toast("Закладка убрана"); }
    else {
      var m = {id: uid(), kind: "bookmark", ch: st.ch, pos: st.pos, locator: locator(), text: visibleText() || chapterTitle(st.ch), note: "",
        created: Date.now() / 1000, updated: Date.now() / 1000};
      marks.push(m); persistMark(m); toast("🔖 Закладка добавлена");
    }
    updateUI(); if ($("#rd-side", root).classList.contains("open")) renderSide();
  }
  function persistMark(m) {
    var p;
    try { p = src.saveMark(Object.assign({book_id: book.id}, m)); } catch (e) { p = null; }
    return Promise.resolve(p).catch(function () { toast("Сохранится, когда появится связь"); });
  }

  // смещения в тексте главы — чтобы выделение легло ровно туда же после смены шрифта
  function textNodes() {
    var out = [], w = document.createTreeWalker(content, NodeFilter.SHOW_TEXT, {acceptNode: function (n) {
      return n.parentNode.closest && n.parentNode.closest(".rd-chend") ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT; }});
    while (w.nextNode()) out.push(w.currentNode);
    return out;
  }
  function offsetOf(node, off) {
    var nodes = textNodes(), acc = 0;
    for (var i = 0; i < nodes.length; i++) {
      if (nodes[i] === node) return acc + off;
      acc += nodes[i].nodeValue.length;
    }
    if (node.nodeType === 1) {   // контейнер: считаем до его off-го ребёнка
      var target = node.childNodes[off] || null, acc2 = 0;
      for (var j = 0; j < nodes.length; j++) {
        if (target && (target === nodes[j] || (target.compareDocumentPosition(nodes[j]) & Node.DOCUMENT_POSITION_FOLLOWING) || target.contains(nodes[j]))) return acc2;
        acc2 += nodes[j].nodeValue.length;
      }
      return acc2;
    }
    return 0;
  }
  function wrapRange(start, end, make) {
    var nodes = textNodes(), acc = 0, first = null;
    for (var i = 0; i < nodes.length && acc < end; i++) {
      var n = nodes[i], len = n.nodeValue.length, s = Math.max(start - acc, 0), e = Math.min(end - acc, len);
      if (e > s && s < len) {
        var target = n;
        if (s > 0) target = target.splitText(s);
        if (e - s < target.nodeValue.length) target.splitText(e - s);
        var w = make();
        target.parentNode.insertBefore(w, target);
        w.appendChild(target);
        first = first || w;
      }
      acc += len;
    }
    return first;
  }
  function locateText(m) {
    if (m.offset == null) { var lo = String(m.locator || "").split(":"); if (lo.length > 2) m.offset = +lo[2]; }
    var txt = content.textContent, s = m.offset != null && !isNaN(m.offset) ? m.offset : -1, q = (m.text || "").slice(0, 400);
    if (s >= 0 && txt.substr(s, q.length) === q) return s;
    if (!q) return -1;
    var guess = Math.round((m.pos || 0) * txt.length), best = -1, bd = 1e12, i = txt.indexOf(q);
    while (i >= 0) { var d = Math.abs(i - guess); if (d < bd) { bd = d; best = i; } i = txt.indexOf(q, i + 1); }
    return best;
  }
  function paintMarks() {
    chMarks(st.ch).forEach(function (m) {
      if (m.kind !== "quote" && m.kind !== "highlight") return;
      var s = locateText(m);
      if (s < 0) return;
      wrapRange(s, s + (m.text || "").length, function () {
        var e = document.createElement("mark");
        e.className = "rd-hl " + (m.color || "yellow"); e.setAttribute("data-mid", m.id);
        if (m.note) e.title = m.note;
        return e;
      });
    });
  }
  function paintFind(q) {
    $$("mark.rd-find", content).forEach(function (mk) { var p = mk.parentNode; while (mk.firstChild) p.insertBefore(mk.firstChild, mk); p.removeChild(mk); p.normalize(); });
    if (!q) return [];
    var txt = content.textContent.toLowerCase(), ql = q.toLowerCase(), pos = [], i = txt.indexOf(ql);
    while (i >= 0 && pos.length < 300) { pos.push(i); i = txt.indexOf(ql, i + ql.length); }
    var els = [];
    for (var k = pos.length - 1; k >= 0; k--) {
      var el = wrapRange(pos[k], pos[k] + ql.length, function () { var e = document.createElement("mark"); e.className = "rd-find"; return e; });
      if (el) els.unshift(el);
    }
    return els;
  }
  function gotoOffset(off) {
    var el = wrapRange(off, off + 1, function () { var e = document.createElement("span"); e.className = "rd-tmp"; return e; });
    if (el) { revealEl(el); var p = el.parentNode; while (el.firstChild) p.insertBefore(el.firstChild, el); p.removeChild(el); p.normalize(); }
  }

  // всплывающее меню выделения
  var selbar = $("#rd-selbar", root), selInfo = null;
  function onSelect() {
    var sel = window.getSelection();
    if (!sel || sel.isCollapsed || !sel.rangeCount) { selbar.classList.remove("on"); selInfo = null; return; }
    var r = sel.getRangeAt(0);
    if (!content.contains(r.commonAncestorContainer)) { selbar.classList.remove("on"); return; }
    var text = sel.toString().trim();
    if (!text) return;
    var start = offsetOf(r.startContainer, r.startOffset);
    var full = content.textContent, real = full.indexOf(sel.toString(), Math.max(0, start - 5));
    if (real >= 0 && real - start < 40) start = real + (sel.toString().length - sel.toString().replace(/^\s+/, "").length);
    selInfo = {text: text, offset: start, pos: full.length ? start / full.length : 0};
    var rect = r.getBoundingClientRect(), rr = root.getBoundingClientRect();
    selbar.classList.add("on");
    var x = clamp(rect.left + rect.width / 2 - rr.left - selbar.offsetWidth / 2, 8, rr.width - selbar.offsetWidth - 8);
    var y = rect.top - rr.top - selbar.offsetHeight - 10;
    if (y < 60) y = rect.bottom - rr.top + 10;
    selbar.style.left = x + "px"; selbar.style.top = y + "px";
  }
  document.addEventListener("selectionchange", debounce(onSelect, 180));
  selbar.addEventListener("mousedown", function (e) { e.preventDefault(); });
  selbar.addEventListener("click", function (e) {
    var b = e.target.closest("button"); if (!b || !selInfo) return;
    var a = b.dataset.a, info = selInfo;
    if (a === "copy") { (navigator.clipboard ? navigator.clipboard.writeText(info.text) : Promise.reject()).then(function () { toast("Скопировано"); }, function () { document.execCommand("copy"); toast("Скопировано"); }); }
    else if (a === "find") { openFind(info.text.slice(0, 80)); }
    else {
      var m = {id: uid(), kind: a === "hl" ? "highlight" : "quote", ch: st.ch, pos: info.pos, offset: info.offset, locator: st.ch + ":" + info.pos.toFixed(5) + ":" + info.offset,
        text: info.text.slice(0, 4000), note: "", color: b.dataset.c || "yellow", created: Date.now() / 1000, updated: Date.now() / 1000};
      if (a === "notes") m.to_notes = true;
      if (a === "note") { window.getSelection().removeAllRanges(); selbar.classList.remove("on"); return editNote(m, true); }
      marks.push(m); persistMark(m);
      toast(a === "notes" ? "📝 Цитата сохранена в заметки" : a === "hl" ? "Выделено" : "❝ Цитата сохранена");
      refreshMarks();
    }
    window.getSelection().removeAllRanges(); selbar.classList.remove("on");
  });
  function refreshMarks() {
    var keep = mode() === "paged" ? st.page : scroller.scrollTop;
    $$("mark.rd-hl", content).forEach(function (mk) { var p = mk.parentNode; while (mk.firstChild) p.insertBefore(mk.firstChild, mk); p.removeChild(mk); p.normalize(); });
    paintMarks();
    if (mode() === "paged") { content.scrollLeft = keep * st.step; } else scroller.scrollTop = keep;
  }
  function editNote(m, isNew) {
    var pop = $("#rd-notepop", root);
    pop.innerHTML = '<div class="h"><span>' + (m.kind === "bookmark" ? "Закладка" : "Цитата") + '</span><button class="rd-ib" data-x>✕</button></div>' +
      (m.text ? '<q style="display:block;margin-bottom:10px;border-left:3px solid var(--accent);padding-left:10px;quotes:none">' + esc(m.text.slice(0, 600)) + "</q>" : "") +
      '<textarea placeholder="Ваша мысль, комментарий…">' + esc(m.note || "") + '</textarea>' +
      '<div class="acts">' + (isNew ? "" : '<button class="rd-btn" data-del>Удалить</button>') + '<span style="flex:1"></span>' +
      (m.kind !== "bookmark" ? '<button class="rd-btn" data-notes>📝 В заметки SWDS</button>' : "") + '<button class="rd-btn p" data-save>Сохранить</button></div>';
    pop.classList.add("open"); dim(true, closeNote);
    var ta = $("textarea", pop); setTimeout(function () { ta.focus(); }, 200);
    function done(extra) {
      m.note = ta.value.trim(); m.updated = Date.now() / 1000; Object.assign(m, extra || {});
      if (isNew) marks.push(m);
      persistMark(m); closeNote(); refreshMarks(); if ($("#rd-side", root).classList.contains("open")) renderSide();
    }
    $("[data-x]", pop).onclick = closeNote;
    $("[data-save]", pop).onclick = function () { done(); toast("Сохранено"); };
    if ($("[data-notes]", pop)) $("[data-notes]", pop).onclick = function () { done({to_notes: true}); toast("📝 Отправлено в заметки"); };
    if ($("[data-del]", pop)) $("[data-del]", pop).onclick = function () { m.deleted = 1; m.updated = Date.now() / 1000; persistMark(m); closeNote(); refreshMarks(); renderSide(); updateUI(); };
  }
  function closeNote() { $("#rd-notepop", root).classList.remove("open"); dim(false); }

  // ── панели ──
  var dimCb = null;
  function dim(on, cb) { $("#rd-dimbg", root).classList.toggle("on", on); dimCb = on ? cb : null; }
  $("#rd-dimbg", root).onclick = function () { if (dimCb) dimCb(); closeAll(); };
  function closeAll() { ["#rd-side", "#rd-find"].forEach(function (s) { $(s, root).classList.remove("open"); }); $("#rd-set", root).classList.remove("open"); closeNote(); dim(false); }
  var sideTab = "toc";
  function openSide(tab) { closeAll(); sideTab = tab || sideTab; renderSide(); $("#rd-side", root).classList.add("open"); dim(true, closeAll); }
  function renderSide() {
    $$("#rd-side .rd-tabs button", root).forEach(function (b) { b.classList.toggle("on", b.dataset.t === sideTab); });
    var L = $("#rd-sidelist", root), h = "";
    if (sideTab === "toc") {
      var list = toc.length ? toc : chapters.map(function (c, i) { return {title: c.title, ch: i, level: 1}; });
      var cur = -1;
      list.forEach(function (t, i) { if (t.ch < st.ch || (t.ch === st.ch && !t.anchor)) cur = i; else if (t.ch === st.ch && cur < 0) cur = i; });
      h = list.map(function (t, i) {
        var pct = total ? Math.round(cum[t.ch] / total * 100) : 0;
        return '<a class="l' + Math.min(t.level || 1, 4) + (i === cur ? " cur" : "") + '" data-ch="' + t.ch + '" data-a="' + esc(t.anchor || "") + '"' + (t.page ? ' data-page="' + t.page + '"' : "") + '><span>' + esc(t.title) + '</span><span class="pg">' + (t.page ? t.page : pct + "%") + "</span></a>";
      }).join("") || '<div class="rd-empty">В книге нет оглавления</div>';
      L.innerHTML = '<div class="rd-toc">' + h + "</div>";
      var c = $(".cur", L); if (c) setTimeout(function () { c.scrollIntoView({block: "center"}); }, 50);
    } else {
      var want = sideTab === "bm" ? ["bookmark"] : ["quote", "highlight"];
      var ms = marks.filter(function (m) { return !m.deleted && want.indexOf(m.kind) >= 0; }).sort(function (a, b) { return a.ch - b.ch || a.pos - b.pos; });
      h = ms.map(function (m) {
        var pct = total ? Math.round((cum[m.ch] + m.pos * (chapters[m.ch] ? chapters[m.ch].chars : 0)) / total * 100) : 0;
        return '<div class="rd-mk" data-id="' + esc(m.id) + '" style="--c:var(--hl-' + (m.color || "yellow") + ')"><q>' + esc((m.text || "").slice(0, 400)) + "</q>" +
          (m.note ? "<em>✎ " + esc(m.note) + "</em>" : "") +
          '<small><span>' + esc(chapterTitle(m.ch)) + " · " + pct + "%</span>" +
          '<button data-act="edit" title="Заметка">✎</button>' + (m.kind !== "bookmark" ? '<button data-act="notes" title="В заметки SWDS">📝</button><button data-act="share" title="Копировать">⧉</button>' : "") +
          '<button data-act="del" title="Удалить">🗑</button></small></div>';
      }).join("") || '<div class="rd-empty">' + (sideTab === "bm" ? "Закладок пока нет — нажмите 🔖 сверху" : "Выделите текст — появится меню «Цитата»") + "</div>";
      L.innerHTML = h;
    }
  }
  $("#rd-side .rd-tabs", root).onclick = function (e) { var b = e.target.closest("button"); if (b) { sideTab = b.dataset.t; renderSide(); } };
  $("#rd-sidelist", root).onclick = function (e) {
    var a = e.target.closest("a[data-ch]");
    if (a) { closeAll(); if (a.dataset.page && st.pdf) return pdfGo(+a.dataset.page); var ch = +a.dataset.ch, an = a.dataset.a;
      if (ch === st.ch && !st.loading) { if (an) gotoAnchor(an); else gotoPos(0); } else loadChapter(ch, 0, {anchor: an || null}); return; }
    var mk = e.target.closest(".rd-mk"); if (!mk) return;
    var m = marks.find(function (x) { return x.id === mk.dataset.id; }); if (!m) return;
    var act = e.target.closest("button[data-act]");
    if (act) {
      e.stopPropagation();
      if (act.dataset.act === "del") { m.deleted = 1; m.updated = Date.now() / 1000; persistMark(m); renderSide(); refreshMarks(); updateUI(); }
      else if (act.dataset.act === "edit") editNote(m, false);
      else if (act.dataset.act === "notes") { m.to_notes = true; m.updated = Date.now() / 1000; persistMark(m); toast("📝 Отправлено в заметки"); }
      else if (act.dataset.act === "share") { var t = "«" + m.text + "»\n— " + (book.author ? book.author + ", " : "") + title;
        if (navigator.share) navigator.share({text: t}).catch(function () {}); else if (navigator.clipboard) navigator.clipboard.writeText(t).then(function () { toast("Скопировано"); }); }
      return;
    }
    closeAll();
    if (st.pdf) return;
    var go = function () { if (m.kind === "bookmark") gotoPos(m.pos); else { var s = locateText(m); if (s >= 0) gotoOffset(s); else gotoPos(m.pos); } };
    if (m.ch === st.ch) go(); else loadChapter(m.ch, m.pos).then(go);
  };

  // поиск
  function openFind(q) {
    closeAll(); $("#rd-find", root).classList.add("open"); dim(true, closeAll);
    var inp = $("#rd-q", root); if (q != null) { inp.value = q; doFind(); } setTimeout(function () { inp.focus(); inp.select(); }, 150);
  }
  function doFind() {
    var q = $("#rd-q", root).value.trim(), L = $("#rd-hits", root);
    if (q.length < 2) return;
    L.innerHTML = '<div class="rd-empty">Ищу…</div>';
    Promise.resolve(src.search(q)).then(function (hits) {
      hits = hits || [];
      L.innerHTML = hits.length ? hits.map(function (h, i) {
        var sn = esc(h.snip || "").replace(/\[\[/g, "<b>").replace(/\]\]/g, "</b>");
        return '<div class="rd-hit" data-i="' + i + '"><small>' + esc(chapterTitle(h.ch) || ("Глава " + (h.ch + 1))) + "</small>…" + sn + "…</div>";
      }).join("") : '<div class="rd-empty">Ничего не нашлось</div>';
      L._hits = hits; L._q = q;
    }).catch(function () { L.innerHTML = '<div class="rd-empty">Поиск сейчас недоступен</div>'; });
  }
  $("#rd-qgo", root).onclick = doFind;
  $("#rd-q", root).addEventListener("keydown", function (e) { if (e.key === "Enter") doFind(); });
  $("#rd-hits", root).onclick = function (e) {
    var h = e.target.closest(".rd-hit"); if (!h) return;
    var L = $("#rd-hits", root), hit = L._hits[+h.dataset.i], q = L._q;
    closeAll();
    st.findQ = q.split(/\s+/)[0];
    var go = function () {
      var els = paintFind(st.findQ);
      var core = (hit.snip || "").replace(/\[\[|\]\]/g, "").replace(/…/g, "").trim().slice(0, 40), txt = content.textContent, at = core ? txt.indexOf(core) : -1;
      var target = els[0];
      if (at >= 0) { var tlo = txt.toLowerCase(); var k = tlo.indexOf(st.findQ.toLowerCase(), at); var idx = 0, i2 = tlo.indexOf(st.findQ.toLowerCase());
        while (i2 >= 0 && i2 < k) { idx++; i2 = tlo.indexOf(st.findQ.toLowerCase(), i2 + 1); } target = els[idx] || target; }
      if (target) { target.classList.add("cur"); revealEl(target); }
    };
    if (hit.ch === st.ch) go(); else loadChapter(hit.ch, 0).then(go);
  };

  // настройки
  function renderSettings() {
    var P = $("#rd-set", root);
    P.innerHTML = '<div class="rd-set">' +
      '<h4>Тема</h4><div class="rd-themes">' + THEMES.map(function (t) { return '<button data-theme="' + t[0] + '" title="' + t[3] + '" style="background:' + t[1] + ';color:' + t[2] + '" class="' + (S.theme === t[0] ? "on" : "") + '">Aa</button>'; }).join("") + "</div>" +
      '<label class="rd-check"><input type="checkbox" data-k="autoTheme"' + (S.autoTheme ? " checked" : "") + '> Тёмная тема, когда тёмная на устройстве</label>' +
      '<h4>Шрифт</h4><div class="rd-fonts">' + Object.keys(FONTS).map(function (k) { return '<button data-font="' + k + '" class="' + (S.font === k ? "on" : "") + '" style="font-family:' + FONTS[k].stack.replace(/"/g, "&quot;") + '">' + FONTS[k].label + "</button>"; }).join("") + "</div>" +
      '<div class="rd-row"><button class="rd-ib" data-fs="-1">A−</button><input type="range" min="12" max="34" data-k="fs" value="' + S.fs + '"><button class="rd-ib" data-fs="1">A+</button><b>' + S.fs + "</b></div>" +
      '<h4>Текст</h4><div class="rd-row"><span style="width:92px">Интервал</span><input type="range" min="1.2" max="2.2" step="0.05" data-k="lh" value="' + S.lh + '"><b>' + (+S.lh).toFixed(2) + "</b></div>" +
      '<div class="rd-row"><span style="width:92px">Поля</span><input type="range" min="8" max="120" step="4" data-k="margin" value="' + S.margin + '"><b>' + S.margin + "</b></div>" +
      '<div class="rd-row"><span style="width:92px">Ширина</span><input type="range" min="480" max="1200" step="20" data-k="maxw" value="' + S.maxw + '"><b>' + S.maxw + "</b></div>" +
      '<div class="rd-row"><div class="rd-seg" style="flex:1" data-seg="align"><button data-v="justify" class="' + (S.align === "justify" ? "on" : "") + '">По ширине</button><button data-v="left" class="' + (S.align === "left" ? "on" : "") + '">По левому краю</button></div></div>' +
      '<label class="rd-check"><input type="checkbox" data-k="indent"' + (S.indent ? " checked" : "") + '> Абзацы с красной строки (как в бумажной книге)</label>' +
      (kind === "flow" || kind === "images" ? '<h4>Листание</h4><div class="rd-seg" data-seg="mode"><button data-v="paged" class="' + (S.mode === "paged" ? "on" : "") + '">📖 Страницы</button><button data-v="scroll" class="' + (S.mode === "scroll" ? "on" : "") + '">📜 Прокрутка</button></div>' +
        '<label class="rd-check"><input type="checkbox" data-k="spread"' + (S.spread ? " checked" : "") + '> Разворот в две колонки на широком экране</label>' : "") +
      (kind === "pdf" ? '<label class="rd-check"><input type="checkbox" data-k="pdfInvert"' + (S.pdfInvert ? " checked" : "") + '> Инвертировать PDF в тёмной теме</label>' : "") +
      '<h4>Яркость</h4><div class="rd-row"><span>☀</span><input type="range" min="0" max="70" data-k="dim" value="' + (70 - (S.dim || 0)) + '" data-inv="70"><span>🌙</span></div>' +
      "</div>";
  }
  $("#rd-set", root).addEventListener("click", function (e) {
    var b = e.target.closest("button"); if (!b) return;
    if (b.dataset.theme) { S.theme = b.dataset.theme; S.autoTheme = false; }
    else if (b.dataset.font) S.font = b.dataset.font;
    else if (b.dataset.fs) S.fs = clamp(S.fs + (+b.dataset.fs), 12, 34);
    else if (b.parentNode.dataset.seg) S[b.parentNode.dataset.seg] = b.dataset.v;
    else return;
    applySettings(); renderSettings(); relayoutKeep();
  });
  $("#rd-set", root).addEventListener("input", function (e) {
    var i = e.target; if (!i.dataset.k) return;
    var k = i.dataset.k, v = i.type === "checkbox" ? i.checked : +i.value;
    if (i.dataset.inv) v = +i.dataset.inv - v;
    S[k] = v; applySettings();
    var b = i.parentNode.querySelector("b"); if (b) b.textContent = k === "lh" ? (+v).toFixed(2) : v;
    if (k !== "dim" && k !== "autoTheme") relayoutKeepSoon();
  });
  var relayoutKeepSoon = debounce(function () { relayoutKeep(); }, 150);
  function relayoutKeep() { var p = st.pos; layout(); gotoPos(p, true); }

  // ── листание: клики, свайпы, клавиши, колесо ──
  var touch = null;
  view.addEventListener("click", function (e) {
    if (e.target.closest("a, button, mark.rd-hl, img, .tbl, input, textarea")) return;
    var sel = window.getSelection(); if (sel && !sel.isCollapsed) return;
    if (kind === "table" || kind === "pdf") { root.classList.toggle("immersive"); return; }
    var x = e.clientX / window.innerWidth;
    if (x < 0.28) prev(); else if (x > 0.72) next(); else root.classList.toggle("immersive");
  });
  view.addEventListener("touchstart", function (e) { if (e.touches.length === 1) touch = {x: e.touches[0].clientX, y: e.touches[0].clientY, t: Date.now()}; }, {passive: true});
  view.addEventListener("touchend", function (e) {
    if (!touch || mode() !== "paged") return;
    var t = e.changedTouches[0], dx = t.clientX - touch.x, dy = t.clientY - touch.y;
    var sel = window.getSelection();
    if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy) * 1.5 && Date.now() - touch.t < 600 && (!sel || sel.isCollapsed)) { if (dx < 0) next(); else prev(); }
    touch = null;
  }, {passive: true});
  var wheelLock = 0;
  view.addEventListener("wheel", function (e) {
    if (mode() !== "paged") return;
    e.preventDefault();
    if (Date.now() - wheelLock < 350 || Math.abs(e.deltaY) + Math.abs(e.deltaX) < 8) return;
    wheelLock = Date.now();
    if (e.deltaY > 0 || e.deltaX > 0) next(); else prev();
  }, {passive: false});
  scroller.addEventListener("scroll", debounce(function () { if (mode() === "scroll" && kind !== "pdf") { readScrollPos(); onMove(); } }, 120), {passive: true});
  document.addEventListener("keydown", function (e) {
    if (e.target.closest && e.target.closest("input, textarea")) { if (e.key === "Escape") { e.target.blur(); closeAll(); } return; }
    var k = e.key;
    if (k === "ArrowRight" || k === "PageDown" || (k === " " && !e.shiftKey)) { if (mode() === "paged" || k !== " ") { e.preventDefault(); next(); } }
    else if (k === "ArrowLeft" || k === "PageUp" || (k === " " && e.shiftKey)) { if (mode() === "paged" || k !== " ") { e.preventDefault(); prev(); } }
    else if (k === "Escape") { closeAll(); root.classList.remove("immersive"); }
    else if (k === "t" || k === "е") openSide("toc");
    else if (k === "b" || k === "и") toggleBookmark();
    else if (k === "/") { e.preventDefault(); openFind(); }
    else if (k === "f" || k === "а") fullscreen();
    else if (k === "Home") loadChapter(0, 0);
    else if (k === "End") loadChapter(chapters.length - 1, 1);
  });
  window.addEventListener("resize", debounce(function () { applySettings(); relayoutKeep(); if (st.pdf) pdfResize(); }, 200));

  // ссылки, сноски, картинки
  content.addEventListener("click", function (e) {
    var a = e.target.closest("a"), img = e.target.closest("img"), mk = e.target.closest("mark.rd-hl");
    if (mk && !a) { var m = marks.find(function (x) { return x.id === mk.dataset.mid; }); if (m) editNote(m, false); return; }
    if (img && !a) { var lb = $("#rd-lb", root); $("img", lb).src = img.src; lb.classList.add("on"); return; }
    var nb = e.target.closest("[data-next]"); if (nb) { loadChapter(st.ch + 1, 0); return; }
    var db = e.target.closest("[data-done]"); if (db) { st.pos = 1; save(true); toast("🎉 Книга прочитана!"); return; }
    if (!a) return;
    var h = a.getAttribute("href") || "";
    var m2 = h.match(/^#ch(\d+):(.*)$/);
    if (m2) {
      e.preventDefault();
      var n = +m2[1], anchor = m2[2];
      var isNote = a.classList.contains("note") || a.closest("sup") || /^\s*[\[{(]?\*?\d{1,4}[\]})]?\s*$/.test(a.textContent);
      if (isNote) return showFootnote(n, anchor);
      if (n === st.ch) { if (anchor) gotoAnchor(anchor); } else loadChapter(n, 0, {anchor: anchor || null});
    } else if (/^https?:|^mailto:/.test(h)) { /* внешняя ссылка — откроется в новой вкладке */ }
    else e.preventDefault();
  });
  $("#rd-lb", root).onclick = function () { this.classList.remove("on"); };
  function showFootnote(n, anchor) {
    getChapter(n).then(function (html) {
      var d = document.createElement("div"); d.innerHTML = html;
      var el = anchor ? d.querySelector('[id="' + anchor.replace(/"/g, "") + '"]') : null;
      if (!el) { loadChapter(n, 0, {anchor: anchor}); return; }
      var t = el.querySelector("h1,h2,h3,h4,h5"); if (t && el.children.length > 1) t.remove();
      rewrite(el);
      var pop = $("#rd-notepop", root);
      pop.innerHTML = '<div class="h"><span>Примечание</span><button class="rd-ib" data-x>✕</button></div><div>' + el.innerHTML + '</div><div class="acts"><button class="rd-btn" data-go>Перейти к примечаниям</button></div>';
      pop.classList.add("open"); dim(true, closeNote);
      $("[data-x]", pop).onclick = closeNote;
      $("[data-go]", pop).onclick = function () { closeNote(); loadChapter(n, 0, {anchor: anchor}); };
    });
  }

  // ── кнопки ──
  $("#rd-back", root).onclick = function () { save(true); setTimeout(function () { src.back(); }, 60); };
  $("#rd-tocb", root).onclick = function () { openSide("toc"); };
  $("#rd-findb", root).onclick = function () { openFind(); };
  $("#rd-bmb", root).onclick = toggleBookmark;
  $("#rd-setb", root).onclick = function () { var P = $("#rd-set", root), o2 = P.classList.contains("open"); closeAll(); if (!o2) { renderSettings(); P.classList.add("open"); dim(true, closeAll); $("#rd-dimbg", root).style.background = "transparent"; } };
  $("#rd-dimbg", root).addEventListener("transitionend", function () { if (!this.classList.contains("on")) this.style.background = ""; });
  function fullscreen() { if (document.fullscreenElement) document.exitFullscreen(); else if (root.requestFullscreen) root.requestFullscreen().catch(function () {}); }
  $("#rd-fsb", root).onclick = fullscreen;
  var sliderDrag = false, sl = $("#rd-slider", root);
  sl.addEventListener("input", function () {
    sliderDrag = true;
    var v = sl.value / 1000;
    if (st.pdf) { $("#rd-pct", root).textContent = "стр. " + Math.max(1, Math.round(v * (st.pdf.pages - 1)) + 1); return; }
    var t = fromOverall(v); $("#rd-pct", root).textContent = Math.round(v * 100) + "%"; $("#rd-pageinfo", root).textContent = chapterTitle(t.ch);
  });
  sl.addEventListener("change", function () {
    sliderDrag = false; var v = sl.value / 1000;
    if (st.pdf) return pdfGo(Math.round(v * (st.pdf.pages - 1)) + 1);
    var t = fromOverall(v);
    if (t.ch === st.ch) gotoPos(t.pos); else loadChapter(t.ch, t.pos);
  });

  // ── таблицы: листы ──
  function renderSheets() {
    var bar = $("#rd-sheets", root);
    if (kind !== "table" || chapters.length < 2) { bar.hidden = true; return; }
    bar.hidden = false;
    bar.innerHTML = chapters.map(function (c, i) { return '<button data-ch="' + i + '" class="' + (i === st.ch ? "on" : "") + '">' + esc(c.title) + "</button>"; }).join("");
  }
  $("#rd-sheets", root).onclick = function (e) { var b = e.target.closest("button"); if (b) loadChapter(+b.dataset.ch, 0); };

  // ── чтение вслух ──
  var tts = {blocks: [], i: 0, paused: false, utter: null};
  function ttsStart() {
    if (!src.speak && !window.speechSynthesis) { toast("Синтез речи недоступен на этом устройстве"); return; }
    var blocks = $$("p, h1, h2, h3, h4, li, blockquote > p, .v, td", content).filter(function (b) { return b.textContent.trim() && !b.closest(".rd-chend"); });
    var startI = 0;
    if (mode() === "paged") { var bl = box.getBoundingClientRect().left; startI = Math.max(0, blocks.findIndex(function (b) { var r = b.getBoundingClientRect(); return r.right > bl + 4; })); }
    else { startI = Math.max(0, blocks.findIndex(function (b) { return b.getBoundingClientRect().bottom > 80; })); }
    tts.blocks = blocks; tts.i = startI; st.tts = true; root.classList.add("tts-on"); $("#rd-ttsb", root).classList.add("on");
    $("#rd-tts-rate", root).value = S.rate || 1;
    ttsSpeak();
  }
  function ttsSpeak() {
    if (!st.tts) return;
    $$(".rd-tts", content).forEach(function (x) { x.classList.remove("rd-tts"); });
    if (tts.i >= tts.blocks.length) {
      if (st.ch < chapters.length - 1) { loadChapter(st.ch + 1, 0).then(function () { tts.blocks = $$("p, h1, h2, h3, h4, li, .v", content).filter(function (b) { return b.textContent.trim() && !b.closest(".rd-chend"); }); tts.i = 0; ttsSpeak(); }); }
      else ttsStop();
      return;
    }
    var b = tts.blocks[tts.i]; b.classList.add("rd-tts");
    var r = b.getBoundingClientRect(), br = box.getBoundingClientRect();
    if (mode() === "paged" ? (r.left > br.right - 4 || r.right < br.left) : (r.top > window.innerHeight - 120 || r.bottom < 60)) revealEl(b);
    var text = b.textContent.replace(/\s+/g, " ").trim();
    var onEnd = function () { if (!st.tts || tts.paused) return; tts.i++; ttsSpeak(); };
    if (src.speak) { src.speak(text, onEnd, S.rate || 1); return; }
    var u = new SpeechSynthesisUtterance(text);
    u.lang = (pack.meta && pack.meta.lang) || "ru-RU"; u.rate = S.rate || 1;
    var voices = speechSynthesis.getVoices().filter(function (v) { return v.lang && v.lang.toLowerCase().indexOf(u.lang.slice(0, 2).toLowerCase()) === 0; });
    if (voices.length) u.voice = voices.find(function (v) { return /natural|online|neural/i.test(v.name); }) || voices[0];
    u.onend = onEnd; u.onerror = function () { if (st.tts && !tts.paused) { tts.i++; setTimeout(ttsSpeak, 100); } };
    tts.utter = u; speechSynthesis.speak(u);
  }
  function ttsStop() {
    st.tts = false; tts.paused = false; root.classList.remove("tts-on"); $("#rd-ttsb", root).classList.remove("on");
    if (src.stopSpeak) src.stopSpeak(); else if (window.speechSynthesis) speechSynthesis.cancel();
    $$(".rd-tts", content).forEach(function (x) { x.classList.remove("rd-tts"); });
  }
  $("#rd-ttsb", root).onclick = function () { if (st.tts) ttsStop(); else ttsStart(); };
  $("#rd-tts-stop", root).onclick = ttsStop;
  $("#rd-tts-pause", root).onclick = function () {
    tts.paused = !tts.paused; this.textContent = tts.paused ? "▶" : "⏸";
    if (tts.paused) { if (src.stopSpeak) src.stopSpeak(); else speechSynthesis.cancel(); } else ttsSpeak();
  };
  $("#rd-tts-rate", root).oninput = function () { S.rate = +this.value; saveSettings(S); };

  // ── PDF ──
  function pdfInit() {
    $("#rd-loading", root).style.display = "none";
    view.innerHTML = '<div class="rd-pdf" id="rd-pdf"></div>';
    var holder = $("#rd-pdf", root);
    var url = src.pdfUrl;
    function iframe() {
      holder.innerHTML = '<iframe src="' + esc(url) + '#page=' + (st.pdfStart || 1) + '" title="PDF"></iframe>';
      $("#rd-pageinfo", root).textContent = "Встроенный просмотр PDF браузера";
    }
    if (src.pdfInfo) return pdfNative(holder);
    if (!url) { holder.innerHTML = '<div class="rd-file"><div class="ic">📄</div><h2>PDF</h2><p>Нет файла</p></div>'; return; }
    loadScript("https://cdn.jsdelivr.net/npm/pdfjs-dist@3.11.174/build/pdf.min.js").then(function () {
      var lib = window.pdfjsLib; if (!lib) throw new Error("pdf.js");
      lib.GlobalWorkerOptions.workerSrc = "https://cdn.jsdelivr.net/npm/pdfjs-dist@3.11.174/build/pdf.worker.min.js";
      return lib.getDocument({url: url, withCredentials: true}).promise;
    }).then(function (doc) {
      st.pdf = {doc: doc, pages: doc.numPages, cur: st.pdfStart || 1, scale: 1, els: []};
      for (var i = 1; i <= doc.numPages; i++) {
        var d = document.createElement("div"); d.className = "pg"; d.dataset.n = i; d.innerHTML = '<span class="no">' + i + "</span>";
        holder.appendChild(d); st.pdf.els.push(d);
      }
      pdfResize();
      var io = new IntersectionObserver(function (ents) {
        ents.forEach(function (en) { if (en.isIntersecting) pdfRender(+en.target.dataset.n); });
      }, {root: holder, rootMargin: "800px 0px"});
      st.pdf.els.forEach(function (el) { io.observe(el); });
      holder.addEventListener("scroll", debounce(function () {
        var mid = holder.scrollTop + holder.clientHeight / 3, n = 1;
        st.pdf.els.forEach(function (el) { if (el.offsetTop <= mid) n = +el.dataset.n; });
        if (n !== st.pdf.cur) { st.pdf.cur = n; onMove(); }
      }, 150));
      setTimeout(function () { pdfGo(st.pdf.cur); }, 60);
      if (!toc.length) doc.getOutline().then(function (ol) {
        if (!ol) return;
        var flat = [];
        function walk(items, lvl) { items.forEach(function (it) { flat.push({it: it, lvl: lvl}); if (it.items && it.items.length) walk(it.items, lvl + 1); }); }
        walk(ol, 1);
        Promise.all(flat.map(function (f) {
          var dest = f.it.dest;
          return (typeof dest === "string" ? doc.getDestination(dest) : Promise.resolve(dest)).then(function (d) { return d ? doc.getPageIndex(d[0]) : -1; }).catch(function () { return -1; });
        })).then(function (pages) { toc = flat.map(function (f, i) { return {title: f.it.title, ch: 0, level: f.lvl, page: pages[i] + 1}; }).filter(function (t) { return t.page > 0; }); });
      }).catch(function () {});
      onMove();
    }).catch(function () { iframe(); });
  }
  // PDF силами устройства (Pocket: PdfRenderer) — страницы приходят картинками
  function pdfNative(holder) {
    Promise.resolve(src.pdfInfo()).then(function (info) {
      st.pdf = {pages: info.pages, cur: clamp(st.pdfStart || 1, 1, info.pages), els: [], native: true, sizes: info.sizes || []};
      for (var i = 1; i <= info.pages; i++) {
        var d = document.createElement("div"); d.className = "pg"; d.dataset.n = i; d.innerHTML = '<span class="no">' + i + "</span>";
        holder.appendChild(d); st.pdf.els.push(d);
      }
      pdfResize();
      var io = new IntersectionObserver(function (ents) { ents.forEach(function (en) { if (en.isIntersecting) pdfRender(+en.target.dataset.n); }); },
        {root: holder, rootMargin: "1200px 0px"});
      st.pdf.els.forEach(function (el) { io.observe(el); });
      holder.addEventListener("scroll", debounce(function () {
        var mid = holder.scrollTop + holder.clientHeight / 3, n = 1;
        st.pdf.els.forEach(function (el) { if (el.offsetTop <= mid) n = +el.dataset.n; });
        if (n !== st.pdf.cur) { st.pdf.cur = n; onMove(); }
      }, 150));
      setTimeout(function () { pdfGo(st.pdf.cur); }, 80);
      onMove();
    }).catch(function (e) { holder.innerHTML = '<div class="rd-file"><div class="ic">📄</div><h2>PDF не открылся</h2><p>' + esc(e && e.message || e) + "</p></div>"; });
  }
  function pdfResize() {
    if (!st.pdf) return;
    if (st.pdf.native) {
      var hw = Math.min($("#rd-pdf", root).clientWidth - 8, 1000);
      st.pdf.width = hw;
      st.pdf.els.forEach(function (el, i) { var sz = st.pdf.sizes[i] || st.pdf.sizes[0] || {w: 1, h: 1.41};
        el.style.width = hw + "px"; el.style.height = Math.round(hw * sz.h / sz.w) + "px"; el._done = 0; });
      return;
    }
    var holder = $("#rd-pdf", root), w = Math.min(holder.clientWidth - 16, 1000);
    st.pdf.doc.getPage(1).then(function (p) {
      var vp = p.getViewport({scale: 1}); st.pdf.scale = w / vp.width;
      st.pdf.els.forEach(function (el) { el.style.width = w + "px"; el.style.height = Math.round(vp.height * st.pdf.scale) + "px"; el._done = 0; var c = el.querySelector("canvas"); if (c) c.remove(); });
    });
  }
  function pdfRender(n) {
    if (st.pdf.native) {
      var ne = st.pdf.els[n - 1]; if (!ne || ne._done === st.pdf.width) return; ne._done = st.pdf.width;
      var px = Math.round(st.pdf.width * Math.min(window.devicePixelRatio || 1, 2.5));
      Promise.resolve(src.pdfPage(n, px)).then(function (u) {
        var im = ne.querySelector("img") || document.createElement("img");
        im.style.cssText = "display:block;width:100%;height:100%"; im.src = u; im.alt = "";
        if (!im.parentNode) ne.insertBefore(im, ne.firstChild);
      }).catch(function () { ne._done = 0; });
      return;
    }
    var el = st.pdf.els[n - 1]; if (!el || el._done === st.pdf.scale) return; el._done = st.pdf.scale;
    st.pdf.doc.getPage(n).then(function (p) {
      var dpr = Math.min(window.devicePixelRatio || 1, 2), vp = p.getViewport({scale: st.pdf.scale * dpr});
      var c = document.createElement("canvas"); c.width = vp.width; c.height = vp.height;
      var old = el.querySelector("canvas"); if (old) old.remove();
      el.insertBefore(c, el.firstChild);
      p.render({canvasContext: c.getContext("2d"), viewport: vp});
    });
  }
  function pdfGo(n) {
    if (!st.pdf) return; n = clamp(n, 1, st.pdf.pages); var el = st.pdf.els[n - 1];
    if (el) $("#rd-pdf", root).scrollTop = el.offsetTop - 60; st.pdf.cur = n; onMove();
  }
  function loadScript(u) {
    return new Promise(function (ok, bad) {
      if (window.pdfjsLib) return ok();
      var s = document.createElement("script"); s.src = u; s.onload = ok; s.onerror = bad; document.head.appendChild(s);
      setTimeout(bad, 12000);
    });
  }

  // ── старт ──
  applySettings();
  var init = o.initial || {};
  var loc = String(init.locator || book.locator || "");
  if (kind === "pdf") {
    var pm = loc.match(/^p:(\d+)/); st.pdfStart = pm ? +pm[1] : 1;
    if (init.page) st.pdfStart = init.page;
    $("#rd-bmb", root).style.display = "none"; $("#rd-ttsb", root).style.display = "none";
    pdfInit(); return api();
  }
  if (kind === "file" || !chapters.length || (chapters.length === 1 && !chapters[0].file)) {
    $("#rd-loading", root).style.display = "none";
    view.innerHTML = '<div class="rd-file"><div class="ic">📦</div><h2>' + esc(title) + '</h2><p>Этот формат (' + esc((book.fmt || "").toUpperCase()) + ') читалка не открывает — скачайте файл и откройте подходящей программой.</p>' +
      (src.fileUrl ? '<p><a class="rd-btn p" href="' + esc(src.fileUrl) + '" download>⬇ Скачать</a></p>' : "") + (src.openFile ? '<p><button class="rd-btn p" id="rd-openf">Открыть в другом приложении</button></p>' : "") + "</div>";
    if (src.openFile) $("#rd-openf", root).onclick = function () { src.openFile(); };
    $$(".rd-ib", root).forEach(function (b) { if (b.id !== "rd-back") b.style.display = "none"; });
    return api();
  }
  if (kind === "table") { $("#rd-ttsb", root).style.display = "none"; }
  var lm = loc.match(/^(\d+):([\d.]+)/);
  var startCh = init.ch != null ? init.ch : lm ? +lm[1] : 0, startPos = init.ch != null ? (init.pos || 0) : lm ? +lm[2] : 0;
  if (init.q) st.findQ = init.q;
  loadChapter(startCh, startPos).then(function () {
    lastSavedOv = overall();
    if (init.q) { var els = $$("mark.rd-find", content); if (els[0]) { els[0].classList.add("cur"); revealEl(els[0]); } }
    if (!localStorage.getItem("swds.reader.hint")) { toast("Нажмите по краям — листать, в центре — меню"); try { localStorage.setItem("swds.reader.hint", "1"); } catch (e) {} }
  });
  function api() { return {save: save, next: function () { st.pdf ? pdfGo(st.pdf.cur + 1) : next(); }, prev: function () { st.pdf ? pdfGo(st.pdf.cur - 1) : prev(); },
    goto: function (ch, pos) { return loadChapter(ch, pos); }, marks: function () { return marks; }, ttsDone: function () { if (st.tts && !tts.paused) { tts.i++; ttsSpeak(); } }}; }
  return api();
}

window.SWDSReader = {start: start, FONTS: FONTS, THEMES: THEMES};
})();
