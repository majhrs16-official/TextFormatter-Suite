/* txf.js — vista TXF/Chain: chips, reorden, tarjetas */
(function (global) {
  'use strict';

  const $ = global.Suite.utils.$;
  const $$ = global.Suite.utils.$$;
  const Suite = global.Suite;
  const StateStore = global.StateStore;
  const UI = global.Suite.i18n.UI;

  const DEFAULT_CHIPS = ['○ echo', '✎ reformatear', '🔊 sonidos', '⌡ silenciar', '🌐 traducir'];

  function renderTxf() {
    const row = $('#chainRow');
    const st = StateStore.getState();
    const order = UI.chainOrder && UI.chainOrder.length ? UI.chainOrder : Object.keys(st.channels).sort();

    // Diff DEFAULT_CHIPS (always first, never removed)
    const defaultChips = Array.from(row.querySelectorAll('.rule-chip[data-template]'));
    DEFAULT_CHIPS.forEach((chip, i) => {
      let el = defaultChips[i];
      if (!el) {
        el = document.createElement('span');
        el.className = 'rule-chip';
        el.draggable = true;
        row.insertBefore(el, row.firstChild);
      }
      if (el.textContent !== chip) {
        el.textContent = chip;
      }
      if (el.dataset.template !== chip) {
        el.dataset.template = chip;
      }
      if (!el._dragBound) {
        addChipDrag(el);
        el._dragBound = true;
      }
    });

    // Diff channel chips (after separator)
    const sep =
      row.querySelector('.rule-chip[data-sep]') ||
      (() => {
        const s = document.createElement('span');
        s.style.cssText = 'width:1px;align-self:stretch;background:var(--line);margin:0 4px';
        s.dataset.sep = '1';
        row.appendChild(s);
        return s;
      })();
    const channelChips = Array.from(row.querySelectorAll('.rule-chip[data-channel]'));
    const channelByEl = new Map(channelChips.map(el => [el.dataset.channel, el]));
    const newChannels = order.filter(name => st.channels[name]);
    newChannels.forEach((name, i) => {
      let el = channelByEl.get(name);
      if (!el) {
        el = document.createElement('span');
        el.className = 'rule-chip';
        el.draggable = true;
        el.dataset.channel = name;
        el.title = 'arrastrar dentro de la chain para reordenar';
        row.insertBefore(el, sep.nextSibling);
      }
      if (el.textContent !== '◄ ' + name) {
        el.textContent = '◄ ' + name;
      }
      if (!el._dragBound) {
        addChipDrag(el);
        el._dragBound = true;
      }
    });
    // remove channels no longer in order
    for (const [name, el] of channelByEl) {
      if (!newChannels.includes(name)) {
        el.remove();
      }
    }

    bindChainReorder(row);

    // Cards diff (viewport)
    const vp = document.querySelector('.viewport[data-canvas="txf"]');
    const wrap =
      vp.querySelector('.txf-wrap') ||
      (() => {
        const w = document.createElement('div');
        w.className = 'txf-wrap';
        w.style.cssText = 'display:grid;gap:10px;padding:12px';
        vp.appendChild(w);
        return w;
      })();
    const cards = Array.from(wrap.querySelectorAll('.card[data-channel]'));
    const cardByEl = new Map(cards.map(el => [el.dataset.channel, el]));
    const sortedChannels = Object.keys(st.channels).sort();
    sortedChannels.forEach(name => {
      const c = st.channels[name];
      let el = cardByEl.get(name);
      if (!el) {
        el = document.createElement('div');
        el.className = 'card';
        el.style.cursor = 'pointer';
        el.dataset.channel = name;
        wrap.appendChild(el);
      }
      const sel = UI.sel && UI.sel.type === 'channel' && UI.sel.key === name;
      el.style.cssText =
        'cursor:pointer' + (sel ? ';border-color:var(--blue);box-shadow:0 0 0 2px var(--blue-soft)' : '');
      const firstMsg = (c.messages || [])[0] || '(sin plantilla)';
      const sounds = c.sounds && c.sounds.length ? ' · 🔊 ' + c.sounds.length : '';
      const newHTML =
        '<h3><span style="background:var(--green)" class="gr"></span>' +
        Suite.utils.esc(name) +
        '</h3>' +
        '<div class="f" style="font-family:var(--mono);font-size:11px;color:var(--txt2)">' +
        Suite.utils.esc(firstMsg) +
        '</div>' +
        '<div style="font-size:10.5px;color:var(--muted)">' +
        (c.messages || []).length +
        ' msgs' +
        sounds +
        ' · ' +
        Suite.utils.esc(c['lang-source']) +
        '→' +
        Suite.utils.esc(c['lang-target']) +
        '</div>';
      if (el.innerHTML !== newHTML) {
        el.innerHTML = newHTML;
      }
      if (!el._clickBound) {
        el.onclick = () => {
          UI.sel = { type: 'channel', key: name };
          renderTxf();
          Suite.views.renderProps();
        };
        el._clickBound = true;
      }
    });
    // remove cards for deleted channels
    for (const [name, el] of cardByEl) {
      if (!st.channels[name]) {
        el.remove();
      }
    }

    if (!Object.keys(st.channels).length && wrap.children.length === 0) {
      wrap.innerHTML =
        '<div style="color:var(--muted);font-size:12px">empty — drag a default chip out of the chain</div>';
    }
  }

  function addChipDrag(el) {
    el.addEventListener('dragstart', e => {
      e.dataTransfer.setData('text/plain', el.dataset.template || el.dataset.channel || '');
      e.dataTransfer.setData('text/chip-channel', el.dataset.channel || '');
      e.dataTransfer.setData('text/chip-template', el.dataset.template || '');
      e.dataTransfer.effectAllowed = 'copy';
      el.classList.add('dragging');
      UI.draggingChip = el.dataset.channel || el.dataset.template || '';
    });
    el.addEventListener('dragend', e => {
      el.classList.remove('dragging');
      UI.draggingChip = '';
      const row = $('#chainRow');
      if (!row) {
        return;
      }
      const r = row.getBoundingClientRect();
      const inside = e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom;
      if (inside) {
        return;
      } // reorder handled by bindChainReorder drop
      const channel = e.dataTransfer.getData('text/chip-channel');
      const template = e.dataTransfer.getData('text/chip-template');
      const st = StateStore.getState();
      if (channel && st.channels[channel]) {
        return;
      }
      if (template) {
        addChannelFromTemplate(template);
      }
    });
  }

  function bindChainReorder(row) {
    let prev = null;
    row.addEventListener('dragover', e => {
      e.preventDefault();
      const chip = row.querySelector('.rule-chip.dragging');
      if (!chip) {
        return;
      }
      const after = [...row.querySelectorAll('.rule-chip')].find(
        c => c !== chip && e.clientX < c.getBoundingClientRect().right - 12
      );
      if (after && after !== prev) {
        row.insertBefore(chip, after);
        prev = after;
      } else if (!after && row.lastChild !== chip && prev !== 'end') {
        row.appendChild(chip);
        prev = 'end';
      }
    });
    row.addEventListener('drop', e => {
      e.preventDefault();
      const id = UI.draggingChip;
      if (!id) {
        return;
      }
      const st = StateStore.getState();
      if (st.channels[id]) {
        const names = [...row.querySelectorAll('.rule-chip[data-channel]')].map(c => c.dataset.channel);
        UI.chainOrder = names;
      }
    });
  }

  function addChannelFromTemplate(template) {
    const names = {
      '○ echo': 'echo.chat',
      '✎ reformatear': 'reformat.chat',
      '🔊 sonidos': 'sounds.chat',
      '⌡ silenciar': 'mute.chat',
      '🌐 traducir': 'translate.chat',
    };
    const base = (names[template] || 'new.chat').replace('.chat', '');
    let name = base + '.chat';
    let i = 2;
    const st = StateStore.getState();
    while (st.channels[name]) {
      name = base + '-v' + i++ + '.chat';
    }
    StateStore.mutate('add channel via chain', () => {
      const s = StateStore.getState();
      s.channels[name] = channelSpec(name);
      if (template.includes('traducir')) {
        s.channels[name]['lang-source'] = 'es';
        s.channels[name]['lang-target'] = 'en';
      }
      Suite.model.addNode(s, 'input', name, 60 + Math.random() * 200, 100 + Math.random() * 180);
    });
    Suite.utils.toast(global.Suite.i18n.t('toast_added'), 'ok');
  }

  function channelSpec(name) {
    const st = StateStore.getState();
    const c = Suite.model.defaults().channels['chat.global'];
    const spec = Suite.model.clone(c);
    spec.name = name;
    spec.messages = ['&7▘ &f%player_name%&7: %content%'];
    return spec;
  }

  global.Suite = global.Suite || {};
  global.Suite.views = global.Suite.views || {};
  Object.assign(global.Suite.views, {
    renderTxf,
    addChipDrag,
    bindChainReorder,
    addChannelFromTemplate,
    DEFAULT_CHIPS,
    channelSpec,
  });
})(window || this);
