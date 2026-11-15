const { chromium } = require('playwright');
const assert = require('node:assert/strict');
(async () => {
  const browser = await chromium.launch({ executablePath: '/usr/bin/chromium', headless: true,
    args: ['--no-sandbox', '--autoplay-policy=no-user-gesture-required'] });
  try {
    const page = await browser.newPage({ viewport: { width: 1100, height: 1000 } });
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(process.argv[2], { waitUntil: 'load' });
    await page.waitForFunction(() => [...document.querySelectorAll('audio')].every(a => a.readyState >= 2));
    const durations = await page.locator('audio').evaluateAll(xs => xs.map(a => a.duration));
    assert.equal(durations.length, 8);
    for (const duration of durations) assert.ok(Math.abs(duration - 3.3) < .001);
    for (const a of await page.locator('audio').all()) {
      await a.evaluate(async el => { await el.play(); });
      await page.waitForFunction(el => el.currentTime > .02 && !el.paused,
        await a.elementHandle(), { timeout: 10000 });
      await a.evaluate(el => { el.pause(); el.currentTime = 0; });
    }
    await page.setViewportSize({ width: 390, height: 844 });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    if (process.argv[3]) await page.screenshot({ path: process.argv[3], fullPage: true });
    assert.deepEqual(errors, []);
    console.log('PASS: eight decoded 3.3-second clips, playback and pause, 390px layout, no page errors.');
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exit(1); });
