const { JSDOM } = require('jsdom');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { installFetch } = require('./_fetch.cjs');

const dir = path.join(__dirname, '..', '..');
const html = fs.readFileSync(path.join(dir, 'index.html'), 'utf8');
const dom = new JSDOM(html, { runScripts: 'outside-only', url: 'https://localhost/' });
const { window } = dom;
installFetch(window, dir);
for (const g of ['window', 'document', 'navigator', 'TextEncoder', 'Blob', 'URL', 'console'])
  if (!(g in global)) global[g] = window[g];
for (const g of ['TextEncoder', 'TextDecoder']) if (!(g in window)) window[g] = globalThis[g];
window.URL.createObjectURL = () => 'blob:x';
window.URL.revokeObjectURL = () => {};
window.Error = Error;

const errors = [];
window.addEventListener('error', e => errors.push(e.message));
const files = [
  'yaml',
  'yamlCst',
  'packageDb',
  'rom',
  'disasm',
  'zip',
  'suiteArchive',
  'model',
  'suiteMapping',
  'validate',
  'preview',
  'stateStore',
  'i18n',
  'utils',
  'sidebar',
  'canvas',
  'txf',
  'props',
  'paths',
  'config',
  'docking',
  'actions',
  'perms',
  'kernel',
  'previewView',
  'importExport',
  'packages',
  'roms',
  'toolbar',
  'status',
  'core',
];
for (const f of files) window.eval(fs.readFileSync(path.join(dir, 'js', f + '.js'), 'utf8'));
window.document.dispatchEvent(new window.Event('DOMContentLoaded'));

const CONFIG_A = [
  '# Suite de prueba',
  'config-version: 1.0',
  'general:',
  '  language: es  # idioma elegido',
  'quick-look: false',
  '',
].join('\n');
const CHANNEL_A = ['config-version: 1.0', 'name: general', 'permission: cht.chat.general', ''].join('\n');
const PACKAGES_A = [
  'Name: iflow',
  'Version: 1.0.0',
  'Channel: stable',
  'Type: PACKAGE',
  'Dependencies: ',
  'Arch: none',
  'Platform: common',
  'URL: ',
  'OS: none',
  'SHA256: ',
  'Size: 0',
  'Files: ',
  '',
].join('\n');
const NOTES_A = '# notas que el editor no modela\n' + 'línea repetida que se conserva\n'.repeat(80);
const BLOB_A = [0x00, 0x80, 0xff, 0xfe, 0x01, 0x02];
const ROM_A = [0x01, 0x02, 0x03];
const CONFIG_B = ['# sin versión declarada', 'general:', '  language: es', ''].join('\n');
const CONFIG_E = ['# suite con base corrupta', 'config-version: 1.0', 'general:', '  language: es', ''].join('\n');
const BROKEN_PACKAGES = ['Name: roto', '# el manager ignoraría este comentario', ''].join('\n');

function buildFlow() {
  const out = Buffer.alloc(22 + 32);
  out.writeUInt32BE(0x464c4f57, 0);
  out[4] = 2;
  out[5] = 2;
  out[6] = 2;
  out[7] = 1;
  out.writeUInt16BE(0, 8);
  out.writeUInt16BE(2, 10);
  const hash = crypto.createHash('sha256').update(out.subarray(0, 22)).digest();
  hash.copy(out, 22);
  return new window.Uint8Array(out);
}
const VALID_FLOW = buildFlow();

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

function makeZip(entries) {
  return window.Suite.zip.build(entries);
}

async function importZip(entries, name) {
  const input = window.Suite.utils.$('#suiteInput');
  const file = new window.File([makeZip(entries)], name || 'suite.zip', { type: 'application/zip' });
  Object.defineProperty(input, 'files', { value: [file], configurable: true });
  input.dispatchEvent(new window.Event('change'));
  await sleep(100);
}

const realBlob = window.Blob;
window.HTMLAnchorElement.prototype.click = () => {};
let captured = null;

function exportZip() {
  captured = null;
  window.Blob = function (parts, options) {
    captured = { parts, options };
    return new realBlob(parts, options);
  };
  try {
    window.Suite.utils.$('#dlSuiteBtn').click();
  } finally {
    window.Blob = realBlob;
  }
  return captured;
}

function entriesOf(capture) {
  const reader = new window.Suite.zip.Reader(new Uint8Array(capture.parts[0]));
  const map = new Map();
  for (const entry of reader.entries) {
    map.set(entry.filename, entry.getData());
  }
  return map;
}

function textOf(bytes) {
  return new TextDecoder('utf-8').decode(bytes);
}

function methodsOf(capture) {
  const reader = new window.Suite.zip.Reader(new Uint8Array(capture.parts[0]));
  return new Set(reader.entries.map(entry => entry.method));
}

