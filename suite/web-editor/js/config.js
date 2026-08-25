/* config.js — vista Config: binds, sync fields, translators, renderConfigValues */
(function (global) {
  'use strict';

  const $ = global.Suite.utils.$;
  const $$ = global.Suite.utils.$$;
  const Suite = global.Suite;
  const StateStore = global.StateStore;
  const UI = global.Suite.i18n.UI;

  async function bindConfig() {
    await global.Suite.paths.load();
    const st = StateStore.getState();
    $('#cfgLang').value = st.config.general.language || 'en';
    $('#cfgLang').addEventListener('change', () =>
      StateStore.mutate('lang', () => {
        const s = StateStore.getState();
        s.config.general.language = $('#cfgLang').value;
      })
    );
    const claim = Suite.utils.$('#cfgClaimMode');
    if (claim) {
      claim.value = st.config.chat?.['claim-mode'] || 'cancel-event';
      claim.addEventListener('change', () =>
        StateStore.mutate('claim-mode', () => {
          const s = StateStore.getState();
          s.config.chat = s.config.chat || {};
          s.config.chat['claim-mode'] = claim.value;
        })
      );
    }
    $('#cfgMaxSteps').value = st.graph.guard['max-steps'];
    $('#cfgMaxSteps').addEventListener('change', () => {
      const v = parseInt($('#cfgMaxSteps').value, 10) || 512;
      StateStore.mutate('max-steps', () => {
        const s = StateStore.getState();
        s.graph.guard['max-steps'] = v;
      });
    });
    // generic data-bind switches - rendered dynamically from paths.json
    renderSwitches();
    // Delegated binding: covers dynamic (config section) AND static
    // (translators/sync sections) switches, surviving any re-render.
    if (!Suite.views._switchDelegateBound) {
      Suite.views._switchDelegateBound = true;
      document.addEventListener('click', e => {
        const sw = e.target.closest('.switch[data-bind]');
        if (!sw) {
          return;
        }
        StateStore.mutate('toggle', () => {
          const s = StateStore.getState();
          Suite.utils.setPath(s, sw.dataset.bind, !Suite.utils.getPath(s, sw.dataset.bind));
        });
        sw.classList.toggle('on', !!Suite.utils.getPath(StateStore.getState(), sw.dataset.bind));
        Suite.views.renderSwitches();
        Suite.views.renderStatus();
      });
    }
    $('#discordIntents').addEventListener('click', () => {
      StateStore.mutate('intents', () => {
        const s = StateStore.getState();
        const d = s.sync.discord;
        d.intents = d.intents && d.intents.length ? [] : ['GUILD_MESSAGES', 'MESSAGE_CONTENT'];
      });
      $('#discordIntents').classList.toggle('on', StateStore.getState().sync.discord.intents.length > 0);
    });
  }

  function renderSwitches() {
    const paths = global.Suite.paths.getAllSwitchPaths();
    const st = StateStore.getState();
    const container = Suite.utils.$('#configSection .switches-container');
    if (!container) {
      return;
    }
    // Solo knobs de config.yml/rules.yml: los switches de translators/sync
    // viven en sus propias secciones (estáticos) y no deben duplicarse aquí.
    const CONFIG_SCOPE = p => p.startsWith('config.') || p.startsWith('graph.');
    container.innerHTML = global.Suite.paths
      .getAllSwitchPaths()
      .filter(({ path }) => CONFIG_SCOPE(path))
      .map(({ path, label, desc }) => {
        const meta = global.Suite.paths.getPathMeta(path);
        const value = Suite.utils.getPath(StateStore.getState(), path);
        return (
          '<div class="field"><span class="cap">' +
          label +
          (meta.desc ? '<small>' + meta.desc + '</small>' : '') +
          '</span><span class="switch' +
          (value ? ' on' : '') +
          '" data-bind="' +
          path +
          '"></span></div>'
        );
      })
      .join('');
    // Sincronizar también los switches estáticos (translators/sync) con el estado.
    Suite.utils.$$('.switch[data-bind]').forEach(sw => {
      const desired = !!Suite.utils.getPath(st, sw.dataset.bind);
      if (sw.classList.contains('on') !== desired) {
        sw.classList.toggle('on', desired);
      }
    });
  }

  function bindSyncFields() {
    const set = (selId, path, parse) => {
      const el = Suite.utils.$(selId);
      if (!el) {
        return;
      }
      if (path) {
        el.value = Suite.utils.getPath(StateStore.getState(), path) || '';
      }
      el.addEventListener('change', () => {
        const v = parse ? parse(el.value) : el.value;
        if (path) {
          StateStore.mutate('sync', () => {
            const s = StateStore.getState();
            Suite.utils.setPath(s, path, v);
          });
        }
        Suite.views.renderStatus();
      });
    };
    set('#discordToken', 'sync.discord.token');
    set('#telegramToken', 'sync.telegram.token');
    set('#telegramChatId', 'sync.telegram.chat-id');
    set('#telegramHub', 'sync.telegram.hub');
    set('#httpWebhook', 'sync.http.webhook-url');
    set('#httpPort', 'sync.http.inbound-port', parseInt);
    set('#httpPath', 'sync.http.path');
    set('#tcpHost', 'sync.tcp-udp.host');
    set('#velocitySecret', 'sync.velocity.secret');
    set('#velocityMapping', 'sync.velocity.mapping');
    const st = StateStore.getState();
    const tcpPortsEl = Suite.utils.$('#tcpPorts');
    if (tcpPortsEl) {
      tcpPortsEl.value = [st.sync['tcp-udp']['outbound-port'], st.sync['tcp-udp']['inbound-port']].join(' ');
      tcpPortsEl.addEventListener('change', () => {
        const p = (tcpPortsEl.value.match(/\d+/g) || []).map(Number);
        StateStore.mutate('sync', () => {
          const s = StateStore.getState();
          s.sync['tcp-udp']['outbound-port'] = p[0] || 0;
          s.sync['tcp-udp']['inbound-port'] = p[1] || 0;
        });
      });
    }
    const velocityServersEl = Suite.utils.$('#velocityServers');
    if (velocityServersEl) {
      velocityServersEl.value = (st.sync.velocity.servers || []).join(', ');
      velocityServersEl.addEventListener('change', () => {
        StateStore.mutate('sync', () => {
          const s = StateStore.getState();
          s.sync.velocity.servers = velocityServersEl.value
            .split(',')
            .map(s => s.trim())
            .filter(Boolean);
        });
      });
    }

    const pasteTokenBtn = Suite.utils.$('#pasteTokenBtn');
    if (pasteTokenBtn) {
      pasteTokenBtn.addEventListener('click', () => pasteToken('#discordToken'));
    }
    const pasteTelegramBtn = Suite.utils.$('#pasteTelegramBtn');
    if (pasteTelegramBtn) {
      pasteTelegramBtn.addEventListener('click', () => pasteToken('#telegramToken'));
    }
    function pasteToken(inputId) {
      navigator.clipboard.readText().then(t => {
        if (t && t.trim()) {
          const input = Suite.utils.$(inputId);
          input.value = t.trim();
          StateStore.mutate('token', () => {
            const s = StateStore.getState();
            Suite.utils.setPath(
              s,
              input.id === 'discordToken' ? 'sync.discord.token' : 'sync.telegram.token',
              t.trim()
            );
          });
          Suite.utils.toast(global.Suite.i18n.t('toast_paste_ok'), 'ok');
        }
      });
    }

    const proto = document.querySelector('[data-pane="tcpudp"]').querySelectorAll('[data-prot]');
    function refreshProto() {
      const st = StateStore.getState();
      proto.forEach(p =>
        p.classList.toggle('on', (st.sync['tcp-udp'].protocol || 'TCP').toUpperCase() === p.dataset.prot)
      );
    }
    refreshProto();
    proto.forEach(p =>
      p.addEventListener('click', () => {
        StateStore.mutate('proto', () => {
          const s = StateStore.getState();
          s.sync['tcp-udp'].protocol = p.dataset.prot;
        });
        refreshProto();
        Suite.views.renderStatus();
      })
    );
    Suite.utils.$$('.etab').forEach(tab =>
      tab.addEventListener('click', () => {
        Suite.utils.$$('.etab').forEach(t => t.classList.toggle('active', t === tab));
        Suite.utils.$$('[data-pane]').forEach(p => (p.hidden = p.dataset.pane !== tab.dataset.pane));
      })
    );
  }

  function bindConfigSelects() {
    /* translators */
    const gs = Suite.utils.$('#googlePool');
    if (gs) {
      const st = StateStore.getState();
      gs.value = st.translators.google.pool['max-concurrent'];
      gs.addEventListener('change', () =>
        StateStore.mutate('pool', () => {
          const s = StateStore.getState();
          s.translators.google.pool['max-concurrent'] = parseInt(gs.value, 10) || 1;
        })
      );
    }
    const lurl = Suite.utils.$('#libreUrl');
    if (lurl) {
      const st = StateStore.getState();
      lurl.value = st.translators.libre['base-url'];
      lurl.addEventListener('change', () =>
        StateStore.mutate('libre', () => {
          const s = StateStore.getState();
          s.translators.libre['base-url'] = lurl.value;
        })
      );
    }
    const lkey = Suite.utils.$('#libreKey');
    if (lkey) {
      const st = StateStore.getState();
      lkey.value = st.translators.libre['api-key'];
      lkey.addEventListener('change', () =>
        StateStore.mutate('libre', () => {
          const s = StateStore.getState();
          s.translators.libre['api-key'] = lkey.value;
        })
      );
    }
    const testBtn = Suite.utils.$('#testBtn');
    if (testBtn) {
      testBtn.addEventListener('click', () => {
        const st = StateStore.getState();
        const txt = Suite.utils.$('#testText')?.value || 'hola mundo';
        const src = (st.config.general.language || 'en') === 'es' ? 'es' : 'en';
        const first = st.channels[Object.keys(st.channels)[0]];
        const dst = (first && first['lang-target']) || 'en';
        const testOut = Suite.utils.$('#testOut');
        if (testOut) {
          setTimeout(() => {
            testOut.innerHTML =
              '<b>' +
              src +
              ' → ' +
              dst +
              '</b> · «' +
              Suite.utils.esc(txt) +
              '» → «<span style="color:var(--green)">' +
              Suite.utils.esc(txt + ' (traducción demo)') +
              '</span>»';
          }, 240);
        }
      });
    }
  }

  function renderConfigValues() {
    const st = StateStore.getState();
    const cfgLang = Suite.utils.$('#cfgLang');
    if (cfgLang && cfgLang.value !== st.config.general.language) {
      cfgLang.value = st.config.general.language || 'en';
    }
    const cfgMaxSteps = Suite.utils.$('#cfgMaxSteps');
    if (cfgMaxSteps && cfgMaxSteps.value !== String(st.graph.guard['max-steps'])) {
      cfgMaxSteps.value = st.graph.guard['max-steps'];
    }
    const cfgClaimMode = Suite.utils.$('#cfgClaimMode');
    if (cfgClaimMode) {
      const desired = st.config.chat?.['claim-mode'] || 'cancel-event';
      if (cfgClaimMode.value !== desired) {
        cfgClaimMode.value = desired;
      }
    }
    Suite.utils.$$('.switch[data-bind]').forEach(sw => {
      const desired = !!Suite.utils.getPath(st, sw.dataset.bind);
      if (sw.classList.contains('on') !== desired) {
        sw.classList.toggle('on', desired);
      }
    });
    const discordIntents = Suite.utils.$('#discordIntents');
    if (discordIntents) {
      const desired = (st.sync.discord.intents || []).length > 0;
      if (discordIntents.classList.contains('on') !== desired) {
        discordIntents.classList.toggle('on', desired);
      }
    }
  }

  global.Suite = global.Suite || {};
  global.Suite.views = global.Suite.views || {};
  Object.assign(global.Suite.views, {
    bindConfig,
    bindSyncFields,
    bindConfigSelects,
    renderConfigValues,
    renderSwitches,
  });
})(window || this);
