/* sidebar.js — barra lateral (grupos, paleta, canales) */
(function (global) {
  'use strict';

  const $ = global.Suite.utils.$;
  const $$ = global.Suite.utils.$$;
  const t = global.Suite.i18n.t;
  const UI = global.Suite.i18n.UI;
  const StateStore = global.StateStore;
  const Suite = global.Suite;

  const GROUPS = { chat: 0, staff: 0, vip: 0, bypass: 0 };

const EXTENSION_GROUPS = {
  enabled: 0,
  disabled: 0,
  unloaded: 0,
};

  function renderSidebar() {
    const sb = $('#sidebarBody');
    const st = StateStore.getState();

    // Determine if we need full rebuild (side changed) or just channel list update
    const sideChanged = sb.dataset.side !== UI.side;
    if (sideChanged) {
      sb.innerHTML =
        '<div class="seg" style="display:flex;gap:3px;margin-bottom:10px">' +
        '<span class="' +
        (UI.side === 'groups' ? 'on' : '') +
        '" data-side="groups">' +
        t('groups') +
        '</span>' +
        '<span class="' +
        (UI.side === 'palette' ? 'on' : '') +
        '" data-side="palette">' +
        t('palette') +
        '</span>' +
        '<span class="' +
        (UI.side === 'extensions' ? 'on' : '') +
        '" data-side="extensions">' +
        t('extensions') +
        '</span>' +
        '</div>' +
        (UI.side === 'groups' ? groupsPane() : UI.side === 'extensions' ? extensionsPane() : palettePane());
      sb.dataset.side = UI.side;
      // Re-bind event handlers
      sb.querySelectorAll('[data-side]').forEach(
        el =>
          (el.onclick = () => {
            UI.side = el.dataset.side;
            renderSidebar();
          })
      );
      sb.querySelectorAll('[data-view]').forEach(el => (el.onclick = () => Suite.views.switchView(el.dataset.view)));
      sb.querySelectorAll('[data-channel]').forEach(el => {
        el.onclick = () => {
          UI.sel = { type: 'channel', key: el.dataset.channel };
          if (UI.view !== 'txf') {
            Suite.views.switchView('txf');
          }
          Suite.views.renderTxf();
          Suite.views.renderProps();
        };
      });
      const addBtn = sb.querySelector('#addChannel');
      if (addBtn) {
        addBtn.onclick = addChannelPrompt;
      }
      sb.querySelectorAll('.pal-item').forEach(el => el.addEventListener('dragstart', onPalDrag));
      // Extensions event handlers
      sb.querySelectorAll('[data-ext-id]').forEach(el => {
        el.onclick = () => {
          UI.sel = { type: 'extension', key: el.dataset.extId };
          Suite.views.switchView('extensions');
          Suite.views.renderProps();
        };
      });
      sb.querySelectorAll('[data-ext-action]').forEach(el => {
        el.onclick = (e) => {
          e.stopPropagation();
          const action = el.dataset.extAction;
          const extId = el.dataset.extId;
          if (action === 'enable') {
            enableExtension(extId);
          } else if (action === 'disable') {
            disableExtension(extId);
          } else if (action === 'reload') {
            reloadExtension(extId);
          } else if (action === 'config') {
            showExtensionConfig(extId);
          }
        };
      });
      return;
    }

    // Side unchanged - diff channel list if in groups view
    if (UI.side === 'groups') {
      const channels = Object.keys(st.channels).sort();
      const byGroup = {};
      for (const c of channels) {
        const g = c.split('.')[0] || 'misc';
        (byGroup[g] = byGroup[g] || []).push(c);
      }

      const ul = sb.querySelector('.sgroup:last-child ul');
      if (ul) {
        const existingItems = Array.from(ul.querySelectorAll('li[data-channel]'));
        const existingByChannel = new Map(existingItems.map(el => [el.dataset.channel, el]));
        const allChannels = channels;
        const groups = Object.keys(byGroup).sort();

        const first = true;
        for (const g of groups) {
          // Group header (text node, not channel) - keep as is
          // Channel items
          for (const c of byGroup[g]) {
            let li = existingByChannel.get(c);
            const sel = UI.sel && UI.sel.type === 'channel' && UI.sel.key === c;
            if (!li) {
              li = document.createElement('li');
              li.dataset.channel = c;
              li.textContent = 'â—„ ' + Suite.utils.esc(c);
              ul.appendChild(li);
            }
            if (li.textContent !== 'â—„ ' + Suite.utils.esc(c)) {
              li.textContent = 'â—„ ' + Suite.utils.esc(c);
            }
            li.classList.toggle('active', sel);
            if (!li._clickBound) {
              li.onclick = () => {
                UI.sel = { type: 'channel', key: li.dataset.channel };
                if (UI.view !== 'txf') {
                  Suite.views.switchView('txf');
                }
                Suite.views.renderTxf();
                Suite.views.renderProps();
              };
              li._clickBound = true;
            }
          }
        }
        // Remove channels no longer present
        for (const [ch, li] of existingByChannel) {
          if (!channels.includes(ch)) {
            li.remove();
          }
        }
      }
    }

    // Extensions view - diff extensions list
    if (UI.side === 'extensions') {
      const extensions = Object.keys(st.extensions || {}).sort();
      const ul = sb.querySelector('.sgroup:last-child ul');
      if (ul) {
        const existingItems = Array.from(ul.querySelectorAll('li[data-ext-id]'));
        const existingByExt = new Map(existingItems.map(el => [el.dataset.extId, el]));
        const allExts = extensions;
        const groups = Object.keys(EXTENSION_GROUPS).sort();

        for (const g of groups) {
          const exts = extensions.filter(id => {
            const ext = st.extensions[id];
            return ext && ext.enabled === (g === 'enabled');
          });
          for (const extId of exts) {
            const ext = st.extensions[extId];
            let li = existingByExt.get(extId);
            const sel = UI.sel && UI.sel.type === 'extension' && UI.sel.key === extId;
            if (!li) {
              li = document.createElement('li');
              li.dataset.extId = extId;
              li.className = 'ext-item';
              li.innerHTML = extensionItemHTML(extId, ext);
              ul.appendChild(li);
            } else {
              li.innerHTML = extensionItemHTML(extId, ext);
            }
            li.classList.toggle('active', sel);
            if (!li._clickBound) {
              li.onclick = () => {
                UI.sel = { type: 'extension', key: li.dataset.extId };
                Suite.views.switchView('extensions');
                Suite.views.renderProps();
              };
              li._clickBound = true;
            }
          }
        }
        // Remove extensions no longer present
        for (const [extId, li] of existingByExt) {
          if (!exts.includes(extId)) {
            li.remove();
          }
        }
      }
    }
  }

  function groupsPane() {
    const st = StateStore.getState();
    const channels = Object.keys(st.channels).sort();
    const byGroup = {};
    for (const c of channels) {
      const g = c.split('.')[0] || 'misc';
      (byGroup[g] = byGroup[g] || []).push(c);
    }
    const views = ['config', 'txf', 'iflow', 'translators', 'sync', 'perm', 'kernel'];
    let h = '<div class="sgroup"><h4>' + t('views') + ' <span class="badge">7</span></h4><ul>';
    for (const v of views) {
      h += '<li data-view="' + v + '" class="' + (UI.view === v ? 'active' : '') + '">' + t('v_' + v) + '</li>';
    }
    h +=
      '</ul></div><div class="sgroup"><h4>' +
      t('channels') +
      ' <span class="badge">' +
      channels.length +
      '</span></h4><ul>';
    for (const g of Object.keys(byGroup).sort()) {
      h +=
        '<li style="font-size:10px;color:var(--muted);text-transform:uppercase;padding:4px 10px;cursor:default">' +
        g +
        '</li>';
      for (const c of byGroup[g]) {
        h +=
          '<li data-channel="' +
          Suite.utils.esc(c) +
          '" class="' +
          (UI.sel && UI.sel.type === 'channel' && UI.sel.key === c ? 'active' : '') +
          '">â—„ ' +
          Suite.utils.esc(c) +
          '</li>';
      }
    }
    h +=
      '</ul><button class="tbtn" id="addChannel" style="width:100%;margin-top:8px">' +
      t('add_channel') +
      '</button></div>';
    return h;
  }

  function palettePane() {
    const kinds = ['input', 'cond', 'transform', 'loop', 'sleep', 'output', 'redirect'];
    const colorMap = {
      input: 'var(--green)',
      cond: 'var(--amber)',
      transform: 'var(--purple)',
      loop: 'var(--blue)',
      sleep: 'var(--red)',
      output: 'var(--green)',
      redirect: 'var(--red)',
    };
    let h = '<div class="sgroup"><h4>' + t('palette_kinds') + '</h4><div style="display:grid;gap:5px;padding:4px">';
    for (const k of kinds) {
      h +=
        '<div class="pal-item" draggable="true" data-kind="' +
        k +
        '" style="justify-content:flex-start"><span class="dot" style="background:' +
        colorMap[k] +
        '"></span>' +
        t('kind_' + k) +
        '</div>';
    }
    h += '</div></div><div class="sgroup"><h4>' + t('tokens') + '</h4><div style="display:grid;gap:5px;padding:4px">';
    for (const tk of ['%player_name%', '%content%', '%lang_source%', '%lang_target%']) {
      h +=
        '<div class="pal-item token" draggable="true" data-token="' +
        Suite.utils.esc(tk) +
        '" style="justify-content:flex-start">' +
        Suite.utils.esc(tk) +
        '</div>';
    }
    h += '</div></div>';
    return h;
  }

  function onPalDrag(e) {
    const item = e.target.closest('.pal-item');
    const kind = item.dataset.kind;
    const token = item.dataset.token;
    const dchip = item.dataset.dchip;
    e.dataTransfer.setData('text/suite-kind', kind || '');
    e.dataTransfer.setData('text/suite-token', token || '');
    e.dataTransfer.setData('text/suite-dchip', dchip || '');
    e.dataTransfer.effectAllowed = 'copy';
  }

  function extensionsPane() {
    const st = StateStore.getState();
    const extensions = Object.keys(st.extensions || {}).sort();
    const groups = { enabled: [], disabled: [] };
    for (const id of Object.keys(st.extensions || {}).sort()) {
      const ext = st.extensions[id];
      if (ext && ext.enabled) {
        groups.enabled.push(id);
      } else {
        groups.disabled.push(id);
      }
    }
    let h = '<div class="sgroup"><h4>' + t('extensions') + ' <span class="badge">' + Object.keys(st.extensions || {}).length + '</span></h4><ul>';
    for (const [group, exts] of Object.entries({ enabled: [], disabled: [] })) {
      const exts = Object.keys(st.extensions || {}).sort().filter(id => {
        const ext = st.extensions[id];
        return ext && ext.enabled === (group === 'enabled');
      });
      if (exts.length === 0) continue;
      h += '<li style="font-size:10px;color:var(--muted);text-transform:uppercase;padding:4px 10px;cursor:default">' + group + ' (' + exts.length + ')</li>';
      for (const id of exts) {
        h += extensionItemHTML(id, st.extensions[id]);
      }
    }
    h += '</ul></div>';
    return h;
  }

  function extensionItemHTML(id, ext) {
    if (!ext) return '';
    const enabled = ext.enabled;
    const version = ext.version || '1.0.0';
    const author = ext.author || 'Unknown';
    const desc = ext.description || '';
    const st = StateStore.getState();
    return (
      '<div style="display:flex;align-items:center;gap:6px;padding:4px 8px;width:100%">' +
      '<span data-ext-id="' +
      Suite.utils.esc(id) +
      '" class="ext-name" style="font-weight:500;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">' +
      Suite.utils.esc(id) +
      '</span>' +
      '<span class="ext-version" style="font-size:10px;color:var(--muted);margin-right:6px">' +
      Suite.utils.esc(ext.version || '1.0.0') +
      '</span>' +
      '<span class="ext-status ' +
      (st.extensions[id]?.enabled ? 'enabled' : 'disabled') +
      '" style="font-size:10px;padding:1px 4px;border-radius:3px;background:' +
      (st.extensions[id]?.enabled ? 'var(--green)' : 'var(--amber)') +
      ';color:white">' +
      (st.extensions[id]?.enabled ? t('enabled') : 'disabled') +
      '</span>' +
      '<button data-ext-id="' +
      Suite.utils.esc(id) +
      '" data-ext-action="config" class="tbtn" style="font-size:10px;padding:2px 6px;margin-left:4px" title="' +
      t('extension_config') +
      '">' +
      t('extension_config') +
      '</button>' +
      '<button data-ext-id="' +
      Suite.utils.esc(id) +
      '" data-ext-action="' +
      (st.extensions[id]?.enabled ? 'disable' : 'enable') +
      '" class="tbtn" style="font-size:10px;padding:2px 6px;margin-left:2px" title="' +
      (st.extensions[id]?.enabled ? t('extension_disable') : t('extension_enable')) +
      '">' +
      (st.extensions[id]?.enabled ? t('extension_disable') : t('extension_enable')) +
      '</button>' +
      '<button data-ext-id="' +
      Suite.utils.esc(id) +
      '" data-ext-action="reload" class="tbtn" style="font-size:10px;padding:2px 6px;margin-left:2px" title="' +
      t('extension_reload') +
      '">' +
      t('extension_reload') +
      '</button>' +
      '</div>'
    );
  }

  function enableExtension(id) {
    StateStore.mutate('enable extension', () => {
      const st = StateStore.getState();
      if (st.extensions[id]) {
        st.extensions[id].enabled = true;
      }
    });
    Suite.utils.toast(t('toast_ext_enabled', id), 'ok');
  }

  function disableExtension(id) {
    StateStore.mutate('disable extension', () => {
      const st = StateStore.getState();
      if (st.extensions[id]) {
        st.extensions[id].enabled = false;
      }
    });
    Suite.utils.toast(t('toast_ext_disabled', id), 'ok');
  }

  function reloadExtension(id) {
    Suite.utils.toast(t('toast_ext_reloaded', id), 'ok');
  }

  function showExtensionConfig(id) {
    const st = StateStore.getState();
    const ext = st.extensions[id];
    if (!ext) return;
    Suite.utils.toast(t('extension_config') + ': ' + id, 'info');
  }
    const name = window.prompt(t('new_channel_prompt'), t('new_channel'));
    if (name) {
      StateStore.mutate('add channel', () => {
        const st = StateStore.getState();
        let key = name;
        if (st.channels[key]) {
          let i = 2;
          while (st.channels[key + i]) {
            i++;
          }
          key = key + '-' + i;
        }
        Object.assign(st.channels, { [key]: Suite.model.clone(Suite.model.defaultsPkg ? {} : channelSpec(key)) });
        const node = Suite.model.addNode(st, 'input', key, 60 + Math.random() * 200, 100 + Math.random() * 180);
        st.graph.nodes[st.graph.nodes.indexOf(node)].x = 60 + Math.random() * 200;
      });
      Suite.utils.toast(t('toast_added'), 'ok');
    }
  }

  function channelSpec(name) {
    const st = StateStore.getState();
    const c = Suite.model.defaults().channels['chat.global'];
    const spec = Suite.model.clone(c);
    spec.name = name;
    spec.messages = ['&7👉 &f%player_name%&7: %content%'];
    return spec;
  }

  function renderPalette() {
    const vp = document.querySelector('.viewport[data-canvas="iflow"]');
    if (!vp) {
      return;
    }
    const stage = vp._stage;
    if (!stage) {
      return;
    }
    const st = StateStore.getState();
    const nodes = st.graph.nodes || [];
    for (const n of nodes) {
      stage.appendChild(Suite.views.makeNode(n));
    }
    stage.querySelectorAll('.node').forEach(n => Suite.views.bindNode(n));
    Suite.views.drawEdges(stage);
  }

  global.Suite = global.Suite || {};
  global.Suite.views = global.Suite.views || {};
  Object.assign(global.Suite.views, {
    renderSidebar,
    groupsPane,
    palettePane,
    onPalDrag,
    addChannelPrompt,
    channelSpec,
    renderPalette,
    extensionsPane,
    extensionItemHTML,
    enableExtension,
    disableExtension,
    reloadExtension,
    showExtensionConfig,
    GROUPS,
    EXTENSION_GROUPS,
  });
})(window || this);
