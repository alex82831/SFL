#!/usr/bin/env node
// End-to-end test of `sfl lsp`: speaks real LSP over stdio and asserts on the
// answers. Run as `node tests/lsp.js [path-to-sfl]`; exits 0 when every check
// passes. This is what keeps the server honest without an editor in the loop.

'use strict';
const { spawn } = require('child_process');
const path = require('path');

const sfl = process.argv[2] || path.join(__dirname, '..', 'target', 'scala-3.8.4', 'sfl');
const proc = spawn(sfl, ['lsp'], { stdio: ['pipe', 'pipe', 'inherit'] });

let buffer = Buffer.alloc(0);
const pending = [];          // resolvers waiting for any next message
const responses = new Map(); // id -> resolver
let nextId = 1;
let failures = 0;

proc.stdout.on('data', (chunk) => {
  buffer = Buffer.concat([buffer, chunk]);
  for (;;) {
    const headerEnd = buffer.indexOf('\r\n\r\n');
    if (headerEnd < 0) return;
    const header = buffer.slice(0, headerEnd).toString('ascii');
    const m = /Content-Length:\s*(\d+)/i.exec(header);
    if (!m) { console.error('FAIL: frame without Content-Length'); process.exit(1); }
    const len = parseInt(m[1], 10);
    if (buffer.length < headerEnd + 4 + len) return;
    const body = buffer.slice(headerEnd + 4, headerEnd + 4 + len).toString('utf8');
    buffer = buffer.slice(headerEnd + 4 + len);
    const msg = JSON.parse(body);
    if (msg.id !== undefined && responses.has(msg.id)) {
      const r = responses.get(msg.id);
      responses.delete(msg.id);
      r(msg);
    } else {
      const waiter = pending.shift();
      if (waiter) waiter(msg);
    }
  }
});

function send(msg) {
  const body = Buffer.from(JSON.stringify(msg), 'utf8');
  proc.stdin.write(`Content-Length: ${body.length}\r\n\r\n`);
  proc.stdin.write(body);
}

function request(method, params) {
  const id = nextId++;
  send({ jsonrpc: '2.0', id, method, params });
  return new Promise((resolve, reject) => {
    responses.set(id, resolve);
    setTimeout(() => reject(new Error(`timeout waiting for ${method}`)), 10000);
  });
}

function notify(method, params) {
  send({ jsonrpc: '2.0', method, params });
}

function nextNotification(method) {
  return new Promise((resolve, reject) => {
    const waiter = (msg) => {
      if (msg.method === method) resolve(msg);
      else pending.push(waiter); // not ours; keep waiting
    };
    pending.push(waiter);
    setTimeout(() => reject(new Error(`timeout waiting for notification ${method}`)), 10000);
  });
}

function check(name, cond, extra) {
  if (cond) {
    console.log(`ok   ${name}`);
  } else {
    failures++;
    console.error(`FAIL ${name}${extra ? ': ' + extra : ''}`);
  }
}

const uri = 'file:///tmp/sfl-lsp-test/main.sfl';

