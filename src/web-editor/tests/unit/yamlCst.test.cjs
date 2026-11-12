#!/usr/bin/env node
/* tests/unit/yamlCst.test.cjs — unit tests for Suite.yamlCst */

const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const dir = path.join(__dirname, '..', '..');
const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', {
  runScripts: 'outside-only',
  url: 'https://localhost/',
});
const { window } = dom;
window.eval(fs.readFileSync(path.join(dir, 'js', 'yamlCst.js'), 'utf8'));
const Cst = window.Suite.yamlCst;

let passed = 0;
let failed = 0;

function assert(condition, message) {
  if (condition) {
    console.log('✓ ' + message);
    passed++;
  } else {
    console.log('✗ ' + message);
    failed++;
  }
}

function assertEq(actual, expected, message) {
  const same = JSON.stringify(actual) === JSON.stringify(expected);
  assert(same, message + (same ? '' : ' — expected ' + JSON.stringify(expected) + ' got ' + JSON.stringify(actual)));
}

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function apply(text, edit, options) {
  const baseline = Cst.parseValue(text);
  const current = edit(clone(baseline));
  return Cst.apply(text, baseline, current, options || { ensureConfigVersion: true });
}

function throws(fn, pattern, message) {
  try {
    fn();
    assert(false, message + ' — no lanzó');
  } catch (error) {
    assert(error.name === 'CstError' && pattern.test(error.message), message + ' — ' + error.message);
  }
}

function yamlFiles(directory, found) {
  const out = found || [];
  for (const item of fs.readdirSync(directory, { withFileTypes: true })) {
    if (item.name === 'node_modules' || item.name === 'build' || item.name === '.git') {
      continue;
    }
    const full = path.join(directory, item.name);
    if (item.isDirectory()) {
      yamlFiles(full, out);
    } else if (/\.ya?ml$/.test(item.name)) {
      out.push(full);
    }
  }
  return out;
}

console.log('\n=== Suite.yamlCst Unit Tests ===\n');

console.log('--- parseValue ---');
assertEq(
  Cst.parseValue('a: 1\nb: true\nc: null\nd: hola\n'),
  { a: 1, b: true, c: null, d: 'hola' },
  'parsea escalares planos'
);
assertEq(
  Cst.parseValue("a: 'uno dos'\nb: \"tres\\ncuatro\"\n"),
  { a: 'uno dos', b: 'tres\ncuatro' },
  'parsea comillas simples y dobles'
);
assertEq(Cst.parseValue('a: [1, dos, true]\nb: {x: 1, y: hola}\n'), { a: [1, 'dos', true], b: { x: 1, y: 'hola' } }, 'parsea flujo');
assertEq(Cst.parseValue('# solo comentario\n'), null, 'un documento vacío no tiene raíz');
assertEq(Cst.parseValue('\ufeffa: 1\n'), { a: 1 }, 'acepta BOM');

console.log('\n--- round-trip de los YAML reales ---');
const root = path.join(dir, '..', '..');
const files = yamlFiles(path.join(root, 'src'));
let exact = 0;
let added = 0;
let broken = [];
for (const file of files) {
  const text = fs.readFileSync(file, 'utf8');
  try {
    const value = Cst.parseValue(text);
    const out = Cst.apply(text, value, value, { ensureConfigVersion: true });
    if (out === text) {
      exact++;
    } else if (file.endsWith('plugin.yml') && !text.includes('config-version')) {
      added++;
    } else {
      broken.push(path.relative(root, file));
    }
  } catch (error) {
    broken.push(path.relative(root, file) + ': ' + error.message);
  }
}
assertEq(broken, [], 'todos los YAML reales se parsean y reconstruyen sin cambios');
assert(exact >= 34, 'round-trip exacto en al menos 34 archivos (' + exact + '/' + files.length + ')');
assertEq(added, 1, 'solo plugin.yml recibe config-version por diseño');

