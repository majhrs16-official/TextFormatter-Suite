#!/usr/bin/env node
/* tests/unit/packageDb.test.cjs — unit tests for Suite.packageDb */

const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const dir = path.join(__dirname, '..', '..');
const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', {
  runScripts: 'outside-only',
  url: 'https://localhost/',
});
const { window } = dom;
window.eval(fs.readFileSync(path.join(dir, 'js', 'packageDb.js'), 'utf8'));
const Db = window.Suite.packageDb;

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

function throws(fn, pattern, message) {
  try {
    fn();
    assert(false, message + ' — no lanzó');
  } catch (error) {
    assert(error.name === 'PackageDbError' && pattern.test(error.message), message + ' — ' + error.message);
  }
}

const sha = 'abcdef0123456789'.repeat(4);

function entry(over) {
  return Object.assign(Db.create(), over || {});
}

function textOf(entryValue) {
  return Db.serialize([entryValue]);
}

console.log('\n=== Suite.packageDb Unit Tests ===\n');

console.log('--- parse ---');
const single = [
  'Name: iflow',
  'Version: 1.0.0',
  'Channel: stable',
  'Type: PACKAGE',
  'Dependencies: ',
  'Arch: none',
  'Platform: common',
  'URL: ',
  'OS: none',
  'SHA256: ' + sha,
  'Size: 48213',
  'Files: ',
  '  iflow.jar\t48213\t' + sha + '\tiflow',
  '',
].join('\n');
const parsed = Db.parse(single);
assertEq(parsed.length, 1, 'parsea una entrada');
assertEq(parsed[0].name, 'iflow', 'lee Name');
assertEq(parsed[0].version, '1.0.0', 'lee Version');
assertEq(parsed[0].channel, 'stable', 'lee Channel');
assertEq(parsed[0].type, 'PACKAGE', 'lee Type');
assertEq(parsed[0].dependencies, [], 'lee Dependencies vacío');
assertEq(parsed[0].arch, 'none', 'lee Arch');
assertEq(parsed[0].platform, 'common', 'lee Platform');
assertEq(parsed[0].url, '', 'lee URL vacío');
assertEq(parsed[0].os, 'none', 'lee OS');
assertEq(parsed[0].sha256, sha, 'lee SHA256');
assertEq(parsed[0].size, 48213, 'lee Size');
assertEq(parsed[0].files, [{ path: 'iflow.jar', size: 48213, sha256: sha, owner: 'iflow' }], 'lee Files');

const multiple = [
  single,
  'Name: textformatter',
  'Version: 2.0.0',
  'Channel: beta',
  'Type: PACKAGE',
  'Dependencies: iflow@>=1.0.0, iflow-16',
  'Exclude: old-textformatter',
  'Provide: textformatter-core',
  'Arch: none',
  'Platform: common',
  'URL: https://example.com/textformatter.zip',
  'OS: none',
  'SHA256: ',
  'Size: 0',
  'Installed-Time: 2024-01-01T00:00:00Z',
  'ParentPackage: suite',
  'SourceArtifact: ' + sha,
  'Files: ',
  '',
].join('\n');
const entries = Db.parse(multiple);
assertEq(entries.length, 2, 'parsea varias entradas');
assertEq(entries[1].dependencies, ['iflow@>=1.0.0', 'iflow-16'], 'normaliza dependencias');
assertEq(entries[1].exclude, ['old-textformatter'], 'lee Exclude');
assertEq(entries[1].provide, ['textformatter-core'], 'lee Provide');
assertEq(entries[1].extra, { 'Installed-Time': '2024-01-01T00:00:00Z' }, 'conserva campos desconocidos');
assertEq(entries[1].parentPackage, 'suite', 'lee ParentPackage');
assertEq(entries[1].sourceArtifact, sha, 'lee SourceArtifact');
assertEq(Db.parse('').length, 0, 'un archivo vacío no tiene entradas');
assertEq(Db.parse(single.replace(/\n/g, '\r\n'))[0].name, 'iflow', 'acepta CRLF');
assertEq(Db.parse(single.replace(/\n$/, ''))[0].files.length, 1, 'acepta un archivo sin salto final');

