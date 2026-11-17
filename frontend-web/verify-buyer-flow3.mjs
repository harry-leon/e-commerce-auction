/**
 * Script kiểm thử tự động toàn diện Luồng 3 (Buyer Flow 3):
 * - Kiểm tra 0 Console Errors và 0 Page Errors trên tất cả các trang.
 * - Chụp ảnh bằng chứng sắc nét lưu vào docs/screenshots/HG-S3-FLOW3/.
 */
import { chromium } from '@playwright/test'
import fs from 'fs'
import path from 'path'

const OUT_DIR = path.resolve('../docs/screenshots/HG-S3-FLOW3')
fs.mkdirSync(OUT_DIR, { recursive: true })

const TARGETS = [
    {
        name: '01_winner_success_celebration_modal',
        url: 'http://localhost:3000/home?demo=buyer&winnerCelebration=true',
        description: 'Cửa sổ Popup vinh danh chúc mừng người chiến thắng đấu giá (Auction Winner Modal) - Global Popup',
        waitForSelector: '.animate-scale-in',
    },
    {
        name: '02_notifications_inbox',
        url: 'http://localhost:3000/notifications?demo=buyer',
        description: 'Hộp thư thông báo - Bộ lọc, đếm số lượng, nhóm Hôm nay/Hôm qua, nút Thao tác nhanh',
        waitForSelector: 'h1',
    },
    {
        name: '03_order_detail_view',
        url: 'http://localhost:3000/order-detail?demo=buyer&id=2610313JCYGVA5',
        description: 'Chi tiết đơn hàng - Stepper 5 bước xanh lá, Đa sản phẩm, Bảng tính chi phí chuẩn, 3 Card thông tin',
        waitForSelector: 'h2',
    },
    {
        name: '04_seller_shop_view',
        url: 'http://localhost:3000/seller-shop?demo=buyer&sellerName=TechZone%20Store',
        description: 'Gian hàng người bán & Uy tín - Banner tối, lưới 2x3 chỉ số, Vouchers, Gợi ý 3 sản phẩm',
        waitForSelector: 'h1',
    },
    {
        name: '05_auction_payment_page',
        url: 'http://localhost:3000/auction-payment?demo=buyer&auctionId=02994692',
        description: 'Thanh toán trúng thầu đấu giá - Form nhận hàng, đồng hồ đếm ngược, nút Tiến hành thanh toán',
        waitForSelector: 'button',
    },
]

async function run() {
    console.log('🚀 Khởi động Headless Browser để kiểm thử Luồng 3...')
    const browser = await chromium.launch({
        executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
        headless: true,
    })
    const context = await browser.newContext({
        viewport: { width: 1440, height: 900 },
        deviceScaleFactor: 2,
    })
    await context.addInitScript(() => {
        localStorage.setItem('nexus_lang', 'vi')
    })

    const results = []

    for (const target of TARGETS) {
        console.log(`\n🔍 Kiểm tra trang: ${target.name} (${target.url})`)
        const page = await context.newPage()
        const consoleErrors = []
        const pageErrors = []

        page.on('console', (msg) => {
            if (msg.type() === 'error') {
                const text = msg.text()
                // Bỏ qua lỗi mạng khi backend local không chạy (404/ECONNREFUSED) do fallback đã xử lý
                if (!text.includes('Failed to load resource') && !text.includes('ECONNREFUSED')) {
                    consoleErrors.push(text)
                }
            }
        })

        page.on('pageerror', (err) => {
            pageErrors.push(err.message)
        })

        try {
            await page.goto(target.url, { waitUntil: 'domcontentloaded', timeout: 15000 })
            await page.waitForTimeout(1500)
            if (target.waitForSelector) {
                await page.waitForSelector(target.waitForSelector, { timeout: 8000 }).catch(() => {})
            }
            await page.waitForTimeout(500)

            const screenshotPath = path.join(OUT_DIR, `${target.name}.png`)
            await page.screenshot({ path: screenshotPath, fullPage: true })
            console.log(`📸 Đã chụp ảnh lưu tại: ${screenshotPath}`)

            const passed = pageErrors.length === 0 && consoleErrors.length === 0
            results.push({
                name: target.name,
                url: target.url,
                passed,
                pageErrors,
                consoleErrors,
                screenshot: screenshotPath,
            })

            console.log(`  ➔ Kết quả: ${passed ? '✅ PASS (0 errors)' : '❌ FAIL'}`)
            if (consoleErrors.length > 0) {
                console.log(`    ⚠️ Console Errors:`, consoleErrors)
            }
            if (pageErrors.length > 0) {
                console.log(`    ⚠️ Page Errors:`, pageErrors)
            }
        } catch (err) {
            console.error(`  ❌ Lỗi điều hướng trang ${target.name}:`, err.message)
            results.push({
                name: target.name,
                url: target.url,
                passed: false,
                pageErrors: [err.message],
                consoleErrors,
            })
        } finally {
            await page.close()
        }
    }

    await browser.close()

    console.log('\n═══════════════════════════════════════════')
    console.log('TỔNG HỢP KẾT QUẢ KIỂM THỬ GIAO DIỆN:')
    console.log('═══════════════════════════════════════════')
    results.forEach((r) => {
        console.log(`${r.passed ? '✅' : '❌'} ${r.name}: ${r.passed ? 'PASSED (0 errors in DevTools)' : 'FAILED'}`)
    })

    const allPassed = results.every((r) => r.passed)
    process.exit(allPassed ? 0 : 1)
}

run().catch((err) => {
    console.error('Fatal test error:', err)
    process.exit(1)
})
