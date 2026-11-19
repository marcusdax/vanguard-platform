#!/usr/bin/env node
/**
 * Vanguard Platform Integrity & Rebrand Verification Script:
 *  - Check C-04: vanguard-verify.min.js < 5120 bytes
 *  - Parity: packages/trust-engine/src matches services/api/src/engine byte-for-byte
 *  - Check C-09: registers/fees.csv worker floor = 0 bps
 *  - Rebrand hygiene: zero legacy AUCTUM / RoofPro marks in web/vanguard/vision/brand config
 */
import assert from 'node:assert/strict';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

const root = process.cwd();

// 1. brand.config.json check
const brandConfig = JSON.parse(readFileSync(resolve(root, 'brand.config.json'), 'utf8'));
assert.ok(brandConfig.platformName, 'brand.config.json missing platformName');
assert.ok(brandConfig.mobileAppName, 'brand.config.json missing mobileAppName');

// 2. Check C-04 browser verifier budget (< 5 KB)
const verifierPath = resolve(root, 'apps/web/public/verifier/vanguard-verify.min.js');
const verifierBytes = statSync(verifierPath).size;
assert.ok(
  verifierBytes < 5120,
  `vanguard-verify.min.js exceeds 5120 bytes (${verifierBytes} B)`,
);

// 3. Trust Engine parity between packages/trust-engine/src and services/api/src/engine
const engineFiles = ['canonical.ts', 'merkle.ts', 'credentials.ts', 'economics.ts', 'index.ts'];
for (const file of engineFiles) {
  const pkg = readFileSync(resolve(root, 'packages/trust-engine/src', file), 'utf8');
  const api = readFileSync(resolve(root, 'services/api/src/engine', file), 'utf8');
  assert.equal(pkg, api, `Engine parity mismatch on ${file}`);
}

// 4. Fee register check
const feesCsv = readFileSync(resolve(root, 'registers/fees.csv'), 'utf8');
assert.ok(feesCsv.includes('vanguard.b2b.bounty,BUYER,1000,0'), 'Check C-09 missing vanguard.b2b.bounty');
assert.ok(feesCsv.includes('helios.local.b2c,REQUESTER,500,0'), 'Check C-09 missing helios.local.b2c');

// 5. Walk brand-sensitive directories for stale marks
const forbidden = /\b(auctum|roofpro)\b/i;
const scanRoots = [
  resolve(root, 'apps/web/src'),
  resolve(root, 'apps/web/public'),
  resolve(root, 'apps/web/index.html'),
  resolve(root, 'services/api/src/services/vanguard'),
  resolve(root, 'services/vision'),
  resolve(root, 'brand.config.json'),
];

function walkFiles(target) {
  const st = statSync(target);
  if (st.isFile()) return [target];
  let results = [];
  for (const entry of readdirSync(target)) {
    results = results.concat(walkFiles(join(target, entry)));
  }
  return results;
}

for (const target of scanRoots) {
  for (const file of walkFiles(target)) {
    const content = readFileSync(file, 'utf8');
    const match = content.match(forbidden);
    assert.equal(match, null, `Legacy brand mark "${match?.[0]}" found in ${file}`);
  }
}

console.log(
  `brand-check passed: ${brandConfig.platformName} / ${brandConfig.mobileAppName}, verifier=${verifierBytes}B, parity=5/5, 0 brand leaks`,
);
