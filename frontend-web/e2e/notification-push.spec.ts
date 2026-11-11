import { expect, test } from '@playwright/test'

/** Browser dùng cookie HttpOnly, subscribe kênh cá nhân và tải lại inbox REST khi có push; WS/REST là fixture. */
test('receives a winner push and refreshes the inbox without reading tokens', async ({
    page,
    context,
}) => {
    const user = {
        id: '00000000-0000-0000-0000-000000000021',
        fullName: 'Notification Buyer',
        email: 'notification@example.com',
        status: 'ACTIVE',
        roles: ['BUYER'],
    }
    let items = [
        {
            id: 'initial',
            orderId: null,
            type: 'ORDER_CREATED',
            title: 'Đơn đã tạo',
            content: 'Thông báo ban đầu',
            read: false,
            createdAt: '2026-11-11T10:00:00Z',
        },
    ]
    await context.addCookies([
        {
            name: 'nexus_access',
            value: 'test-http-only',
            url: 'http://localhost:18443',
            httpOnly: true,
            sameSite: 'Strict',
        },
    ])
    await page.route('**/api/v1/**', async (route) => {
        const path = new URL(route.request().url()).pathname
        await route.fulfill({
            json: {
                success: true,
                data:
                    path === '/api/v1/users/me'
                        ? user
                        : path === '/api/v1/notifications'
                          ? { content: items, totalElements: items.length, unreadCount: 1 }
                          : [],
            },
        })
    })
    let sendPush: (() => void) | undefined
    await page.routeWebSocket('**/ws/notifications', (socket) => {
        socket.onMessage((raw) => {
            const frame = raw.toString()
            if (frame.startsWith('CONNECT')) {
                expect(frame).not.toContain('Authorization')
                socket.send('CONNECTED\nversion:1.2\nheart-beat:10000,10000\n\n\0')
            } else if (frame.startsWith('SUBSCRIBE')) {
                expect(frame).toContain('destination:/user/queue/notifications')
                sendPush = () => {
                    items = [
                        {
                            ...items[0],
                            id: 'winner',
                            type: 'AUCTION_WON',
                            title: 'Bạn đã thắng phiên',
                            content: 'Đơn đấu giá mới đã lưu tại server',
                        },
                    ]
                    socket.send(
                        'MESSAGE\nsubscription:notifications\n\n' +
                            JSON.stringify({
                                id: 'push-winner',
                                title: items[0].title,
                                content: items[0].content,
                            }) +
                            '\0',
                    )
                }
            }
        })
    })
    await page.goto('/notifications')
    await expect(page.getByText('Thông báo ban đầu', { exact: true })).toBeVisible()
    await expect.poll(() => Boolean(sendPush)).toBe(true)
    sendPush!()
    await expect(page.getByText('Đơn đấu giá mới đã lưu tại server', { exact: true })).toBeVisible()
    expect(await page.evaluate(() => document.cookie)).not.toContain('nexus_access')
})
