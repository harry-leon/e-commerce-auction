/**
 * Bộ kiểm thử tích hợp Reputation, Rating và Notification UI (Sprint 3 - HA-S3-2).
 * 
 * 1. Chức năng:
 *    - AC 1 & 8: Buyer đánh giá Seller sau khi giao dịch hoàn tất (DELIVERED/COMPLETED).
 *    - AC 2 & 8: Seller đánh giá Buyer sau khi giao dịch hoàn tất.
 *    - AC 3: Hiển thị form đánh giá và gửi rating (1..5 sao + comment max 1000 ký tự).
 *    - AC 4: Hiển thị điểm uy tín và trust level badge (VIP, HIGH, STANDARD, LOW).
 *    - AC 5: Hiển thị lịch sử đánh giá liên quan đến tài khoản.
 *    - AC 6 & 7: Kiểm tra điều kiện đánh giá và chặn tự đánh giá chính mình.
 *    - AC 9 & 10: Notification Inbox hiển thị danh sách, trạng thái đã đọc/chưa đọc, đánh dấu đọc.
 *    - AC 11: Quản lý tùy chọn nhận thông báo (Notification Preferences: IN_APP, EMAIL, PUSH).
 *    - AC 12 & 13: Xử lý quyền push notification trên trình duyệt.
 *    - AC 14: Xử lý các trạng thái loading, empty, error state.
 * 
 * 2. Đầu vào & kết quả:
 *    - Mô phỏng các API client (reputationApi, notificationsApi) và component React.
 *    - Kiểm tra DOM rendering, event triggers, API calls, and state transitions.
 * 
 * 3. Luồng xử lý:
 *    - Render component -> Tương tác người dùng (click, type) -> Khẳng định API được gọi với đúng payload.
 * 
 * 4. Quy tắc nghiệp vụ:
 *    - Điểm < 60: LOW, 60-109: STANDARD, 110-149: HIGH, >= 150: VIP.
 *    - Không được tự đánh giá (raterId !== rateeId).
 *    - Phải chọn ít nhất 1 sao trước khi gửi.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import {
    TrustLevelBadge,
    RatingStars,
    RatingModal,
    RatingsList,
} from '../components/ReputationRating'
import {
    calculateTrustLevel,
    reputationApi,
    type RatingResponse,
} from '../api/reputation'
import * as notifApi from '../api/notifications'
import { NotificationInbox } from '../features/buyer/NotificationInbox'
import type { AppState } from '../types'

// Mock App Context
const holder = vi.hoisted(() => ({ current: {} as AppState }))
vi.mock('../context', () => ({
    useApp: () => holder.current,
}))

// Mock Layout
vi.mock('../components/Layout', () => ({
    BuyerLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}))

describe('Sprint 3 - HA-S3-2: Reputation, Rating and Notification UI', () => {
    beforeEach(() => {
        vi.restoreAllMocks()
        holder.current = {
            user: { id: 'user-buyer-1', name: 'Nguyễn Văn Buyer', role: 'BUYER' },
            lang: 'vi',
            showToast: vi.fn(),
            navigate: vi.fn(),
        } as unknown as AppState
    })

    // ─── AC 4: Trust Level và Reputation Score ──────────────────────────────────────────
    describe('AC 4: Trust Level & Reputation Calculation', () => {
        it('tính toán chính xác cấp độ uy tín theo mốc điểm nghiệp vụ', () => {
            expect(calculateTrustLevel(0)).toBe('LOW')
            expect(calculateTrustLevel(59)).toBe('LOW')
            expect(calculateTrustLevel(60)).toBe('STANDARD')
            expect(calculateTrustLevel(109)).toBe('STANDARD')
            expect(calculateTrustLevel(110)).toBe('HIGH')
            expect(calculateTrustLevel(149)).toBe('HIGH')
            expect(calculateTrustLevel(150)).toBe('VIP')
            expect(calculateTrustLevel(200)).toBe('VIP')
        })

        it('hiển thị đầy đủ huy hiệu TrustLevelBadge theo từng cấp độ và điểm số', () => {
            const { rerender } = render(<TrustLevelBadge level="VIP" score={165} />)
            expect(screen.getByText('VIP Trust')).toBeInTheDocument()
            expect(screen.getByText('(165)')).toBeInTheDocument()

            rerender(<TrustLevelBadge level="HIGH" score={120} />)
            expect(screen.getByText('High Trust')).toBeInTheDocument()
            expect(screen.getByText('(120)')).toBeInTheDocument()

            rerender(<TrustLevelBadge level="STANDARD" score={80} />)
            expect(screen.getByText('Standard')).toBeInTheDocument()
            expect(screen.getByText('(80)')).toBeInTheDocument()

            rerender(<TrustLevelBadge level="LOW" score={45} />)
            expect(screen.getByText('Low Trust')).toBeInTheDocument()
            expect(screen.getByText('(45)')).toBeInTheDocument()
        })
    })

    // ─── AC 3, 6, 7, 8: Biểu mẫu Rating và Điều kiện gửi đánh giá ──────────────────────
    describe('AC 3, 6, 7, 8: Rating Form & Submission', () => {
        it('hiển thị số sao RatingStars và cho phép chọn số sao 1..5', () => {
            const handleRatingChange = vi.fn()
            render(<RatingStars value={3} onChange={handleRatingChange} />)

            const starButtons = screen.getAllByRole('button')
            expect(starButtons).toHaveLength(5)

            // Click vào ngôi sao thứ 5
            fireEvent.click(starButtons[4])
            expect(handleRatingChange).toHaveBeenCalledWith(5)
        })

        it('chặn tự đánh giá chính mình (AC 7: raterId === rateeId)', () => {
            render(
                <RatingModal
                    isOpen={true}
                    onClose={vi.fn()}
                    orderId="order-101"
                    rateeId="user-buyer-1" // Trùng với user.id hiện tại
                    rateeName="Chính Mình"
                    role="buyer"
                />,
            )

            expect(
                screen.getByText(/Hệ thống không cho phép bạn tự đánh giá chính tài khoản của mình/i),
            ).toBeInTheDocument()
            expect(
                screen.getByRole('button', { name: 'Đóng' }),
            ).toBeInTheDocument()
        })

        it('hiển thị form đánh giá và gửi dữ liệu thành công lên API (AC 1, 2, 3, 8)', async () => {
            const createRatingSpy = vi
                .spyOn(reputationApi, 'createRating')
                .mockResolvedValueOnce({
                    id: 'rating-1',
                    orderId: 'order-101',
                    raterId: 'user-buyer-1',
                    rateeId: 'user-seller-2',
                    score: 5,
                    comment: 'Hàng chất lượng, giao nhanh!',
                    isAggregated: false,
                    isDisputed: false,
                    createdAt: '2026-11-13T10:00:00Z',
                })

            const onCloseMock = vi.fn()
            const onSuccessMock = vi.fn()

            render(
                <RatingModal
                    isOpen={true}
                    onClose={onCloseMock}
                    orderId="order-101"
                    rateeId="user-seller-2"
                    rateeName="Shop Đồ Gỗ Cao Cấp"
                    role="buyer"
                    onSubmitSuccess={onSuccessMock}
                />,
            )

            expect(
                screen.getByText(/Đánh giá người bán: Shop Đồ Gỗ Cao Cấp/i),
            ).toBeInTheDocument()

            // Chọn 5 sao
            const starButtons = screen.getAllByRole('button', { name: /sao/i })
            fireEvent.click(starButtons[4])

            // Nhập nhận xét
            const commentInput = screen.getByPlaceholderText(/Chia sẻ trải nghiệm/i)
            fireEvent.change(commentInput, {
                target: { value: 'Hàng chất lượng, giao nhanh!' },
            })

            // Nhấn gửi đánh giá
            const submitBtn = screen.getByRole('button', { name: 'Gửi đánh giá' })
            fireEvent.click(submitBtn)

            await waitFor(() => {
                expect(createRatingSpy).toHaveBeenCalledWith({
                    orderId: 'order-101',
                    rateeId: 'user-seller-2',
                    score: 5,
                    comment: 'Hàng chất lượng, giao nhanh!',
                })
                expect(onSuccessMock).toHaveBeenCalled()
                expect(onCloseMock).toHaveBeenCalled()
            })
        })

        it('xử lý lỗi khi API trả về lỗi trùng lặp đánh giá hoặc không hợp lệ (AC 6, 14)', async () => {
            vi.spyOn(reputationApi, 'createRating').mockRejectedValueOnce(
                new Error('Đơn hàng này đã được đánh giá trước đó.'),
            )

            render(
                <RatingModal
                    isOpen={true}
                    onClose={vi.fn()}
                    orderId="order-101"
                    rateeId="user-seller-2"
                    rateeName="Shop Công Nghệ"
                    role="buyer"
                />,
            )

            // Chọn 4 sao
            const starButtons = screen.getAllByRole('button', { name: /sao/i })
            fireEvent.click(starButtons[3])

            // Bấm gửi
            fireEvent.click(screen.getByRole('button', { name: 'Gửi đánh giá' }))

            await waitFor(() => {
                expect(
                    screen.getByText('Đơn hàng này đã được đánh giá trước đó.'),
                ).toBeInTheDocument()
            })
        })
    })

    // ─── AC 5 & 14: Lịch sử đánh giá (RatingsList) ──────────────────────────────────────
    describe('AC 5 & 14: Ratings History & States', () => {
        it('hiển thị danh sách lịch sử đánh giá nhận được', () => {
            const mockRatings: RatingResponse[] = [
                {
                    id: 'r-1',
                    orderId: 'ord-1',
                    raterId: 'user-x',
                    raterName: 'Khách Hàng A',
                    rateeId: 'user-buyer-1',
                    score: 5,
                    comment: 'Rất nhiệt tình và uy tín',
                    isAggregated: true,
                    isDisputed: false,
                    createdAt: '2026-11-10T12:00:00Z',
                },
                {
                    id: 'r-2',
                    orderId: 'ord-2',
                    raterId: 'user-y',
                    raterName: 'Khách Hàng B',
                    rateeId: 'user-buyer-1',
                    score: 4,
                    comment: '',
                    isAggregated: true,
                    isDisputed: false,
                    createdAt: '2026-11-11T14:30:00Z',
                },
            ]

            render(<RatingsList ratings={mockRatings} />)

            expect(screen.getByText(/Khách Hàng A/)).toBeInTheDocument()
            expect(screen.getByText('Rất nhiệt tình và uy tín')).toBeInTheDocument()
            expect(screen.getByText(/Khách Hàng B/)).toBeInTheDocument()
            expect(screen.getByText('(Không có nhận xét)')).toBeInTheDocument()
        })

        it('hiển thị trạng thái tải và trạng thái rỗng khi chưa có đánh giá', () => {
            const { rerender } = render(<RatingsList ratings={[]} loading={true} />)
            expect(screen.getByText(/Đang tải lịch sử đánh giá/i)).toBeInTheDocument()

            rerender(<RatingsList ratings={[]} loading={false} />)
            expect(screen.getByText(/Chưa có đánh giá nào/i)).toBeInTheDocument()
        })
    })

    // ─── AC 9, 10, 11, 12, 13: Notification Inbox, Preferences, Push Notification ──────
    describe('AC 9, 10, 11, 12, 13: Notification Features', () => {
        it('hiển thị hộp thư thông báo, phân biệt đã đọc/chưa đọc và lọc theo tab (AC 9, 10)', async () => {
            const mockInbox: notifApi.Inbox = {
                content: [
                    {
                        id: 'notif-1',
                        orderId: 'ord-101',
                        type: 'ORDER_DELIVERED',
                        title: 'Đơn hàng đã giao thành công',
                        content: 'Kiện hàng ORD-101 đã được giao tới bạn.',
                        read: false,
                        createdAt: '2026-11-13T08:00:00Z',
                    },
                    {
                        id: 'notif-2',
                        orderId: 'ord-100',
                        type: 'PAYMENT_SUCCESS',
                        title: 'Thanh toán hoàn tất',
                        content: 'Giao dịch 250,000 VND thành công.',
                        read: true,
                        createdAt: '2026-11-12T09:00:00Z',
                    },
                ],
                totalElements: 2,
                unreadCount: 1,
            }

            vi.spyOn(notifApi, 'getNotifications').mockResolvedValue(mockInbox)
            vi.spyOn(notifApi, 'getPushPermissionStatus').mockReturnValue('granted')

            render(<NotificationInbox />)

            // Kiểm tra các thông báo được hiển thị
            expect(
                await screen.findByText('Đơn hàng đã giao thành công'),
            ).toBeInTheDocument()
            expect(screen.getByText('Thanh toán hoàn tất')).toBeInTheDocument()

            // Kiểm tra nút đánh dấu đọc cho tin chưa đọc
            expect(screen.getByRole('button', { name: 'Đánh dấu đã đọc' })).toBeInTheDocument()

            // Chuyển sang tab "Chưa đọc"
            fireEvent.click(screen.getByRole('button', { name: /Chưa đọc \(1\)/i }))
            expect(screen.getByText('Đơn hàng đã giao thành công')).toBeInTheDocument()
            expect(screen.queryByText('Thanh toán hoàn tất')).not.toBeInTheDocument()
        })

        it('mở modal cài đặt thông báo và lưu tùy chọn preferences thành công (AC 11)', async () => {
            const mockPreferences: notifApi.NotificationPreference[] = [
                { notificationType: 'ORDER', channel: 'IN_APP', enabled: true },
                { notificationType: 'ORDER', channel: 'EMAIL', enabled: false },
                { notificationType: 'ORDER', channel: 'PUSH', enabled: true },
            ]

            vi.spyOn(notifApi, 'getNotifications').mockResolvedValue({
                content: [],
                totalElements: 0,
                unreadCount: 0,
            })
            vi.spyOn(notifApi, 'getNotificationPreferences').mockResolvedValue(mockPreferences)
            const updatePrefSpy = vi
                .spyOn(notifApi, 'updateNotificationPreferences')
                .mockResolvedValue(mockPreferences)
            vi.spyOn(notifApi, 'getPushPermissionStatus').mockReturnValue('granted')

            render(<NotificationInbox />)

            // Mở modal Preferences
            const prefButton = await screen.findByRole('button', { name: /Cài đặt thông báo/i })
            fireEvent.click(prefButton)

            // Kiểm tra modal xuất hiện
            expect(
                await screen.findByText('Cài đặt Tùy chọn Nhận thông báo'),
            ).toBeInTheDocument()

            // Thay đổi checkbox email của mục Đơn hàng & Giao hàng
            const emailCheckbox = screen.getByLabelText(/Đơn hàng & Giao hàng email/i)
            fireEvent.click(emailCheckbox)

            // Nhấn lưu tùy chọn
            const saveBtn = screen.getByRole('button', { name: 'Lưu tùy chọn' })
            fireEvent.click(saveBtn)

            await waitFor(() => {
                expect(updatePrefSpy).toHaveBeenCalled()
            })
        })

        it('hiển thị banner xin cấp quyền push notification và kích hoạt requestPermission (AC 12, 13)', async () => {
            vi.spyOn(notifApi, 'getNotifications').mockResolvedValue({
                content: [],
                totalElements: 0,
                unreadCount: 0,
            })
            vi.spyOn(notifApi, 'getPushPermissionStatus').mockReturnValue('default')
            const requestPermissionSpy = vi
                .spyOn(notifApi, 'requestPushPermission')
                .mockResolvedValue('granted')

            render(<NotificationInbox />)

            // Banner phải xuất hiện khi quyền chưa được cấp
            expect(
                await screen.findByText(/Nhận thông báo đơn hàng & đấu giá tức thì/i),
            ).toBeInTheDocument()

            // Click nút Bật thông báo
            const enablePushBtn = screen.getByRole('button', { name: /Bật thông báo/i })
            fireEvent.click(enablePushBtn)

            await waitFor(() => {
                expect(requestPermissionSpy).toHaveBeenCalled()
            })
        })
    })
})
