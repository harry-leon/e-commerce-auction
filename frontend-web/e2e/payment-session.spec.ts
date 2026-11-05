import { test, expect } from '@playwright/test'

/** Khởi động chưa có cookie không được cản login; sau login, reload lấy lại user từ cookie mà không gửi Bearer từ JS. */
test('can log in normally after anonymous session restoration and reload using cookies', async ({
    page,
}) => {
    const user = {
        id: 'login-buyer',
        fullName: 'Login Buyer',
        email: 'buyer@example.test',
        roles: ['BUYER'],
        status: 'ACTIVE',
    }
    await page.route('**/api/v1/**', async (route) => {
        const request = route.request()
        const path = new URL(request.url()).pathname
        if (path === '/api/v1/auth/login') {
            return route.fulfill({
                headers: {
                    'set-cookie': 'nexus_access=test-login; HttpOnly; Path=/; SameSite=Strict',
                },
                json: { success: true, data: { user } },
            })
        }
        if (
            path === '/api/v1/auth/refresh' ||
            (path === '/api/v1/users/me' && !(await request.headerValue('cookie')))
        ) {
            return route.fulfill({ status: 401, json: { success: false } })
        }
        expect(await request.headerValue('authorization')).toBeNull()
        return route.fulfill({
            json: { success: true, data: path === '/api/v1/users/me' ? user : [] },
        })
    })
    await page.goto('/login')
    await page.getByLabel('Email', { exact: true }).fill(user.email)
    await page.getByLabel('Password', { exact: true }).fill('StrongPassword1!')
    await page.getByRole('button', { name: 'Login', exact: true }).click()
    await expect(page).toHaveURL('/home')
    await expect(page.getByText('Login Buyer', { exact: true }).first()).toBeVisible()
    await page.reload()
    await expect(page.getByText('Login Buyer', { exact: true }).first()).toBeVisible()
})

/** Hồi quy return từ cổng thanh toán: chỉ có cookie HttpOnly, không có profile trong sessionStorage của origin/tab mới.
 * API được chặn bằng fixture; không dùng thông tin giao dịch sandbox làm bằng chứng xác thực.
 */
for (const [status, title] of [
    ['SUCCESS', 'Payment confirmed'],
    ['FAILED', 'Payment failed'],
    ['CANCELLED', 'Payment failed'],
    ['PENDING', 'Waiting for payment confirmation'],
]) {
    test(`keeps the authenticated header after a ${status} payment return and reload`, async ({
        page,
        context,
    }) => {
        await context.addCookies([
            {
                name: 'nexus_access',
                value: 'test-cookie-only',
                url: 'http://localhost:18443',
                httpOnly: true,
                sameSite: 'Strict',
            },
        ])
        const requests: string[] = []
        await page.route('**/api/v1/**', async (route) => {
            const path = new URL(route.request().url()).pathname
            requests.push(path)
            expect(await route.request().headerValue('cookie')).toContain(
                'nexus_access=test-cookie-only',
            )
            const data =
                path === '/api/v1/users/me'
                    ? {
                          id: 'buyer-1',
                          fullName: 'Payment Buyer',
                          email: 'buyer@example.test',
                          roles: ['BUYER'],
                          status: 'ACTIVE',
                      }
                    : path === '/api/v1/payments/payment-1'
                      ? {
                            paymentId: 'payment-1',
                            orderId: 'order-1',
                            status,
                            amount: 50000,
                            currency: 'VND',
                        }
                      : []
            await route.fulfill({ json: { success: true, data } })
        })
        // Điều hướng thật từ site khác để kiểm tra cookie Strict sau khi trang return đã tải xong.
        await page.route('https://payment-provider.test/**', (route) =>
            route.fulfill({
                contentType: 'text/html',
                body: '<a href="http://localhost:18443/payment-result?vnp_TxnRef=payment-1">Return to Nexus</a>',
            }),
        )
        await page.goto('https://payment-provider.test/return')
        await page.getByRole('link', { name: 'Return to Nexus' }).click()
        await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible()
        await expect(page.getByText('Payment Buyer', { exact: true }).first()).toBeVisible()
        await page.reload()
        await expect(page.getByText('Payment Buyer', { exact: true }).first()).toBeVisible()
        expect(requests).not.toContain('/api/v1/auth/logout')
        expect(await page.evaluate(() => localStorage.getItem('nexus.session'))).toBeNull()
        expect(await page.evaluate(() => sessionStorage.getItem('nexus.session'))).not.toContain(
            'test-cookie-only',
        )
    })
}
