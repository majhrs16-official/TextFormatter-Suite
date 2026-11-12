/* importExport.js — import/export del proyecto plano y de la suite.
 *
 * El modo proyecto conserva el ZIP plano del editor (`config.yml`, `channels/`,
 * `sync/`...). El modo suite trabaja con el conjunto seleccionado de artefactos
 * de `config/` y `manager/library/status`, conserva los archivos no editados y
 * confirma el reemplazo del estado antes de aplicarlo. */
(function (global) {
  'use strict';

  const $ = global.Suite.utils.$;
  const Suite = global.Suite;
  const StateStore = global.StateStore;

  // Base importada de la suite: el archivo original y sus baselines. Se clona
  // en cada exportación para que un undo no reexporte bytes ya escritos.
  let suiteSource = null;
  let pendingSuiteImport = null;

  function resetSuiteSource() {
    suiteSource = null;
  }

  function download(buffer, name) {
    const blob = new Blob([buffer], { type: 'application/zip' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    a.click();
    URL.revokeObjectURL(url);
  }

  function bindImportExport() {
    bindProject();
    bindSuite();
    bindPaste();
  }

  /* ── Proyecto plano ─────────────────────────────────────── */
  function bindProject() {
    const dlBtn = $('#dlBtn');
    if (dlBtn) {
      dlBtn.addEventListener('click', () => {
        const st = StateStore.getState();
        const v = Suite.validate.validate(st);
        if (v.blocking) {
          Suite.utils.toast(v.errors + ' error(s) — ' + Suite.i18n.t('toast_blocked'), 'err', 3600);
          return;
        }
        const files = Suite.model.exportFiles(st, v);
        const entries = Object.keys(files)
          .sort()
          .map(path => ({ name: path, data: files[path] }));
        download(Suite.zip.build(entries), 'suite-project-' + Date.now() + '.zip');
        Suite.utils.toast(Suite.i18n.t('toast_export_ok'), 'ok');
      });
    }
    const fileInput = $('#fileInput');
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
              resetSuiteSource();
              Suite.utils.toast(Suite.i18n.t('toast_import_ok'), 'ok');
              Suite.views.renderAll();
            } else {
              Suite.utils.toast(Suite.i18n.t('toast_yml_not_found'), 'warn');
            }
          } catch (err) {
            Suite.utils.toast(Suite.i18n.t('toast_zip_invalid') + err.message, 'err');
          }
        };
        reader.readAsArrayBuffer(file);
        e.target.value = '';
      });
    }
  }

  /* ── Suite ──────────────────────────────────────────────── */
  function bindSuite() {
    const importSuiteBtn = $('#importSuiteBtn');
    const suiteInput = $('#suiteInput');
    if (importSuiteBtn && suiteInput) {
      importSuiteBtn.addEventListener('click', () => suiteInput.click());
      suiteInput.addEventListener('change', e => {
        const file = e.target.files[0];
        if (!file) {
          return;
        }
        const reader = new FileReader();
        reader.onload = () => {
          try {
            const archive = Suite.suiteArchive.fromZip(new Uint8Array(reader.result));
            if (!archive.paths().length) {
              Suite.utils.toast(Suite.i18n.t('toast_suite_empty'), 'warn');
              return;
            }
            const mapping = Suite.suiteMapping.fromSuite(archive);
            const summary = Suite.suiteMapping.summarize(archive, mapping);
            showSuiteSummary(summary, mapping.warnings, () => {
              suiteSource = { archive, mapping };
              StateStore.replace(mapping.state);
              Suite.views.renderAll();
              Suite.utils.toast(Suite.i18n.t('toast_suite_import_ok'), 'ok');
            });
          } catch (err) {
            Suite.utils.toast(Suite.i18n.t('toast_suite_error') + err.message, 'err', 4200);
          }
        };
        reader.readAsArrayBuffer(file);
        e.target.value = '';
      });
    }

    const dlSuiteBtn = $('#dlSuiteBtn');
    if (dlSuiteBtn) {
      dlSuiteBtn.addEventListener('click', exportSuite);
    }

    const cancel = $('#suiteModalCancel');
    if (cancel) {
      cancel.addEventListener('click', () => {
        pendingSuiteImport = null;
        closeSuiteModal();
      });
    }
    const apply = $('#suiteModalApply');
    if (apply) {
      apply.addEventListener('click', () => {
        const run = pendingSuiteImport;
        pendingSuiteImport = null;
        closeSuiteModal();
        if (run) {
          run();
        }
      });
    }
  }

  function closeSuiteModal() {
    const modal = $('#suiteModal');
    if (modal) {
      modal.classList.remove('show');
    }
  }

  function showSuiteErrors(errors) {
    const modal = $('#suiteModal');
    const body = $('#suiteModalBody');
    if (!modal || !body) {
      return;
    }
    const t = key => Suite.i18n.t(key);
    const title = $('#suiteModalTitle');
    if (title) {
      title.textContent = t('suite_validate_title');
    }
    body.textContent = '';
    const list = document.createElement('ul');
    list.className = 'm-warn';
    const limit = 20;
    for (const error of errors.slice(0, limit)) {
      const item = document.createElement('li');
      item.textContent = error.path + ' — ' + error.message;
      list.appendChild(item);
    }
    if (errors.length > limit) {
      const item = document.createElement('li');
      item.textContent = '… +' + (errors.length - limit);
      list.appendChild(item);
    }
    body.appendChild(list);
    const cancel = $('#suiteModalCancel');
    if (cancel) {
      cancel.textContent = t('suite_validate_close');
    }
    const apply = $('#suiteModalApply');
    if (apply) {
      apply.style.display = 'none';
    }
    pendingSuiteImport = null;
    modal.classList.add('show');
  }

  function showSuiteSummary(summary, warnings, onApply) {
    const modal = $('#suiteModal');
    const body = $('#suiteModalBody');
    if (!modal || !body) {
      return;
    }
    const t = key => Suite.i18n.t(key);
    const title = $('#suiteModalTitle');
    if (title) {
      title.textContent = t('suite_import_title');
    }

    body.textContent = '';
    const rows = [
      ['suite_summary_files', summary.files],
      ['suite_summary_modules', summary.modules.length ? summary.modules.join(', ') : '—'],
      ['suite_summary_mapped', summary.mapped],
      ['suite_summary_opaque', summary.opaque],
      ['suite_summary_deferred', summary.deferred],
      ['suite_summary_extra', summary.extra],
      ['suite_summary_warnings', summary.warnings],
    ];
    for (const [key, value] of rows) {
      const row = document.createElement('div');
      row.className = 'm-row';
      const label = document.createElement('span');
      label.textContent = t(key);
      const val = document.createElement('b');
      val.textContent = String(value);
      row.appendChild(label);
      row.appendChild(val);
      body.appendChild(row);
    }

    const note = document.createElement('p');
    note.className = 'm-note';
    note.textContent = t('suite_import_replace');
    body.appendChild(note);

    if (warnings && warnings.length) {
      const limit = 20;
      const list = document.createElement('ul');
      list.className = 'm-warn';
      for (const warning of warnings.slice(0, limit)) {
        const item = document.createElement('li');
        item.textContent = warning.path + ' — ' + warning.message;
        list.appendChild(item);
      }
      if (warnings.length > limit) {
        const item = document.createElement('li');
        item.textContent = '… +' + (warnings.length - limit);
        list.appendChild(item);
      }
      body.appendChild(list);
    }

    const cancel = $('#suiteModalCancel');
    if (cancel) {
      cancel.textContent = t('suite_import_cancel');
    }
    const apply = $('#suiteModalApply');
    if (apply) {
      apply.textContent = t('suite_import_apply');
      apply.style.display = '';
    }
    pendingSuiteImport = onApply;
    modal.classList.add('show');
  }

  function exportSuite() {
    const st = StateStore.getState();
    const v = Suite.validate.validate(st);
    if (v.blocking) {
      Suite.utils.toast(v.errors + ' error(s) — ' + Suite.i18n.t('toast_blocked'), 'err', 3600);
      return;
    }
    try {
      const base = suiteSource ? suiteSource.archive.clone() : undefined;
      const mapping = suiteSource ? suiteSource.mapping : undefined;
      const result = Suite.suiteMapping.toSuite(st, base, mapping);
      const check = Suite.suiteMapping.validateSuite(result.archive, mapping);
      if (check.errors.length > 0) {
        Suite.utils.toast(Suite.i18n.t('suite_validate_blocked') + ' ' + check.errors.length, 'err', 4200);
        showSuiteErrors(check.errors);
        return;
      }
      download(result.archive.toZip(), 'TextFormatter-Suite-' + Date.now() + '.zip');
      Suite.utils.toast(
        Suite.i18n.t('toast_suite_export_ok') + ' · ' + result.archive.paths().length + ' file(s)',
        'ok'
      );
      if (check.warnings.length) {
        Suite.utils.toast(check.warnings.length + ' ' + Suite.i18n.t('suite_summary_warnings'), 'warn', 3600);
      } else if (result.warnings.length) {
        Suite.utils.toast(result.warnings.length + ' ' + Suite.i18n.t('suite_summary_warnings'), 'warn', 3600);
      }
    } catch (err) {
      Suite.utils.toast(Suite.i18n.t('toast_suite_error') + err.message, 'err', 4200);
    }
  }

  /* ── Pegar YAML ─────────────────────────────────────────── */
  function bindPaste() {
    const pasteBtn = $('#pasteBtn');
    if (pasteBtn) {
      pasteBtn.addEventListener('click', () => {
        $('#modal').classList.add('show');
        $('#modalText').value = '';
        $('#modalText').focus();
      });
    }
    const cancel = $('#modalCancel');
    if (cancel) {
      cancel.addEventListener('click', () => $('#modal').classList.remove('show'));
    }
    const apply = $('#modalApply');
    if (apply) {
      apply.addEventListener('click', () => {
        const txt = $('#modalText').value;
        if (!txt.trim()) {
          $('#modal').classList.remove('show');
          return;
        }
        const files = classifyPaste(txt);
        if (!files) {
          Suite.utils.toast(Suite.i18n.t('toast_yaml_invalid'), 'err');
        } else {
          StateStore.replace(Suite.model.importFromFiles(StateStore.getState(), files));
          resetSuiteSource();
          Suite.utils.toast(Suite.i18n.t('toast_imported'), 'ok');
          Suite.views.renderAll();
        }
        $('#modal').classList.remove('show');
      });
    }
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
  Object.assign(global.Suite.views, { bindImportExport, classifyPaste, exportSuite, showSuiteSummary });
})(window || this);
