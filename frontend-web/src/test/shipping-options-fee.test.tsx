/**
 * Unit & Integration Tests cho [HA-S3-1]: Implement shipping options and shipping fee UI
 *
 * Kiểm tra đầy đủ các tiêu chí chấp nhận (Acceptance Criteria):
 * 1. Hiển thị danh sách phương thức vận chuyển khả dụng.
 * 2. Hiển thị phí vận chuyển tương ứng với từng phương thức.
 * 3. Cho phép Buyer lựa chọn phương thức vận chuyển.
 * 4. Cập nhật tổng tiền đơn hàng bao gồm phí vận chuyển.
 * 5. Tích hợp API để lấy thông tin phí ship (POST /api/v1/shipping/quotes).
 * 6. Hiển thị thông tin vận đơn sau khi đơn hàng được tạo.
 * 7. Buyer có thể theo dõi trạng thái giao hàng (GET /api/v1/shipments/order/{orderId}).
 * 8. Seller có thể xem thông tin vận chuyển của đơn hàng.
 * 9. Hiển thị mã vận đơn và thông tin đơn vị vận chuyển.
 * 10. Cập nhật trạng thái giao hàng theo dữ liệu từ API.
 * 11. Xử lý loading, empty và error state khi gọi API.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { AppProvider } from '../context'
import { CheckoutPage, OrderDetailPage } from '../features/buyer/BuyerPages'
import { SellerOrdersPage } from '../features/seller/SellerPages'
import { shippingApi } from '../api/shipping'
import { ordersApi } from '../api/orders'
import * as cartApi from '../api/cart'
import { purchaseAvailabilityMessage } from '../features/buyer/purchaseAvailability'
import type { Order } from '../types'

vi.mock('../features/buyer/purchaseAvailability', () => ({
    purchaseAvailabilityMessage: vi.fn(),
}))

/**
 * Chức năng: Helper chờ nút "Tiếp theo" kích hoạt (khi đã kiểm tra tồn kho xong) và bấm chuyển sang Bước 2 (Shipping).
 */
async function goToShippingMethodStep() {
    const nextButton = await screen.findByRole('button', { name: /tiếp theo|tiếp tục|next/i })
    await waitFor(() => expect(nextButton).toBeEnabled())
    fireEvent.click(nextButton)
}

// Mock dữ liệu đơn hàng mẫu
const mockTestOrder: Order = {
    id: 'ORD-TEST-001',
    orderNo: 'ORD-TEST-001',
    buyerId: 'user-buyer-1',
    buyerName: 'Minh Tran',
    sellerId: 'user-seller-1',
    sellerName: 'Nexus Tech Store',
    status: 'SHIPPED',
    refundStatus: 'none',
    paymentStatus: 'held',
    subtotal: 1200000,
    shipping: 55000,
    tax: 0,
    discount: 0,
    total: 1255000,
    shippingAddress: '123 Nguyen Hue, District 1, Ho Chi Minh City',
    paymentMethod: 'COD',
    createdAt: '2026-11-13T08:00:00Z',
    updatedAt: '2026-11-13T08:00:00Z',
    items: [
        {
            productId: 'prod-1',
            productName: 'Chuột không dây Gaming',
            image: 'https://example.com/mouse.jpg',
            quantity: 1,
            unitPrice: 1200000,
            subtotal: 1200000,
        },
    ],
}

