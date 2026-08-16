/* importExport.js — import/export ZIP */
(function (global) {
  'use strict';

  const $ = global.Suite.utils.$;
  const Suite = global.Suite;
  const StateStore = global.StateStore;

  function bindImportExport() {
    const dlBtn = Suite.utils.$('#dlBtn');
    if (dlBtn) {
      dlBtn.addEventListener('click', () => {
        const st = StateStore.getState();
        const v = Suite.validate.validate(st);
        if (v.blocking) {
          Suite.utils.toast(v.errors + ' error(s) â€” ' + global.Suite.i18n.t('toast_blocked'), 'err', 3600);
          return;
        }
        const files = Suite.model.exportFiles(st, v);
        const entries = Object.keys(files)
          .sort()
          .map(path => ({ name: path, data: files[path] }));
        const buf = Suite.zip.build(entries);
        const blob = new Blob([buf], { type: 'application/zip' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'suite-project-' + Date.now() + '.zip';
        a.click();
        URL.revokeObjectURL(url);
        Suite.utils.toast(global.Suite.i18n.t('toast_export_ok'), 'ok');
      });
    }
    const fileInput = Suite.utils.$('#fileInput');
    if (fileInput) {
      fileInput.addEventListener('change', e => {
        const file = e.target.files[0];
        if (!file) {
          return;
        }
        const reader = new FileReader();
        reader.onload = () => {
          try {
            const zip = new Suite.zip.Reader(new Uint8Array(reader.result));
            const files = {};
            for (const entry of zip) {
              if (!entry.isDirectory) {
                const data = entry.getData();
                const txt = new TextDecoder().decode(data);
                files[entry.filename] = txt;
              }
            }
            if (Object.keys(files).length) {
              StateStore.replace(Suite.model.importFromFiles(StateStore.getState(), files));
              Suite.utils.toast(global.Suite.i18n.t('toast_import_ok'), 'ok');
              Suite.views.renderAll();
            } else {
              Suite.utils.toast('no .yml/.json found', 'warn');
            }
          } catch (err) {
            Suite.utils.toast('zip invÃ¡lido: ' + err.message, 'err');
          }
        };
        reader.readAsArrayBuffer(file);
        e.target.value = '';
      });
    }
    $('#pasteBtn').addEventListener('click', () => {
      $('#modal').classList.add('show');
      $('#modalText').value = '';
      $('#modalText').focus();
    });
    $('#modalCancel').addEventListener('click', () => $('#modal').classList.remove('show'));
    $('#modalApply').addEventListener('click', () => {
      const txt = $('#modalText').value;
      if (!txt.trim()) {
        $('#modal').classList.remove('show');
        return;
      }
      const files = classifyPaste(txt);
      if (!files) {
        Suite.utils.toast('no se pudo interpretar el YAML', 'err');
      } else {
        StateStore.replace(Suite.model.importFromFiles(StateStore.getState(), files));
        Suite.utils.toast('importado', 'ok');
        Suite.views.renderAll();
      }
      $('#modal').classList.remove('show');
    });
  }

  function classifyPaste(txt) {
    let obj = null;
    try {
      obj = Suite.yaml.parse(txt);
    } catch (e) {
      return null;
    }
    if (!obj || typeof obj !== 'object') {
      return null;
    }
    const files = {};
    if (obj.config) {
      files['config.yml'] = Suite.yaml.stringify(obj.config);
    }
    if (obj.channels) {
      for (const [k, v] of Object.entries(obj.channels)) {
        files['channels/' + k + '.yml'] = Suite.yaml.stringify(v);
      }
    }
    if (obj.rules) {
      files['rules.yml'] = Suite.yaml.stringify(obj.rules);
    }
    if (obj.sync) {
      for (const [k, v] of Object.entries(obj.sync)) {
        files['sync/' + k + '.yml'] = Suite.yaml.stringify(v);
      }
    }
    if (obj.translators) {
      for (const [k, v] of Object.entries(obj.translators)) {
        files['translators/' + k + '.yml'] = Suite.yaml.stringify(v);
      }
    }
    if (obj.perms) {
      files['perms.yml'] = Suite.yaml.stringify(obj.perms);
    }
    if (obj.extra) {
      for (const [k, v] of Object.entries(obj.extra)) {
        files[k] = v;
      }
    }
    return Object.keys(files).length ? files : null;
  }

  global.Suite = global.Suite || {};
  global.Suite.views = global.Suite.views || {};
  Object.assign(global.Suite.views, { bindImportExport, classifyPaste });
})(window || this);
