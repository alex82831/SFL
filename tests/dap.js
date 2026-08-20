#!/usr/bin/env node
// End-to-end test of `sfl dap`: a real DAP session — breakpoints, stepping,
// variables, evaluate, output events, clean shutdown.
//
//   node tests/dap.js [path-to-sfl]            # stdio transport (VSCode's path)
//   node tests/dap.js [path-to-sfl] --socket   # socket transport (IntelliJ's path)
//
// The socket run also pins the ready-line contract the IntelliJ plugin's
// port-extraction pattern depends on.

'use strict';
const { spawn } = require('child_process');
const fs = require('fs');
const net = require('net');
const os = require('os');
const path = require('path');

const sfl = process.argv[2] || path.join(__dirname, '..', 'target', 'scala-3.8.4', 'sfl');
const socketMode = process.argv.includes('--socket');

// The debuggee: a function with a loop, so stepping and locals are visible.
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sfl-dap-'));
const program = path.join(dir, 'main.sfl');
fs.writeFileSync(program, [
  'def accumulate(n) {',     // 1
  '  var total = 0',         // 2
  '  for (i in range(1, n + 1)) {', // 3
  '    total += i',          // 4  <- breakpoint
  '  }',                     // 5
  '  total',                 // 6
  '}',                       // 7
  'val result = accumulate(4)',      // 8
  'println("result=" + result)',     // 9
  '',
].join('\n'));

const proc = spawn(sfl, socketMode ? ['dap', '--socket'] : ['dap'], {
  stdio: ['pipe', 'pipe', 'inherit'],
});

let buffer = Buffer.alloc(0);
let nextSeq = 1;
let failures = 0;
const respWaiters = new Map(); // request seq -> resolve
const eventQueue = [];
const eventWaiters = [];

// The channel the protocol flows over: the child's stdio, or a socket dialled
// to the port announced on stdout — exactly what the IntelliJ plugin does.
let channel = null;
const channelReady = socketMode
  ? new Promise((resolve, reject) => {
      let intro = '';
      const onIntro = (chunk) => {
        intro += chunk.toString('utf8');
        const m = /^DAP server listening on port (\d+)$/m.exec(intro);
        if (m) {
          proc.stdout.off('data', onIntro);
          const port = parseInt(m[1], 10);
          // LSP4IJ's tracker probes ports by connecting and closing; the
          // adapter must survive that and still serve the real client.
          const probe = net.connect(port, '127.0.0.1', () => probe.destroy());
          probe.on('error', () => {});
          setTimeout(() => {
            const sock = net.connect(port, '127.0.0.1', () => resolve(sock));
            sock.on('data', onData);
            sock.on('error', reject);
          }, 150);
        }
      };
      proc.stdout.on('data', onIntro);
      setTimeout(() => reject(new Error('timeout waiting for the ready line')), 10000);
    })
  : Promise.resolve(null);

function onData(chunk) {
  buffer = Buffer.concat([buffer, chunk]);
  for (;;) {
    const headerEnd = buffer.indexOf('\r\n\r\n');
    if (headerEnd < 0) return;
    const m = /Content-Length:\s*(\d+)/i.exec(buffer.slice(0, headerEnd).toString('ascii'));
    if (!m) { console.error('FAIL: frame without Content-Length'); process.exit(1); }
    const len = parseInt(m[1], 10);
    if (buffer.length < headerEnd + 4 + len) return;
    const msg = JSON.parse(buffer.slice(headerEnd + 4, headerEnd + 4 + len).toString('utf8'));
    buffer = buffer.slice(headerEnd + 4 + len);
    if (msg.type === 'response') {
      const w = respWaiters.get(msg.request_seq);
      if (w) { respWaiters.delete(msg.request_seq); w(msg); }
    } else if (msg.type === 'event') {
      const wi = eventWaiters.findIndex((w) => w.name === msg.event);
      if (wi >= 0) eventWaiters.splice(wi, 1)[0].resolve(msg);
      else eventQueue.push(msg);
    }
  }
}

if (!socketMode) {
  proc.stdout.on('data', onData);
}

function send(command, args) {
  const seq = nextSeq++;
  const body = Buffer.from(JSON.stringify({ seq, type: 'request', command, arguments: args }), 'utf8');
  const sink = channel ?? proc.stdin;
  sink.write(`Content-Length: ${body.length}\r\n\r\n`);
  sink.write(body);
  return new Promise((resolve, reject) => {
    respWaiters.set(seq, resolve);
    setTimeout(() => reject(new Error(`timeout waiting for response to ${command}`)), 10000);
  });
}

function nextEvent(name) {
  const i = eventQueue.findIndex((e) => e.event === name);
  if (i >= 0) return Promise.resolve(eventQueue.splice(i, 1)[0]);
  return new Promise((resolve, reject) => {
    eventWaiters.push({ name, resolve });
    setTimeout(() => reject(new Error(`timeout waiting for event ${name}`)), 10000);
  });
}

function check(name, cond, extra) {
  if (cond) console.log(`ok   ${name}`);
  else { failures++; console.error(`FAIL ${name}${extra ? ': ' + extra : ''}`); }
}

