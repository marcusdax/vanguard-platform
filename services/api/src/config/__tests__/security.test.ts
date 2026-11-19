import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { resolveJwtSecret } from '../secrets';
import { isAllowedOrigin } from '../origins';
import { MIME_EXTENSIONS, SERVABLE_UPLOAD_EXTENSIONS, safeUploadStem, storedUploadName } from '../../middleware/upload';

const strong = 'a'.repeat(40) + 'f00d';

describe('security: JWT secrets', () => {
  it('uses a strong configured secret as-is', () => {
    assert.equal(resolveJwtSecret('access', { JWT_SECRET: strong, NODE_ENV: 'production' }), strong);
  });

  it('refuses to start in production with a missing, short or placeholder secret', () => {
    for (const JWT_SECRET of [undefined, 'short', 'roofpro-crm-super-secret-key-change-in-production']) {
      assert.throws(() => resolveJwtSecret('access', { JWT_SECRET, NODE_ENV: 'production' }), /JWT_SECRET/);
    }
    assert.throws(() => resolveJwtSecret('refresh', { NODE_ENV: 'production' }), /JWT_REFRESH_SECRET/);
  });

  it('falls back with a warning outside production, never silently', () => {
    const warnings: string[] = [];
    const value = resolveJwtSecret('access', { NODE_ENV: 'development' }, (m) => warnings.push(m));
    assert.ok(value.length >= 32);
    assert.equal(warnings.length, 1);
    assert.match(warnings[0], /development-only fallback/);
  });
});

describe('security: CORS origins', () => {
  const allowed = ['http://localhost:3000'];

  it('allows configured origins and requests without an Origin header', () => {
    assert.ok(isAllowedOrigin('http://localhost:3000', allowed, false));
    assert.ok(isAllowedOrigin(undefined, allowed, false));
    assert.equal(isAllowedOrigin('https://evil.example', allowed, false), false);
  });

  it('in tunnel mode admits HTTPS quick-tunnel hosts only', () => {
    assert.ok(isAllowedOrigin('https://bobby-zope-affairs-rear.trycloudflare.com', allowed, true));
    assert.ok(isAllowedOrigin('https://abc123.ngrok-free.app', allowed, true));
    assert.equal(isAllowedOrigin('https://evil.example', allowed, true), false);
    assert.equal(isAllowedOrigin('http://x.trycloudflare.com', allowed, true), false); // not HTTPS
    assert.equal(isAllowedOrigin('https://trycloudflare.com.evil.example', allowed, true), false);
    assert.equal(isAllowedOrigin('https://a.b.trycloudflare.com', allowed, true), false); // one label only
    assert.equal(isAllowedOrigin('https://x.trycloudflare.com:8443', allowed, true), false);
  });
});

describe('security: uploads', () => {
  it('stores files with the extension implied by the MIME type, never the client filename', () => {
    const name = storedUploadName('evil.html', 'image/png');
    assert.match(name, /^evil-[0-9a-f-]{36}\.png$/);
    assert.match(storedUploadName('roof photo (1).JPEG', 'image/jpeg'), /^roof-photo-1-[0-9a-f-]{36}\.jpg$/);
    assert.throws(() => storedUploadName('x.svg', 'image/svg+xml'), /not allowed/);
    assert.throws(() => storedUploadName('x.html', 'text/html'), /not allowed/);
  });

  it('strips paths and unsafe characters from the readable part of the name', () => {
    assert.equal(safeUploadStem('../../etc/passwd'), 'passwd');
    assert.equal(safeUploadStem('..\\..\\boot.ini'), 'boot');
    assert.equal(safeUploadStem('.htaccess'), 'htaccess'); // never a dotfile
    assert.equal(safeUploadStem('...'), 'file');
    assert.equal(safeUploadStem(''), 'file');
  });

  it('serves only document and media extensions', () => {
    for (const ext of Object.values(MIME_EXTENSIONS)) assert.ok(SERVABLE_UPLOAD_EXTENSIONS.has(ext));
    for (const ext of ['.html', '.htm', '.svg', '.js', '.xml', '.xhtml', '']) assert.equal(SERVABLE_UPLOAD_EXTENSIONS.has(ext), false);
  });
});