console.log('\n--- serialize ---');
assertEq(
  Db.serialize(entries),
  [
    'Name: iflow',
    'Version: 1.0.0',
    'Channel: stable',
    'Type: PACKAGE',
    'Dependencies: ',
    'Arch: none',
    'Platform: common',
    'URL: ',
    'OS: none',
    'SHA256: ' + sha,
    'Size: 48213',
    'Files: ',
    '  iflow.jar\t48213\t' + sha + '\tiflow',
    '',
    'Name: textformatter',
    'Version: 2.0.0',
    'Channel: beta',
    'Type: PACKAGE',
    'Dependencies: iflow@>=1.0.0,iflow-16',
    'Exclude: old-textformatter',
    'Provide: textformatter-core',
    'Arch: none',
    'Platform: common',
    'URL: https://example.com/textformatter.zip',
    'OS: none',
    'SHA256: ',
    'Size: 0',
    'SourceArtifact: ' + sha,
    'ParentPackage: suite',
    'Installed-Time: 2024-01-01T00:00:00Z',
    'Files: ',
    '',
  ].join('\n'),
  'serializa el formato canónico del manager'
);
assertEq(Db.serialize([]), '', 'serializa una base vacía');
assertEq(Db.serialize([entry({ name: 'b' }), entry({ name: 'a' })]).indexOf('Name: a') < Db.serialize([entry({ name: 'b' }), entry({ name: 'a' })]).indexOf('Name: b'), true, 'ordena por nombre');
const stable = Db.serialize(Db.parse(multiple));
assertEq(Db.serialize(Db.parse(stable)), stable, 'la serialización es idempotente');
assertEq(Db.canonical(entries), Db.serialize(entries), 'canonical usa el formato serializado');
assertEq(Db.parse(stable)[1].extra['Installed-Time'], '2024-01-01T00:00:00Z', 'el round-trip conserva extra');

console.log('\n--- create, listas y archivos ---');
const created = Db.create();
assertEq(created.name, 'new-package', 'create usa un nombre por defecto');
assertEq(created.type, 'PACKAGE', 'create usa PACKAGE');
assertEq(created.files, [], 'create no inventa archivos');
assertEq(Db.normalizeDependency(' iflow '), 'iflow', 'normaliza una dependencia simple');
assertEq(Db.normalizeDependency('iflow@*'), 'iflow', 'omite la restricción *');
assertEq(Db.normalizeDependency('iflow >=1.0.0'), 'iflow@>=1.0.0', 'acepta la forma con espacio');
assertEq(Db.parseDependencies('a, b@>=1, ,c'), ['a', 'b@>=1', 'c'], 'separa y deduplica dependencias');
assertEq(Db.parseNameList('a, b  a\nc'), ['a', 'b', 'c'], 'separa nombres y deduplica');
assertEq(
  Db.parseFiles('a.jar\t1\t' + sha + '\ta\n\nb.jar\t2\t' + sha + '\t'),
  [
    { path: 'a.jar', size: 1, sha256: sha, owner: 'a' },
    { path: 'b.jar', size: 2, sha256: sha, owner: '' },
  ],
  'parseFiles ignora líneas vacías'
);
assertEq(
  Db.formatFiles([
    { path: 'a.jar', size: 1, sha256: sha, owner: 'a' },
    { path: 'b.jar', size: 2, sha256: sha, owner: '' },
  ]),
  'a.jar\t1\t' + sha + '\ta\nb.jar\t2\t' + sha + '\t',
  'formatFiles produce líneas con tabuladores'
);
assertEq(Db.idOf(entry({ name: 'x', version: '1.0.0', channel: 'stable' })), 'x_1.0.0_stable', 'idOf usa name_version_channel');

