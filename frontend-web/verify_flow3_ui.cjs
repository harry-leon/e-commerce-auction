/**
 * Script kiểm thử tự động toàn diện UI cho Luồng 3 (Buyer)
 *
 * Chức năng:
 * - Dùng Playwright điều hướng qua các trang Buyer Flow 3 tại http://localhost:3000.
 * - Quét sạch console errors & page errors (đảm bảo 0 error theo đúng yêu cầu).
 * - Chụp ảnh screenshot full-page sắc nét lưu vào docs/screenshots/HG-S3-FLOW3/.
 */

const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');

const SCREENSHOT_DIR = path.resolve(__dirname, '../docs/screenshots/HG-S3-FLOW3');
if (!fs.existsSync(SCREENSHOT_DIR)) {
    fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });
}

async function runVerification() {
    console.log('--- BẮT ĐẦU KIỂM THỬ GIAO DIỆN & DEVTOOLS CONSOLE ---');
    
    const browser = await chromium.launch({
        headless: true,
        executablePath: 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
        args: ['--no-sandbox', '--disable-setuid-sandbox']
    });

    const context = await browser.newContext({
        viewport: { width: 1440, height: 960 },
        deviceScaleFactor: 1.5,
    });

    const page = await context.newPage();

    const pagesToTest = [
        {
            name: 'Notifications (Khớp Ảnh 1)',
            url: 'http://localhost:3000/notifications?demo=buyer',
            screenshot: 'notifications_style_match_v2.png',
        },
        {
            name: 'Auction Payment - Winner Celebration Popup & Fireworks (Yêu cầu popup & pháo hoa)',
            url: 'http://localhost:3000/auction-payment?demo=buyer',
            screenshot: 'auction-payment_celebration_popup_fireworks_v2.png',
        },
        {
            name: 'Order Detail - 5-Step Stepper & Multi-item Non-Zero (Khớp Ảnh 2)',
            url: 'http://localhost:3000/order-detail?id=ORD-1761922751509-0&demo=buyer',
            screenshot: 'order-detail_multi-items_stepper_match_v2.png',
        },
        {
            name: 'Order History - Search, Tabs, Shop Header, Multi-item Card (Khớp Ảnh 3)',
            url: 'http://localhost:3000/order-history?demo=buyer',
            screenshot: 'order-history_style_match_v2.png',
        },
        {
            name: 'Seller Shop - Dark Hero Banner, 2x3 Metrics, Vouchers, 3 Products (Khớp Ảnh 4)',
            url: 'http://localhost:3000/seller-shop?demo=buyer',
            screenshot: 'seller-shop_custom_style_match_v2.png',
        },
        {
            name: 'Public Reputation - Match Image 4 View',
            url: 'http://localhost:3000/public-reputation?demo=buyer',
            screenshot: 'public-reputation_custom_style_match_v2.png',
        },
    ];

    const results = [];

    for (const item of pagesToTest) {
        console.log(`\nKiểm tra trang: [${item.name}] tại ${item.url}`);
        const consoleErrors = [];
        const pageErrors = [];

        const consoleListener = (msg) => {
            if (msg.type() === 'error') {
                const text = msg.text();
                // Bỏ qua lỗi icon mạng hoặc websocket nếu không phải code bug
                if (!text.includes('favicon.ico') && !text.includes('ws://localhost')) {
                    consoleErrors.push(text);
                }
            }
        };
        const errorListener = (err) => {
            pageErrors.push(err.message || String(err));
        };

        page.on('console', consoleListener);
        page.on('pageerror', errorListener);

        try {
            await page.goto(item.url, { waitUntil: 'domcontentloaded', timeout: 15000 });
            await page.waitForTimeout(2000); // Đợi render xong dữ liệu và hiệu ứng

            const screenshotPath = path.join(SCREENSHOT_DIR, item.screenshot);
            await page.screenshot({ path: screenshotPath, fullPage: false });
            console.log(`  ✓ Đã chụp ảnh màn hình: ${item.screenshot}`);

            const hasError = consoleErrors.length > 0 || pageErrors.length > 0;
            if (hasError) {
                console.log(`  ✗ Phát hiện console errors:`, consoleErrors, pageErrors);
            } else {
                console.log(`  ✓ DevTools Console sạch: 0 ERROR, 0 EXCEPTION!`);
            }

            results.push({
                page: item.name,
                url: item.url,
                screenshot: item.screenshot,
                consoleErrorsCount: consoleErrors.length,
                pageErrorsCount: pageErrors.length,
                status: hasError ? 'FAILED' : 'PASSED',
            });
        } catch (err) {
            console.error(`  ✗ Lỗi khi kiểm tra trang:`, err.message);
            results.push({
                page: item.name,
                url: item.url,
                screenshot: item.screenshot,
                error: err.message,
                status: 'ERROR',
            });
        } finally {
            page.off('console', consoleListener);
            page.off('pageerror', errorListener);
        }
    }

    await browser.close();

    console.log('\n═══════════════════════════════════════════');
    console.log('TỔNG HỢP KẾT QUẢ KIỂM THỬ TRÌNH DUYỆT');
    console.log('═══════════════════════════════════════════');
    console.table(results);
}

runVerification().catch(err => {
    console.error('Lỗi thực thi kiểm thử:', err);
    process.exit(1);
});
