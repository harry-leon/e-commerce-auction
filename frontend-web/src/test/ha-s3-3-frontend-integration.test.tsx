/**
 * Bộ kiểm thử tích hợp toàn diện Frontend & Backend Integration (Sprint 3 - HA-S3-3).
 *
 * 1. Chức năng:
 *    - AC 1: Kiểm thử giao diện Shipping Options và phí vận chuyển phản ứng theo giỏ hàng (GHN, GHTK, Freeship).
 *    - AC 2: Kiểm thử tracking và cập nhật trạng thái giao hàng thời gian thực trên OrderDetailPage và SellerOrdersPage.
 *    - AC 3: Kiểm thử chức năng đánh giá hai chiều giữa Buyer và Seller sau khi đơn hoàn tất (DELIVERED/COMPLETED).
 *    - AC 4: Kiểm thử hiển thị Reputation, Trust Level (VIP, HIGH, STANDARD, LOW) và điều kiện tham gia đấu giá.
 *    - AC 5: Kiểm thử Notification Inbox (đã đọc/chưa đọc, lọc tab) và tùy chọn kênh thông báo (Preferences).
 *    - AC 6: Kiểm tra phân quyền truy cập giữa Buyer và Seller, chặn truy cập trái phép và chặn tự đánh giá chính mình.
 *    - AC 7: Xử lý ngoại lệ khi shipping provider timeout hoặc API trả về lỗi 503, hỗ trợ cơ chế fallback và nút thử lại.
 *    - AC 8 & 9: Kiểm thử reconnect WebSocket khi kết nối bị gián đoạn và tự động đồng bộ dữ liệu giao diện khi kết nối lại.
 *    - AC 10: Kiểm tra toàn diện các trạng thái loading, empty và error state trên các thành phần UI.
 *    - AC 11 & 12: Đảm bảo độ ổn định tích hợp cho buổi demo Sprint 3.
 *
 * 2. Đầu vào & kết quả:
 *    - Mô phỏng các API client (shippingApi, reputationApi, notificationsApi, ordersApi).
 *    - Giả lập AppContext với các vai trò BUYER và SELLER, ngôn ngữ 'vi'.
 *    - Đảm bảo component render đúng DOM, phát sinh đúng API call và cập nhật state chính xác.
 *
 * 3. Luồng xử lý:
 *    - Khởi tạo mock context -> Render component -> Mô phỏng tương tác người dùng (click, input, select) -> Khẳng định kết quả.
 *
 * 4. Quy tắc nghiệp vụ:
 *    - Freeship tự động áp dụng khi giá trị đơn hàng vượt ngưỡng quy định.
 *    - Cấm tự đánh giá bản thân (raterId !== rateeId).
 *    - Buyer không được phép truy cập vào các URL quản trị Seller (`seller-*`).
 *    - WebSocket ngắt kết nối sẽ thử lại và tự động nạp lại danh sách thông báo khi kết nối thành công.
 *
 * 5. Lỗi & giới hạn:
 *    - Bắt lỗi timeout hoặc lỗi kết nối, hiển thị banner thông báo thân thiện và nút Thử lại.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import React from 'react'

// Component & Page imports
import { AppRouter } from '../routes/AppRouter'
import { OrderDetailPage, CheckoutPage, MyReputationPage } from '../features/buyer/BuyerPages'
import { SellerOrdersPage } from '../features/seller/SellerPages'
import { NotificationInbox } from '../features/buyer/NotificationInbox'
import { TrustLevelBadge, RatingModal, RatingsList } from '../components/ReputationRating'

// API & Stream imports
import { shippingApi, type ShippingQuoteResponse, type ShipmentResponse } from '../api/shipping'
import { reputationApi, calculateTrustLevel, type ReputationSummary, type RatingResponse } from '../api/reputation'
import * as notifApi from '../api/notifications'
import { NOTIFICATIONS_CHANGED } from '../api/notificationStream'
import { ordersApi } from '../api/orders'
import type { AppState, Order } from '../types'

// Mock context app holder
const contextHolder = vi.hoisted(() => ({
    current: {
        page: 'home',
        routeParams: {},
        pageParams: {},
        user: { id: 'buyer-user-01', name: 'Nguyễn Văn Mua', role: 'buyer' },
        lang: 'vi',
        cartCount: 2,
        showToast: vi.fn(),
        navigate: vi.fn(),
        dismissToast: vi.fn(),
        toasts: [],
    } as unknown as AppState,
}))

vi.mock('../context', () => ({
    useApp: () => contextHolder.current,
}))

// Mock Layout components
vi.mock('../components/Layout', () => ({
    BuyerLayout: ({ children }: { children: React.ReactNode }) => <div data-testid="buyer-layout">{children}</div>,
    SellerLayout: ({ children }: { children: React.ReactNode }) => <div data-testid="seller-layout">{children}</div>,
    AdminLayout: ({ children }: { children: React.ReactNode }) => <div data-testid="admin-layout">{children}</div>,
    CATEGORY_ICONS: {},
}))

describe('[HA-S3-3] Frontend Integration & UI Issues Test Suite', () => {
    beforeEach(() => {
        vi.restoreAllMocks()
        contextHolder.current = {
            page: 'home',
            routeParams: {},
            pageParams: {},
            user: { id: 'buyer-user-01', name: 'Nguyễn Văn Mua', role: 'buyer' },
            lang: 'vi',
            cartCount: 2,
            showToast: vi.fn(),
            navigate: vi.fn(),
            dismissToast: vi.fn(),
            toasts: [],
        } as unknown as AppState
    })

    // ─── AC 1: Shipping Options & Dynamic Fee Calculation ──────────────────────────
    describe('AC 1: Shipping Options & Phí vận chuyển linh hoạt', () => {
        it('hiển thị đầy đủ danh sách phương thức vận chuyển và tính toán lại cước phí phản ứng theo đơn hàng', async () => {
            const mockQuotes: ShippingQuoteResponse = {
                orderTotal: 300000,
                freeShippingThreshold: 500000,
                freeShippingApplied: false,
                defaultCarrier: 'GHN',
                options: [
                    {
                        carrier: 'GHN',
                        serviceType: 'EXPRESS',
                        shippingFee: 55000,
                        freeShippingApplied: false,
                        estimatedDeliveryDays: 2,
                    },
                    {
                        carrier: 'GHTK',
                        serviceType: 'STANDARD',
                        shippingFee: 35000,
                        freeShippingApplied: false,
                        estimatedDeliveryDays: 4,
                    },
                ],
            }

            vi.spyOn(shippingApi, 'getShippingQuotes').mockResolvedValue(mockQuotes)

            render(<CheckoutPage />)

            // Điền thông tin giao hàng ở bước 1 để chuyển sang bước 2
            const nameInput = screen.getByLabelText(/Họ và tên/i)
            const phoneInput = screen.getByLabelText(/Số điện thoại/i)
            const addressInput = screen.getByLabelText(/Địa chỉ nhận hàng/i)

            fireEvent.change(nameInput, { target: { value: 'Nguyễn Văn Mua' } })
            fireEvent.change(phoneInput, { target: { value: '0901234567' } })
            fireEvent.change(addressInput, { target: { value: '123 Đường Lê Lợi' } })

            // Bấm tiếp tục sang bước 2
            const nextButton = screen.getByRole('button', { name: /Tiếp tục/i })
            fireEvent.click(nextButton)

            // Đợi danh sách hãng vận chuyển hiển thị
            expect(await screen.findByText('GHN Express')).toBeInTheDocument()
            expect(screen.getByText('GHTK Standard')).toBeInTheDocument()
            expect(screen.getByText('55.000 ₫')).toBeInTheDocument()
            expect(screen.getByText('35.000 ₫')).toBeInTheDocument()
        })

        it('áp dụng huy hiệu miễn phí vận chuyển khi tổng đơn vượt ngưỡng freeShippingThreshold', async () => {
            const mockFreeQuotes: ShippingQuoteResponse = {
                orderTotal: 650000,
                freeShippingThreshold: 500000,
                freeShippingApplied: true,
                defaultCarrier: 'GHN',
                options: [
                    {
                        carrier: 'GHN',
                        serviceType: 'EXPRESS',
                        shippingFee: 0,
                        freeShippingApplied: true,
                        estimatedDeliveryDays: 2,
                    },
                ],
            }

            vi.spyOn(shippingApi, 'getShippingQuotes').mockResolvedValue(mockFreeQuotes)

            render(<CheckoutPage />)

            const nameInput = screen.getByLabelText(/Họ và tên/i)
            const phoneInput = screen.getByLabelText(/Số điện thoại/i)
            const addressInput = screen.getByLabelText(/Địa chỉ nhận hàng/i)

            fireEvent.change(nameInput, { target: { value: 'Nguyễn Văn Mua' } })
            fireEvent.change(phoneInput, { target: { value: '0901234567' } })
            fireEvent.change(addressInput, { target: { value: '456 Nguyễn Huệ' } })

            fireEvent.click(screen.getByRole('button', { name: /Tiếp tục/i }))

            expect(await screen.findByText('Miễn phí vận chuyển')).toBeInTheDocument()
            expect(screen.getByText('Miễn phí')).toBeInTheDocument()
        })
    })

    // ─── AC 2: Tracking và Cập nhật trạng thái giao hàng ────────────────────────────
    describe('AC 2: Tracking & Cập nhật trạng thái vận đơn thời gian thực', () => {
        it('hiển thị chi tiết hãng vận chuyển, mã vận đơn, timeline trạng thái trên OrderDetailPage', async () => {
            contextHolder.current.pageParams = { id: 'ORD-TEST-001' }

            const mockOrder: Order = {
                id: 'ORD-TEST-001',
                buyerId: 'buyer-user-01',
                buyerName: 'Nguyễn Văn Mua',
                sellerId: 'seller-user-02',
                sellerName: 'Shop Công Nghệ',
                items: [
                    {
                        productId: 'prod-1',
                        productName: 'Bàn phím cơ không dây',
                        image: 'https://placehold.co/100',
                        quantity: 1,
                        unitPrice: 1200000,
                        subtotal: 1200000,
                    },
                ],
                subtotal: 1200000,
                shipping: 35000,
                tax: 0,
                discount: 0,
                total: 1235000,
                status: 'SHIPPED',
                shippingAddress: '123 Lê Lợi, Phường Bến Nghé, Quận 1, TP.HCM',
                createdAt: '2026-11-12T10:00:00Z',
                updatedAt: '2026-11-13T08:00:00Z',
                reviewed: false,
                paymentStatus: 'paid',
                paymentMethod: 'VNPAY',
            }

            const mockShipment: ShipmentResponse = {
                id: 'ship-101',
                orderId: 'ORD-TEST-001',
                carrier: 'GHN',
                serviceType: 'EXPRESS',
                shippingFee: 35000,
                freeShippingApplied: false,
                trackingNumber: 'GHN-VN-987654321',
                status: 'IN_TRANSIT',
                estimatedDeliveryDays: 2,
                shippingAddress: '123 Lê Lợi, Q1, TP.HCM',
            }

            vi.spyOn(ordersApi, 'getOrderById').mockResolvedValue(mockOrder)
            vi.spyOn(shippingApi, 'getShipmentByOrderId').mockResolvedValue(mockShipment)
            vi.spyOn(shippingApi, 'getShipmentTracking').mockResolvedValue({
                shipmentId: 'ship-101',
                trackingNumber: 'GHN-VN-987654321',
                currentStatus: 'IN_TRANSIT',
                carrier: 'GHN',
                history: [
                    {
                        carrierStatus: 'Đang luân chuyển qua kho trung chuyển',
                        internalStatus: 'IN_TRANSIT',
                        occurredAt: '2026-11-13T08:00:00Z',
                        location: 'Kho Tân Bình - TP.HCM',
                        description: 'Kiện hàng đang được luân chuyển tới bưu cục phát',
                    },
                    {
                        carrierStatus: 'Lấy hàng thành công',
                        internalStatus: 'PICKED_UP',
                        occurredAt: '2026-11-12T15:00:00Z',
                        location: 'Kho Người bán - Q7',
                        description: 'Shipper đã lấy kiện hàng từ shop',
                    },
                ],
            })

            render(<OrderDetailPage />)

            // Kiểm tra hiển thị thông tin vận chuyển
            expect(await screen.findByText('GHN-VN-987654321')).toBeInTheDocument()
            expect(screen.getByText('IN_TRANSIT')).toBeInTheDocument()
            expect(screen.getByText('Kiện hàng đang được luân chuyển tới bưu cục phát')).toBeInTheDocument()
            expect(screen.getByText('Shipper đã lấy kiện hàng từ shop')).toBeInTheDocument()
        })
    })

    // ─── AC 3: Đánh giá hai chiều Buyer và Seller ─────────────────────────────────
    describe('AC 3: Đánh giá Buyer / Seller sau giao dịch', () => {
        it('Buyer đánh giá Seller thành công khi đơn ở trạng thái DELIVERED', async () => {
            contextHolder.current.pageParams = { id: 'ORD-DELIVERED-001' }

            const mockDeliveredOrder: Order = {
                id: 'ORD-DELIVERED-001',
                buyerId: 'buyer-user-01',
                buyerName: 'Nguyễn Văn Mua',
                sellerId: 'seller-user-02',
                sellerName: 'Shop Đồ Gia Dụng',
                items: [
                    {
                        productId: 'prod-2',
                        productName: 'Nồi chiên không dầu',
                        image: 'https://placehold.co/100',
                        quantity: 1,
                        unitPrice: 850000,
                        subtotal: 850000,
                    },
                ],
                subtotal: 850000,
                shipping: 0,
                tax: 0,
                discount: 0,
                total: 850000,
                status: 'DELIVERED',
                shippingAddress: '456 Hai Bà Trưng, Q3, TP.HCM',
                createdAt: '2026-11-10T10:00:00Z',
                updatedAt: '2026-11-13T09:00:00Z',
                reviewed: false,
                paymentStatus: 'paid',
                paymentMethod: 'VNPAY',
            }

            vi.spyOn(ordersApi, 'getOrderById').mockResolvedValue(mockDeliveredOrder)
            vi.spyOn(shippingApi, 'getShipmentByOrderId').mockResolvedValue(null)
            vi.spyOn(reputationApi, 'getUserReputation').mockResolvedValue({
                userId: 'seller-user-02',
                score: 135,
                trustLevel: 'HIGH',
                totalTx: 50,
                successTx: 49,
                failedTx: 1,
                auctionWon: 0,
                auctionFailed: 0,
                avgRating: 4.9,
                totalRatings: 30,
            })
            vi.spyOn(reputationApi, 'getRatingsForOrder').mockResolvedValue([])

            const createRatingSpy = vi.spyOn(reputationApi, 'createRating').mockResolvedValue({
                id: 'rate-new-1',
                orderId: 'ORD-DELIVERED-001',
                raterId: 'buyer-user-01',
                rateeId: 'seller-user-02',
                score: 5,
                comment: 'Sản phẩm đóng gói cẩn thận, dùng rất thích!',
                createdAt: '2026-11-13T10:00:00Z',
            })

            render(<OrderDetailPage />)

            // Nút để lại đánh giá xuất hiện khi đơn hàng đã giao
            const leaveReviewBtn = await screen.findByRole('button', { name: /Đánh giá/i })
            fireEvent.click(leaveReviewBtn)

            // Nhập nhận xét
            const commentInput = screen.getByPlaceholderText(/Chia sẻ trải nghiệm của bạn/i)
            fireEvent.change(commentInput, { target: { value: 'Sản phẩm đóng gói cẩn thận, dùng rất thích!' } })

            // Bấm gửi đánh giá
            const submitBtn = screen.getByRole('button', { name: 'Gửi đánh giá' })
            fireEvent.click(submitBtn)

            await waitFor(() => {
                expect(createRatingSpy).toHaveBeenCalledWith({
                    orderId: 'ORD-DELIVERED-001',
                    rateeId: 'seller-user-02',
                    score: 5,
                    comment: 'Sản phẩm đóng gói cẩn thận, dùng rất thích!',
                })
            })
        })

        it('Seller đánh giá Buyer thông qua RatingModal trên SellerOrdersPage', async () => {
            contextHolder.current.user = { id: 'seller-user-02', name: 'Shop Đồ Gia Dụng', role: 'seller' }

            const mockSellerOrders: Order[] = [
                {
                    id: 'ORD-SELLER-01',
                    buyerId: 'buyer-user-01',
                    buyerName: 'Khách Hàng Thân Thiết',
                    sellerId: 'seller-user-02',
                    sellerName: 'Shop Đồ Gia Dụng',
                    items: [
                        {
                            productId: 'prod-3',
                            productName: 'Máy xay sinh tố',
                            image: '',
                            quantity: 1,
                            unitPrice: 500000,
                            subtotal: 500000,
                        },
                    ],
                    subtotal: 500000,
                    shipping: 30000,
                    tax: 0,
                    discount: 0,
                    total: 530000,
                    status: 'DELIVERED',
                    shippingAddress: '789 Trần Hưng Đạo, Q5',
                    createdAt: '2026-11-11T10:00:00Z',
                    updatedAt: '2026-11-13T09:00:00Z',
                    reviewed: false,
                    paymentStatus: 'paid',
                    paymentMethod: 'VNPAY',
                },
            ]

            vi.spyOn(ordersApi, 'getOrders').mockResolvedValue(mockSellerOrders)
            vi.spyOn(reputationApi, 'getUserReputation').mockResolvedValue({
                userId: 'buyer-user-01',
                score: 85,
                trustLevel: 'STANDARD',
                totalTx: 10,
                successTx: 10,
                failedTx: 0,
                auctionWon: 2,
                auctionFailed: 0,
                avgRating: 5.0,
                totalRatings: 5,
            })
            vi.spyOn(reputationApi, 'getRatingsForOrder').mockResolvedValue([])

            const createRatingSpy = vi.spyOn(reputationApi, 'createRating').mockResolvedValue({
                id: 'rate-seller-to-buyer-1',
                orderId: 'ORD-SELLER-01',
                raterId: 'seller-user-02',
                rateeId: 'buyer-user-01',
                score: 5,
                comment: 'Khách nhận hàng nhanh và thanh toán chuẩn.',
                createdAt: '2026-11-13T10:30:00Z',
            })

            render(<SellerOrdersPage />)

            // Click vào đơn hàng để mở modal chi tiết của Seller
            const orderRow = await screen.findByText('ORD-SELLER-01')
            fireEvent.click(orderRow)

            // Nút "Đánh giá người mua" hiển thị
            const rateBuyerBtn = await screen.findByRole('button', { name: /Đánh giá người mua/i })
            fireEvent.click(rateBuyerBtn)

            // Modal RatingModal xuất hiện
            expect(await screen.findByText(/Đánh giá người mua: Khách Hàng Thân Thiết/i)).toBeInTheDocument()

            // Nhập nhận xét và gửi
            const textarea = screen.getByPlaceholderText(/Chia sẻ trải nghiệm về thái độ/i)
            fireEvent.change(textarea, { target: { value: 'Khách nhận hàng nhanh và thanh toán chuẩn.' } })

            const submitBtn = screen.getByRole('button', { name: 'Gửi đánh giá' })
            fireEvent.click(submitBtn)

            await waitFor(() => {
                expect(createRatingSpy).toHaveBeenCalledWith({
                    orderId: 'ORD-SELLER-01',
                    rateeId: 'buyer-user-01',
                    score: 5,
                    comment: 'Khách nhận hàng nhanh và thanh toán chuẩn.',
                })
            })
        })
    })

    // ─── AC 4: Hiển thị Reputation & Trust Level ───────────────────────────────────
    describe('AC 4: Hiển thị điểm uy tín, Trust Level và hồ sơ MyReputationPage', () => {
        it('phân chia chính xác 4 cấp độ uy tín theo mốc điểm nghiệp vụ', () => {
            expect(calculateTrustLevel(40)).toBe('LOW')
            expect(calculateTrustLevel(75)).toBe('STANDARD')
            expect(calculateTrustLevel(125)).toBe('HIGH')
            expect(calculateTrustLevel(160)).toBe('VIP')
        })

        it('hiển thị đầy đủ thông tin Trust Level, Score, thống kê giao dịch trên MyReputationPage', async () => {
            const mockSummary: ReputationSummary = {
                userId: 'buyer-user-01',
                score: 165,
                trustLevel: 'VIP',
                totalTx: 80,
                successTx: 78,
                failedTx: 2,
                auctionWon: 12,
                auctionFailed: 0,
                avgRating: 4.95,
                totalRatings: 45,
            }

            vi.spyOn(reputationApi, 'getMyReputation').mockResolvedValue(mockSummary)
            vi.spyOn(reputationApi, 'getUserRatings').mockResolvedValue([])
            vi.spyOn(reputationApi, 'checkAuctionEligibility').mockResolvedValue({
                userId: 'buyer-user-01',
                eligible: true,
                currentScore: 165,
                minRequiredScore: 40,
                reason: 'Đủ điều kiện tham gia đấu giá',
            })

            render(<MyReputationPage />)

            expect(await screen.findByText('VIP Trust')).toBeInTheDocument()
            expect(screen.getByText('165')).toBeInTheDocument()
            expect(screen.getByText(/Đủ điều kiện/i)).toBeInTheDocument()
            expect(screen.getByText('78')).toBeInTheDocument() // Số đơn thành công
        })
    })

    // ─── AC 5: Notification Inbox & Preferences ────────────────────────────────────
    describe('AC 5: Notification Inbox, lọc tab, đánh dấu đọc và cấu hình Preferences', () => {
        it('hiển thị hộp thư thông báo, số tin chưa đọc và lọc theo tab', async () => {
            const mockInbox: notifApi.Inbox = {
                content: [
                    {
                        id: 'notif-101',
                        type: 'ORDER_DELIVERED',
                        title: 'Đơn hàng ORD-101 đã giao',
                        content: 'Kiện hàng đã đến tay bạn.',
                        read: false,
                        createdAt: '2026-11-13T08:00:00Z',
                    },
                    {
                        id: 'notif-102',
                        type: 'PAYMENT_SUCCESS',
                        title: 'Thanh toán thành công',
                        content: 'Đã nhận thanh toán qua VNPay.',
                        read: true,
                        createdAt: '2026-11-12T14:00:00Z',
                    },
                ],
                totalElements: 2,
                unreadCount: 1,
            }

            vi.spyOn(notifApi, 'getNotifications').mockResolvedValue(mockInbox)
            vi.spyOn(notifApi, 'getPushPermissionStatus').mockReturnValue('granted')

            render(<NotificationInbox />)

            expect(await screen.findByText('Đơn hàng ORD-101 đã giao')).toBeInTheDocument()
            expect(screen.getByText('Thanh toán thành công')).toBeInTheDocument()

            // Lọc tab "Chưa đọc"
            const unreadTabBtn = screen.getByRole('button', { name: /Chưa đọc \(1\)/i })
            fireEvent.click(unreadTabBtn)

            expect(screen.getByText('Đơn hàng ORD-101 đã giao')).toBeInTheDocument()
            expect(screen.queryByText('Thanh toán thành công')).not.toBeInTheDocument()
        })

        it('mở modal Preferences và cập nhật kênh nhận thông báo thành công', async () => {
            vi.spyOn(notifApi, 'getNotifications').mockResolvedValue({
                content: [],
                totalElements: 0,
                unreadCount: 0,
            })
            vi.spyOn(notifApi, 'getPushPermissionStatus').mockReturnValue('granted')

            const mockPrefs: notifApi.NotificationPreference[] = [
                { notificationType: 'ORDER', channel: 'IN_APP', enabled: true },
                { notificationType: 'ORDER', channel: 'EMAIL', enabled: false },
                { notificationType: 'ORDER', channel: 'PUSH', enabled: true },
            ]

            vi.spyOn(notifApi, 'getNotificationPreferences').mockResolvedValue(mockPrefs)
            const updateSpy = vi.spyOn(notifApi, 'updateNotificationPreferences').mockResolvedValue(mockPrefs)

            render(<NotificationInbox />)

            // Mở modal cấu hình
            const prefBtn = await screen.findByRole('button', { name: /Cài đặt thông báo/i })
            fireEvent.click(prefBtn)

            expect(await screen.findByText('Cài đặt Tùy chọn Nhận thông báo')).toBeInTheDocument()

            // Bấm Lưu tùy chọn
            const saveBtn = screen.getByRole('button', { name: 'Lưu tùy chọn' })
            fireEvent.click(saveBtn)

            await waitFor(() => {
                expect(updateSpy).toHaveBeenCalled()
            })
        })
    })

    // ─── AC 6: Role-based Access & Chặn tự đánh giá chính mình ──────────────────────
    describe('AC 6: Kiểm tra phân quyền truy cập và bảo vệ vai trò (RBAC)', () => {
        it('chặn Buyer truy cập vào các trang quản trị của Seller và hiển thị Access denied', () => {
            contextHolder.current.page = 'seller-orders'
            contextHolder.current.user = { id: 'buyer-01', name: 'Buyer User', role: 'buyer' }

            render(<AppRouter />)

            expect(screen.getByRole('alert')).toHaveTextContent('Access denied')
        })

        it('chặn tự đánh giá khi raterId trùng với rateeId trong RatingModal', () => {
            render(
                <RatingModal
                    isOpen={true}
                    onClose={vi.fn()}
                    orderId="order-self-rate"
                    rateeId="buyer-user-01" // Trùng với user.id
                    rateeName="Chính Tôi"
                    role="buyer"
                />,
            )

            expect(
                screen.getByText(/Hệ thống không cho phép bạn tự đánh giá chính tài khoản của mình/i),
            ).toBeInTheDocument()
        })
    })

    // ─── AC 7: Xử lý ngoại lệ Shipping Provider Timeout / Error ─────────────────────
    describe('AC 7: Xử lý lỗi và cơ chế chịu lỗi (Resilience) khi Shipping Provider timeout/lỗi', () => {
        it('tự động fallback cước phí chuẩn khi backend shipping quotes báo lỗi 503 hoặc timeout', async () => {
            // Giả lập backend lỗi 503
            vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('503 Service Unavailable / Gateway Timeout'))

            const fallbackRes = await shippingApi.getShippingQuotes({
                shippingAddress: 'Ho Chi Minh City',
                orderTotal: 250000,
            })

            // Fallback cước phí tự động kích hoạt để không làm gãy luồng thanh toán
            expect(fallbackRes.options).toHaveLength(2)
            expect(fallbackRes.options[0].carrier).toBe('GHN')
            expect(fallbackRes.options[1].carrier).toBe('GHTK')
            expect(fallbackRes.options[0].shippingFee).toBeGreaterThan(0)
        })

        it('hiển thị nút Thử lại khi OrderDetailPage gặp lỗi tải vận đơn', async () => {
            contextHolder.current.pageParams = { id: 'ORD-ERR-001' }

            vi.spyOn(ordersApi, 'getOrderById').mockResolvedValue({
                id: 'ORD-ERR-001',
                buyerId: 'buyer-user-01',
                buyerName: 'Buyer',
                sellerId: 'seller-user-02',
                sellerName: 'Shop',
                items: [],
                subtotal: 100000,
                shipping: 30000,
                tax: 0,
                discount: 0,
                total: 130000,
                status: 'SHIPPED',
                shippingAddress: 'HCM',
                createdAt: '2026-11-13T00:00:00Z',
                updatedAt: '2026-11-13T00:00:00Z',
                reviewed: false,
                paymentStatus: 'paid',
                paymentMethod: 'VNPAY',
            })

            const getShipmentSpy = vi.spyOn(shippingApi, 'getShipmentByOrderId').mockRejectedValueOnce(
                new Error('Lỗi máy chủ đối tác vận chuyển'),
            )

            render(<OrderDetailPage />)

            expect(await screen.findByText('Lỗi máy chủ đối tác vận chuyển')).toBeInTheDocument()
            const retryBtn = screen.getByRole('button', { name: 'Thử lại' })
            expect(retryBtn).toBeInTheDocument()

            // Bấm thử lại
            fireEvent.click(retryBtn)
            expect(getShipmentSpy).toHaveBeenCalledTimes(2)
        })
    })

    // ─── AC 8 & 9: Reconnect WebSocket & Đồng bộ dữ liệu giao diện ──────────────────
    describe('AC 8 & 9: WebSocket Reconnection & Tự động đồng bộ dữ liệu giao diện', () => {
        it('đồng bộ lại danh sách thông báo khi nhận sự kiện NOTIFICATIONS_CHANGED sau khi kết nối lại', async () => {
            const getNotifSpy = vi.spyOn(notifApi, 'getNotifications').mockResolvedValue({
                content: [
                    {
                        id: 'notif-sync-1',
                        type: 'ORDER_DELIVERED',
                        title: 'Thông báo ban đầu',
                        content: 'Nội dung ban đầu',
                        read: false,
                        createdAt: '2026-11-13T08:00:00Z',
                    },
                ],
                totalElements: 1,
                unreadCount: 1,
            })
            vi.spyOn(notifApi, 'getPushPermissionStatus').mockReturnValue('granted')

            render(<NotificationInbox />)

            expect(await screen.findByText('Thông báo ban đầu')).toBeInTheDocument()
            expect(getNotifSpy).toHaveBeenCalledTimes(1)

            // Mô phỏng sự kiện WebSocket reconnect thành công phát NOTIFICATIONS_CHANGED
            fireEvent(window, new Event(NOTIFICATIONS_CHANGED))

            // NotificationInbox tự động fetch lại từ REST để đồng bộ dữ liệu mới nhất
            await waitFor(() => {
                expect(getNotifSpy).toHaveBeenCalledTimes(2)
            })
        })
    })

    // ─── AC 10: Loading, Empty và Error States ──────────────────────────────────────
    describe('AC 10: Trạng thái Loading, Empty và Error State trên các thành phần UI', () => {
        it('hiển thị đúng các trạng thái của RatingsList (Loading -> Empty -> Data)', () => {
            // Loading state
            const { rerender } = render(<RatingsList ratings={[]} loading={true} />)
            expect(screen.getByRole('status')).toBeInTheDocument()
            expect(screen.getByText('Đang tải lịch sử đánh giá...')).toBeInTheDocument()

            // Empty state
            rerender(<RatingsList ratings={[]} loading={false} />)
            expect(screen.getByText('Chưa có đánh giá nào.')).toBeInTheDocument()

            // Error state
            rerender(<RatingsList ratings={[]} error="Không thể tải lịch sử đánh giá" onRetry={vi.fn()} />)
            expect(screen.getByText('Không thể tải lịch sử đánh giá')).toBeInTheDocument()
            expect(screen.getByRole('button', { name: 'Thử lại' })).toBeInTheDocument()
        })

        it('hiển thị empty state trên OrderDetailPage khi đơn hàng chưa bàn giao cho hãng vận chuyển', async () => {
            contextHolder.current.pageParams = { id: 'ORD-PREPARING-001' }

            vi.spyOn(ordersApi, 'getOrderById').mockResolvedValue({
                id: 'ORD-PREPARING-001',
                buyerId: 'buyer-user-01',
                buyerName: 'Buyer',
                sellerId: 'seller-user-02',
                sellerName: 'Shop',
                items: [],
                subtotal: 200000,
                shipping: 30000,
                tax: 0,
                discount: 0,
                total: 230000,
                status: 'PROCESSING',
                shippingAddress: 'HCM',
                createdAt: '2026-11-13T00:00:00Z',
                updatedAt: '2026-11-13T00:00:00Z',
                reviewed: false,
                paymentStatus: 'paid',
                paymentMethod: 'VNPAY',
            })
            vi.spyOn(shippingApi, 'getShipmentByOrderId').mockResolvedValue(null)

            render(<OrderDetailPage />)

            expect(await screen.findByText('Đang chuẩn bị hàng')).toBeInTheDocument()
            expect(
                screen.getByText(/Đơn hàng đang chờ người bán đóng gói và bàn giao cho đơn vị vận chuyển/i),
            ).toBeInTheDocument()
        })
    })
})
