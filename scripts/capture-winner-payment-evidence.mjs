/**
 * Chụp UI thật từ Vite, chỉ mock HTTP payment/profile bằng fixture công khai; không gọi merchant hoặc ghi PAID vào storage.
 * Đầu vào: EVIDENCE_BASE_URL (mặc định localhost:3013), BROWSER_EXECUTABLE_PATH nếu chưa cài Chromium của Playwright.
 * Đầu ra: ảnh PROCESSING/REQUIRES_REVIEW/SUCCESS và sơ đồ giải thích trong docs/test-evidence/tt-s2-3.
 * Mọi ảnh UI gắn nhãn HTTP fixture, không dùng làm bằng chứng đã giao dịch merchant; finally luôn đóng browser.
 */
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const require = createRequire(path.join(root, 'frontend-web/package.json'))
const { chromium } = require('@playwright/test')
const output = path.join(root, 'docs/test-evidence/tt-s2-3')
const paymentId = '11111111-1111-4111-8111-111111111111'
const browser = await chromium.launch({
    headless: true,
    ...(process.env.BROWSER_EXECUTABLE_PATH ? { executablePath: process.env.BROWSER_EXECUTABLE_PATH } : {}),
})

try {
    await mkdir(output, { recursive: true })
    const page = await browser.newPage({ viewport: { width: 1365, height: 900 }, deviceScaleFactor: 1 })
    let status = 'PROCESSING'
    await page.route('**/api/v1/**', async route => {
        const pathname = new URL(route.request().url()).pathname
        const data = pathname === `/api/v1/payments/${paymentId}`
            ? { paymentId, orderId: '22222222-2222-4222-8222-222222222222', status, amount: 5200000,
                currency: 'VND', paymentUrl: null, expiresAt: '2026-11-11T00:00:00Z' }
            : pathname === '/api/v1/users/me'
                ? { id: '33333333-3333-4333-8333-333333333333', username: 'buyer-fixture', fullName: 'Buyer test', roles: ['BUYER'] }
                : []
        await route.fulfill({ json: { success: true, data } })
    })
    for (const [value, file, heading] of [
        ['PROCESSING', '01-processing.png', 'Waiting for payment confirmation'],
        ['REQUIRES_REVIEW', '02-requires-review.png', 'Payment requires review'],
        ['SUCCESS', '03-confirmed.png', 'Payment confirmed'],
    ]) {
        status = value
        await page.goto(`${process.env.EVIDENCE_BASE_URL || 'http://127.0.0.1:3013'}/payment-result?vnp_TxnRef=${paymentId}&vnp_ResponseCode=00`)
        await page.getByRole('heading', { name: heading, exact: true }).waitFor()
        if (status !== 'SUCCESS') assert.equal(await page.getByRole('heading', { name: 'Payment confirmed', exact: true }).count(), 0)
        // Nhãn ảnh nêu rõ giới hạn fixture; không sửa component hoặc trạng thái ứng dụng để dựng kết quả.
        await page.evaluate(value => {
            const label = document.createElement('div')
            label.textContent = `TT-S2-3 • UI thật / HTTP fixture: ${value} • Không phải giao dịch merchant • 10/11/2026`
            label.style.cssText = 'position:fixed;bottom:0;left:0;right:0;padding:12px;background:#172033;color:white;text-align:center;font:14px Segoe UI;z-index:99999'
            document.body.append(label)
        }, status)
        await page.screenshot({ path: path.join(output, file), fullPage: true })
    }
    // Sơ đồ giải thích tĩnh, không phải log hoặc kết quả thực thi; review code/tests là nguồn kiểm chứng hành vi.
    await page.setContent(`<!doctype html><html lang="vi"><meta charset="utf-8"><style>
    body{font:19px/1.6 Segoe UI,sans-serif;background:#f2f5f9;color:#172033;padding:38px}h1{margin:0;font-size:32px}
    small{color:#536174}.row{display:flex;align-items:center;gap:14px;margin:24px 0}.box{background:white;border:2px solid #cbd5e1;border-radius:12px;padding:20px;flex:1}
    .green{border-color:#16a34a}.orange{border-color:#d97706}.blue{border-color:#2563eb}code{font:17px Consolas}footer{border-top:1px solid #ccd5df;padding-top:18px}
    </style><h1>TT-S2-3 · Tiền đã thu ≠ Đơn đã PAID</h1><small>Sơ đồ giải thích từ implementation • không phải bằng chứng giao dịch merchant</small>
    <div class="row"><div class="box">IPN ký hợp lệ<br><code>00 / 00</code></div>→<div class="box blue">Payment SUCCESS<br>inventory PENDING</div>→<div class="box blue">Outbox COMMIT<br>Order PENDING_PAYMENT</div></div>
    <div class="row"><div class="box green">Kho CONFIRMED thắng khóa<br>→ Order PAID<br>→ API SUCCESS<br>→ Thông báo thành công</div><div class="box orange">Sweep / hủy thắng khóa<br>→ Order CANCELLED<br>→ API REQUIRES_REVIEW<br>→ Đối soát, chưa refund tự động</div></div>
    <div class="box">Mạng lỗi / response mất → outbox retry cùng orderId → không trừ kho lần hai.<br>Callback trùng → RspCode 02 → không ghi lặp history / tác vụ.</div>
    <footer>Browser chỉ đọc kết quả backend. Query <code>vnp_ResponseCode=00</code> không đủ để xác nhận thanh toán.</footer></html>`)
    await page.screenshot({ path: path.join(output, '04-payment-flow.png'), fullPage: true })
    console.log('Captured 3 real UI states with HTTP fixtures and 1 explanatory diagram.')
} finally {
    await browser.close()
}
