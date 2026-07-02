import { chromium } from '@playwright/test';
const b = await chromium.launch();
const p = await b.newPage({ viewport: { width: 1366, height: 900 } });
let status = 'ERR';
try {
  const r = await p.goto('http://localhost:3000/', { waitUntil: 'networkidle', timeout: 30000 });
  status = r ? r.status() : 'no-response';
} catch (e) { status = 'ERR ' + e.message; }
await p.waitForTimeout(2500);
await p.screenshot({ path: '_home.png', fullPage: false });
console.log('HTTP_STATUS', status);
console.log('TITLE', await p.title());
await b.close();
