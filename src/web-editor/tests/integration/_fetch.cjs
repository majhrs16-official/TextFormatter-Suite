'use strict';

const fs = require('fs');
const path = require('path');

/**
 * Minimal fetch polyfill for the JSDOM integration harnesses. The browser
 * code loads `paths.json` with fetch(); JSDOM does not provide fetch, so the
 * harness would otherwise silently exercise the fallback table only.
 */
function installFetch(window, rootDir) {
  window.fetch = async (url) => {
    const relative = String(url)
      .replace(/^\.\//, '')
      .split('?')[0]
      .split('#')[0];
    const file = path.isAbsolute(relative) ? relative : path.join(rootDir, relative);
    const text = fs.readFileSync(file, 'utf8');
    return {
      ok: true,
      status: 200,
      text: async () => text,
      json: async () => JSON.parse(text),
    };
  };
}

module.exports = { installFetch };
