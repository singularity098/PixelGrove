#!/usr/bin/env node
// Renders the PWA icons in web/public from the logo icon (web/public/favicon.svg) and
// puts the icon into the README logos (docs/logo-{dark,light}.svg).
//
// Usage: node scripts/make-icons.mjs   (needs Playwright with Chromium)
import { createRequire } from 'node:module';
import { execSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
let playwright;
try {
  playwright = require('playwright');
} catch {
  playwright = require(join(execSync('npm root -g').toString().trim(), 'playwright'));
}

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const pub = join(root, 'web', 'public');
const icon = readFileSync(join(pub, 'favicon.svg'), 'utf8');

// The icon is drawn on a 108-unit grid (Android's adaptive-icon canvas) and shows the
// middle 80 units on a rounded tile. Square variants fill the whole canvas instead, for
// platforms that cut their own shape: maskable icons keep the artwork inside the central
// 80% circle, which it fits with room to spare.
const square = icon.replace(/<rect id="tile"[^>]*\/>/, (tile) =>
  tile.replace(/x="14" y="14" width="80" height="80" rx="18"/, 'width="108" height="108"'),
);
if (square === icon) throw new Error('favicon.svg has no <rect id="tile" ...> to square off');

const browser = await playwright.chromium.launch();
const page = await browser.newPage();
async function render(svg, size, file, opaque = false) {
  const sized = svg.replace('<svg ', `<svg width="${size}" height="${size}" `);
  await page.setViewportSize({ width: size, height: size });
  await page.setContent(
    `<style>html,body{margin:0;background:transparent}svg{display:block}</style>${sized}`,
  );
  await page.screenshot({ path: join(pub, file), omitBackground: !opaque });
}
for (const size of [192, 512]) {
  await render(icon, size, `icon-${size}.png`);
  await render(square, size, `icon-maskable-${size}.png`, true);
}
// iOS rounds the corners itself and shows transparency as black.
await render(square, 180, 'apple-touch-icon.png', true);
await browser.close();

// README logos: the icon (168 px) followed by the wordmark, which stays as it is.
for (const mode of ['dark', 'light']) {
  const file = join(root, 'docs', `logo-${mode}.svg`);
  const logo = readFileSync(file, 'utf8');
  const start = logo.indexOf('<svg', 1);
  const end = logo.indexOf('<svg x="200"');
  if (start < 0 || end < start) throw new Error(`unexpected layout in ${file}`);
  const nested = icon.replace('<svg ', '<svg width="168" height="168" ').replace(/id="sky"/, `id="sky-${mode}"`).replace('url(#sky)', `url(#sky-${mode})`);
  writeFileSync(file, logo.slice(0, start) + nested.trim() + logo.slice(end));
}
