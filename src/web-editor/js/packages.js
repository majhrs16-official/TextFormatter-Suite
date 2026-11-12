/* packages.js — vista Packages: listado y edición de manager/library/status.
 *
 * La base de paquetes no es YAML; se edita con `Suite.packageDb`. La vista
 * muestra una advertencia permanente porque el archivo es la fuente de verdad
 * de instalación del Manager y editarlo a mano puede dejarlo inconsistente. */
(function (global) {
  'use strict';

  const $ = global.Suite.utils.$;
  const Suite = global.Suite;
  const StateStore = global.StateStore;
  const t = global.Suite.i18n.t;

  let selected = -1;

  function entriesOf(state) {
    return Array.isArray(state.packages) ? state.packages : null;
  }

  function current() {
    const state = StateStore.getState();
    const entries = entriesOf(state);
    if (!entries || entries.length === 0) {
      selected = -1;
      return { state, entries, entry: null };
    }
    if (selected < 0 || selected >= entries.length) {
      selected = 0;
    }
    return { state, entries, entry: entries[selected] };
  }

  function renderPackages() {
    const list = $('#pkgList');
    const editor = $('#pkgEditor');
    const warning = $('#pkgWarning');
    if (!list || !editor || !warning) {
      return;
    }
    const { entries, entry } = current();

    const title = document.querySelector('[data-pkg-title]');
    if (title) {
      title.textContent = t('v_packages');
    }
    const subtitle = document.querySelector('[data-pkg-sub]');
    if (subtitle) {
      subtitle.textContent = t('pkg_subtitle');
    }

    warning.innerHTML =
      '<strong>⚠ ' +
      Suite.utils.esc(t('pkg_warning_title')) +
      '</strong> ' +
      Suite.utils.esc(t('pkg_warning')) +
      (entries === null ? '<br><br>' + Suite.utils.esc(t('pkg_no_db')) : '');

    $('#pkgCount').textContent = entries === null ? '' : t('pkg_count').replace('{0}', String(entries.length));

    if (!entries || entries.length === 0) {
      list.innerHTML =
        '<div class="pkg-empty">' + Suite.utils.esc(entries === null ? t('pkg_no_db') : t('pkg_empty')) + '</div>';
      editor.innerHTML = '<div class="pkg-empty">' + Suite.utils.esc(t('pkg_select')) + '</div>';
      return;
    }

    list.innerHTML = entries
      .map((item, index) => {
        const active = index === selected ? ' active' : '';
        const meta =
          item.version +
          ' · ' +
          item.channel +
          ' · ' +
          item.type +
          ' · ' +
          item.files.length +
          ' ' +
          t('pkg_files_count');
        return (
          '<div class="pkg-item' +
          active +
          '" data-pkg-index="' +
          index +
          '"><span class="name">' +
          Suite.utils.esc(item.name) +
          '</span><span class="meta">' +
          Suite.utils.esc(meta) +
          '</span></div>'
        );
      })
      .join('');

    list.querySelectorAll('[data-pkg-index]').forEach(el => {
      el.onclick = () => {
        selected = Number(el.dataset.pkgIndex);
        renderPackages();
      };
    });

    renderEditor(entry);
  }

  function field(label, key, value, options) {
    const attrs = options || {};
    const type = attrs.type || 'text';
    const input =
      '<input type="' +
      type +
      '" data-pkg-field="' +
      key +
      '" value="' +
      Suite.utils.esc(value === undefined || value === null ? '' : String(value)) +
      '"' +
      (attrs.min !== undefined ? ' min="' + attrs.min + '"' : '') +
      ' />';
    return '<label>' + Suite.utils.esc(label) + input + '</label>';
  }

  function textArea(label, key, value, hint) {
    return (
      '<label>' +
      Suite.utils.esc(label) +
      '<textarea data-pkg-field="' +
      key +
      '" spellcheck="false">' +
      Suite.utils.esc(value) +
      '</textarea>' +
      (hint ? '<span class="pkg-hint">' + Suite.utils.esc(hint) + '</span>' : '') +
      '</label>'
    );
  }

  function renderEditor(entry) {
    const editor = $('#pkgEditor');
    const extra = Object.entries(entry.extra || {})
      .map(([key, value]) => key + ': ' + value)
      .join('\n');
    editor.innerHTML =
      '<div class="row">' +
      field(t('pkg_name'), 'name', entry.name) +
      field(t('pkg_version'), 'version', entry.version) +
      field(t('pkg_channel'), 'channel', entry.channel) +
      '<label>' +
      Suite.utils.esc(t('pkg_type')) +
      '<select data-pkg-field="type">' +
      Suite.packageDb.TYPES.map(type => {
        const sel = type === entry.type ? ' selected' : '';
        return '<option value="' + type + '"' + sel + '>' + type + '</option>';
      }).join('') +
      '</select></label>' +
      '</div>' +
      '<div class="row">' +
      field(t('pkg_arch'), 'arch', entry.arch) +
      field(t('pkg_platform'), 'platform', entry.platform) +
      field(t('pkg_os'), 'os', entry.os) +
      field(t('pkg_size'), 'size', entry.size, { type: 'number', min: 0 }) +
      '</div>' +
      '<div class="row">' +
      field(t('pkg_url'), 'url', entry.url) +
      field(t('pkg_sha256'), 'sha256', entry.sha256) +
      field(t('pkg_source'), 'sourceArtifact', entry.sourceArtifact) +
      field(t('pkg_parent'), 'parentPackage', entry.parentPackage) +
      '</div>' +
      textArea(t('pkg_dependencies'), 'dependencies', (entry.dependencies || []).join(', ')) +
      '<div class="row">' +
      textArea(t('pkg_exclude'), 'exclude', (entry.exclude || []).join(' ')) +
      textArea(t('pkg_provide'), 'provide', (entry.provide || []).join(' ')) +
      '</div>' +
      textArea(t('pkg_files'), 'files', Suite.packageDb.formatFiles(entry.files || []), t('pkg_files_hint')) +
      (extra
        ? '<div class="pkg-readonly"><b>' +
          Suite.utils.esc(t('pkg_extra')) +
          '</b>\n' +
          Suite.utils.esc(extra) +
          '</div>'
        : '');

    editor.querySelectorAll('[data-pkg-field]').forEach(el => {
      el.addEventListener('change', () => applyField(el));
    });
  }

  function applyField(el) {
    const fieldName = el.dataset.pkgField;
    const { entries, entry } = current();
    if (!entries || !entry) {
      return;
    }
    const value = el.value;
    let parsed;
    try {
      if (fieldName === 'dependencies') {
        parsed = Suite.packageDb.parseDependencies(value);
      } else if (fieldName === 'exclude' || fieldName === 'provide') {
        parsed = Suite.packageDb.parseNameList(value);
      } else if (fieldName === 'files') {
        parsed = Suite.packageDb.parseFiles(value);
      } else if (fieldName === 'size') {
        parsed = Number(value);
        if (!Number.isSafeInteger(parsed) || parsed < 0) {
          throw new Suite.packageDb.PackageDbError(t('pkg_size_invalid'));
        }
      } else {
        parsed = value;
      }
    } catch (error) {
      Suite.utils.toast(error.message, 'err');
      renderPackages();
      return;
    }

    const index = selected;
    const candidate = JSON.parse(JSON.stringify(entries));
    candidate[index][fieldName] = parsed;
    try {
      Suite.packageDb.validate(candidate);
    } catch (error) {
      Suite.utils.toast(error.message, 'err');
      renderPackages();
      return;
    }
    StateStore.mutate('edit package', () => {
      const state = StateStore.getState();
      state.packages[index][fieldName] = parsed;
    });
    Suite.utils.toast(t('pkg_saved'), 'ok');
  }

  function addPackage() {
    const state = StateStore.getState();
    if (!Array.isArray(state.packages)) {
      state.packages = [];
    }
    const used = new Set(state.packages.map(entry => entry.name));
    let name = 'new-package';
    let counter = 2;
    while (used.has(name)) {
      name = 'new-package-' + counter;
      counter++;
    }
    const entry = Suite.packageDb.create();
    entry.name = name;
    const index = Array.isArray(state.packages) ? state.packages.length : 0;
    StateStore.mutate('add package', () => {
      const next = StateStore.getState();
      if (!Array.isArray(next.packages)) {
        next.packages = [];
      }
      next.packages.push(entry);
    });
    selected = index;
    Suite.utils.toast(t('pkg_added'), 'ok');
  }

  function removePackage() {
    const { entries } = current();
    if (!entries || selected < 0 || selected >= entries.length) {
      return;
    }
    const index = selected;
    StateStore.mutate('remove package', () => {
      StateStore.getState().packages.splice(index, 1);
    });
    selected = Math.max(0, index - 1);
    Suite.utils.toast(t('pkg_removed'), 'ok');
  }

  function bindPackages() {
    const add = $('#pkgAdd');
    const remove = $('#pkgRemove');
    if (add) {
      add.onclick = addPackage;
    }
    if (remove) {
      remove.onclick = removePackage;
    }
  }

  global.Suite = global.Suite || {};
  global.Suite.views = global.Suite.views || {};
  Object.assign(global.Suite.views, {
    renderPackages,
    bindPackages,
    addPackage,
    removePackage,
  });
})(window || this);