function sameBytes(a, b) {
  if (a.length !== b.length) {
    return false;
  }
  for (let i = 0; i < a.length; i++) {
    if (a[i] !== b[i]) {
      return false;
    }
  }
  return true;
}

setTimeout(async () => {
  let pass = 0;
  let fail = 0;
  function ok(condition, name) {
    if (condition) {
      pass++;
      console.log('  ✔', name);
    } else {
      fail++;
      console.log('  ✘', name);
    }
  }

  const S = window.Suite;
  const $ = S.utils.$;
  const store = window.StateStore;

  /* ── Importación con raíz `TextFormatter-Suite/` ─────────── */
  const zipA = [
    { name: 'TextFormatter-Suite/config/suite/config.yml', data: CONFIG_A },
    { name: 'TextFormatter-Suite/config/textformatter/channels/general.yml', data: CHANNEL_A },
    { name: 'TextFormatter-Suite/manager/library/status', data: PACKAGES_A },
    { name: 'TextFormatter-Suite/config/iflow-16/policy.flow', data: new window.Uint8Array(ROM_A) },
    { name: 'TextFormatter-Suite/config/iflow-16/valid.flow', data: VALID_FLOW },
    { name: 'TextFormatter-Suite/config/unknownmod/blob.bin', data: new window.Uint8Array(BLOB_A) },
    { name: 'TextFormatter-Suite/config/custom/notes.txt', data: NOTES_A },
    { name: 'TextFormatter-Suite/README.md', data: '# fuera del conjunto\n' },
    { name: 'TextFormatter-Suite/manager/library/packages/iflow.jar', data: 'jar' },
    { name: 'TextFormatter-Suite/__MACOSX/junk', data: 'x' },
  ];
  await importZip(zipA, 'suite-root.zip');
  ok($('#suiteModal').classList.contains('show'), 'import opens the summary modal');
  const bodyA = $('#suiteModalBody').textContent;
  ok(bodyA.includes('config/unknownmod'), 'the summary lists the modules');
  ok(bodyA.includes('config/iflow-16/policy.flow'), 'the summary lists the ROM warning');
  ok(bodyA.includes('se conserva sin hidratar'), 'the summary lists the binary warning');
  ok($('#suiteModalApply').style.display !== 'none', 'a valid import offers replace state');

  $('#suiteModalApply').click();
  ok(!$('#suiteModal').classList.contains('show'), 'applying closes the modal');
  ok(store.getState().config.general.language === 'es', 'the imported config hydrates the model');
  ok(!!store.getState().channels.general, 'the imported channel hydrates the model');
  ok(store.getState().packages.length === 1, 'the imported package database hydrates the model');
  ok(store.getState().packages[0].name === 'iflow', 'the package name is parsed');
  ok(store.getState().roms.length === 1, 'only the valid ROM is hydrated');
  ok(store.getState().roms[0].path === 'config/iflow-16/valid.flow', 'the valid ROM path is kept');
  ok(!store.getState().roms.some(rom => rom.path === 'config/iflow-16/policy.flow'), 'an invalid ROM is not hydrated');
  ok(store.getState().extra['config/custom/notes.txt'] === NOTES_A, 'unmapped text is kept as extra');

  /* ── Exportación con prefijo fijo y bytes preservados ───── */
  store.mutate('lang', () => {
    store.getState().config.general.language = 'en';
  });
  const captureA = exportZip();
  ok(captureA !== null, 'export builds a ZIP');
  const exportedA = entriesOf(captureA);
  const namesA = [...exportedA.keys()];
  ok(
    namesA.length > 0 && namesA.every(name => name.startsWith('TextFormatter-Suite/')),
    'every exported entry keeps the fixed root'
  );
  const configOut = textOf(exportedA.get('TextFormatter-Suite/config/suite/config.yml'));
  ok(configOut.includes('# Suite de prueba'), 'the header comment survives the export');
  ok(configOut.includes('# idioma elegido'), 'the inline comment survives the export');
  ok(configOut.includes('language: en'), 'the edited value is exported');
  ok(configOut.includes('config-version: 1.0'), 'the config-version line is preserved');
  ok(
    sameBytes(exportedA.get('TextFormatter-Suite/config/unknownmod/blob.bin'), new window.Uint8Array(BLOB_A)),
    'an unknown binary is preserved byte by byte'
  );
  ok(
    sameBytes(exportedA.get('TextFormatter-Suite/config/iflow-16/policy.flow'), new window.Uint8Array(ROM_A)),
    'an invalid ROM is preserved byte by byte'
  );
  ok(
    sameBytes(exportedA.get('TextFormatter-Suite/config/iflow-16/valid.flow'), VALID_FLOW),
    'a valid ROM is preserved byte by byte'
  );
  ok(
    textOf(exportedA.get('TextFormatter-Suite/manager/library/status')) === PACKAGES_A,
    'an unedited package database is preserved'
  );
  ok(textOf(exportedA.get('TextFormatter-Suite/config/custom/notes.txt')) === NOTES_A, 'extra text is preserved');
  ok(
    namesA.every(name => !name.includes('README') && !name.endsWith('.jar')),
    'outside files and JARs are not exported'
  );
  ok(
    namesA.every(name => !name.includes('__MACOSX')),
    'garbage entries are not exported'
  );
  const methodsA = methodsOf(captureA);
  ok(methodsA.has(8) && methodsA.has(0), 'the exported ZIP mixes DEFLATE and STORE');
  const archiveA = S.suiteArchive.fromZip(new Uint8Array(captureA.parts[0]));
  const validationA = S.suiteMapping.validateSuite(archiveA, null);
  ok(validationA.errors.length === 0, 'the exported YAML passes validation');
  ok(
    validationA.warnings.some(warning => warning.message === 'módulo desconocido'),
    'an unknown module is a warning, not an error'
  );

  /* ── Importación sin raíz y bloqueo por config-version ──── */
  await importZip(
    [
      { name: 'config/suite/config.yml', data: CONFIG_B },
      { name: 'config/unknownmod/blob.bin', data: new window.Uint8Array(BLOB_A) },
      { name: 'config/unknownmod/broken.yml', data: 'a: [1, # comentario\n  2]\n' },
    ],
    'suite-rootless.zip'
  );
  ok($('#suiteModal').classList.contains('show'), 'a ZIP without the root is accepted');
  $('#suiteModalApply').click();
  ok(store.getState().config.general.language === 'es', 'the rootless import hydrates the model');

  captured = null;
  const captureB = exportZip();
  ok(captureB === null, 'an invalid config blocks the export');
  ok($('#suiteModal').classList.contains('show'), 'the blocking errors open the modal');
  ok($('#suiteModalApply').style.display === 'none', 'the blocking modal hides replace state');
  ok($('#suiteModalBody').textContent.includes('falta config-version'), 'the modal explains the missing version');
  ok($('#suiteModalBody').textContent.includes('YAML inválido'), 'the modal reports invalid YAML');
  $('#suiteModalCancel').click();
  ok(!$('#suiteModal').classList.contains('show'), 'closing the blocking modal works');

  /* ── Base de paquetes corrupta ──────────────────────────── */
  await importZip(
    [
      { name: 'TextFormatter-Suite/config/suite/config.yml', data: CONFIG_E },
      { name: 'TextFormatter-Suite/manager/library/status', data: BROKEN_PACKAGES },
    ],
    'suite-broken-packages.zip'
  );
  ok($('#suiteModalBody').textContent.includes('base de paquetes inválida'), 'a corrupt package database is reported');
  $('#suiteModalApply').click();
  ok(store.getState().packages === null, 'a corrupt package database is not hydrated');
  const captureE = exportZip();
  ok(captureE !== null, 'a corrupt package database does not block the export');
  const exportedE = entriesOf(captureE);
  ok(
    textOf(exportedE.get('TextFormatter-Suite/manager/library/status')) === BROKEN_PACKAGES,
    'a corrupt package database is preserved byte by byte'
  );

  /* ── zip-slip, duplicados y límites ─────────────────────── */
  await importZip([{ name: 'TextFormatter-Suite/../evil.yml', data: 'config-version: 1.0\n' }], 'slip.zip');
  ok($('#toasts').textContent.includes('escapes the root'), 'zip-slip is rejected');
  ok(!$('#suiteModal').classList.contains('show'), 'zip-slip does not open the summary');

  await importZip(
    [
      { name: 'TextFormatter-Suite/config/suite/config.yml', data: CONFIG_A },
      { name: 'TextFormatter-Suite/config/suite/config.yml', data: CONFIG_A },
    ],
    'duplicate.zip'
  );
  ok($('#toasts').textContent.includes('duplicate ZIP entry'), 'duplicate entries are rejected');

  let limitsError = null;
  try {
    S.suiteArchive.fromZip(new Uint8Array(makeZip(zipA)), { limits: { maxEntryBytes: 8 } });
  } catch (error) {
    limitsError = error.message;
  }
  ok(/per-file limit/.test(String(limitsError)), 'the export limits are enforced while reading');

  ok(errors.length === 0, 'no runtime errors during the suite flow');
  if (errors.length > 0) {
    console.log('errors:', errors);
  }
  console.log('\n' + pass + ' passed, ' + fail + ' failed');
  process.exit(fail ? 1 : 0);
}, 300);
