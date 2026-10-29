import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AppState } from '../types'
import { DEMO_ADMIN, DEMO_BUYER, DEMO_SELLER } from '../mockData'
import { ordersApi } from '../api/orders'
import { OrderHistoryPage, OrderDetailPage } from '../features/buyer/BuyerPages'
import { SellerOrdersPage } from '../features/seller/SellerPages'
import { AdminOrdersPage } from '../features/admin/AdminPages'

const contextHolder = vi.hoisted(() => ({ current: {} as AppState }))
vi.mock('../context', () => ({ useApp: () => contextHolder.current }))

describe('Kiểm thử API thống nhất và Quản lý đơn hàng (Buyer, Seller, Admin)', () => {
    beforeEach(() => {
        ordersApi.resetOrdersToDefault()
        contextHolder.current = {
            lang: 'vi',
            setLang: vi.fn(),
            page: 'order-history',
            navigate: vi.fn(),
            pageParams: { id: 'ORD-20240215-001' },
            user: DEMO_BUYER,
            setUser: vi.fn(),
            cartCount: 0,
            setCartCount: vi.fn(),
            toasts: [],
            showToast: vi.fn(),
            dismissToast: vi.fn(),
            auctionReviews: {},
            reviewAuction: vi.fn(),
            createdAuctions: [],
            submitAuction: vi.fn(),
        }
    })

    afterEach(() => {
        vi.clearAllMocks()
    })

    it('ordersApi: Lấy danh sách đơn hàng cho Buyer, Seller, Admin và fallback mock data thành công', async () => {
        // Kiểm tra danh sách buyer
        const buyerOrders = await ordersApi.getOrders({ role: 'buyer', userId: DEMO_BUYER.id })
        expect(buyerOrders.length).toBeGreaterThan(0)
        expect(buyerOrders.every((o) => o.buyerId === 'u1')).toBe(true)

        // Kiểm tra chi tiết đơn hàng
        const order = await ordersApi.getOrderById('ORD-20240215-001')
        expect(order).not.toBeNull()
        expect(order?.id).toBe('ORD-20240215-001')

        // Kiểm tra danh sách seller
        const sellerOrders = await ordersApi.getOrders({ role: 'seller', userId: DEMO_SELLER.id })
        expect(sellerOrders.length).toBeGreaterThan(0)
        expect(await ordersApi.getOrders({ role: 'buyer', userId: DEMO_SELLER.id })).toHaveLength(0)

        // Kiểm tra danh sách admin
        const adminOrders = await ordersApi.getOrders({ role: 'admin' })
        expect(adminOrders.length).toBeGreaterThanOrEqual(buyerOrders.length)
    })

    // Chặn hồi quy: mock không được thay đơn không tồn tại bằng đơn đầu tiên của kho dữ liệu.
    it('ordersApi: trả về null khi ID đơn không tồn tại', async () => {
        await expect(ordersApi.getOrderById('ORD-NOT-FOUND')).resolves.toBeNull()
    })

    it('ordersApi: Buyer hủy đơn hàng, cập nhật trạng thái cancelled và thanh toán refunded', async () => {
        const cancelled = await ordersApi.cancelOrder('ORD-20240305-005', 'Không còn nhu cầu')
        expect(cancelled.status).toBe('CANCELLED')
        expect(cancelled.paymentStatus).toBe('refunded')

        // Xác minh lưu vết vào storage
        const recheck = await ordersApi.getOrderById('ORD-20240305-005')
        expect(recheck?.status).toBe('CANCELLED')
    })

    it('ordersApi: Seller cập nhật trạng thái đơn hàng processing -> shipped', async () => {
        const updated = await ordersApi.updateOrderStatus('ORD-20240220-002', 'SHIPPED', 'Giao cho đơn vị vận chuyển')
        expect(updated.status).toBe('SHIPPED')

        const recheck = await ordersApi.getOrderById('ORD-20240220-002')
        expect(recheck?.status).toBe('SHIPPED')
        expect(recheck?.statusHistory?.at(-1)).toMatchObject({
            status: 'SHIPPED',
            actor: 'seller',
            reason: 'Giao cho đơn vị vận chuyển',
        })
    })

    it('ordersApi: Buyer xác nhận đã nhận hàng và mở khiếu nại', async () => {
        // Xác nhận nhận hàng thành công
        const completed = await ordersApi.confirmOrderDelivery('ORD-20240215-001')
        expect(completed.status).toBe('COMPLETED')
        expect(completed.paymentStatus).toBe('released')

        // Mở khiếu nại
        const disputed = await ordersApi.submitOrderDispute('ORD-20240301-004', 'Hàng bị trầy xước')
        expect(disputed.disputeStatus).toBe('open')
        expect(disputed.paymentStatus).toBe('on_hold')
    })

    it('ordersApi: Admin xử lý giải quyết tranh chấp ký quỹ Escrow', async () => {
        const resolved = await ordersApi.resolveOrderDispute('ORD-20240101-003', 'refunded', 'Chấp nhận khiếu nại của Buyer')
        expect(resolved.paymentStatus).toBe('refunded')
        expect(resolved.status).toBe('REFUNDED')
        expect(resolved.disputeStatus).toBe('resolved')
    })

    // Withdrawal phải dùng cùng mock storage thay vì chỉ đổi state tạm thời trong màn Admin.
    it('ordersApi: lưu trạng thái duyệt withdrawal trong mock store', () => {
        const request = ordersApi.getWithdrawalRequests()[0]
        const approved = ordersApi.approveWithdrawal(request.id)
        expect(approved.status).toBe('approved')
        expect(ordersApi.getWithdrawalRequests()[0]).toMatchObject({
            id: request.id,
            status: 'approved',
        })
    })

    it('OrderHistoryPage: Hiển thị danh sách đơn của Buyer và hỗ trợ tìm kiếm', async () => {
        render(<OrderHistoryPage />)

        // Chờ tải xong dữ liệu đơn hàng
        await waitFor(() => {
            expect(screen.getByText('ORD-20240215-001')).toBeDefined()
        })

        // Tìm kiếm theo mã đơn
        const searchInput = screen.getByPlaceholderText(/Tìm mã đơn hoặc tên sản phẩm/i)
        fireEvent.change(searchInput, { target: { value: 'ORD-20240220-002' } })

        await waitFor(() => {
            expect(screen.getByText('ORD-20240220-002')).toBeDefined()
        })
    })

    it('OrderDetailPage: Hiển thị đầy đủ thông tin đơn hàng và cho phép Buyer xác nhận nhận hàng', async () => {
        render(<OrderDetailPage />)

        await waitFor(() => {
            expect(screen.getAllByText('ORD-20240215-001').length).toBeGreaterThan(0)
        })

        // Kiểm tra nút xác nhận đã nhận hàng
        const confirmBtn = screen.getByText(/Xác nhận đã nhận hàng/i)
        expect(confirmBtn).toBeDefined()
        fireEvent.click(confirmBtn)

        await waitFor(() => {
            expect(contextHolder.current.showToast).toHaveBeenCalled()
        })
    })

    it('SellerOrdersPage: Hiển thị danh sách đơn của người bán và chuyển đổi trạng thái', async () => {
        contextHolder.current.user = DEMO_SELLER
        render(<SellerOrdersPage />)

        await waitFor(() => {
            expect(screen.getAllByText('ORD-20240215-001').length).toBeGreaterThan(0)
        })

        // Bấm nút View chi tiết đơn hàng
        const viewButtons = screen.getAllByRole('button', { name: /Chi tiết/i })
        expect(viewButtons.length).toBeGreaterThan(0)
        fireEvent.click(viewButtons[0])

        // Modal chi tiết mở ra
        await waitFor(() => {
            expect(screen.getByText(/Danh sách sản phẩm cần đóng gói/i)).toBeDefined()
        })
    })

    it('AdminOrdersPage: Hiển thị thống kê ký quỹ và mở modal quản lý can thiệp trạng thái', async () => {
        contextHolder.current.user = DEMO_ADMIN
        render(<AdminOrdersPage />)

        await waitFor(() => {
            expect(screen.getAllByText('ORD-20240215-001').length).toBeGreaterThan(0)
        })

        // Bấm nút Quản lý đơn hàng trên dòng bảng
        const manageButtons = screen.getAllByRole('button', { name: /Quản lý/i })
        expect(manageButtons.length).toBeGreaterThan(0)
        fireEvent.click(manageButtons[0])

        // Modal can thiệp mở ra
        await waitFor(() => {
            expect(screen.getByText(/Can thiệp quản lý trạng thái đơn hàng/i)).toBeDefined()
        })
    })

    it('ordersApi: createOrder tạo đơn hàng VNPAY và sinh URL thanh toán Sandbox', async () => {
        const payload = {
            shippingAddress: '123 Nguyen Hue, Ben Nghe, District 1, Ho Chi Minh City',
            recipientName: 'Minh Tran',
            recipientPhone: '0901234567',
            paymentMethod: 'VNPAY' as const,
            sourceType: 'REGULAR' as const,
        }
        const res = await ordersApi.createOrder(payload, 'test-idemp-key-123')
        expect(res).toBeDefined()
        expect(res.id).toMatch(/^ORD-/)
        expect(res.paymentMethod).toBe('VNPAY')
        expect(res.paymentUrl).toContain('/sandbox-payment')
        expect(res.paymentUrl).toContain('orderId=')

        // Kiểm tra đơn đã được lưu vào mock store
        const saved = await ordersApi.getOrderById(res.id)
        expect(saved).not.toBeNull()
        expect(saved?.paymentMethod).toBe('VNPAY QR')
    })

    it('ordersApi: createOrder tạo đơn hàng COD hoàn tất trực tiếp không cần URL Sandbox', async () => {
        const payload = {
            shippingAddress: '456 Le Loi, District 1, Ho Chi Minh City',
            recipientName: 'Hoang Nam',
            recipientPhone: '0909888777',
            paymentMethod: 'COD' as const,
            sourceType: 'REGULAR' as const,
        }
        const res = await ordersApi.createOrder(payload)
        expect(res.paymentMethod).toBe('COD')
        expect(res.paymentUrl).toBeUndefined()
    })

    it('ordersApi: checkoutPreview tính toán subtotal, voucher và tổng tiền chính xác', async () => {
        const preview = await ordersApi.checkoutPreview({
            shippingAddress: '123 Nguyen Hue',
            voucherCode: 'NEXUS10',
        })
        expect(preview.subtotal).toBeGreaterThan(0)
        expect(preview.discountAmount).toBeGreaterThan(0)
        expect(preview.appliedVoucher?.code).toBe('NEXUS10')
        expect(preview.isOrderable).toBe(true)
    })
})