console.log('\n--- comentarios, orden y comillas ---');
const commented = [
  '# cabecera del archivo',
  "name: 'chat.global' # nombre visible",
  'format: "<gray>%content%</gray>"',
  'flags:',
  '  - one # primero',
  '  - two',
  'config-version: 1.0',
  '',
].join('\n');
assertEq(
  apply(commented, value => value),
  commented,
  'una edición vacía conserva el archivo byte a byte'
);
assertEq(
  apply(commented, value => {
    value.name = 'otro';
    return value;
  }),
  commented.replace("name: 'chat.global'", "name: 'otro'"),
  'cambiar un escalar conserva comentario inline, comillas y orden'
);
assertEq(
  apply(commented, value => {
    value.extra = true;
    return value;
  }),
  commented.replace('config-version: 1.0', 'extra: true\nconfig-version: 1.0'),
  'una clave nueva se inserta antes de config-version'
);
assertEq(
  apply(commented, value => {
    delete value.format;
    return value;
  }),
  commented.replace('format: "<gray>%content%</gray>"\n', ''),
  'eliminar una clave conserva el resto del archivo'
);

console.log('\n--- colecciones de bloque y flujo ---');
const seq = ['items:', '  - uno', '  - dos # conservado', '  - tres', 'config-version: 1.0', ''].join('\n');
assertEq(
  apply(seq, value => {
    value.items.push('cuatro');
    return value;
  }),
  seq.replace('  - tres', '  - tres\n  - cuatro'),
  'añadir un elemento a una secuencia conserva el formato'
);
assertEq(
  apply(seq, value => {
    value.items.splice(1, 1);
    return value;
  }),
  ['items:', '  - uno', '  - tres', 'config-version: 1.0', ''].join('\n'),
  'eliminar un elemento conserva el comentario de los restantes'
);
const flow = ['items: [uno, dos, tres]', 'map: { a: 1, b: hola }', 'config-version: 1.0', ''].join('\n');
assertEq(
  apply(flow, value => {
    value.items.push('cuatro');
    value.map.c = true;
    return value;
  }),
  ['items: [uno, dos, tres, cuatro]', 'map: { a: 1, b: hola, c: true }', 'config-version: 1.0', ''].join('\n'),
  'editar flujo conserva el estilo en línea'
);

console.log('\n--- mapas anidados y cambios de tipo ---');
const nested = ['outer:', '  inner:', '    a: 1', '    b: dos', '  list:', '    - x', 'config-version: 1.0', ''].join(
  '\n'
);
assertEq(
  apply(nested, value => {
    value.outer.inner.a = 2;
    value.outer.list.push('y');
    return value;
  }),
  nested.replace('    a: 1', '    a: 2').replace('    - x', '    - x\n    - y'),
  'editar un mapa anidado conserva sangría y orden'
);
assertEq(
  apply(nested, value => {
    value.outer = 'plano';
    return value;
  }),
  ['outer: plano', 'config-version: 1.0', ''].join('\n'),
  'convertir una colección sin comentarios en escalar funciona'
);
throws(
  () =>
    apply(['outer:', '  # interno', '  a: 1', 'config-version: 1.0', ''].join('\n'), value => {
      value.outer = 'plano';
      return value;
    }),
  /perdería formato/,
  'convertir una colección con comentarios internos en escalar falla'
);
assertEq(
  apply('outer: plano\nconfig-version: 1.0\n', value => {
    value.outer = { a: 1, b: [2, 3] };
    return value;
  }),
  ['outer:', '  a: 1', '  b:', '    - 2', '    - 3', 'config-version: 1.0', ''].join('\n'),
  'convertir un escalar en colección produce bloque válido'
);

console.log('\n--- config-version y archivos nuevos ---');
assertEq(
  Cst.apply('a: 1\n', { a: 1 }, { a: 2 }, { ensureConfigVersion: true }),
  'a: 2\nconfig-version: 1.0\n',
  'un archivo sin config-version la recibe al editarlo'
);
assertEq(
  Cst.apply('a: 1\nconfig-version: 1.0\n', { a: 1 }, { a: 2 }, { ensureConfigVersion: true }),
  'a: 2\nconfig-version: 1.0\n',
  'config-version existente no se duplica'
);
assertEq(Cst.create({ a: 1 }), 'a: 1\nconfig-version: 1.0\n', 'create serializa y añade config-version');
assertEq(Cst.create({}), 'config-version: 1.0\n', 'create de un mapa vacío emite solo config-version');
assertEq(Cst.create({ a: 1 }, { ensureConfigVersion: false }), 'a: 1\n', 'create puede omitir config-version');

