import { test, expect } from '@playwright/test'

/**
 * Kiểm chứng Sprint 1 trên trình duyệt thật với API fixture: callback VNPAY chỉ tra cứu backend,
 * hộp thư chỉ đổi trạng thái sau khi API xác nhận. DB/IPN/khóa đồng thời được kiểm tra riêng bằng smoke và PostgreSQL.
 */
test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
        sessionStorage.setItem(
            'nexus.session',
            JSON.stringify({
                accessToken: 'browser-test-token',
                refreshToken: 'browser-test-refresh',
                user: {
                    id: 'buyer-test',
                    fullName: 'Sprint Buyer',
                    email: 'buyer@example.test',
                    role: 'buyer',
                    avatar: '/avatar.svg',
                },
            }),
        )
    })
    // Layout có thể tải catalog khi điều hướng; không cho fixture rơi xuống backend đang chạy trên máy.
    await page.route(/\/api\/v1\/(products|categories)(?:\?.*)?$/, (route) =>
        route.fulfill({ json: { success: true, data: [], meta: { total: 0 } } }),
    )
})

// Query giả thành công không được thay kết quả PENDING; nút kiểm tra lại phải đọc SUCCESS từ API.
test('payment callback waits for backend confirmation before showing success', async ({ page }) => {
    let status = 'PENDING'
    await page.route('**/api/v1/payments/payment-test', (route) => {
        expect(route.request().headers().authorization).toBe('Bearer browser-test-token')
        return route.fulfill({
            json: {
                success: true,
                data: {
                    paymentId: 'payment-test',
                    orderId: 'order-test',
                    status,
                    amount: 50000,
                    currency: 'VND',
                },
            },
        })
    })
    await page.goto('/payment-result?vnp_TxnRef=payment-test&status=success&vnp_ResponseCode=00')
    await expect(
        page.getByRole('heading', { name: 'Waiting for payment confirmation' }),
    ).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Payment confirmed', exact: true })).toHaveCount(
        0,
    )
    status = 'SUCCESS'
    await page.getByRole('button', { name: 'Check again', exact: true }).click()
    await expect(
        page.getByRole('heading', { name: 'Payment confirmed', exact: true }),
    ).toBeVisible()
})

// Lỗi mạng và FAILED đều không được hiển thị thành công dù URL tự khai response code 00.
for (const outcome of ['FAILED', 'unavailable']) {
    test(`payment callback handles ${outcome} without trusting query success`, async ({ page }) => {
        await page.route('**/api/v1/payments/payment-test', (route) =>
            route.fulfill(
                outcome === 'unavailable'
                    ? { status: 503, json: { message: 'Payment unavailable' } }
                    : {
                          json: {
                              success: true,
                              data: {
                                  paymentId: 'payment-test',
                                  orderId: 'order-test',
                                  status: outcome,
                              },
                          },
                      },
            ),
        )
        await page.goto(
            '/payment-result?vnp_TxnRef=payment-test&status=success&vnp_ResponseCode=00',
        )
        if (outcome === 'FAILED')
            await expect(
                page.getByRole('heading', { name: 'Payment failed', exact: true }),
            ).toBeVisible()
        else await expect(page.getByRole('alert')).toContainText('Unable to verify payment')
        await expect(
            page.getByRole('heading', { name: 'Payment confirmed', exact: true }),
        ).toHaveCount(0)
    })
}

// Server từ chối ghi READ thì UI giữ unread; thử lại thành công phải tải lại số lượng chưa đọc từ API.
test('notification read failure preserves unread state and successful retry refreshes it', async ({
    page,
}) => {
    let read = false
    let rejectRead = true
    await page.route('**/api/v1/notifications**', async (route) => {
        expect(route.request().headers().authorization).toBe('Bearer browser-test-token')
        if (route.request().method() === 'POST') {
            expect(new URL(route.request().url()).pathname).toBe(
                '/api/v1/notifications/notification-test/read',
            )
            if (rejectRead)
                return route.fulfill({ status: 503, json: { message: 'Inbox unavailable' } })
            read = true
            return route.fulfill({ status: 204 })
        }
        return route.fulfill({
            json: {
                success: true,
                data: {
                    content: [
                        {
                            id: 'notification-test',
                            orderId: 'order-test',
                            type: 'ORDER_CREATED',
                            title: 'Order created',
                            content: 'Order ORD-TEST created',
                            read,
                            createdAt: '2026-10-30T00:00:00Z',
                        },
                    ],
                    totalElements: 1,
                    unreadCount: read ? 0 : 1,
                },
            },
        })
    })
    await page.goto('/notifications')
    await expect(page.getByText('1 chưa đọc · 1 thông báo', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Đánh dấu đã đọc', exact: true }).click()
    await expect(page.getByRole('alert')).toContainText('Inbox unavailable')
    await expect(page.getByText('1 chưa đọc · 1 thông báo', { exact: true })).toBeVisible()
    rejectRead = false
    await page.getByRole('button', { name: 'Đánh dấu đã đọc', exact: true }).click()
    await expect(page.getByText('0 chưa đọc · 1 thông báo', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Đánh dấu đã đọc', exact: true })).toHaveCount(0)
})
