import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { computeSpaceTrust, sanitizeMediaUrls, slugifySpaceName } from '../social';

describe('field social helpers (AUCTUM.COFFEE patterns)', () => {
  it('slugifies arbitrary space titles cleanly', () => {
    assert.equal(slugifySpaceName('North Texas Hail Desk!!'), 'north-texas-hail-desk');
    assert.ok(slugifySpaceName('   ').startsWith('space-'));
  });

  it('computes trust strictly from verified packets and jobs, never social likes/keeps', () => {
    const fresh = computeSpaceTrust(0, 0);
    const verified = computeSpaceTrust(3, 1);
    assert.equal(fresh.band, 'New');
    assert.equal(verified.band, 'Verified');
    assert.ok(verified.score > fresh.score);
  });

  it('sanitizes uploaded media URLs and rejects javascript/data payloads', () => {
    const clean = sanitizeMediaUrls([
      { url: '/uploads/roof-123.jpg', fileName: 'roof.jpg', mimeType: 'image/jpeg', size: 2048 },
      { url: 'javascript:alert(1)', fileName: 'xss.png', mimeType: 'image/png', size: 10 },
      { url: 'https://cdn.example.com/clip.webm', fileName: 'clip.webm', mimeType: 'video/webm', size: 9999 },
    ]);
    assert.equal(clean.length, 2);
    assert.equal(clean[0].fileType, 'IMAGE');
    assert.equal(clean[1].fileType, 'VIDEO');
  });
});
