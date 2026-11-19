import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  attributionNote,
  CODE_LENGTH,
  contrastRatio,
  DEFAULT_BRAND,
  generatePieceCode,
  normalizeBrand,
  normalizeHex,
  normalizePieceCode,
  readableTextOn,
  validateCapture,
  validatePieceInput,
} from '../studio';

describe('marketing studio: piece codes', () => {
  it('generates readable, fixed-length, non-sequential codes', () => {
    const codes = new Set(Array.from({ length: 500 }, () => generatePieceCode()));
    assert.equal(codes.size, 500);
    for (const c of codes) {
      assert.equal(c.length, CODE_LENGTH);
      assert.match(c, /^[0-9A-HJKMNP-TV-Z]+$/); // no I, L, O, U
    }
  });

  it('accepts codes typed by a person and rejects anything else', () => {
    const code = generatePieceCode(Buffer.from([0, 1, 2, 3, 4, 5, 6, 7, 8, 9]));
    assert.equal(code, '0123456789');
    assert.equal(normalizePieceCode('o123-4567 89'), '0123456789');
    assert.equal(normalizePieceCode('l23456789O'), '1234567890');
    assert.equal(normalizePieceCode('ABC'), null);
    assert.equal(normalizePieceCode('ABCDEFGHUU'), null); // U is not in the alphabet
    assert.equal(normalizePieceCode(42), null);
  });
});

describe('marketing studio: brand colors', () => {
  it('normalizes hex colors and ignores invalid ones', () => {
    assert.equal(normalizeHex('#abc'), '#AABBCC');
    assert.equal(normalizeHex('8c2f22'), '#8C2F22');
    assert.equal(normalizeHex('red'), null);
    assert.equal(normalizeHex('javascript:alert(1)'), null);
    assert.deepEqual(normalizeBrand({ primary: '#123456', accent: 'nope' }), { primary: '#123456', accent: DEFAULT_BRAND.accent });
    assert.deepEqual(normalizeBrand(null), DEFAULT_BRAND);
  });

  it('picks the more readable text color for any background', () => {
    assert.equal(readableTextOn('#17231D'), '#FFFFFF');
    assert.equal(readableTextOn('#F1F2EA'), '#111111');
    assert.equal(readableTextOn('#FFD400'), '#111111');
    assert.ok(contrastRatio('#000000', '#FFFFFF') > 20);
  });
});

describe('marketing studio: piece input', () => {
  it('requires kind, title and headline on create, and enforces lengths', () => {
    const ok = validatePieceInput({ kind: 'DOOR_HANGER', title: 'Hail — McKinney', headline: 'Free roof inspection' });
    assert.ok(ok.ok);
    assert.deepEqual(ok.value, { kind: 'DOOR_HANGER', title: 'Hail — McKinney', headline: 'Free roof inspection', body: '', cta: '' });

    const bad = validatePieceInput({ kind: 'BILLBOARD', title: ' ', headline: 'x'.repeat(91) });
    assert.equal(bad.ok, false);
    assert.equal(bad.errors!.length, 3);
  });

  it('allows partial updates without re-sending required fields', () => {
    const r = validatePieceInput({ cta: 'Scan for a free inspection' }, true);
    assert.ok(r.ok);
    assert.deepEqual(r.value, { cta: 'Scan for a free inspection' });
    assert.equal(validatePieceInput({ headline: '' }, true).ok, false);
  });
});

describe('marketing studio: scan-to-lead capture', () => {
  const good = { name: 'Avery Coleman', phone: '(469) 555-1101', propertyAddress: '1420 W Hunt St, McKinney, TX', consent: true };

  it('accepts a complete request and splits the name', () => {
    const r = validateCapture(good);
    assert.ok(r.ok);
    assert.equal(r.value!.firstName, 'Avery');
    assert.equal(r.value!.lastName, 'Coleman');
    assert.equal(r.value!.email, null);
  });

  it('requires consent, an address and a way to reach the homeowner', () => {
    const r = validateCapture({ name: 'Avery' });
    assert.equal(r.ok, false);
    assert.deepEqual(r.errors!.sort(), [
      'a phone number or email is required',
      'consent to be contacted is required',
      'property address is required',
    ]);
    assert.equal(validateCapture({ ...good, phone: '555' }).ok, false);
    assert.equal(validateCapture({ ...good, phone: '', email: 'not-an-email' }).ok, false);
    assert.ok(validateCapture({ ...good, phone: '', email: 'A@Example.com' }).value?.email === 'a@example.com');
  });

  it('flags the honeypot as a bot without storing anything', () => {
    const r = validateCapture({ ...good, website: 'http://spam.example' });
    assert.equal(r.ok, false);
    assert.equal(r.bot, true);
  });

  it('writes the attribution into the lead note', () => {
    const note = attributionNote({ kind: 'YARD_SIGN', title: 'Storm 11/12', code: 'ABCDEFGH12' }, 'North Dallas hail', 'Leak over the garage');
    assert.match(note, /yard sign "Storm 11\/12" \(code ABCDEFGH12\) from campaign "North Dallas hail"/);
    assert.match(note, /Homeowner message: Leak over the garage/);
  });
});