console.log('\n--- CRLF, BOM y fin de archivo ---');
const crlf = '# cabecera\r\na: 1\r\nitems:\r\n  - uno\r\n';
assertEq(
  apply(
    crlf,
    value => {
      value.a = 2;
      value.items.push('dos');
      return value;
    },
    { ensureConfigVersion: false }
  ),
  '# cabecera\r\na: 2\r\nitems:\r\n  - uno\r\n  - dos\r\n',
  'conserva CRLF al editar'
);
const bom = '\ufeffa: 1\n';
assertEq(
  apply(
    bom,
    value => {
      value.a = 2;
      return value;
    },
    { ensureConfigVersion: false }
  ),
  '\ufeffa: 2\n',
  'conserva el BOM'
);
assertEq(
  apply('a: 1', value => {
    value.b = 2;
    return value;
  }),
  'a: 1\nb: 2\nconfig-version: 1.0\n',
  'un archivo sin salto final recibe uno al añadir contenido'
);

console.log('\n--- errores explícitos ---');
throws(() => Cst.parseValue('a: |\n  hola\n'), /block scalars/, 'rechaza block scalars');
throws(() => Cst.parseValue('a: &x 1\n'), /anclas, alias y tags/, 'rechaza anclas');
throws(() => Cst.parseValue('a: *x\n'), /anclas, alias y tags/, 'rechaza alias');
throws(() => Cst.parseValue('a: !!str 1\n'), /anclas, alias y tags/, 'rechaza tags');
throws(() => Cst.parseValue('? [a, b]\n: 1\n'), /claves complejas/, 'rechaza claves complejas');
throws(() => Cst.parseValue('a:\n\tb: 1\n'), /tabulaciones/, 'rechaza tabs en la sangría');
throws(() => Cst.parseValue('a: 1\na: 2\n'), /clave duplicada/, 'rechaza claves duplicadas');
throws(() => Cst.parseValue('a: [1,\n  2]\n'), /flujo multilínea/, 'rechaza flujo multilínea');
throws(() => Cst.parseValue('a: [1, # x\n  2]\n'), /comentarios dentro de una colección de flujo/, 'rechaza comentarios en flujo');
throws(() => Cst.parseValue('a: 1\r\nb: 2\n'), /saltos de línea mixtos/, 'rechaza saltos mixtos');
throws(() => Cst.parseValue('a: 1\rb: 2\n'), /CR aislado/, 'rechaza CR aislado');
throws(() => Cst.parseValue('a: 1\n---\nb: 2\n'), /documentos múltiples/, 'rechaza documentos múltiples');
throws(() => Cst.parseValue('a: "x\n'), /comilla doble sin cerrar/, 'rechaza comillas sin cerrar');

console.log('\n--- canonical y formato de escalares ---');
assertEq(Cst.canonical({ b: 1, a: [2, 3] }), '{"a":[2,3],"b":1}', 'canonical ordena las claves');
assertEq(Cst.canonical(undefined), 'null', 'canonical distingue undefined');
assertEq(Cst.formatScalar('hola', 'plain'), 'hola', 'formatScalar deja un escalar plano');
assertEq(Cst.formatScalar('true', 'plain'), '"true"', 'formatScalar protege los booleanos como texto');
assertEq(Cst.formatScalar('a b', 'single'), "'a b'", 'formatScalar respeta comillas simples');
assertEq(Cst.formatScalar('a"b', 'double'), '"a\\"b"', 'formatScalar escapa comillas dobles');
assertEq(Cst.formatFlowValue({ a: 1, b: ['x'] }), '{ a: 1, b: [x] }', 'formatFlowValue serializa flujo');

console.log('\n' + passed + ' passed, ' + failed + ' failed\n');
if (failed > 0) {
  process.exitCode = 1;
}
