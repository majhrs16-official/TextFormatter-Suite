/* perms.js — tabla de permisos */
(function (global) {
  'use strict';

  const $ = global.Suite.utils.$;
  const $$ = global.Suite.utils.$$;
  const Suite = global.Suite;
  const StateStore = global.StateStore;

  function renderPerms() {
    const st = StateStore.getState();
    const table = $('#permsTable');
    const roles = st.perms.roles,
      cols = st.perms.cols,
      m = st.perms.matrix;

    // Check if table structure changed (roles/cols changed)
    const existingHeader = table.querySelector('thead tr') || table.querySelector('tr');
    const currentCols = existingHeader
      ? Array.from(existingHeader.querySelectorAll('th'))
          .slice(1)
          .map(th => th.textContent)
      : [];
    const structureChanged = currentCols.length !== cols.length || cols.some((c, i) => currentCols[i] !== c);

    if (structureChanged) {
      // Full rebuild
      let h =
        '<thead><tr><th>rol \\ permiso</th>' +
        cols.map(c => '<th>' + Suite.utils.esc(c) + '</th>').join('') +
        '</tr></thead><tbody>';
      for (const r of roles) {
        h += '<tr><td>' + Suite.utils.esc(r) + '</td>';
        for (let i = 0; i < cols.length; i++) {
          h +=
            '<td><button class="rowtoggle" data-r="' +
            Suite.utils.esc(r) +
            '" data-c="' +
            Suite.utils.esc(cols[i]) +
            '" style="width:28px;height:28px;border-radius:4px;background:' +
            (m[r][i] ? 'var(--green)' : 'var(--line)') +
            '"></button></td>';
        }
        h += '</tr>';
      }
      h += '</tbody>';
      table.innerHTML = h;
      bindPermButtons();
      return;
    }

    // Incremental update - only toggle button backgrounds
    const tbody = table.querySelector('tbody') || table;
    tbody.querySelectorAll('tr').forEach(row => {
      const r = row.dataset.r || row.querySelector('td')?.textContent;
      if (!r) {
        return;
      }
      row.querySelectorAll('.rowtoggle').forEach((btn, i) => {
        const desired = m[r]?.[i] === 1;
        const current = btn.style.background === 'var(--green)';
        if (desired !== current) {
          btn.style.background = desired ? 'var(--green)' : 'var(--line)';
        }
      });
    });

    // Ensure rowtoggles have data attributes
    tbody.querySelectorAll('.rowtoggle').forEach((btn, idx) => {
      if (!btn.dataset.r) {
        const row = btn.closest('tr');
        const cells = row.querySelectorAll('td, th');
        const r = cells[0]?.textContent;
        if (r) {
          btn.dataset.r = r;
          btn.dataset.c = cols[idx - 1] || '';
        }
      }
    });
    bindPermButtons();
  }

  function bindPermButtons() {
    const table = $('#permsTable');
    table.querySelectorAll('.rowtoggle').forEach(el => {
      if (el._bound) {
        return;
      }
      el._bound = true;
      el.addEventListener('click', () => {
        const r = el.dataset.r,
          c = el.dataset.c;
        StateStore.mutate('perm', () => {
          const s = StateStore.getState();
          s.perms.matrix[r][c] = s.perms.matrix[r][c] ? 0 : 1;
        });
        Suite.views.renderPerms();
      });
    });
  }

  global.Suite = global.Suite || {};
  global.Suite.views = global.Suite.views || {};
  Object.assign(global.Suite.views, { renderPerms });
})(window || this);