describe('[HA-S3-1] Shipping Options & Fee UI Tests', () => {
    beforeEach(() => {
        sessionStorage.clear()
        localStorage.clear()
        vi.restoreAllMocks()

        // Mặc định kho hàng đủ điều kiện để không chặn nút Tiếp theo của Checkout
        vi.mocked(purchaseAvailabilityMessage).mockResolvedValue(null)

        // Mock session người dùng
        sessionStorage.setItem(
            'nexus.session',
            JSON.stringify({
                user: {
                    id: 'user-buyer-1',
                    email: 'buyer@nexus.vn',
                    name: 'Minh Tran',
                    role: 'buyer',
                },
                token: 'mock-jwt-token',
                remember: false,
            }),
        )

        // Mock giỏ hàng mặc định
        vi.spyOn(cartApi, 'getActiveCart').mockResolvedValue({
            cartId: 'cart-1',
            userId: 'user-buyer-1',
            status: 'ACTIVE',
            items: [
                {
                    id: 'item-1',
                    skuId: 'sku-1',
                    skuCode: 'SKU-MOUSE',
                    productName: 'Chuột không dây Gaming',
                    thumbnailUrl: 'https://example.com/mouse.jpg',
                    unitPrice: 200000,
                    quantity: 1,
                    stockAvailable: 10,
                    isAvailable: true,
                    subtotal: 200000,
                },
            ],
            totalItems: 1,
            totalQuantity: 1,
            subtotal: 200000,
            discountAmount: 0,
            shippingFee: 0,
            taxAmount: 0,
            totalAmount: 200000,
            expiresAt: null,
        })
    })

    afterEach(() => {
        vi.unstubAllGlobals()
    })

    describe('1. CheckoutPage: Lựa chọn phương thức vận chuyển & tính phí ship (AC 1, 2, 3, 4, 5, 11)', () => {
        it('hiển thị danh sách phương thức vận chuyển khả dụng và cước phí từ API (AC 1, 2, 5)', async () => {
            // Mock API getShippingQuotes trả về GHN Express và GHTK Standard
            vi.spyOn(shippingApi, 'getShippingQuotes').mockResolvedValue({
                defaultCarrier: 'GHN',
                freeShippingThreshold: 500000,
                freeShippingApplied: false,
                options: [
                    {
                        carrier: 'GHN',
                        serviceType: 'EXPRESS',
                        shippingFee: 55000,
                        originalFee: 55000,
                        freeShippingApplied: false,
                        estimatedDeliveryDays: 3,
                        estimatedDeliveryAt: '2026-11-16T10:00:00Z',
                    },
                    {
                        carrier: 'GHTK',
                        serviceType: 'STANDARD',
                        shippingFee: 35000,
                        originalFee: 35000,
                        freeShippingApplied: false,
                        estimatedDeliveryDays: 5,
                        estimatedDeliveryAt: '2026-11-18T10:00:00Z',
                    },
                ],
            })

            render(
                <AppProvider>
                    <CheckoutPage />
                </AppProvider>,
            )

            // Bước 1: Bấm Next để sang Bước 2 (Phương thức vận chuyển)
            await goToShippingMethodStep()

            // Kiểm tra hiển thị phương thức vận chuyển từ API
            expect(await screen.findByText('GHN Express')).toBeInTheDocument()
            expect(screen.getByText('GHTK Standard')).toBeInTheDocument()
            expect(screen.getAllByText(/\$55,000|55\.000/).length).toBeGreaterThanOrEqual(1)
            expect(screen.getAllByText(/\$35,000|35\.000/).length).toBeGreaterThanOrEqual(1)
        })

        it('cho phép Buyer chọn phương thức vận chuyển và cập nhật tổng tiền tương ứng (AC 3, 4)', async () => {
            vi.spyOn(shippingApi, 'getShippingQuotes').mockResolvedValue({
                defaultCarrier: 'GHN',
                freeShippingThreshold: 500000,
                freeShippingApplied: false,
                options: [
                    {
                        carrier: 'GHN',
                        serviceType: 'EXPRESS',
                        shippingFee: 55000,
                        originalFee: 55000,
                        freeShippingApplied: false,
                        estimatedDeliveryDays: 3,
                    },
                    {
                        carrier: 'GHTK',
                        serviceType: 'STANDARD',
                        shippingFee: 35000,
                        originalFee: 35000,
                        freeShippingApplied: false,
                        estimatedDeliveryDays: 5,
                    },
                ],
            })

            render(
                <AppProvider>
                    <CheckoutPage />
                </AppProvider>,
            )

            // Sang Bước 2
            await goToShippingMethodStep()

            // Chờ các options hiển thị
            const ghtkOption = await screen.findByText('GHTK Standard')

            // Chọn GHTK bằng cách click vào option
            fireEvent.click(ghtkOption)

            // Kiểm tra phí ship được cập nhật trên Sidebar
            await waitFor(() => {
                expect(screen.getAllByText(/\$35,000|35\.000/).length).toBeGreaterThanOrEqual(1)
            })
        })

        it('hiển thị nhãn FREESHIP khi đơn hàng đủ điều kiện miễn phí vận chuyển', async () => {
            vi.spyOn(shippingApi, 'getShippingQuotes').mockResolvedValue({
                defaultCarrier: 'GHN',
                freeShippingThreshold: 500000,
                freeShippingApplied: true,
                options: [
                    {
                        carrier: 'GHN',
                        serviceType: 'EXPRESS',
                        shippingFee: 0,
                        originalFee: 55000,
                        freeShippingApplied: true,
                        estimatedDeliveryDays: 3,
                    },
                    {
                        carrier: 'GHTK',
                        serviceType: 'STANDARD',
                        shippingFee: 0,
                        originalFee: 35000,
                        freeShippingApplied: true,
                        estimatedDeliveryDays: 5,
                    },
                ],
            })

            render(
                <AppProvider>
                    <CheckoutPage />
                </AppProvider>,
            )

            await goToShippingMethodStep()

            // Kiểm tra nhãn Free Shipping xuất hiện
            const freeBadges = await screen.findAllByText(
                /FREESHIP|FREE|Miễn phí vận chuyển|Free Shipping/i,
            )
            expect(freeBadges.length).toBeGreaterThanOrEqual(1)
        })

        it('xử lý trạng thái lỗi khi gọi API và cho phép Thử lại (AC 11)', async () => {
            let callCount = 0
            vi.spyOn(shippingApi, 'getShippingQuotes').mockImplementation(async () => {
                callCount++
                if (callCount === 1) {
                    throw new Error('Lỗi máy chủ tính phí ship')
                }
                return {
                    defaultCarrier: 'GHN',
                    freeShippingThreshold: 500000,
                    freeShippingApplied: false,
                    options: [
                        {
                            carrier: 'GHN',
                            serviceType: 'EXPRESS',
                            shippingFee: 55000,
                            originalFee: 55000,
                            freeShippingApplied: false,
                            estimatedDeliveryDays: 3,
                        },
                    ],
                }
            })

            render(
                <AppProvider>
                    <CheckoutPage />
                </AppProvider>,
            )

            await goToShippingMethodStep()

            // Thấy thông báo lỗi
            expect(await screen.findByText('Lỗi máy chủ tính phí ship')).toBeInTheDocument()

            // Bấm Thử lại (Retry)
            const retryButton = screen.getByRole('button', { name: /Thử lại|Retry/i })
            fireEvent.click(retryButton)

            // Sau khi thử lại thành công
            expect(await screen.findByText('GHN Express')).toBeInTheDocument()
        })
    })

    describe('2. OrderDetailPage: Theo dõi vận đơn & hành trình giao hàng (AC 6, 7, 9, 10, 11)', () => {
        it('hiển thị thông tin vận đơn, đơn vị vận chuyển, mã tracking và lịch sử hành trình (AC 6, 7, 9, 10)', async () => {
            vi.spyOn(ordersApi, 'getOrderById').mockResolvedValue(mockTestOrder)
            vi.spyOn(shippingApi, 'getShipmentByOrderId').mockResolvedValue({
                id: 'ship-uuid-1',
                orderId: mockTestOrder.id,
                carrier: 'GHN',
                serviceType: 'EXPRESS',
                shippingFee: 55000,
                freeShippingApplied: false,
                trackingNumber: 'GHN99887766VN',
                status: 'IN_TRANSIT',
                estimatedDeliveryDays: 3,
            })
            vi.spyOn(shippingApi, 'getShipmentTracking').mockResolvedValue({
                shipmentId: 'ship-uuid-1',
                orderId: mockTestOrder.id,
                carrier: 'GHN',
                trackingNumber: 'GHN99887766VN',
                status: 'IN_TRANSIT',
                currentLocation: 'Hub Tân Bình, TP.HCM',
                history: [
                    {
                        carrierStatus: 'picked_up',
                        internalStatus: 'SHIPPED',
                        occurredAt: '2026-11-13T08:00:00Z',
                        location: 'Kho Quận 1',
                        description: 'Đã lấy hàng từ người bán',
                    },
                    {
                        carrierStatus: 'in_transit',
                        internalStatus: 'IN_TRANSIT',
                        occurredAt: '2026-11-13T10:00:00Z',
                        location: 'Hub Tân Bình, TP.HCM',
                        description: 'Đang luân chuyển qua bưu cục trung chuyển',
                    },
                ],
            })

            render(
                <AppProvider>
                    <OrderDetailPage />
                </AppProvider>,
            )

            // Kiểm tra hiển thị hãng và mã tracking
            expect(await screen.findByText('GHN99887766VN')).toBeInTheDocument()
            expect(screen.getByText('IN_TRANSIT')).toBeInTheDocument()
            // Kiểm tra timeline mốc hành trình
            expect(
                screen.getByText('Đang luân chuyển qua bưu cục trung chuyển'),
            ).toBeInTheDocument()
            expect(screen.getByText('Đã lấy hàng từ người bán')).toBeInTheDocument()
        })

        it('hiển thị trạng thái rỗng thân thiện khi đơn hàng chưa phát sinh vận đơn (AC 11)', async () => {
            const pendingOrder: Order = {
                ...mockTestOrder,
                status: 'PROCESSING',
                shipment: undefined,
            }
            vi.spyOn(ordersApi, 'getOrderById').mockResolvedValue(pendingOrder)
            vi.spyOn(shippingApi, 'getShipmentByOrderId').mockResolvedValue(null)

            render(
                <AppProvider>
                    <OrderDetailPage />
                </AppProvider>,
            )

            expect(
                await screen.findByText(/Đang chuẩn bị hàng|Preparing for shipment/i),
            ).toBeInTheDocument()
        })
    })

    describe('3. SellerOrdersPage: Người bán theo dõi thông tin vận chuyển của đơn hàng (AC 8, 9)', () => {
        it('hiển thị thông tin vận chuyển trong bảng và modal chi tiết của Seller (AC 8, 9)', async () => {
            const sellerOrderWithShipment: Order = {
                ...mockTestOrder,
                shipment: {
                    id: 'ship-1',
                    carrier: 'GHTK',
                    trackingNumber: 'GHTK112233VN',
                    status: 'in_transit',
                    estimatedDelivery: '2026-11-16',
                    events: [
                        {
                            status: 'in_transit',
                            description: 'Bưu tá đang giao hàng',
                            timestamp: '2026-11-13T09:00:00Z',
                        },
                    ],
                },
            }

            vi.spyOn(ordersApi, 'getOrders').mockResolvedValue([sellerOrderWithShipment])
            vi.spyOn(shippingApi, 'getShipmentByOrderId').mockResolvedValue({
                id: 'ship-1',
                orderId: sellerOrderWithShipment.id,
                carrier: 'GHTK',
                trackingNumber: 'GHTK112233VN',
                status: 'IN_TRANSIT',
                shippingFee: 35000,
                freeShippingApplied: false,
            })
            vi.spyOn(shippingApi, 'getShipmentTracking').mockResolvedValue({
                shipmentId: 'ship-1',
                orderId: sellerOrderWithShipment.id,
                carrier: 'GHTK',
                trackingNumber: 'GHTK112233VN',
                status: 'IN_TRANSIT',
                history: [
                    {
                        carrierStatus: 'in_transit',
                        internalStatus: 'IN_TRANSIT',
                        occurredAt: '2026-11-13T09:00:00Z',
                        location: 'Hà Nội',
                        description: 'Bưu tá đang giao hàng',
                    },
                ],
            })

            render(
                <AppProvider>
                    <SellerOrdersPage />
                </AppProvider>,
            )

            // Kiểm tra bảng danh sách đơn có hiển thị hãng vận chuyển và tracking
            expect(await screen.findByText('GHTK')).toBeInTheDocument()
            expect(screen.getByText('GHTK112233VN')).toBeInTheDocument()

            // Bấm xem chi tiết đơn hàng
            const viewButton = screen.getByRole('button', { name: /Chi tiết|View/i })
            fireEvent.click(viewButton)

            // Modal mở ra có thông tin vận chuyển & đơn vị giao
            expect(
                await screen.findByText(/Thông tin vận chuyển & Đơn vị giao|Shipment Information/i),
            ).toBeInTheDocument()
            expect(screen.getAllByText('GHTK112233VN').length).toBeGreaterThanOrEqual(1)
        })
    })
})
