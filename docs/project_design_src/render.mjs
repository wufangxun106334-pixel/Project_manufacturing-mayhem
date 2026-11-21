import puppeteer from '/usr/local/lib/node_modules/@mermaid-js/mermaid-cli/node_modules/puppeteer/lib/puppeteer/puppeteer.js';

const [htmlPath, pdfPath] = process.argv.slice(2);
const browser = await puppeteer.launch({ headless: 'shell' });
const page = await browser.newPage();
await page.goto('file://' + htmlPath, { waitUntil: 'networkidle0' });
await page.pdf({
  path: pdfPath,
  format: 'A4',
  printBackground: true,
  displayHeaderFooter: true,
  headerTemplate: '<span></span>',
  footerTemplate: '<div style="font-size:8px;width:100%;text-align:center;color:#777;">Manufacturing Mayhem — Project Design · page <span class="pageNumber"></span> / <span class="totalPages"></span></div>',
  margin: { top: '16mm', bottom: '18mm', left: '14mm', right: '14mm' },
});
await browser.close();
console.log('written', pdfPath);