console.log('\n--- errores explícitos ---');
throws(() => Db.parse('Name: x\nVersion: 1\nChannel: c\nType: PACKAGE\n# comentario\nFiles: \n'), /línea no reconocida/, 'rechaza comentarios que el manager ignoraría');
throws(() => Db.parse('Name: x\nVersion: 1\nChannel: c\nType: PACKAGE\ntexto suelto\nFiles: \n'), /línea no reconocida/, 'rechaza texto suelto');
throws(() => Db.parse('Version: 1\nChannel: c\nType: PACKAGE\nFiles: \n'), /falta el campo obligatorio Name/, 'exige Name');
throws(() => Db.parse('Name: x\nVersion: 1\nChannel: c\nType: INVALID\nFiles: \n'), /tipo de paquete inválido/, 'rechaza Type inválido');
throws(() => Db.parse('Name: x\nVersion: 1\nChannel: c\nType: PACKAGE\nSize: nope\nFiles: \n'), /tamaño del paquete no es un entero/, 'rechaza Size inválido');
throws(() => Db.parse('Name: x\nVersion: 1\nChannel: c\nType: PACKAGE\nSize: -1\nFiles: \n'), /tamaño del paquete no es un entero/, 'rechaza Size negativo');
throws(() => Db.parse('Name: x\nVersion: 1\nChannel: c\nType: PACKAGE\nSHA256: bad\nFiles: \n'), /sha256 inválido/, 'rechaza SHA256 inválido');
throws(() => Db.parse('Name: x\nVersion: 1\nChannel: c\nType: PACKAGE\nSize: 0\nFiles: \n  a\t1\tbad\td\n'), /sha256 inválido en el archivo/, 'rechaza sha256 de archivo inválido');
throws(() => Db.parse('Name: x\nVersion: 1\nChannel: c\nType: PACKAGE\nSize: 0\nFiles: \n  a\t-1\t' + sha + '\td\n'), /tamaño del archivo no es un entero/, 'rechaza tamaño de archivo negativo');
throws(
  () => Db.parse('Name: x\nVersion: 1\nChannel: c\nType: PACKAGE\nSize: 0\nFiles: \n  a\t1\t' + sha + '\td\n  a\t1\t' + sha + '\td\n'),
  /ruta de archivo duplicada/,
  'rechaza archivos duplicados'
);
throws(() => Db.parse('Name: x\nName: y\nVersion: 1\nChannel: c\nType: PACKAGE\nFiles: \n'), /campo duplicado Name/, 'rechaza campos duplicados');
throws(() => Db.parse('Name: x\nVersion: 1\nChannel: c\nType: PACKAGE\nFiles: \nFiles: \n'), /sección Files duplicada/, 'rechaza Files duplicado');
throws(() => Db.parse('Name: x\nVersion: 1\nChannel: c\nType: PACKAGE\nSize: 0\nFiles: \n  a\t1\n'), /línea de archivo inválida/, 'rechaza líneas de archivo incompletas');
throws(() => Db.parse('Name: x\u0000\nVersion: 1\nChannel: c\nType: PACKAGE\nFiles: \n'), /NUL/, 'rechaza NUL');
throws(() => Db.parse('Name: x\rVersion: 1\nChannel: c\nType: PACKAGE\nFiles: \n'), /CR aislado/, 'rechaza CR aislado');
throws(() => Db.parse('a: 1\nb: 2\n'), /falta el campo obligatorio Name|no reconocida/, 'no acepta YAML');
throws(
  () =>
    Db.parse(
      'Name: x\nVersion: 1\nChannel: c\nType: PACKAGE\nFiles: \n\nName: x\nVersion: 1\nChannel: c\nType: PACKAGE\nFiles: \n'
    ),
  /identificador de paquete duplicado/,
  'rechaza identificadores duplicados'
);
throws(() => Db.serialize([entry({ size: -1 })]), /tamaño debe ser un entero no negativo/, 'serialize valida el tamaño');
throws(() => Db.serialize([entry({ type: 'OTHER' })]), /tipo de paquete inválido/, 'serialize valida el tipo');
throws(() => Db.serialize([entry({ exclude: ['a b'] })]), /exclude contiene/, 'serialize valida Exclude');
throws(() => Db.serialize([entry({ extra: { Name: 'x' } })]), /campo extra Name es un campo conocido/, 'serialize rechaza extras que pisan campos conocidos');
throws(() => Db.serialize([entry({ dependencies: ['a,b'] })]), /dependencia inválida/, 'serialize valida dependencias');

console.log('\n--- extra multilínea ---');
const multiline = entry({ name: 'multi', extra: { Nota: 'primera\nsegunda' } });
const multilineText = textOf(multiline);
assert(multilineText.includes('Nota: primera\n  segunda\n'), 'serializa un extra multilínea con continuación');
assertEq(Db.parse(multilineText)[0].extra.Nota, 'primera\nsegunda', 'el extra multilínea vuelve intacto');

console.log('\n' + passed + ' passed, ' + failed + ' failed\n');
if (failed > 0) {
  process.exitCode = 1;
}
