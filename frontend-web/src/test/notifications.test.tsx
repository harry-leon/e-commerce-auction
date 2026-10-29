import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { NotificationInbox } from '../features/buyer/NotificationInbox'
import { getNotifications, readNotification, type Inbox } from '../api/notifications'
import type { AppState } from '../types'

const holder = vi.hoisted(() => ({ current: {} as AppState }))
vi.mock('../context', () => ({ useApp: () => holder.current }))
vi.mock('../components/Layout', () => ({
    BuyerLayout: ({ children }: { children: React.ReactNode }) => children,
}))
vi.mock('../api/notifications', () => ({ getNotifications: vi.fn(), readNotification: vi.fn() }))
const inbox: Inbox = {
    content: [
        {
            id: 'notice-1',
            orderId: 'order-1',
            type: 'PAYMENT_SUCCESS',
            title: 'Thanh toán thành công',
            content: 'Đơn ORD-DEMO • 50000.00 VND',
            read: false,
            createdAt: '2026-10-29T01:02:03Z',
        },
    ],
    totalElements: 1,
    unreadCount: 1,
}

beforeEach(() => {
    vi.resetAllMocks()
    holder.current = { user: { id: 'buyer-1' }, navigate: vi.fn() } as unknown as AppState
})

/** Nội dung/số tiền lấy API; ghi READ thành công mới tải lại, đọc tất cả vẫn đi qua backend. */
it('loads real notifications and persists individual and bulk reads', async () => {
    vi.mocked(getNotifications).mockResolvedValue(inbox)
    vi.mocked(readNotification).mockResolvedValue()
    render(<NotificationInbox />)
    expect(await screen.findByText(inbox.content[0].content)).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Đánh dấu đã đọc' }))
    await waitFor(() => expect(getNotifications).toHaveBeenCalledTimes(2))
    expect(readNotification).toHaveBeenCalledWith('notice-1')
    fireEvent.click(screen.getByRole('button', { name: 'Đánh dấu tất cả đã đọc' }))
    await waitFor(() => expect(readNotification).toHaveBeenCalledWith(undefined))
})

/** Lỗi tải/đánh dấu được hiển thị, không tạo mock; retry tải lại vẫn thực hiện HTTP mới. */
it('shows failures and supports retry without fake success', async () => {
    vi.mocked(getNotifications)
        .mockRejectedValueOnce(new Error('Service unavailable'))
        .mockResolvedValue(inbox)
    vi.mocked(readNotification).mockRejectedValue(new Error('Read failed'))
    render(<NotificationInbox />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Service unavailable')
    fireEvent.click(screen.getByRole('button', { name: 'Tải lại' }))
    await screen.findByText(inbox.content[0].content)
    fireEvent.click(screen.getByRole('button', { name: 'Đánh dấu đã đọc' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Read failed')
    expect(screen.getByRole('button', { name: 'Đánh dấu đã đọc' })).toBeVisible()
})

/** Chuyển tài khoản hủy request và xóa inbox cũ; guest không gọi API, không lộ tin của phiên trước. */
it('clears previous account data and aborts stale requests on logout', async () => {
    vi.mocked(getNotifications).mockResolvedValue(inbox)
    const view = render(<NotificationInbox />)
    await screen.findByText(inbox.content[0].content)
    const signal = vi.mocked(getNotifications).mock.calls[0][1]
    holder.current = { user: null, navigate: vi.fn() } as unknown as AppState
    view.rerender(<NotificationInbox />)
    expect(signal?.aborted).toBe(true)
    expect(screen.queryByText(inbox.content[0].content)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Đăng nhập để xem thông báo' }))
    expect(holder.current.navigate).toHaveBeenCalledWith('login')
})

/** Phân trang gọi đúng page, trạng thái rỗng không hiển thị các bản ghi từ trang trước. */
it('paginates and shows an empty inbox', async () => {
    vi.mocked(getNotifications)
        .mockResolvedValueOnce({ ...inbox, totalElements: 21 })
        .mockResolvedValue({ content: [], totalElements: 21, unreadCount: 0 })
    render(<NotificationInbox />)
    await screen.findByText(inbox.content[0].content)
    fireEvent.click(screen.getByRole('button', { name: 'Trang sau' }))
    await screen.findByText('Chưa có thông báo trên trang này.')
    expect(getNotifications).toHaveBeenLastCalledWith(1, expect.any(AbortSignal))
    fireEvent.click(screen.getByRole('button', { name: 'Trang trước' }))
    await waitFor(() =>
        expect(getNotifications).toHaveBeenLastCalledWith(0, expect.any(AbortSignal)),
    )
})
