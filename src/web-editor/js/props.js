/* props.js — panel de propiedades (renderProps, bindPropsInputs) */
(function (global) {
  'use strict';

  const $ = global.Suite.utils.$;
  const $$ = global.Suite.utils.$$;
  const Suite = global.Suite;
  const StateStore = global.StateStore;
  const UI = global.Suite.i18n.UI;

  function renderProps() {
    const empty = $('#pfEmpty');
    const form = $('#pfForm');
    const sel = UI.sel;
    if (!sel) {
      empty.style.display = '';
      form.style.display = 'none';
      return;
    }
    empty.style.display = 'none';
    form.style.display = '';

    const st = StateStore.getState();
    const c = sel.type === 'channel' ? st.channels[sel.key] : null;
    const n = sel.type === 'node' ? st.graph.nodes.find(x => x.id === sel.id) : null;
    const e = sel.type === 'edge' ? { from: sel.from, to: sel.to } : null;

    if (sel.type === 'channel' && !c) {
      UI.sel = null;
      return;
    }
    if (sel.type === 'node' && !n) {
      UI.sel = null;
      return;
    }
    if (sel.type === 'edge' && !(e && st.graph.edges?.some(x => x.from === e.from && x.to === e.to))) {
      UI.sel = null;
      return;
    }

    // Quick check if selection changed - if so, full rebuild
    const pf = form;
    if (pf.dataset.selType !== sel.type || pf.dataset.selKey !== (sel.key || sel.id || sel.from + '>' + sel.to)) {
      pf.dataset.selType = sel.type;
      pf.dataset.selKey = sel.key || sel.id || sel.from + '>' + sel.to;
      fullRender();
      return;
    }

    // Incremental update based on selection type
    if (sel.type === 'channel') {
      const pfId = $('#pfId');
      if (pfId.value !== sel.key) {
        pfId.value = sel.key;
      }
      fillLangSelects(c);
      const pfText = $('#pfText');
      const msgText = (c.messages || []).join('\n');
      if (pfText.value !== msgText) {
        pfText.value = msgText;
      }
      const pfRate = $('#pfRate');
      if (pfRate.value !== String(c['rate-limit-per-second'] || 0)) {
        pfRate.value = String(c['rate-limit-per-second'] || 0);
      }
      const pfShowSender = $('#pfShowSender');
      pfShowSender.classList.toggle('on', !!c['show-sender']);
      renderSounds(c.sounds || []);
    } else if (sel.type === 'node') {
      const pfId = $('#pfId');
      if (pfId.value !== n.id) {
        pfId.value = n.id;
      }
      $('#pfKind')
        .querySelectorAll('[data-kind]')
        .forEach(el => el.classList.toggle('active', el.dataset.kind === n.kind));
    }
  }

  function populateKindSelector() {
    const kindEl = $('#pfKind');
    if (kindEl.children.length > 0) {
      return; // Already populated
    }
    const kinds = ['input', 'cond', 'transform', 'loop', 'sleep', 'output', 'redirect', 'channel_redirect'];
    const colorMap = {
      input: 'var(--green)',
      cond: 'var(--amber)',
      transform: 'var(--purple)',
      loop: 'var(--blue)',
      sleep: 'var(--red)',
      output: 'var(--green)',
      redirect: 'var(--red)',
      channel_redirect: 'var(--red)',
    };
    for (const k of kinds) {
      const btn = document.createElement('span');
      btn.className = 'seg-btn';
      btn.dataset.kind = k;
      btn.innerHTML =
        '<span class="dot" style="background:' + colorMap[k] + '"></span>' + global.Suite.i18n.t('kind_' + k);
      kindEl.appendChild(btn);
    }
  }

  function fullRender() {
    const empty = $('#pfEmpty');
    const form = $('#pfForm');
    const sel = UI.sel;
    if (!sel) {
      empty.style.display = '';
      form.style.display = 'none';
      return;
    }
    empty.style.display = 'none';
    form.style.display = '';

    const st = StateStore.getState();
    const c = sel.type === 'channel' ? st.channels[sel.key] : null;
    const n = sel.type === 'node' ? st.graph.nodes.find(x => x.id === sel.id) : null;
    const e = sel.type === 'edge' ? { from: sel.from, to: sel.to } : null;

    if (sel.type === 'channel' && !c) {
      UI.sel = null;
      return;
    }
    if (sel.type === 'node' && !n) {
      UI.sel = null;
      return;
    }
    if (sel.type === 'edge' && !(e && st.graph.edges?.some(x => x.from === e.from && x.to === e.to))) {
      UI.sel = null;
      return;
    }

    if (sel.type === 'channel') {
      $('#pfKindWrap').style.display = 'none';
      $('#pfId').value = sel.key;
      $('#pfId').readOnly = true;
      fillLangSelects(c);
      $('#pfText').value = (c.messages || []).join('\n');
      $('#pfRate').value = c['rate-limit-per-second'] || 0;
      $('#pfShowSender').classList.toggle('on', !!c['show-sender']);
      renderSounds(c.sounds || []);
    } else if (sel.type === 'node') {
      $('#pfKindWrap').style.display = '';
      $('#pfId').value = n.id;
      $('#pfId').readOnly = true;
      // Ensure kind selector is populated
      populateKindSelector();
      $('#pfKind')
        .querySelectorAll('[data-kind]')
        .forEach(el => el.classList.toggle('active', el.dataset.kind === n.kind));
      $('#pfText').value = '';
      $('#pfRateWrap').style.display = 'none';
      $('#pfLangWrap').style.display = 'none';
      $('#pfSenderWrap').style.display = 'none';
      $('#pfSoundsWrap').style.display = 'none';
      // Render rule-specific properties
      renderRuleNodeProps(n);
    } else if (sel.type === 'edge') {
      $('#pfKindWrap').style.display = 'none';
      $('#pfId').value = e.from + ' → ' + e.to;
      $('#pfId').readOnly = true;
      $('#pfText').value = '';
      $('#pfRateWrap').style.display = 'none';
      $('#pfLangWrap').style.display = 'none';
      $('#pfSenderWrap').style.display = 'none';
      $('#pfSoundsWrap').style.display = 'none';
    }
  }

  function renderTransformProps(n) {
    const form = $('#pfForm');
    const transforms = n.transforms || [];

    // Clear existing transform fields (except the kind selector and id)
    const existingTransformFields = form.querySelectorAll('.pf-transform-field');
    existingTransformFields.forEach(el => el.remove());

    if (transforms.length === 0) {
      // Show empty state with add button
      const div = document.createElement('div');
      div.className = 'pf-transform-field';
      div.innerHTML = `
        <div class="pf-empty-transform">
          <p>No transforms configured</p>
          <button type="button" class="btn btn-primary" onclick="Suite.views.addTransformOp()">Add Transform</button>
        </p>
      `;
      const form = $('#pfForm');
      form.appendChild(div);
      return;
    }

    // Render each transform operation
    transforms.forEach((tr, index) => {
      const div = document.createElement('div');
      div.className = 'pf-transform-field';
      div.dataset.index = index;

      let html = `
        <div class="pf-transform-header">
          <span class="pf-transform-op">${tr.op}</span>
          <button type="button" class="btn btn-sm btn-danger" onclick="Suite.views.removeTransformOp(${index})">×</button>
        </div>
      `;

      switch (tr.op) {
        case 'rewrite':
          html += `
            <div class="pf-field">
              <label>Template</label>
              <textarea class="pf-transform-template" data-index="${index}">${Suite.utils.esc(tr.template || '')}</textarea>
            </div>
          `;
          break;
        case 'sounds':
          html += `
            <div class="pf-field">
              <label>Add Sounds</label>
              <input type="text" class="pf-sounds-add" value="${(tr.add || []).join(', ')}" placeholder="comma-separated sound names" data-index="${index}">
            </div>
            <div class="pf-field">
              <label>Remove Sounds</label>
              <input type="text" class="pf-sounds-remove" value="${(tr.remove || []).join(', ')}" placeholder="comma-separated sound names" data-index="${index}">
            </div>
          `;
          break;
        case 'sleep':
          html += `
            <div class="pf-field">
              <label>Milliseconds</label>
              <input type="number" class="pf-sleep-millis" value="${tr.millis || 0}" min="0" data-index="${index}">
            </div>
          `;
          break;
        case 'setLangSource':
          html += `
            <div class="pf-field">
              <label>Source Language</label>
              <select class="pf-lang-source" data-index="${index}">
                ${['auto', 'en', 'es', 'pt', 'de', 'fr', 'it', 'ja', 'ko', 'zh', 'ar', 'ru']
                  .map(l => `<option value="${l}" ${l === (tr.lang || 'auto') ? 'selected' : ''}>${l}</option>`)
                  .join('')}
              </select>
            </div>
          `;
          break;
        case 'setLangTarget':
          html += `
            <div class="pf-field">
              <label>Target Language</label>
              <select class="pf-lang-target" data-index="${index}">
                ${['auto', 'en', 'es', 'pt', 'de', 'fr', 'it', 'ja', 'ko', 'zh', 'ar', 'ru']
                  .map(l => `<option value="${l}" ${l === (tr.lang || 'auto') ? 'selected' : ''}>${l}</option>`)
                  .join('')}
              </select>
            </div>
          `;
          break;
        case 'setColorMode':
          html += `
            <div class="pf-field">
              <label>Color Mode</label>
              <select class="pf-color-mode" data-index="${index}">
                <option value="GRADIENT" ${tr.mode === 'GRADIENT' ? 'selected' : ''}>GRADIENT</option>
                <option value="SOLID" ${tr.mode === 'SOLID' ? 'selected' : ''}>SOLID</option>
                <option value="NONE" ${tr.mode === 'NONE' ? 'selected' : ''}>NONE</option>
              </select>
            </div>
          `;
          break;
        case 'setFormatPapi':
          html += `
            <div class="pf-field">
              <label>Enable PAPI Placeholders</label>
              <input type="checkbox" class="pf-format-papi" ${tr.enabled ? 'checked' : ''} data-index="${index}">
            </div>
          `;
          break;
        case 'setChannel':
          html += `
            <div class="pf-field">
              <label>Target Channel</label>
              <input type="text" class="pf-set-channel" value="${tr.channelPath || ''}" placeholder="channel.path" data-index="${index}">
            </div>
          `;
          break;
      }

      const transformDiv = document.createElement('div');
      transformDiv.className = 'pf-transform-field';
      transformDiv.dataset.index = index;
      transformDiv.innerHTML = html;
      const form = $('#pfForm');
      form.appendChild(transformDiv);

      // Bind events for this transform
      const templateArea = transformDiv.querySelector('.pf-transform-template');
      if (templateArea) {
        templateArea.addEventListener('change', e => {
          const idx = parseInt(e.target.dataset.index);
          StateStore.mutate('transform', st => {
            const n = st.graph.nodes.find(x => x.id === UI.sel.id);
            if (n && n.transforms[idx]) {
              n.transforms[idx].template = e.target.value;
            }
          });
        });
      }

      const addInput = transformDiv.querySelector('.pf-sounds-add');
      if (addInput) {
        addInput.addEventListener('change', e => {
          const idx = parseInt(e.target.dataset.index);
          StateStore.mutate('transform', st => {
            const n = st.graph.nodes.find(x => x.id === UI.sel.id);
            if (n && n.transforms[idx]) {
              n.transforms[idx].add = e.target.value
                .split(',')
                .map(s => s.trim())
                .filter(Boolean);
            }
          });
        });
      }

      const removeInput = transformDiv.querySelector('.pf-sounds-remove');
      if (removeInput) {
        removeInput.addEventListener('change', e => {
          const idx = parseInt(e.target.dataset.index);
          StateStore.mutate('transform', st => {
            const n = st.graph.nodes.find(x => x.id === UI.sel.id);
            if (n && n.transforms[idx]) {
              n.transforms[idx].remove = e.target.value
                .split(',')
                .map(s => s.trim())
                .filter(Boolean);
            }
          });
        });
      }

      const sleepInput = transformDiv.querySelector('.pf-sleep-millis');
      if (sleepInput) {
        sleepInput.addEventListener('change', e => {
          const idx = parseInt(e.target.dataset.index);
          StateStore.mutate('transform', st => {
            const n = st.graph.nodes.find(x => x.id === UI.sel.id);
            if (n && n.transforms[idx]) {
              n.transforms[idx].millis = parseInt(e.target.value) || 0;
            }
          });
        });
      }

      const langSourceSelect = transformDiv.querySelector('.pf-lang-source');
      if (langSourceSelect) {
        langSourceSelect.addEventListener('change', e => {
          const idx = parseInt(e.target.dataset.index);
          StateStore.mutate('transform', st => {
            const n = st.graph.nodes.find(x => x.id === UI.sel.id);
            if (n && n.transforms[idx]) {
              n.transforms[idx].lang = e.target.value;
            }
          });
        });
      }

      const langTargetSelect = transformDiv.querySelector('.pf-lang-target');
      if (langTargetSelect) {
        langTargetSelect.addEventListener('change', e => {
          const idx = parseInt(e.target.dataset.index);
          StateStore.mutate('transform', st => {
            const n = st.graph.nodes.find(x => x.id === UI.sel.id);
            if (n && n.transforms[idx]) {
              n.transforms[idx].lang = e.target.value;
            }
          });
        });
      }

      const colorModeSelect = transformDiv.querySelector('.pf-color-mode');
      if (colorModeSelect) {
        colorModeSelect.addEventListener('change', e => {
          const idx = parseInt(e.target.dataset.index);
          StateStore.mutate('transform', st => {
            const n = st.graph.nodes.find(x => x.id === UI.sel.id);
            if (n && n.transforms[idx]) {
              n.transforms[idx].mode = e.target.value;
            }
          });
        });
      }

      const papiCheckbox = transformDiv.querySelector('.pf-format-papi');
      if (papiCheckbox) {
        papiCheckbox.addEventListener('change', e => {
          const idx = parseInt(e.target.dataset.index);
          StateStore.mutate('transform', st => {
            const n = st.graph.nodes.find(x => x.id === UI.sel.id);
            if (n && n.transforms[idx]) {
              n.transforms[idx].enabled = e.target.checked;
            }
          });
        });
      }

      const channelInput = transformDiv.querySelector('.pf-set-channel');
      if (channelInput) {
        channelInput.addEventListener('change', e => {
          const idx = parseInt(e.target.dataset.index);
          StateStore.mutate('transform', st => {
            const n = st.graph.nodes.find(x => x.id === UI.sel.id);
            if (n && n.transforms[idx]) {
              n.transforms[idx].channelPath = e.target.value;
            }
          });
        });
      }
    });
  }

  function renderRuleNodeProps(n) {
    const form = $('#pfForm');
    const st = StateStore.getState();

    // Remove existing rule-specific fields
    const existingRuleFields = form.querySelectorAll('.pf-rule-field');
    existingRuleFields.forEach(el => el.remove());

    let html = '';

    // Common fields for all node kinds
    html += `
      <div class="pf-rule-field">
        <label>${global.Suite.i18n.t('label')} / ID</label>
        <input type="text" class="pf-rule-label" value="${Suite.utils.esc(n.label || '')}" placeholder="Label">
      </div>
    `;

    // Kind-specific fields
    switch (n.kind) {
      case 'cond':
        html += renderCondFields(n);
        break;
      case 'transform':
        // Transform nodes use renderTransformProps
        renderTransformProps(n);
        return;
      case 'redirect':
      case 'channel_redirect':
        html += renderRedirectFields(n);
        break;
      case 'sleep':
        html += renderSleepFields(n);
        break;
      case 'loop':
        html += renderLoopFields(n);
        break;
      case 'output':
        html += renderOutputFields(n);
        break;
      case 'input':
        html += renderInputFields(n);
        break;
    }

    if (html) {
      const div = document.createElement('div');
      div.className = 'pf-rule-field';
      div.innerHTML = html;
      form.appendChild(div);

      // Bind events
      bindRuleNodeEvents(n);
    }
  }

  function renderPriorityField(n) {
    const p = n.priority || 100;
    return `
      <div class="pf-rule-field">
        <label>${global.Suite.i18n.t('priority')}</label>
        <input type="number" class="pf-priority" value="${p}" min="0" max="10000" step="10" placeholder="100 (lower = higher priority)">
      </div>
    `;
  }

  function renderCondFields(n) {
    let h = '';
    h += `
      <div class="pf-rule-field">
        <label>${global.Suite.i18n.t('matcher')}</label>
        <div style="display:grid;gap:4px">
          <input type="text" class="pf-matcher-channel" placeholder="channel (e.g. chat.global)" value="${Suite.utils.esc(n.matcher?.channel || '')}">
          <input type="text" class="pf-matcher-sender" placeholder="sender pattern" value="${Suite.utils.esc(n.matcher?.sender || '')}">
          <input type="text" class="pf-matcher-receiver" placeholder="receiver pattern" value="${Suite.utils.esc(n.matcher?.receiver || '')}">
          <select class="pf-matcher-direction">
            <option value="">${global.Suite.i18n.t('direction_any')}</option>
            <option value="INITIATOR" ${n.matcher?.direction === 'INITIATOR' ? 'selected' : ''}>INITIATOR</option>
            <option value="OTHERS" ${n.matcher?.direction === 'OTHERS' ? 'selected' : ''}>OTHERS</option>
            <option value="ALL" ${n.matcher?.direction === 'ALL' ? 'selected' : ''}>ALL</option>
            <option value="CONSOLE" ${n.matcher?.direction === 'CONSOLE' ? 'selected' : ''}>CONSOLE</option>
            <option value="WORLD" ${n.matcher?.direction === 'WORLD' ? 'selected' : ''}>WORLD</option>
            <option value="RADIUS" ${n.matcher?.direction === 'RADIUS' ? 'selected' : ''}>RADIUS</option>
            <option value="PERMISSION" ${n.matcher?.direction === 'PERMISSION' ? 'selected' : ''}>PERMISSION</option>
            <option value="SPECIFIC" ${n.matcher?.direction === 'SPECIFIC' ? 'selected' : ''}>SPECIFIC</option>
          </select>
        </div>
      </div>
    `;
    h += `
      <div class="pf-rule-field">
        <label>${global.Suite.i18n.t('condition')} (SpEL)</label>
        <textarea class="pf-condition" rows="3" placeholder="'spam' in #msg.texts[0]">${Suite.utils.esc(n.condition || '')}</textarea>
      </div>
    `;
    h += `
      <div class="pf-rule-field">
        <label>${global.Suite.i18n.t('actions')}</label>
        <div style="display:flex;gap:4px;flex-wrap:wrap">
          <button type="button" class="btn btn-sm pf-add-action" data-action="cancel">${global.Suite.i18n.t('action_cancel')}</button>
          <button type="button" class="btn btn-sm pf-add-action" data-action="skipTranslate">${global.Suite.i18n.t('action_skipTranslate')}</button>
          <button type="button" class="btn btn-sm pf-add-action" data-action="rewrite">${global.Suite.i18n.t('action_rewrite')}</button>
          <button type="button" class="btn btn-sm pf-add-action" data-action="sounds">${global.Suite.i18n.t('action_sounds')}</button>
          <button type="button" class="btn btn-sm pf-add-action" data-action="sleep">${global.Suite.i18n.t('action_sleep')}</button>
          <button type="button" class="btn btn-sm pf-add-action" data-action="setLangSource">${global.Suite.i18n.t('action_setLangSource')}</button>
          <button type="button" class="btn btn-sm pf-add-action" data-action="setLangTarget">${global.Suite.i18n.t('action_setLangTarget')}</button>
          <button type="button" class="btn btn-sm pf-add-action" data-action="setFormatPapi">${global.Suite.i18n.t('action_setFormatPapi')}</button>
          <button type="button" class="btn btn-sm pf-add-action" data-action="setChannel">${global.Suite.i18n.t('action_setChannel')}</button>
        </div>
        <div class="pf-actions-list" style="margin-top:4px">
          ${(n.actions || []).map((a, i) => `<span class="pf-action-tag" data-index="${i}">${Suite.utils.esc(a)} <button type="button" class="pf-remove-action" data-index="${i}">×</button></span>`).join('')}
        </div>
      </div>
    `;
    h += `
      <div class="pf-rule-field">
        <label>${global.Suite.i18n.t('target')}</label>
        <select class="pf-target">
          <option value="DROP" ${n.target === 'DROP' ? 'selected' : ''}>${global.Suite.i18n.t('target_drop')}</option>
          <option value="REJECT" ${n.target === 'REJECT' ? 'selected' : ''}>${global.Suite.i18n.t('target_reject')}</option>
          <option value="LOG" ${n.target === 'LOG' ? 'selected' : ''}>${global.Suite.i18n.t('target_log')}</option>
          <option value="REDIRECT" ${n.target === 'REDIRECT' ? 'selected' : ''}>${global.Suite.i18n.t('target_redirect')}</option>
          <option value="CHANNEL_REDIRECT" ${n.target === 'CHANNEL_REDIRECT' ? 'selected' : ''}>${global.Suite.i18n.t('target_channelRedirect')}</option>
        </select>
      </div>
    `;
    h += renderPriorityField(n);
    return h;
  }

  function renderRedirectFields(n) {
    let h = '';
    h += `
      <div class="pf-rule-field">
        <label>${global.Suite.i18n.t('target_channel')}</label>
        <input type="text" class="pf-redirect-channel" value="${Suite.utils.esc(n.target?.channel || '')}" placeholder="channel.path">
      </div>
    `;
    if (n.kind === 'channel_redirect') {
      h += `
        <div class="pf-rule-field">
          <label>${global.Suite.i18n.t('redirect_channel')}</label>
          <input type="text" class="pf-redirect-channel" value="${Suite.utils.esc(n.redirectChannel || '')}" placeholder="channel.path">
        </div>
      `;
    }
    h += renderPriorityField(n);
    return h;
  }

  function renderSleepFields(n) {
    let h = '';
    const ms = (n.transforms && n.transforms.find(t => t.op === 'sleep'))?.millis || 0;
    h += `
      <div class="pf-rule-field">
        <label>${global.Suite.i18n.t('sleep_millis')}</label>
        <input type="number" class="pf-sleep-millis" value="${ms}" min="0" step="100">
      </div>
    `;
    h += renderPriorityField(n);
    return h;
  }

  function renderLoopFields(n) {
    let h = '';
    h += `
      <div class="pf-rule-field">
        <label>${global.Suite.i18n.t('loop_back_to')}</label>
        <select class="pf-loop-back">
          <option value="">${global.Suite.i18n.t('select_node')}</option>
          ${st.graph.nodes
            .filter(x => x.kind === 'cond')
            .map(
              x =>
                `<option value="${x.id}" ${n.loopBack === x.id ? 'selected' : ''}>${Suite.utils.esc(x.label || x.id)}</option>`
            )
            .join('')}
        </select>
      </div>
    `;
    h += renderPriorityField(n);
    return h;
  }

  function renderOutputFields(n) {
    let h = '';
    h += `
      <div class="pf-rule-field">
        <label>${global.Suite.i18n.t('output_channel')}</label>
        <input type="text" class="pf-output-channel" value="${Suite.utils.esc(n.label || '')}" placeholder="channel.path">
      </div>
    `;
    h += renderPriorityField(n);
    return h;
  }

  function renderInputFields(n) {
    let h = '';
    h += `
      <div class="pf-rule-field">
        <label>${global.Suite.i18n.t('input_channel')}</label>
        <input type="text" class="pf-input-channel" value="${Suite.utils.esc(n.label || '')}" placeholder="channel.path">
      </div>
    `;
    h += renderPriorityField(n);
    return h;
  }

  function bindRuleNodeEvents(n) {
    const form = $('#pfForm');
    const st = StateStore.getState();

    // Label
    const labelInput = form.querySelector('.pf-rule-label');
    if (labelInput) {
      labelInput.addEventListener('change', e => {
        StateStore.mutate('rule label', st => {
          const node = st.graph.nodes.find(x => x.id === n.id);
          if (node) node.label = e.target.value;
        });
      });
    }

    // Cond fields
    const matcherChannel = form.querySelector('.pf-matcher-channel');
    if (matcherChannel) {
      matcherChannel.addEventListener('change', e => {
        StateStore.mutate('matcher', st => {
          const node = st.graph.nodes.find(x => x.id === n.id);
          if (node) {
            node.matcher = node.matcher || {};
            node.matcher.channel = e.target.value || undefined;
          }
        });
      });
    }
    const matcherSender = form.querySelector('.pf-matcher-sender');
    if (matcherSender) {
      matcherSender.addEventListener('change', e => {
        StateStore.mutate('matcher', st => {
          const node = st.graph.nodes.find(x => x.id === n.id);
          if (node) {
            node.matcher = node.matcher || {};
            node.matcher.sender = e.target.value || undefined;
          }
        });
      });
    }
    const matcherReceiver = form.querySelector('.pf-matcher-receiver');
    if (matcherReceiver) {
      matcherReceiver.addEventListener('change', e => {
        StateStore.mutate('matcher', st => {
          const node = st.graph.nodes.find(x => x.id === n.id);
          if (node) {
            node.matcher = node.matcher || {};
            node.matcher.receiver = e.target.value || undefined;
          }
        });
      });
    }
    const matcherDirection = form.querySelector('.pf-matcher-direction');
    if (matcherDirection) {
      matcherDirection.addEventListener('change', e => {
        StateStore.mutate('matcher', st => {
          const node = st.graph.nodes.find(x => x.id === n.id);
          if (node) {
            node.matcher = node.matcher || {};
            node.matcher.direction = e.target.value || undefined;
          }
        });
      });
    }

    // Condition
    const conditionArea = form.querySelector('.pf-condition');
    if (conditionArea) {
      conditionArea.addEventListener('change', e => {
        StateStore.mutate('condition', st => {
          const node = st.graph.nodes.find(x => x.id === n.id);
          if (node) node.condition = e.target.value || undefined;
        });
      });
    }

    // Target
    const targetSelect = form.querySelector('.pf-target');
    if (targetSelect) {
      targetSelect.addEventListener('change', e => {
        StateStore.mutate('target', st => {
          const node = st.graph.nodes.find(x => x.id === n.id);
          if (node) node.target = e.target.value;
        });
      });
    }

    // Actions
    form.querySelectorAll('.pf-add-action').forEach(btn => {
      btn.addEventListener('click', e => {
        const action = e.target.dataset.action;
        StateStore.mutate('add action', st => {
          const node = st.graph.nodes.find(x => x.id === n.id);
          if (node) {
            node.actions = node.actions || [];
            node.actions.push(action);
          }
        });
        Suite.views.renderProps();
      });
    });

    form.querySelectorAll('.pf-remove-action').forEach(btn => {
      btn.addEventListener('click', e => {
        const idx = parseInt(e.target.dataset.index);
        StateStore.mutate('remove action', st => {
          const node = st.graph.nodes.find(x => x.id === n.id);
          if (node && node.actions) {
            node.actions.splice(idx, 1);
          }
        });
        Suite.views.renderProps();
      });
    });

    // Redirect fields
    const redirectChannel = form.querySelector('.pf-redirect-channel');
    if (redirectChannel) {
      redirectChannel.addEventListener('change', e => {
        StateStore.mutate('redirect', st => {
          const node = st.graph.nodes.find(x => x.id === n.id);
          if (node) {
            if (n.kind === 'channel_redirect') {
              node.redirectChannel = e.target.value || undefined;
            } else {
              node.target = node.target || {};
              node.target.channel = e.target.value || undefined;
            }
          }
        });
      });
    }

    // Sleep
    const sleepMillis = form.querySelector('.pf-sleep-millis');
    if (sleepMillis) {
      sleepMillis.addEventListener('change', e => {
        const ms = parseInt(e.target.value) || 0;
        StateStore.mutate('sleep', st => {
          const node = st.graph.nodes.find(x => x.id === n.id);
          if (node) {
            node.transforms = node.transforms || [];
            let sleepTr = node.transforms.find(t => t.op === 'sleep');
            if (!sleepTr) {
              sleepTr = { op: 'sleep', millis: 0 };
              node.transforms.push(sleepTr);
            }
            sleepTr.millis = ms;
          }
        });
      });
    }

    // Loop back
    const loopBack = form.querySelector('.pf-loop-back');
    if (loopBack) {
      loopBack.addEventListener('change', e => {
        StateStore.mutate('loop', st => {
          const node = st.graph.nodes.find(x => x.id === n.id);
          if (node) node.loopBack = e.target.value || undefined;
        });
      });
    }

    // Output channel
    const outputChannel = form.querySelector('.pf-output-channel');
    if (outputChannel) {
      outputChannel.addEventListener('change', e => {
        StateStore.mutate('output', st => {
          const node = st.graph.nodes.find(x => x.id === n.id);
          if (node) node.label = e.target.value;
        });
      });
    }

    // Input channel
    const inputChannel = form.querySelector('.pf-input-channel');
    if (inputChannel) {
      inputChannel.addEventListener('change', e => {
        StateStore.mutate('input', st => {
          const node = st.graph.nodes.find(x => x.id === n.id);
          if (node) node.label = e.target.value;
        });
      });
    }

    // Priority
    const priorityInput = form.querySelector('.pf-priority');
    if (priorityInput) {
      priorityInput.addEventListener('change', e => {
        const p = parseInt(e.target.value) || 100;
        StateStore.mutate('priority', st => {
          const node = st.graph.nodes.find(x => x.id === n.id);
          if (node) node.priority = p;
        });
      });
    }
  }

  function fillLangSelects(c) {
    const src = $('#pfLangSource'),
      dst = $('#pfLangTarget');
    const known = ['auto', 'en', 'es', 'pt', 'de', 'fr', 'it', 'ja', 'ko', 'zh', 'ar', 'ru'];
    if (src.options.length <= 1) {
      known.forEach(l => {
        src.add(new Option(l, l));
        dst.add(new Option(l, l));
      });
    }
    src.value = c['lang-source'] || 'auto';
    dst.value = c['lang-target'] || 'auto';
  }

  function renderSounds(sounds) {
    const ul = $('#pfSounds');
    ul.innerHTML = '';
    sounds.forEach((s, i) => {
      const li = document.createElement('li');
      li.innerHTML =
        '<span style="flex:1">🔊 ' +
        Suite.utils.esc(s.name) +
        '</span><span style="font-size:10px;color:var(--muted)">v' +
        (s.volume || 1) +
        ' p' +
        (s.pitch || 1) +
        '</span><span data-x class="x">✗</span>';
      li.onclick = e => {
        if (e.target.hasAttribute('data-x')) {
          StateStore.mutate('del sound', st => {
            const arr = st.channels[UI.sel.key].sounds;
            arr.splice(i, 1);
          });
        } else if (Suite.preview.playSound) {
          Suite.preview.playSound(s.name);
        }
      };
      ul.appendChild(li);
    });
  }

  function bindPropsInputs() {
    $('#pfId').addEventListener('change', () => {
      const sel = UI.sel;
      if (!sel) {
        return;
      }
      if (sel.type === 'channel') {
        const newName = $('#pfId').value.trim();
        if (newName && newName !== sel.key) {
          const existed = !!StateStore.getState().channels[newName];
          StateStore.mutate('rename', st => {
            if (!existed) {
              Suite.model.renameChannel(st, sel.key, newName);
            }
          });
          UI.sel.key = newName;
          Suite.views.renderSidebar();
          Suite.views.renderTxf();
          Suite.views.renderProps();
          Suite.views.renderPreviewChannels();
          Suite.views.renderStatus();
        }
      }
    });
    $('#pfText').addEventListener('change', () => {
      const sel = UI.sel;
      if (!sel) {
        return;
      }
      const val = $('#pfText').value;
      if (sel.type === 'channel') {
        StateStore.mutate('templates', st => {
          st.channels[sel.key].messages = val.split('\n').filter(x => x.trim() !== '');
        });
      } else {
        StateStore.mutate('property', st => {
          const n = st.graph.nodes.find(x => x.id === sel.id);
          if (!n) {
            return;
          }
          if (n.kind === 'transform') {
            let tr = (n.transforms || []).find(x => x.op === 'rewrite');
            if (!tr) {
              n.transforms = n.transforms || [];
              tr = { op: 'rewrite', template: '' };
              n.transforms.push(tr);
            }
            tr.template = val;
          } else if (n.kind === 'cond') {
            n.matcher = n.matcher || {};
            n.matcher.channel = val;
          } else if (n.kind === 'redirect') {
            n.target = { channel: val };
          } else {
            n.label = val || n.kind;
          }
        });
      }
    });
    $('#pfRate').addEventListener('change', () => {
      const sel = UI.sel;
      if (!sel || sel.type !== 'channel') {
        return;
      }
      const v = parseFloat($('#pfRate').value) || 0;
      StateStore.mutate('rate', st => {
        st.channels[sel.key]['rate-limit-per-second'] = v;
      });
    });
    $('#pfLangSource').addEventListener('change', () => {
      const sel = UI.sel;
      if (!sel) {
        return;
      }
      StateStore.mutate('lang', st => {
        st.channels[sel.key]['lang-source'] = $('#pfLangSource').value;
      });
    });
    $('#pfLangTarget').addEventListener('change', () => {
      const sel = UI.sel;
      if (!sel) {
        return;
      }
      StateStore.mutate('lang', st => {
        st.channels[sel.key]['lang-target'] = $('#pfLangTarget').value;
      });
    });
    $('#pfShowSender').addEventListener('click', () => {
      const sel = UI.sel;
      if (!sel || sel.type !== 'channel') {
        return;
      }
      StateStore.mutate('sender', st => {
        st.channels[sel.key]['show-sender'] = !st.channels[sel.key]['show-sender'];
      });
      $('#pfShowSender').classList.toggle('on');
    });
    $('#pfSoundBtn').addEventListener('click', () => {
      const sel = UI.sel;
      if (!sel || sel.type !== 'channel') {
        return;
      }
      const name = $('#pfSoundAdd').value.trim();
      if (!name) {
        return;
      }
      StateStore.mutate('add sound', st => {
        st.channels[sel.key].sounds = st.channels[sel.key].sounds || [];
        st.channels[sel.key].sounds.push({ name, volume: 1.0, pitch: 1.0 });
      });
      $('#pfSoundAdd').value = '';
      Suite.views.renderProps();
    });
    $('#pfKind')
      .querySelectorAll('[data-kind]')
      .forEach(
        el =>
          (el.onclick = () => {
            if (!UI.sel) {
              return;
            }
            const nodeId = UI.sel.id;
            StateStore.mutate('kind', st => {
              const target = st.graph.nodes.find(x => x.id === nodeId);
              if (!target) {
                return;
              }
              target.kind = el.dataset.kind;
              target.matcher = target.matcher || {};
            });
            Suite.views.renderCanvas(UI.view);
            Suite.views.renderProps();
            Suite.views.renderStatus();
          })
      );
  }

  global.Suite = global.Suite || {};
  global.Suite.views = global.Suite.views || {};
  Object.assign(global.Suite.views, {
    renderProps,
    fillLangSelects,
    renderSounds,
    bindPropsInputs,
    renderTransformProps,
    addTransformOp: function () {
      const sel = UI.sel;
      if (!sel || sel.type !== 'node') return;
      const n = StateStore.getState().graph.nodes.find(x => x.id === UI.sel.id);
      if (!n) return;

      StateStore.mutate('add transform', st => {
        const n = st.graph.nodes.find(x => x.id === UI.sel.id);
        if (!n) return;
        n.transforms = n.transforms || [];
        n.transforms.push({ op: 'rewrite', template: '' });
      });
      Suite.views.renderProps();
    },
    removeTransformOp: function (index) {
      const sel = UI.sel;
      if (!sel || sel.type !== 'node') return;

      StateStore.mutate('remove transform', st => {
        const n = st.graph.nodes.find(x => x.id === UI.sel.id);
        if (n && n.transforms) {
          n.transforms.splice(index, 1);
        }
      });
      Suite.views.renderProps();
    },
  });
})(window || this);
