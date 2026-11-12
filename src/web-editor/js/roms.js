/* roms.js — vista ROM: listado, alta, reemplazo, clon y borrado.
 *
 * Las ROM son binarias y no se editan campo a campo: se validan al entrar y se
 * sustituyen por un archivo nuevo. El clon duplica bytes y sidecar en una ruta
 * nueva de la suite; el runtime solo carga las rutas canónicas, así que la
 * vista lo advierte. Un `.wasm` recalcula su SHA-256 al añadirlo o reemplazarlo. */
(function (global) {
  'use strict';

  const $ = global.Suite.utils.$;
  const Suite = global.Suite;
  const StateStore = global.StateStore;
  const t = global.Suite.i18n.t;
  const Rom = global.Suite.rom;

  let selected = -1;

  function entriesOf(state) {
    return Array.isArray(state.roms) ? state.roms : [];
  }

  function current() {
    const state = StateStore.getState();
    const entries = entriesOf(state);
    if (entries.length === 0) {
      selected = -1;
      return { entries, entry: null };
    }
    if (selected < 0 || selected >= entries.length) {
      selected = 0;
    }
    return { entries, entry: entries[selected] };
  }

  function usedPaths(entries, except) {
    const used = new Set();
    for (const entry of entries) {
      if (entry === except) {
        continue;
      }
      used.add(entry.path);
      if (entry.sidecarPath) {
        used.add(entry.sidecarPath);
      }
    }
    return used;
  }

  function byteLength(entry) {
    try {
      return Rom.fromBase64(entry.data).length;
    } catch (error) {
      return 0;
    }
  }

  function renderRoms() {
    const list = $('#romList');
    const editor = $('#romEditor');
    const warning = $('#romWarning');
    if (!list || !editor || !warning) {
      return;
    }
    const { entries, entry } = current();

    const title = document.querySelector('[data-rom-title]');
    if (title) {
      title.textContent = t('v_rom');
    }
    const subtitle = document.querySelector('[data-rom-sub]');
    if (subtitle) {
      subtitle.textContent = t('rom_subtitle');
    }

    warning.innerHTML =
      '<strong>⚠ ' + Suite.utils.esc(t('rom_warning_title')) + '</strong> ' + Suite.utils.esc(t('rom_warning'));

    $('#romCount').textContent = entries.length === 0 ? '' : t('rom_count').replace('{0}', String(entries.length));

    if (entries.length === 0) {
      list.innerHTML = '<div class="pkg-empty">' + Suite.utils.esc(t('rom_no_entries')) + '</div>';
      editor.innerHTML = '<div class="pkg-empty">' + Suite.utils.esc(t('rom_select')) + '</div>';
      return;
    }

    list.innerHTML = entries
      .map((item, index) => {
        const active = index === selected ? ' active' : '';
        const flag = item.canonical ? t('rom_canonical') : t('rom_clone');
        const status = item.valid ? t('rom_valid') : t('rom_invalid');
        const meta = t(item.kind === 'wasm' ? 'rom_kind_wasm' : 'rom_kind_flow') + ' · ' + flag + ' · ' + status;
        return (
          '<div class="pkg-item' +
          active +
          '" data-rom-index="' +
          index +
          '"><span class="name">' +
          Suite.utils.esc(Rom.basename(item.path)) +
          '</span><span class="meta">' +
          Suite.utils.esc(meta) +
          '</span></div>'
        );
      })
      .join('');

    list.querySelectorAll('[data-rom-index]').forEach(el => {
      el.onclick = () => {
        selected = Number(el.dataset.romIndex);
        renderRoms();
      };
    });

    renderEditor(entry);
  }

  function renderEditor(entry) {
    const editor = $('#romEditor');
    const meta = entry.meta ? JSON.stringify(entry.meta, null, 2) : '';
    editor.innerHTML =
      '<div class="row">' +
      '<label>' +
      Suite.utils.esc(t('rom_path')) +
      '<input type="text" data-rom-field="path" value="' +
      Suite.utils.esc(entry.path) +
      '"' +
      (entry.canonical ? ' readonly' : '') +
      ' />' +
      '</label>' +
      '<label>' +
      Suite.utils.esc(t('rom_kind_flow')) +
      '<input type="text" readonly value="' +
      Suite.utils.esc(entry.kind === 'wasm' ? t('rom_kind_wasm') : t('rom_kind_flow')) +
      '" />' +
      '</label>' +
      '<label>' +
      Suite.utils.esc(t('rom_size')) +
      '<input type="text" readonly value="' +
      byteLength(entry) +
      '" />' +
      '</label>' +
      '</div>' +
      '<div class="pkg-readonly"><b>' +
      Suite.utils.esc(t('rom_sha256')) +
      '</b>\n' +
      Suite.utils.esc(entry.sha256 || '—') +
      (entry.sidecarPath ? '\n' + Suite.utils.esc(entry.sidecarPath) : '') +
      '</div>' +
      (meta
        ? '<div class="pkg-readonly"><b>' + Suite.utils.esc(t('rom_meta')) + '</b>\n' + Suite.utils.esc(meta) + '</div>'
        : '') +
      '<div class="pkg-hint">' +
      Suite.utils.esc(t('rom_add_hint')) +
      '</div>';

    const path = editor.querySelector('[data-rom-field="path"]');
    if (path && !entry.canonical) {
      path.addEventListener('change', () => applyPath(path));
    }
  }

  function applyPath(input) {
    const { entries, entry } = current();
    if (!entry || entry.canonical) {
      return;
    }
    const path = input.value.trim();
    const kind = Rom.kindOfPath(path);
    if (!kind || kind !== entry.kind || !Rom.isRomPath(path)) {
      Suite.utils.toast(t('rom_path_invalid'), 'err');
      renderRoms();
      return;
    }
    if (usedPaths(entries, entry).has(path)) {
      Suite.utils.toast(t('rom_duplicate_path'), 'err');
      renderRoms();
      return;
    }
    const sidecarPath = kind === 'wasm' ? Rom.sidecarPathFor(path) : null;
    if (sidecarPath && usedPaths(entries, entry).has(sidecarPath)) {
      Suite.utils.toast(t('rom_duplicate_path'), 'err');
      renderRoms();
      return;
    }
    const index = selected;
    StateStore.mutate('edit rom path', () => {
      const target = StateStore.getState().roms[index];
      target.path = path;
      target.canonical = false;
      if (kind === 'wasm') {
        target.sidecarPath = sidecarPath;
        target.sidecarText = Rom.formatSidecar(target.sha256, Rom.basename(path));
      }
    });
    Suite.utils.toast(t('rom_replaced'), 'ok');
  }

  function addRomBytes(bytes, filename) {
    const kind = Rom.kindOfPath(filename || '');
    if (!kind) {
      Suite.utils.toast(t('rom_invalid_file') + t('rom_path_invalid'), 'err');
      return null;
    }
    const state = StateStore.getState();
    const entries = entriesOf(state);
    const used = usedPaths(entries, null);
    const canonical = Rom.canonicalPath(kind);
    const path = used.has(canonical) ? Rom.clonePath(canonical, used) : canonical;
    const entry = Rom.entryFromBytes(path, bytes, { requireSidecar: false });
    if (!entry.valid) {
      Suite.utils.toast(t('rom_invalid_file') + entry.error, 'err');
      return null;
    }
    if (kind === 'wasm') {
      entry.sidecarText = Rom.formatSidecar(entry.sha256, Rom.basename(path));
    }
    const index = entries.length;
    StateStore.mutate('add rom', () => {
      StateStore.getState().roms.push(entry);
    });
    selected = index;
    Suite.utils.toast(t('rom_added'), 'ok');
    return entry;
  }

  function replaceRomBytes(bytes) {
    const { entries, entry } = current();
    if (!entry) {
      return null;
    }
    const kind = Rom.kindOfPath(entry.path);
    const sidecarPath = kind === 'wasm' ? entry.sidecarPath || Rom.sidecarPathFor(entry.path) : null;
    const hash = kind === 'wasm' ? Rom.sha256Hex(bytes) : null;
    const replacement = Rom.entryFromBytes(entry.path, bytes, {
      id: entry.id,
      sidecarPath,
      sidecarText: kind === 'wasm' ? Rom.formatSidecar(hash, Rom.basename(entry.path)) : null,
      requireSidecar: false,
    });
    if (!replacement.valid) {
      Suite.utils.toast(t('rom_invalid_file') + replacement.error, 'err');
      return null;
    }
    const index = entries.indexOf(entry);
    StateStore.mutate('replace rom', () => {
      StateStore.getState().roms[index] = replacement;
    });
    selected = index;
    Suite.utils.toast(t('rom_replaced'), 'ok');
    return replacement;
  }

  function cloneRom() {
    const { entries, entry } = current();
    if (!entry) {
      return null;
    }
    const used = usedPaths(entries, null);
    const suggested = Rom.clonePath(entry.path, used);
    const answer = global.prompt ? global.prompt(t('rom_clone_prompt'), suggested) : suggested;
    if (answer === null || answer === undefined) {
      return null;
    }
    const path = String(answer).trim();
    const kind = Rom.kindOfPath(path);
    if (!kind || kind !== entry.kind || !Rom.isRomPath(path) || used.has(path)) {
      Suite.utils.toast(t('rom_duplicate_path'), 'err');
      return null;
    }
    const clone = JSON.parse(JSON.stringify(entry));
    clone.id = path;
    clone.path = path;
    clone.canonical = false;
    if (kind === 'wasm') {
      clone.sidecarPath = Rom.sidecarPathFor(path);
      if (used.has(clone.sidecarPath)) {
        Suite.utils.toast(t('rom_duplicate_path'), 'err');
        return null;
      }
      clone.sidecarText = Rom.formatSidecar(clone.sha256, Rom.basename(path));
    }
    const index = entries.length;
    StateStore.mutate('clone rom', () => {
      StateStore.getState().roms.push(clone);
    });
    selected = index;
    Suite.utils.toast(t('rom_cloned'), 'ok');
    return clone;
  }

  function removeRom() {
    const { entries } = current();
    if (entries.length === 0 || selected < 0 || selected >= entries.length) {
      return;
    }
    const index = selected;
    StateStore.mutate('remove rom', () => {
      StateStore.getState().roms.splice(index, 1);
    });
    selected = Math.max(0, index - 1);
    Suite.utils.toast(t('rom_removed'), 'ok');
  }

  function pickFile(callback) {
    const input = $('#romInput');
    if (!input) {
      return;
    }
    input.value = '';
    input.onchange = () => {
      const file = input.files && input.files[0];
      if (!file) {
        return;
      }
      const reader = new global.FileReader();
      reader.onload = () => callback(new Uint8Array(reader.result), file.name);
      reader.onerror = () => Suite.utils.toast(String(reader.error || 'read error'), 'err');
      reader.readAsArrayBuffer(file);
    };
    input.click();
  }

  function bindRoms() {
    const add = $('#romAdd');
    const clone = $('#romClone');
    const replace = $('#romReplace');
    const remove = $('#romRemove');
    if (add) {
      add.onclick = () => pickFile((bytes, name) => addRomBytes(bytes, name));
    }
    if (clone) {
      clone.onclick = cloneRom;
    }
    if (replace) {
      replace.onclick = () => pickFile(bytes => replaceRomBytes(bytes));
    }
    if (remove) {
      remove.onclick = removeRom;
    }
  }

  global.Suite = global.Suite || {};
  global.Suite.views = global.Suite.views || {};
  Object.assign(global.Suite.views, {
    renderRoms,
    bindRoms,
    addRomBytes,
    replaceRomBytes,
    cloneRom,
    removeRom,
  });
})(window || this);
