/* utils.js — utilidades UI compartidas */
(function (global) {
  'use strict';

  const $ = s => document.querySelector(s);
  const $$ = s => Array.from(document.querySelectorAll(s));

  function toast(msg, level, ms) {
    const box = $('#toasts');
    if (!box) {
      return;
    }
    const el = document.createElement('div');
    el.className = 'toast ' + (level || 'info');
    el.textContent = msg;
    box.appendChild(el);
    setTimeout(() => {
      el.style.opacity = '0';
      setTimeout(() => el.remove(), 300);
    }, ms || 2500);
  }

  function applyTheme() {
    document.documentElement.setAttribute('data-theme', global.Suite.i18n.UI.theme);
    $$('.switch[data-bind]').forEach(sw => sw.classList.toggle('on', !!getPath(global.Suite.i18n.UI, sw.dataset.bind)));
  }

  function applyLang() {
    $$('[data-i18n]').forEach(el => {
      const k = el.dataset.i18n;
      if (k) {
        el.textContent = global.Suite.i18n.t(k);
      }
    });
    $$('[data-i18n-placeholder]').forEach(el => {
      const k = el.dataset.i18nPlaceholder;
      if (k) {
        el.placeholder = global.Suite.i18n.t(k);
      }
    });
    $$('[data-i18n-title]').forEach(el => {
      const k = el.dataset.i18nTitle;
      if (k) {
        el.title = global.Suite.i18n.t(k);
      }
    });
  }

  function human(b) {
    return b < 1024 ? b + ' B' : (b / 1024).toFixed(1) + ' KB';
  }
  function esc(s) {
    return String(s).replace(
      /[&<>"']/g,
      c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]
    );
  }

  function getPath(o, p) {
    return p.split('.').reduce((a, k) => a && a[k], o);
  }
  function setPath(o, p, v) {
    const ks = p.split('.');
    let t = o;
    for (let i = 0; i < ks.length - 1; i++) {
      t = t[ks[i]] ?? (t[ks[i]] = {});
    }
    t[ks[ks.length - 1]] = v;
  }

  global.Suite = global.Suite || {};
  global.Suite.utils = {
    $: $,
    $$: $$,
    toast: toast,
    applyTheme: applyTheme,
    applyLang: applyLang,
    human: human,
    esc: esc,
    getPath: getPath,
    setPath: setPath,
  };
})(window || this);