async function main() {
  channel = await channelReady;
  if (socketMode) {
    check('socket transport connected', channel !== null);
  }
  const init = await send('initialize', { adapterID: 'sfl', linesStartAt1: true });
  check('initialize succeeds', init.success === true);
  check('configurationDone supported', init.body.supportsConfigurationDoneRequest === true);
  await nextEvent('initialized');
  check('initialized event arrives', true);

  const bp = await send('setBreakpoints', {
    source: { path: program },
    breakpoints: [{ line: 4 }],
  });
  check('breakpoint verified', bp.success && bp.body.breakpoints[0].verified === true);

  const launch = await send('launch', { program });
  check('launch succeeds', launch.success === true);
  await send('configurationDone', {});

  // -- first hit -------------------------------------------------------------
  const stop1 = await nextEvent('stopped');
  check('stops at the breakpoint', stop1.body.reason === 'breakpoint');
  const tid = stop1.body.threadId;

  const threads = await send('threads', {});
  check('main thread listed', threads.body.threads.some((t) => t.id === tid && t.name === 'main'));

  const stack = await send('stackTrace', { threadId: tid });
  const frames = stack.body.stackFrames;
  check('top frame is accumulate at line 4',
    frames[0].name === 'accumulate' && frames[0].line === 4, JSON.stringify(frames[0]));
  check('bottom frame is the top level at line 8',
    frames[frames.length - 1].name === '(top level)' && frames[frames.length - 1].line === 8,
    JSON.stringify(frames[frames.length - 1]));

  const scopes = await send('scopes', { frameId: frames[0].id });
  const locals = scopes.body.scopes.find((s) => s.name === 'Locals');
  check('locals scope offered', !!locals);

  const vars1 = await send('variables', { variablesReference: locals.variablesReference });
  const byName1 = Object.fromEntries(vars1.body.variables.map((v) => [v.name, v]));
  check('n is 4', byName1.n && byName1.n.value === '4', JSON.stringify(vars1.body.variables));
  check('total is 0 on first hit', byName1.total && byName1.total.value === '0');
  check('loop variable i is 1', byName1.i && byName1.i.value === '1');

  // -- second hit: state advanced ---------------------------------------------
  const cont = await send('continue', { threadId: tid });
  check('continue succeeds', cont.success === true);
  const stop2 = await nextEvent('stopped');
  check('second breakpoint hit', stop2.body.reason === 'breakpoint');
  const stack2 = await send('stackTrace', { threadId: tid });
  const scopes2 = await send('scopes', { frameId: stack2.body.stackFrames[0].id });
  const locals2 = scopes2.body.scopes.find((s) => s.name === 'Locals');
  const vars2 = await send('variables', { variablesReference: locals2.variablesReference });
  const byName2 = Object.fromEntries(vars2.body.variables.map((v) => [v.name, v]));
  check('total advanced to 1', byName2.total && byName2.total.value === '1');
  check('i advanced to 2', byName2.i && byName2.i.value === '2');

  // -- stepping ----------------------------------------------------------------
  // Clear the breakpoint first: a step that lands on a still-set breakpoint
  // reports 'breakpoint', which is correct but not what this check is about.
  await send('setBreakpoints', { source: { path: program }, breakpoints: [] });
  await send('next', { threadId: tid });
  const stop3 = await nextEvent('stopped');
  check('next stops with reason step', stop3.body.reason === 'step');

  // -- evaluate ----------------------------------------------------------------
  const st3 = await send('stackTrace', { threadId: tid });
  const ev1 = await send('evaluate', { expression: 'total', frameId: st3.body.stackFrames[0].id });
  check('evaluate a local by name', ev1.success && /^\d+$/.test(ev1.body.result), JSON.stringify(ev1.body));
  const ev2 = await send('evaluate', { expression: '6 * 7' });
  check('evaluate a global expression', ev2.success && ev2.body.result === '42');

  // -- run to completion ---------------------------------------------------------
  await send('continue', { threadId: tid });

  let output = '';
  for (;;) {
    const e = await nextEvent('output').catch(() => null);
    if (!e) break;
    output += e.body.output;
    if (output.includes('result=')) break;
  }
  check('program output arrives as events', output.includes('result=10'), JSON.stringify(output));

  const exited = await nextEvent('exited');
  check('exited with code 0', exited.body.exitCode === 0);
  await nextEvent('terminated');
  check('terminated event arrives', true);

  const bye = await send('disconnect', {});
  check('disconnect acknowledged', bye.success === true);
  const code = await new Promise((resolve) => proc.on('exit', resolve));
  check('adapter process exits cleanly', code === 0, `exit code ${code}`);

  if (channel) {
    channel.destroy();
  }
  const label = socketMode ? 'dap(socket)' : 'dap(stdio)';
  console.log(failures === 0 ? `${label}: all checks passed` : `${label}: ${failures} check(s) FAILED`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('FAIL (exception):', e.message);
  proc.kill();
  process.exit(1);
});
