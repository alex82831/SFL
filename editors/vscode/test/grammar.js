#!/usr/bin/env node
// Tokenizes a representative SFL sample with the real TextMate engine and asserts
// the scopes that matter. This is what CI runs instead of eyeballing colours.

'use strict';
const fs = require('fs');
const path = require('path');
const oniguruma = require('vscode-oniguruma');
const vsctm = require('vscode-textmate');

const wasmPath = require.resolve('vscode-oniguruma/release/onig.wasm');
const grammarPath = path.join(__dirname, '..', 'syntaxes', 'sfl.tmLanguage.json');

let failures = 0;
function check(name, cond, extra) {
  if (cond) console.log(`ok   ${name}`);
  else { failures++; console.error(`FAIL ${name}${extra ? ': ' + extra : ''}`); }
}

async function main() {
  const wasm = fs.readFileSync(wasmPath).buffer;
  await oniguruma.loadWASM(wasm);
  const registry = new vsctm.Registry({
    onigLib: Promise.resolve({
      createOnigScanner: (s) => new oniguruma.OnigScanner(s),
      createOnigString: (s) => new oniguruma.OnigString(s),
    }),
    loadGrammar: async () => vsctm.parseRawGrammar(fs.readFileSync(grammarPath, 'utf8'), grammarPath),
  });
  const grammar = await registry.loadGrammar('source.sfl');

  // The scopes covering the first character of `needle` within `line`.
  function scopesAt(line, needle) {
    const at = line.indexOf(needle);
    const r = grammar.tokenizeLine(line, vsctm.INITIAL);
    for (const t of r.tokens) {
      if (at >= t.startIndex && at < t.endIndex) return t.scopes.join(' ');
    }
    return '';
  }

  check('keyword val', scopesAt('val x = 1', 'val').includes('storage.type.sfl'));
  check('keyword if', scopesAt('if (a) then b else c', 'if').includes('keyword.control.sfl'));
  check('constant true', scopesAt('val t = true', 'true').includes('constant.language.sfl'));
  check('def name is a function', scopesAt('def area(r) { r }', 'area').includes('entity.name.function.sfl'));
  check('builtin println', scopesAt('println(1)', 'println').includes('support.function.builtin.sfl'));
  check('user call is a call', scopesAt('area(2)', 'area').includes('entity.name.function.call.sfl'));
  check('keyword not a call: if(', !scopesAt('if(x) then 1 else 2', 'if').includes('entity.name.function.call.sfl'));
  check('number', scopesAt('val n = 1_000.5e3', '1_000').includes('constant.numeric.sfl'));
  check('hex number', scopesAt('val h = 0xFF_AA', '0xFF').includes('constant.numeric.hex.sfl'));
  check('double string', scopesAt('val s = "hi"', '"hi').includes('string.quoted.double.sfl'));
  check('single string', scopesAt("val s = 'hi'", "'hi").includes('string.quoted.single.sfl'));
  check('escape in string', scopesAt('val s = "a\\nb"', '\\n').includes('constant.character.escape.sfl'));
  check('raw string', scopesAt("val re = r'a\\d+'", "a\\d").includes('string.quoted.raw.sfl'));
  check('no escape scope in raw string', !scopesAt("val re = r'a\\d+'", '\\d').includes('constant.character.escape.sfl'));
  check('interpolation punctuation', scopesAt('val s = "n=${n}!"', '${').includes('punctuation.section.interpolation.begin.sfl'));
  check('interpolated code is code', scopesAt('val s = "v=${count + 1}"', 'count').includes('meta.interpolation.sfl'));
  check('builtin inside interpolation', scopesAt('val s = "${length(xs)}"', 'length').includes('support.function.builtin.sfl'));
  check('line comment', scopesAt('val x = 1 // note', '// note').includes('comment.line.double-slash.sfl'));
  check('block comment', scopesAt('val x = /* mid */ 1', 'mid').includes('comment.block.sfl'));
  check('operator |>', scopesAt('xs |> length()', '|>').includes('keyword.operator.sfl'));
  check('operator ??', scopesAt('a ?? b', '??').includes('keyword.operator.sfl'));
  check('single-quote string does not interpolate', !scopesAt("val s = 'a${x}b'", '${').includes('punctuation.section.interpolation.begin.sfl'));

  console.log(failures === 0 ? 'grammar: all checks passed' : `grammar: ${failures} check(s) FAILED`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => { console.error('FAIL (exception):', e); process.exit(1); });
