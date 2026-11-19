/**
 * Backend test runner — dependency-free.
 *
 * Discovers every *.test.ts under src/ and runs them with Node's built-in test
 * runner through tsx (TypeScript ESM). Avoids Jest/ESM configuration pitfalls
 * and works identically on Windows, macOS and Linux.
 *
 * Usage: npm test            (or: node scripts/run-tests.mjs)
 */
import { readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { spawnSync } from 'node:child_process';

const ROOT = process.cwd();
const SRC = join(ROOT, 'src');

function walk(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    const stat = statSync(full);
    if (stat.isDirectory()) {
      if (entry === 'node_modules' || entry === 'dist') continue;
      walk(full, out);
    } else if (entry.endsWith('.test.ts')) {
      out.push(full);
    }
  }
  return out;
}

const files = walk(SRC).sort();
if (files.length === 0) {
  console.log('[tests] no backend *.test.ts files found under src/');
  process.exit(0);
}

console.log(`[tests] running ${files.length} file(s):`);
for (const f of files) console.log('  -', relative(ROOT, f));

const result = spawnSync(
  process.execPath,
  ['--import', 'tsx', '--test', ...files],
  { stdio: 'inherit', env: process.env, cwd: ROOT }
);

process.exit(result.status ?? 1);
