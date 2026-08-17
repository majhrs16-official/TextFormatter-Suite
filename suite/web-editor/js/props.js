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
      $('#pfKind')
        .querySelectorAll('[data-kind]')
        .forEach(el => el.classList.toggle('active', el.dataset.kind === n.kind));
      $('#pfText').value = '';
      $('#pfRateWrap').style.display = 'none';
      $('#pfLangWrap').style.display = 'none';
      $('#pfSenderWrap').style.display = 'none';
      $('#pfSoundsWrap').style.display = 'none';
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
          StateStore.mutate('del sound', () => {
            const st = StateStore.getState();
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
          StateStore.mutate('rename', () => {
            if (!existed) {
              Suite.model.renameChannel(StateStore.getState(), sel.key, newName);
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
        StateStore.mutate('templates', () => {
          const st = StateStore.getState();
          st.channels[sel.key].messages = val.split('\n').filter(x => x.trim() !== '');
        });
      } else {
        StateStore.mutate('property', () => {
          const n = StateStore.getState().graph.nodes.find(x => x.id === sel.id);
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
      StateStore.mutate('rate', () => {
        const st = StateStore.getState();
        st.channels[sel.key]['rate-limit-per-second'] = v;
      });
    });
    $('#pfLangSource').addEventListener('change', () => {
      const sel = UI.sel;
      if (!sel) {
        return;
      }
      StateStore.mutate('lang', () => {
        const st = StateStore.getState();
        st.channels[sel.key]['lang-source'] = $('#pfLangSource').value;
      });
    });
    $('#pfLangTarget').addEventListener('change', () => {
      const sel = UI.sel;
      if (!sel) {
        return;
      }
      StateStore.mutate('lang', () => {
        const st = StateStore.getState();
        st.channels[sel.key]['lang-target'] = $('#pfLangTarget').value;
      });
    });
    $('#pfShowSender').addEventListener('click', () => {
      const sel = UI.sel;
      if (!sel || sel.type !== 'channel') {
        return;
      }
      StateStore.mutate('sender', () => {
        const st = StateStore.getState();
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
      StateStore.mutate('add sound', () => {
        const st = StateStore.getState();
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
            StateStore.mutate('kind', () => {
              const target = StateStore.getState().graph.nodes.find(x => x.id === nodeId);
              if (!target) {
                return;
              }
              target.kind = el.dataset.kind;
              target.matcher = target.matcher || {};
            });
            Suite.views.renderCanvas('iflow');
            Suite.views.renderProps();
            Suite.views.renderStatus();
          })
      );
  }

  global.Suite = global.Suite || {};
  global.Suite.views = global.Suite.views || {};
  Object.assign(global.Suite.views, { renderProps, fillLangSelects, renderSounds, bindPropsInputs });
})(window || this);
