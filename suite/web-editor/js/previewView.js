/* previewView.js — vista Preview */
(function (global) {
  'use strict';

  const $ = global.Suite.utils.$;
  const $$ = global.Suite.utils.$$;
  const Suite = global.Suite;
  const StateStore = global.StateStore;
  const UI = global.Suite.i18n.UI;

  function renderPreviewChannels() {
    const st = StateStore.getState();
    const s = $('#pvChannel');
    const cur = s.value;
    const channels = Object.keys(st.channels);
    // Only rebuild if channel list changed
    const existingOpts = Array.from(s.options).map(o => o.value);
    if (channels.length === existingOpts.length && channels.every((c, i) => c === existingOpts[i])) {
      if (s.value !== cur && channels.includes(cur)) {
        s.value = cur;
      }
      return;
    }
    s.innerHTML = channels
      .map(n => '<option' + (n === cur ? ' selected' : '') + '>' + Suite.utils.esc(n) + '</option>')
      .join('');
  }

  function bindPreview() {
    renderPreviewChannels();
    $('#pvRun').addEventListener('click', runPreview);
    $('#pvInput').addEventListener('keydown', e => {
      if (e.key === 'Enter') {
        runPreview();
      }
    });
  }

  function runPreview() {
    const st = StateStore.getState();
    const channel = $('#pvChannel').value || Object.keys(st.channels)[0];
    const text = $('#pvInput').value;
    if (!channel) {
      $('#pvResult').textContent = 'no channels';
      return;
    }
    const res = Suite.preview.simulate(st, channel, text);
    const chan = st.channels[channel];
    const vars = Suite.preview.tokens(chan);
    vars.content = text;
    const nodeById = new Map((st.graph.nodes || []).map(n => [n.id, n]));
    if (!res.ok) {
      $('#pvResult').innerHTML =
        '<span style="color:var(--red)">' + Suite.utils.esc(res.reason || 'unknown error') + '</span>';
      $('#pvPath').innerHTML = '';
      return;
    }
    const outputs = res.outputs || [];
    $('#pvResult').innerHTML =
      '<span style="color:var(--green)">' +
      outputs.map(o => Suite.utils.esc(o.text || o.target || '')).join('<br>') +
      '</span>';
    $('#pvPath').innerHTML = '';
    outputs.forEach((o, i) => {
      const step = document.createElement('div');
      step.style.cssText = 'font-family:var(--mono);font-size:11px;margin:2px 0';
      const label = o.target || '(sin salida)';
      step.innerHTML =
        '<b>#' +
        (i + 1) +
        '</b> ' +
        Suite.utils.esc(label) +
        ' &rarr; ' +
        Suite.utils.esc(o.text || '(no output)') +
        (o.guard ? ' <span style="color:var(--red)">(guard max-steps)</span>' : '');
      $('#pvPath').appendChild(step);
      if (o.path && o.path.length) {
        const trail = document.createElement('div');
        trail.style.cssText = 'font-family:var(--mono);font-size:10px;color:var(--muted);margin:0 0 4px 14px';
        trail.textContent = o.path
          .map(id => {
            const n = nodeById.get(id);
            return n ? n.kind + ':' + id : id;
          })
          .join(' > ');
        $('#pvPath').appendChild(trail);
      }
    });
    if (res.copies > 1) {
      const info = document.createElement('div');
      info.style.cssText = 'font-size:11px;color:var(--amber)';
      info.textContent = 'fan-out: ' + res.copies + ' copias';
      $('#pvPath').appendChild(info);
    }
    if (res.steps >= (st.graph.guard['max-steps'] || 512)) {
      const warn = document.createElement('div');
      warn.style.cssText = 'font-size:11px;color:var(--red)';
      warn.textContent = 'guard max-steps alcanzado';
      $('#pvPath').appendChild(warn);
    }
  }

  global.Suite = global.Suite || {};
  global.Suite.views = global.Suite.views || {};
  Object.assign(global.Suite.views, { renderPreviewChannels, bindPreview, runPreview });
})(window || this);
