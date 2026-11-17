/**
 * Unit tests cho Shipping & Fulfillment API client (Sprint 3 - HA-S3-1)
 *
 * Kiểm tra các hàm nghiệp vụ:
 * 1. getShippingQuotes: Báo giá đa hãng từ API hoặc fallback an toàn khi backend offline / lỗi.
 * 2. getCarriers: Lấy danh sách hãng vận chuyển hỗ trợ.
 * 3. getShipmentByOrderId: Tra cứu vận đơn theo mã đơn hàng, trả null nếu 404.
 * 4. getShipmentTracking: Lấy hành trình và lịch sử trạng thái vận đơn.
 * 5. createShipment: Tạo vận đơn mới có kèm Idempotency-Key.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
    shippingApi,
    getFallbackShippingQuotes,
    type ShippingQuoteResponse,
    type ShipmentResponse,
    type ShipmentTrackingResponse,
} from './shipping'

describe('Shipping API Service', () => {
    beforeEach(() => {
        sessionStorage.clear()
        localStorage.clear()
    })

    afterEach(() => {
        vi.unstubAllGlobals()
    })

    describe('getShippingQuotes', () => {
        it('gửi POST /shipping/quotes và trả về kết quả báo giá từ backend khi thành công', async () => {
            const mockBackendQuote: ShippingQuoteResponse = {
                orderId: 'order-123',
                defaultCarrier: 'GHN',
                freeShippingThreshold: 500000,
                freeShippingApplied: false,
                options: [
                    {
                        carrier: 'GHN',
                        serviceType: 'EXPRESS',
                        shippingFee: 45000,
                        originalFee: 45000,
                        freeShippingApplied: false,
                        estimatedDeliveryDays: 2,
                        estimatedDeliveryAt: '2026-11-15T10:00:00Z',
                    },
                    {
                        carrier: 'GHTK',
                        serviceType: 'STANDARD',
                        shippingFee: 30000,
                        originalFee: 30000,
                        freeShippingApplied: false,
                        estimatedDeliveryDays: 4,
                        estimatedDeliveryAt: '2026-11-17T10:00:00Z',
                    },
                ],
            }

            const mockFetch = vi.fn().mockResolvedValue({
                ok: true,
                status: 200,
                json: async () => ({
                    success: true,
                    data: mockBackendQuote,
                }),
            })
            vi.stubGlobal('fetch', mockFetch)

            const result = await shippingApi.getShippingQuotes({
                shippingAddress: '123 Nguyen Hue, District 1, HCMC',
                orderTotal: 200000,
            })

            expect(mockFetch).toHaveBeenCalledTimes(1)
            const [url, options] = mockFetch.mock.calls[0]
            expect(url).toContain('/shipping/quotes')
            expect(options.method).toBe('POST')
            expect(JSON.parse(options.body)).toEqual({
                shippingAddress: '123 Nguyen Hue, District 1, HCMC',
                orderTotal: 200000,
            })
            expect(result.options).toHaveLength(2)
            expect(result.options[0].carrier).toBe('GHN')
            expect(result.options[0].shippingFee).toBe(45000)
        })

        it('tự động sử dụng fallback chuẩn khi backend trả lỗi hoặc không kết nối được', async () => {
            const mockFetch = vi.fn().mockRejectedValue(new Error('Network error'))
            vi.stubGlobal('fetch', mockFetch)

            const result = await shippingApi.getShippingQuotes({
                shippingAddress: '456 Le Duan, Hanoi',
                orderTotal: 300000,
            })

            expect(result.options).toHaveLength(2)
            expect(result.defaultCarrier).toBe('GHN')
            expect(result.options.map((o) => o.carrier)).toEqual(['GHN', 'GHTK'])
            expect(result.freeShippingApplied).toBe(false)
            expect(result.options[0].shippingFee).toBe(55000)
            expect(result.options[1].shippingFee).toBe(35000)
        })

        it('không tự động miễn phí vận chuyển theo tổng tiền hàng; chỉ miễn phí khi có voucher FREESHIP', () => {
            // Đơn hàng giá trị cao (600.000 đ) nhưng không có voucher thì vẫn phải trả phí vận chuyển chuẩn
            const fallbackNoVoucher = getFallbackShippingQuotes(600000)
            expect(fallbackNoVoucher.freeShippingApplied).toBe(false)
            expect(fallbackNoVoucher.options[0].shippingFee).toBe(55000)
            expect(fallbackNoVoucher.options[1].shippingFee).toBe(35000)

            // Khi áp dụng voucher FREESHIP hợp lệ, phí vận chuyển được giảm về 0 đ
            const fallbackWithVoucher = getFallbackShippingQuotes(200000, 'FREESHIP')
            expect(fallbackWithVoucher.freeShippingApplied).toBe(true)
            expect(fallbackWithVoucher.options[0].shippingFee).toBe(0)
            expect(fallbackWithVoucher.options[1].shippingFee).toBe(0)
            expect(fallbackWithVoucher.options[0].originalFee).toBe(55000)
        })
    })

    describe('getCarriers', () => {
        it('gửi GET /shipping/carriers và trả về danh sách hãng hợp lệ', async () => {
            const mockCarriers = [
                { code: 'GHN', name: 'GHN Express', active: true },
                { code: 'GHTK', name: 'GHTK Standard', active: true },
            ]

            const mockFetch = vi.fn().mockResolvedValue({
                ok: true,
                status: 200,
                json: async () => ({
                    success: true,
                    data: mockCarriers,
                }),
            })
            vi.stubGlobal('fetch', mockFetch)

            const result = await shippingApi.getCarriers()
            expect(result).toHaveLength(2)
            expect(result[0].code).toBe('GHN')
        })
    })

    describe('getShipmentByOrderId', () => {
        it('gửi GET /shipments/order/{orderId} và trả về thông tin vận đơn', async () => {
            const mockShipment: ShipmentResponse = {
                id: 'shipment-uuid-1',
                orderId: 'order-uuid-1',
                carrier: 'GHN',
                serviceType: 'EXPRESS',
                shippingFee: 55000,
                freeShippingApplied: false,
                trackingNumber: 'GHN12345678VN',
                status: 'SHIPPED',
                estimatedDeliveryDays: 3,
                shippingAddress: '123 Nguyen Hue',
            }

            const mockFetch = vi.fn().mockResolvedValue({
                ok: true,
                status: 200,
                json: async () => ({
                    success: true,
                    data: mockShipment,
                }),
            })
            vi.stubGlobal('fetch', mockFetch)

            const result = await shippingApi.getShipmentByOrderId('order-uuid-1')
            expect(result).not.toBeNull()
            expect(result?.trackingNumber).toBe('GHN12345678VN')
            expect(result?.carrier).toBe('GHN')
            expect(result?.status).toBe('SHIPPED')
        })

        it('trả về null khi backend trả về 404 Not Found (đơn hàng chưa có vận đơn)', async () => {
            const mockFetch = vi.fn().mockResolvedValue({
                ok: false,
                status: 404,
                json: async () => ({
                    success: false,
                    error: { code: 'RESOURCE_NOT_FOUND', message: 'Shipment not found' },
                }),
            })
            vi.stubGlobal('fetch', mockFetch)

            const result = await shippingApi.getShipmentByOrderId('non-existent-order')
            expect(result).toBeNull()
        })
    })

    describe('getShipmentTracking', () => {
        it('gửi GET /shipments/{id}/tracking và trả về hành trình chi tiết', async () => {
            const mockTracking: ShipmentTrackingResponse = {
                shipmentId: 'shipment-uuid-1',
                orderId: 'order-uuid-1',
                carrier: 'GHN',
                trackingNumber: 'GHN12345678VN',
                status: 'IN_TRANSIT',
                carrierStatus: 'in_transit',
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
                        occurredAt: '2026-11-13T10:30:00Z',
                        location: 'Hub Tân Bình, TP.HCM',
                        description: 'Đang luân chuyển qua bưu cục trung chuyển',
                    },
                ],
            }

            const mockFetch = vi.fn().mockResolvedValue({
                ok: true,
                status: 200,
                json: async () => ({
                    success: true,
                    data: mockTracking,
                }),
            })
            vi.stubGlobal('fetch', mockFetch)

            const result = await shippingApi.getShipmentTracking('shipment-uuid-1')
            expect(result).not.toBeNull()
            expect(result?.trackingNumber).toBe('GHN12345678VN')
            expect(result?.status).toBe('IN_TRANSIT')
            expect(result?.history).toHaveLength(2)
        })
    })

    describe('createShipment', () => {
        it('gửi POST /shipments có kèm Idempotency-Key và trả về vận đơn mới được tạo', async () => {
            const mockCreated: ShipmentResponse = {
                id: 'shipment-new-uuid',
                orderId: 'order-999',
                carrier: 'GHTK',
                shippingFee: 35000,
                freeShippingApplied: false,
                trackingNumber: 'GHTK999888VN',
                status: 'PENDING',
            }

            const mockFetch = vi.fn().mockResolvedValue({
                ok: true,
                status: 201,
                json: async () => ({
                    success: true,
                    data: mockCreated,
                }),
            })
            vi.stubGlobal('fetch', mockFetch)

            const result = await shippingApi.createShipment(
                {
                    orderId: 'order-999',
                    carrier: 'GHTK',
                    recipientName: 'Minh Tran',
                    recipientPhone: '0901234567',
                    shippingAddress: '123 Nguyen Hue',
                },
                'idemp-key-1234',
            )

            expect(mockFetch).toHaveBeenCalledTimes(1)
            const [, options] = mockFetch.mock.calls[0]
            const headers =
                options.headers instanceof Headers
                    ? options.headers.get('Idempotency-Key')
                    : options.headers['Idempotency-Key']
            expect(headers).toBe('idemp-key-1234')
            expect(result.id).toBe('shipment-new-uuid')
            expect(result.trackingNumber).toBe('GHTK999888VN')
        })
    })
})
