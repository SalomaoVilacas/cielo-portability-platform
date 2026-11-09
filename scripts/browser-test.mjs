import { pathToFileURL } from "node:url";
const modulePath = process.env.PLAYWRIGHT_MODULE;
if (!modulePath)
    throw new Error(
        "Set PLAYWRIGHT_MODULE to an existing Playwright installation; this script never installs dependencies.",
    );
const { chromium } = await import(pathToFileURL(modulePath));
const browser = await chromium.launch({
    headless: true,
    ...(process.env.CHROME_BIN ? { executablePath: process.env.CHROME_BIN } : {}),
});
try {
    const page = await browser.newPage();
    const { verifyPortability } = await import("./browser-flow.mjs");
    await verifyPortability(page, process.env.APP_URL || "http://localhost:5173");
} finally {
    await browser.close();
}