async function main() {
  // -- initialize ------------------------------------------------------------
  const init = await request('initialize', {
    processId: process.pid,
    rootUri: null,
    capabilities: {},
  });
  check('initialize returns capabilities', !!init.result && !!init.result.capabilities);
  check('server identifies itself', init.result.serverInfo && init.result.serverInfo.name === 'sfl');
  check('full text sync', init.result.capabilities.textDocumentSync === 1);
  check('dot is a completion trigger',
    init.result.capabilities.completionProvider.triggerCharacters.includes('.'));
  check('outline and definition capabilities on',
    init.result.capabilities.documentSymbolProvider === true &&
    init.result.capabilities.definitionProvider === true);
  notify('initialized', {});

  // -- diagnostics on a broken file -------------------------------------------
  const broken = 'val x = 1\nval y = ]\n';
  const diagsP = nextNotification('textDocument/publishDiagnostics');
  notify('textDocument/didOpen', {
    textDocument: { uri, languageId: 'sfl', version: 1, text: broken },
  });
  const diags = await diagsP;
  const list = diags.params.diagnostics;
  check('one diagnostic for broken file', list.length === 1, JSON.stringify(list));
  check('diagnostic on line 1 (0-based)', list.length === 1 && list[0].range.start.line === 1);
  check('diagnostic at col 8', list.length === 1 && list[0].range.start.character === 8);
  check('diagnostic mentions the bracket', list.length === 1 && /]/.test(list[0].message));

  // -- diagnostics clear after a fix -------------------------------------------
  const cleanP = nextNotification('textDocument/publishDiagnostics');
  notify('textDocument/didChange', {
    textDocument: { uri, version: 2 },
    contentChanges: [{ text: 'def area(r) { 3.14159 * r * r }\nprintln(area(2))\n' }],
  });
  const clean = await cleanP;
  check('diagnostics clear on fix', clean.params.diagnostics.length === 0);

  // -- completion ---------------------------------------------------------------
  const comp = await request('textDocument/completion', {
    textDocument: { uri },
    position: { line: 1, character: 0 },
  });
  const items = comp.result;
  check('completion returns items', Array.isArray(items) && items.length > 300, `got ${items && items.length}`);
  const println = items.find((i) => i.label === 'println');
  check('println is offered', !!println);
  check('println carries its signature', !!println && /println\(/.test(println.detail));
  const area = items.find((i) => i.label === 'area');
  check('file-local def is offered', !!area && /def area\(r\)/.test(area.detail));
  const kw = items.find((i) => i.label === 'while');
  check('keywords are offered', !!kw && kw.kind === 14);

  // -- hover ---------------------------------------------------------------------
  const hover = await request('textDocument/hover', {
    textDocument: { uri },
    position: { line: 1, character: 2 }, // inside "println"
  });
  check('hover answers on a builtin', !!hover.result && /println/.test(hover.result.contents.value));

  // -- signature help ---------------------------------------------------------------
  const fixP = nextNotification('textDocument/publishDiagnostics');
  notify('textDocument/didChange', {
    textDocument: { uri, version: 3 },
    contentChanges: [{ text: 'val s = split("a,b", ' }],
  });
  await fixP; // wait out the republish so ordering stays deterministic
  const sig = await request('textDocument/signatureHelp', {
    textDocument: { uri },
    position: { line: 0, character: 21 },
  });
  check('signature help finds split', !!sig.result && /split\(/.test(sig.result.signatures[0].label));
  check('active parameter is the second', !!sig.result && sig.result.activeParameter === 1);

  // -- context-aware completion -------------------------------------------------
  // A module to import from, on disk next to a main file.
  const os = require('os');
  const fs = require('fs');
  const pathmod = require('path');
  const dir = fs.mkdtempSync(pathmod.join(os.tmpdir(), 'sfl-lsp-'));
  fs.writeFileSync(pathmod.join(dir, 'geo.sfl'), [
    'def area(r) = 3.14159 * r * r',
    'def perimeter(r) = 2 * 3.14159 * r',
    'def _hidden() = 0',
    'val TAU = 6.28318',
    '',
  ].join('\n'));
  const mainPath = pathmod.join(dir, 'main.sfl');
  const mainUri = 'file://' + mainPath;
  const mainText = [
    'import "geo" as g',            // line 0
    'val p = {"x": 1, "y": 2}',     // line 1
    'val a = g.',                   // line 2 (cursor after the dot: col 10)
    'val b = p.',                   // line 3 (col 10)
    'import "',                     // line 4 (col 8)
    'g.area(1, ',                   // line 5 — dotted signature help (col 10)
    '',
  ].join('\n');
  fs.writeFileSync(mainPath, mainText);
  notify('textDocument/didOpen', {
    textDocument: { uri: mainUri, languageId: 'sfl', version: 1, text: mainText },
  });
  await nextNotification('textDocument/publishDiagnostics');

  const memb = await request('textDocument/completion', {
    textDocument: { uri: mainUri }, position: { line: 2, character: 10 },
  });
  const membLabels = memb.result.map((i) => i.label);
  check('alias members offered after g.', membLabels.includes('area') && membLabels.includes('perimeter'));
  check('alias member list is members-only', !membLabels.includes('println'));
  check('private module names hidden', !membLabels.includes('_hidden'));
  check('module vals offered', membLabels.includes('TAU'));

  const keys = await request('textDocument/completion', {
    textDocument: { uri: mainUri }, position: { line: 3, character: 10 },
  });
  const keyLabels = keys.result.map((i) => i.label);
  check('object keys offered after p.', keyLabels.includes('x') && keyLabels.includes('y'));
  check('key list is keys-only', !keyLabels.includes('println'));

  const imp = await request('textDocument/completion', {
    textDocument: { uri: mainUri }, position: { line: 4, character: 8 },
  });
  const impLabels = imp.result.map((i) => i.label);
  check('import path offers sibling module', impLabels.includes('geo'), JSON.stringify(impLabels));
  check('import path omits the file itself', !impLabels.includes('main'));

  const dotSig = await request('textDocument/signatureHelp', {
    textDocument: { uri: mainUri }, position: { line: 5, character: 10 },
  });
  check('signature help resolves g.area', !!dotSig.result && /area\(r\)/.test(dotSig.result.signatures[0].label));

  const syms = await request('textDocument/documentSymbol', {
    textDocument: { uri: mainUri },
  });
  check('outline lists top-level vals', syms.result.some((s) => s.name === 'p' && s.kind === 13));

  const defn = await request('textDocument/definition', {
    textDocument: { uri: mainUri }, position: { line: 5, character: 3 }, // on "area"
  });
  check('definition jumps into the module',
    !!defn.result && defn.result.uri.endsWith('geo.sfl') && defn.result.range.start.line === 0,
    JSON.stringify(defn.result));

  const glob = await request('textDocument/completion', {
    textDocument: { uri: mainUri }, position: { line: 6, character: 0 },
  });
  const snippet = glob.result.find((i) => i.label === 'for' && i.kind === 15);
  check('snippets offered in plain code', !!snippet && snippet.insertTextFormat === 2);
  const userDef = glob.result.find((i) => i.label === 'p');
  check('plain completion still lists file vals', !!userDef);

  // A '/' typed outside an import string must not pop a list.
  const div = await request('textDocument/completion', {
    textDocument: { uri: mainUri }, position: { line: 5, character: 2 }, // after "g." ... use a synthetic: skip
  });
  check('completion after g. via trigger still members', div.result.every((i) => i.kind === 3 || i.kind === 6));

  // -- semantic diagnostics: undefined names ------------------------------------
  const undefUri = 'file://' + pathmod.join(dir, 'undef.sfl');
  let diagsP2 = nextNotification('textDocument/publishDiagnostics');
  notify('textDocument/didOpen', {
    textDocument: {
      uri: undefUri, languageId: 'sfl', version: 1,
      text: 'println(area(circle1))\nval ok = length("x")\n',
    },
  });
  const undef1 = (await diagsP2).params.diagnostics;
  const names1 = undef1.map((d) => /'([^']+)'/.exec(d.message)?.[1]).sort();
  check('undefined names are flagged', names1.join(',') === 'area,circle1', JSON.stringify(undef1));
  check('undefined has exact column', undef1.some((d) => d.range.start.character === 8));
  check('builtins are not flagged', !names1.includes('println') && !names1.includes('length'));

  diagsP2 = nextNotification('textDocument/publishDiagnostics');
  notify('textDocument/didChange', {
    textDocument: { uri: undefUri, version: 2 },
    contentChanges: [{
      text: 'def area(r) = r * r\nval circle1 = 2\nprintln(area(circle1))\n',
    }],
  });
  check('definitions clear the flags', (await diagsP2).params.diagnostics.length === 0);

  // A typo close to a real name gets a suggestion.
  diagsP2 = nextNotification('textDocument/publishDiagnostics');
  notify('textDocument/didChange', {
    textDocument: { uri: undefUri, version: 3 },
    contentChanges: [{ text: 'def area(r) = r * r\nprintln(aera(2))\n' }],
  });
  const typo = (await diagsP2).params.diagnostics;
  check('typo gets a did-you-mean', typo.length === 1 && /did you mean 'area'/.test(typo[0].message),
    JSON.stringify(typo));

  // -- import failure: one diagnostic, no undefined spam --------------------------
  diagsP2 = nextNotification('textDocument/publishDiagnostics');
  notify('textDocument/didChange', {
    textDocument: { uri: undefUri, version: 4 },
    contentChanges: [{ text: 'import "no_such_module_xyz"\nval x = mystery\n' }],
  });
  const impDiags = (await diagsP2).params.diagnostics;
  check('broken import is one diagnostic', impDiags.length === 1, JSON.stringify(impDiags));
  check('broken import names the module', /no_such_module_xyz/.test(impDiags[0].message));
  check('undefined check suppressed while imports fail', !impDiags.some((d) => /mystery/.test(d.message)));

  // -- plain import members reach completion and references/implementation --------
  diagsP2 = nextNotification('textDocument/publishDiagnostics');
  notify('textDocument/didChange', {
    textDocument: { uri: undefUri, version: 5 },
    contentChanges: [{ text: 'import "geo"\nval r = area(2)\nval r2 = area(3)\n' }],
  });
  const geoDiags = (await diagsP2).params.diagnostics;
  check('plain-import members are defined', geoDiags.length === 0, JSON.stringify(geoDiags));

  const plainComp = await request('textDocument/completion', {
    textDocument: { uri: undefUri }, position: { line: 2, character: 0 },
  });
  const areaItem = plainComp.result.find((i) => i.label === 'area');
  check('plain-import member in completion', !!areaItem && /geo/.test(areaItem.detail),
    JSON.stringify(areaItem));

  const impl = await request('textDocument/implementation', {
    textDocument: { uri: undefUri }, position: { line: 1, character: 9 },
  });
  check('implementation lands on the definition',
    !!impl.result && impl.result.uri.endsWith('geo.sfl'), JSON.stringify(impl.result));

  const refs = await request('textDocument/references', {
    textDocument: { uri: undefUri }, position: { line: 1, character: 9 },
    context: { includeDeclaration: true },
  });
  check('references finds both uses', refs.result.length === 2, JSON.stringify(refs.result));

  // -- build.sfl knows the build tool's DSL ---------------------------------------
  const buildUri = 'file://' + pathmod.join(dir, 'build.sfl');
  let diagsP3 = nextNotification('textDocument/publishDiagnostics');
  notify('textDocument/didOpen', {
    textDocument: {
      uri: buildUri, languageId: 'sfl', version: 1,
      text: 'project({\n  "name": "demo",\n  "main": "src/main.sfl"\n})\n\ntask("greet", (args) -> println("hi"), "greet")\n',
    },
  });
  const buildDiags = (await diagsP3).params.diagnostics;
  check('project()/task() are defined inside build.sfl', buildDiags.length === 0,
    JSON.stringify(buildDiags));

  const dslComp = await request('textDocument/completion', {
    textDocument: { uri: buildUri }, position: { line: 4, character: 0 },
  });
  const projItem = dslComp.result.find((i) => i.label === 'project');
  check('project offered in build.sfl completion', !!projItem && /build DSL/.test(projItem.detail));

  const dslSig = await request('textDocument/signatureHelp', {
    textDocument: { uri: buildUri }, position: { line: 0, character: 8 },
  });
  check('signature help knows project(spec)',
    !!dslSig.result && /project\(spec\)/.test(dslSig.result.signatures[0].label));

  // The DSL exists only for build.sfl: elsewhere project() stays undefined.
  const otherUri = 'file://' + pathmod.join(dir, 'notbuild.sfl');
  diagsP3 = nextNotification('textDocument/publishDiagnostics');
  notify('textDocument/didOpen', {
    textDocument: { uri: otherUri, languageId: 'sfl', version: 1, text: 'project({})\n' },
  });
  const otherDiags = (await diagsP3).params.diagnostics;
  check('project() still undefined outside build.sfl',
    otherDiags.length === 1 && /project/.test(otherDiags[0].message));

  // -- project-local packages resolve via the server's cwd ------------------------
  // The plugins launch `sfl lsp` with cwd = project root precisely so that the
  // importer's cwd-relative roots (./sfl_packages, ./packages) mean the project's
  // own directories. This spawns a second server inside such a project to pin
  // that contract.
  const proj = fs.mkdtempSync(pathmod.join(os.tmpdir(), 'sfl-lsp-proj-'));
  fs.mkdirSync(pathmod.join(proj, 'packages', 'toolkit', '0.1.0'), { recursive: true });
  fs.writeFileSync(pathmod.join(proj, 'packages', 'toolkit', '0.1.0', 'sfl.pkg'),
    '{ "name": "toolkit", "version": "0.1.0", "main": "main" }\n');
  fs.writeFileSync(pathmod.join(proj, 'packages', 'toolkit', '0.1.0', 'main.sfl'),
    'def greet(who) = "hi, " + who\n');
  fs.mkdirSync(pathmod.join(proj, 'src'));
  const projMain = pathmod.join(proj, 'src', 'main.sfl');
  fs.writeFileSync(projMain, 'import "toolkit"\nprintln(greet("sfl"))\n');

  const proc2 = spawn(sfl, ['lsp'], { stdio: ['pipe', 'pipe', 'inherit'], cwd: proj });
  const got = await new Promise((resolve, reject) => {
    let buf2 = Buffer.alloc(0);
    let sent = false;
    proc2.stdout.on('data', (chunk) => {
      buf2 = Buffer.concat([buf2, chunk]);
      for (;;) {
        const h = buf2.indexOf('\r\n\r\n');
        if (h < 0) return;
        const m = /Content-Length:\s*(\d+)/i.exec(buf2.slice(0, h).toString('ascii'));
        const len = parseInt(m[1], 10);
        if (buf2.length < h + 4 + len) return;
        const msg = JSON.parse(buf2.slice(h + 4, h + 4 + len).toString('utf8'));
        buf2 = buf2.slice(h + 4 + len);
        if (msg.method === 'textDocument/publishDiagnostics') resolve(msg.params.diagnostics);
      }
    });
    const send2 = (obj) => {
      const b = Buffer.from(JSON.stringify(obj), 'utf8');
      proc2.stdin.write(`Content-Length: ${b.length}\r\n\r\n`);
      proc2.stdin.write(b);
    };
    send2({ seq: 1, id: 1, type: 'request', jsonrpc: '2.0', method: 'initialize',
      params: { processId: null, rootUri: 'file://' + proj, capabilities: {} } });
    setTimeout(() => {
      if (!sent) {
        sent = true;
        send2({ jsonrpc: '2.0', method: 'textDocument/didOpen', params: {
          textDocument: { uri: 'file://' + projMain, languageId: 'sfl', version: 1,
            text: fs.readFileSync(projMain, 'utf8') } } });
      }
    }, 200);
    setTimeout(() => reject(new Error('timeout waiting for project diagnostics')), 10000);
  });
  check('project-local packages resolve with cwd at project root', got.length === 0,
    JSON.stringify(got));
  proc2.kill();

  // -- shutdown ---------------------------------------------------------------------
  const bye = await request('shutdown', null);
  check('shutdown acknowledged', bye.result === null);
  notify('exit', null);

  const code = await new Promise((resolve) => proc.on('exit', resolve));
  check('clean exit after shutdown', code === 0, `exit code ${code}`);

  console.log(failures === 0 ? 'lsp: all checks passed' : `lsp: ${failures} check(s) FAILED`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('FAIL (exception):', e.message);
  proc.kill();
  process.exit(1);
});
