/**
 * Chức năng: Bộ kiểm thử đơn vị & tích hợp (Unit & Integration Tests) cho nghiệp vụ:
 *            1. Đồng hồ đếm ngược phiên đấu giá (CountdownTimer): tự động cập nhật, gia hạn theo Anti-sniping, dừng khi hết giờ.
 *            2. Hiển thị trạng thái kết quả phiên đấu giá (AuctionDetailPage): Winner, Loser, No Bids, Reserve Not Met, bảng tổng kết kết quả.
 *            3. Quy trình thanh toán và khởi tạo đơn hàng trúng đấu giá theo hợp đồng (AuctionPaymentPage).
 * Đầu vào: User context (DEMO_BUYER), Mock Auctions (Active, Ended, Extended), API Adapter, Testing Library.
 * Kết quả: Kiểm chứng đáp ứng 100% của 10 Tiêu chí chấp nhận (Acceptance Criteria):
 *          - AC1: Hiển thị countdown thời gian còn lại.
 *          - AC2: Countdown tự động cập nhật không cần reload.
 *          - AC3: Xử lý trường hợp phiên đấu giá được gia hạn thời gian (Anti-sniping).
 *          - AC4: Cập nhật countdown theo thời gian kết thúc mới.
 *          - AC5: Dừng countdown khi phiên đấu giá kết thúc.
 *          - AC6: Hiển thị trạng thái Winner khi người dùng thắng đấu giá.
 *          - AC7: Hiển thị trạng thái Loser khi người dùng không thắng đấu giá.
 *          - AC8: Hiển thị thông tin kết quả đấu giá chính xác (Bảng tổng kết).
 *          - AC9: Điều hướng winner đến quy trình tạo hoặc xem Order theo contract.
 *          - AC10: Xử lý trường hợp phiên đấu giá kết thúc nhưng chưa có người thắng.
 * Quy tắc & lý do: Giả lập đồng hồ Vitest fake timers, mô phỏng localStorage và API Adapter bảo đảm tính minh bạch.
 */

import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AppState, Auction, Order } from '../types'
import { DEMO_BUYER, PRODUCTS } from '../mockData'
import { auctionsApi } from '../api/auctions'
import { readLocalMockOrders, writeLocalMockOrders } from '../api/orders'
import { CountdownTimer } from '../components/UI'
import { AuctionDetailPage, AuctionPaymentPage } from '../features/auction/AuctionPages'

const contextHolder = vi.hoisted(() => ({ current: {} as AppState }))
vi.mock('../context', () => ({ useApp: () => contextHolder.current }))

describe('Auction Countdown, Extension, Result Settlement & Order Flow', () => {
    beforeEach(() => {
        localStorage.clear()
        sessionStorage.clear()
        auctionsApi.resetMockStore()

        // Giả lập context mặc định
        contextHolder.current = {
            lang: 'vi',
            setLang: vi.fn(),
            page: 'auction-detail',
            navigate: vi.fn(),
            pageParams: { id: 'a1' },
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
        vi.useRealTimers()
    })

    // ─────────────────────────────────────────────────────────────────────────────
    // 1. CountdownTimer: Cập nhật thời gian thực, gia hạn và dừng khi kết thúc (AC 1, 2, 4, 5)
    // ─────────────────────────────────────────────────────────────────────────────
    describe('CountdownTimer Component (AC 1, 2, 4, 5)', () => {
        it('AC1 & AC2: hiển thị thời gian còn lại và tự động cập nhật mỗi giây không cần reload', () => {
            vi.useFakeTimers()
            const baseTime = 1700000000000
            vi.setSystemTime(baseTime)
            const targetEndTime = new Date(baseTime + 600 * 1000).toISOString()

            const { container } = render(<CountdownTimer endTime={targetEndTime} />)
            expect(container.textContent).toBe('00:10:00')

            // Tiến nhanh 120 giây
            act(() => {
                vi.advanceTimersByTime(120 * 1000)
            })

            // Thời gian còn lại phải tự cập nhật còn 8m
            expect(container.textContent).toBe('00:08:00')
        })

        it('AC4: cập nhật countdown theo thời gian kết thúc mới khi được gia hạn (Extension)', () => {
            vi.useFakeTimers()
            const baseTime = 1700000000000
            vi.setSystemTime(baseTime)
            const initialEndTime = new Date(baseTime + 300 * 1000).toISOString() // 5 phút

            const { container, rerender } = render(<CountdownTimer endTime={initialEndTime} />)
            expect(container.textContent).toBe('00:05:00')

            // Gia hạn thêm 10 phút nữa (tổng 15 phút)
            const extendedEndTime = new Date(baseTime + 900 * 1000).toISOString()
            rerender(<CountdownTimer endTime={extendedEndTime} />)

            // Countdown phải tính lại ngay lập tức theo mốc mới
            expect(container.textContent).toBe('00:15:00')
        })

        it('AC5: dừng countdown và kích hoạt callback onEnd khi phiên đấu giá kết thúc', () => {
            vi.useFakeTimers()
            const baseTime = 1700000000000
            vi.setSystemTime(baseTime)
            const shortEndTime = new Date(baseTime + 2000).toISOString() // 2 giây
            const onEndMock = vi.fn()

            const { container } = render(
                <CountdownTimer endTime={shortEndTime} onEnd={onEndMock} />,
            )

            expect(onEndMock).not.toHaveBeenCalled()

            // Cho thời gian trôi qua 3 giây (vượt quá endTime)
            act(() => {
                vi.advanceTimersByTime(3000)
            })

            // Callback onEnd được gọi đúng 1 lần và countdown hiển thị 00:00:00
            expect(onEndMock).toHaveBeenCalledTimes(1)
            expect(container.textContent).toBe('00:00:00')
        })
    })

    // ─────────────────────────────────────────────────────────────────────────────
    // 2. Chi tiết phiên đấu giá: Anti-sniping Extension & Countdown Realtime (AC 3, 4)
    // ─────────────────────────────────────────────────────────────────────────────
    describe('AuctionDetailPage: Anti-sniping Extension (AC 3, 4)', () => {
        it('AC3 & AC4: hiển thị banner gia hạn và cập nhật endTime khi phiên kích hoạt Anti-sniping', async () => {
            const extendedAuction: Auction = {
                id: 'test-auc-extended',
                productId: 'p1',
                product: PRODUCTS[0],
                sellerId: 'u2',
                sellerName: 'TechZone Official',
                status: 'active',
                startingPrice: 100,
                currentBid: 150,
                minIncrement: 10,
                startTime: new Date(Date.now() - 3600000).toISOString(),
                endTime: new Date(Date.now() + 300000).toISOString(), // Còn 5 phút
                bidCount: 5,
                visibility: 'public',
                bids: [],
                isExtended: true,
                extensionCount: 2,
            }

            auctionsApi.addCreatedAuction(extendedAuction)
            contextHolder.current.pageParams = { id: 'test-auc-extended' }

            render(<AuctionDetailPage />)

            await waitFor(() => {
                expect(screen.getByText(/Phiên đã được gia hạn/i)).toBeInTheDocument()
                expect(screen.getByText(/\+2 lần/i)).toBeInTheDocument()
                expect(screen.getByText(/Quy tắc chống bắn tỉa phút chót/i)).toBeInTheDocument()
            })
        })
    })

    // ─────────────────────────────────────────────────────────────────────────────
    // 3. Trạng thái Winner, Loser và Bảng tổng kết kết quả (AC 6, 7, 8, 9, 10)
    // ─────────────────────────────────────────────────────────────────────────────
    describe('AuctionDetailPage: Settlement Results & Order Navigation (AC 6, 7, 8, 9, 10)', () => {
        it('AC6 & AC8 & AC9: hiển thị trạng thái Winner kèm bảng tổng kết và nút điều hướng thanh toán', async () => {
            // Phiên đã kết thúc và DEMO_BUYER (u1, Minh Tran) là winner
            const wonAuction: Auction = {
                id: 'test-auc-winner',
                productId: 'p1',
                product: PRODUCTS[0],
                sellerId: 'u2',
                sellerName: 'TechZone Official',
                status: 'ended',
                startingPrice: 100,
                currentBid: 250,
                minIncrement: 10,
                startTime: new Date(Date.now() - 7200000).toISOString(),
                endTime: new Date(Date.now() - 3600000).toISOString(),
                bidCount: 8,
                visibility: 'public',
                winnerId: DEMO_BUYER.id,
                winnerName: DEMO_BUYER.fullName,
                extensionCount: 1,
                bids: [
                    {
                        id: 'b-win',
                        bidderId: DEMO_BUYER.id,
                        bidderName: DEMO_BUYER.fullName,
                        amount: 250,
                        timestamp: new Date(Date.now() - 4000000).toISOString(),
                    },
                ],
            }

            auctionsApi.addCreatedAuction(wonAuction)
            contextHolder.current.pageParams = { id: 'test-auc-winner' }

            render(<AuctionDetailPage />)

            await waitFor(() => {
                // AC6: Hiển thị trạng thái Winner
                expect(screen.getByText('CHIẾN THẮNG')).toBeInTheDocument()
                expect(screen.getByText(/Chúc mừng! Bạn đã thắng phiên đấu giá/i)).toBeInTheDocument()
                expect(screen.getByText(/Giá thắng chung cuộc/i)).toBeInTheDocument()

                // AC8: Bảng tổng kết kết quả đấu giá chính xác
                expect(screen.getByText(/Tổng kết kết quả đấu giá/i)).toBeInTheDocument()
                expect(screen.getByText(/Giá khởi điểm/i)).toBeInTheDocument()
                expect(screen.getByText(/Mức tăng/i)).toBeInTheDocument()
                expect(screen.getByText(/\+150\.0%/i)).toBeInTheDocument() // (250-100)/100 = 150%
                expect(screen.getByText(/Số lần gia hạn/i)).toBeInTheDocument()

                // AC9: Nút điều hướng thanh toán / đặt hàng
                const checkoutBtn = screen.getByRole('button', {
                    name: /Tiến hành đặt hàng & Thanh toán/i,
                })
                expect(checkoutBtn).toBeInTheDocument()
                fireEvent.click(checkoutBtn)
                expect(contextHolder.current.navigate).toHaveBeenCalledWith('auction-payment', {
                    auctionId: 'test-auc-winner',
                })
            })
        })

        it('AC9: khi Winner đã có đơn hàng thì hiển thị nút Xem chi tiết đơn hàng (order-detail)', async () => {
            const auctionId = 'test-auc-with-order'
            const existingOrder: Order = {
                id: 'ORD-AUC-123456',
                orderNo: 'ORD-AUC-123456',
                buyerId: DEMO_BUYER.id,
                buyerName: DEMO_BUYER.fullName,
                sellerId: 'u2',
                sellerName: 'TechZone',
                items: [
                    {
                        productId: 'p1',
                        productName: 'Test Product',
                        quantity: 1,
                        unitPrice: 300,
                        subtotal: 300,
                        image: '',
                    },
                ],
                subtotal: 300,
                tax: 24,
                discount: 0,
                shipping: 5.99,
                total: 329.99,
                status: 'PAID',
                refundStatus: 'none',
                paymentStatus: 'held',
                paymentMethod: 'Credit Card',
                shippingAddress: '123 Test St',
                isAuctionOrder: true,
                sourceType: 'AUCTION',
                auctionId: auctionId,
                createdAt: new Date().toISOString(),
                updatedAt: new Date().toISOString(),
            }
            writeLocalMockOrders([existingOrder])

            const wonAuctionWithOrder: Auction = {
                id: auctionId,
                productId: 'p1',
                product: PRODUCTS[0],
                sellerId: 'u2',
                sellerName: 'TechZone Official',
                status: 'ended',
                startingPrice: 100,
                currentBid: 300,
                minIncrement: 10,
                startTime: new Date(Date.now() - 7200000).toISOString(),
                endTime: new Date(Date.now() - 3600000).toISOString(),
                bidCount: 5,
                visibility: 'public',
                winnerId: DEMO_BUYER.id,
                winnerName: DEMO_BUYER.fullName,
                orderId: existingOrder.id,
                bids: [],
            }

            auctionsApi.addCreatedAuction(wonAuctionWithOrder)
            contextHolder.current.pageParams = { id: auctionId }

            render(<AuctionDetailPage />)

            await waitFor(() => {
                expect(screen.getByText(/Đơn hàng đã được tạo/i)).toBeInTheDocument()
                const viewOrderBtn = screen.getByRole('button', {
                    name: /Xem chi tiết đơn hàng/i,
                })
                expect(viewOrderBtn).toBeInTheDocument()
                fireEvent.click(viewOrderBtn)
                expect(contextHolder.current.navigate).toHaveBeenCalledWith('order-detail', {
                    id: existingOrder.id,
                })
            })
        })

        it('AC7: hiển thị trạng thái Loser khi người dùng đã tham gia nhưng bị vượt giá', async () => {
            // DEMO_BUYER đã đặt 180 nhưng người thắng chung cuộc là 220
            const lostAuction: Auction = {
                id: 'test-auc-loser',
                productId: 'p1',
                product: PRODUCTS[0],
                sellerId: 'u2',
                sellerName: 'TechZone Official',
                status: 'ended',
                startingPrice: 100,
                currentBid: 220,
                minIncrement: 10,
                startTime: new Date(Date.now() - 7200000).toISOString(),
                endTime: new Date(Date.now() - 3600000).toISOString(),
                bidCount: 4,
                visibility: 'public',
                winnerId: 'u-other-winner',
                winnerName: 'Nguyen Van A',
                bids: [
                    {
                        id: 'b-win',
                        bidderId: 'u-other-winner',
                        bidderName: 'Nguyen Van A',
                        amount: 220,
                        timestamp: new Date(Date.now() - 3700000).toISOString(),
                    },
                    {
                        id: 'b-mine',
                        bidderId: DEMO_BUYER.id,
                        bidderName: DEMO_BUYER.fullName,
                        amount: 180,
                        timestamp: new Date(Date.now() - 3800000).toISOString(),
                    },
                ],
            }

            auctionsApi.addCreatedAuction(lostAuction)
            contextHolder.current.pageParams = { id: 'test-auc-loser' }

            render(<AuctionDetailPage />)

            await waitFor(() => {
                // AC7: Hiển thị badge Loser và so sánh giá
                expect(screen.getByText(/KHÔNG TRÚNG ĐẤU GIÁ/i)).toBeInTheDocument()
                expect(screen.getByText(/Rất tiếc! Bạn không thắng phiên đấu giá này/i)).toBeInTheDocument()
                expect(screen.getByText(/Giá cao nhất của bạn/i)).toBeInTheDocument()
                expect(screen.getByText(/Giá thắng chung cuộc/i)).toBeInTheDocument()
                expect(screen.getByText(/Tiền ký quỹ\/đặt cọc của bạn \(nếu có\) sẽ được hoàn trả/i)).toBeInTheDocument()

                // Nút chuyển duyệt các phiên khác
                const browseBtn = screen.getByRole('button', {
                    name: /Duyệt các phiên đấu giá khác/i,
                })
                expect(browseBtn).toBeInTheDocument()
                fireEvent.click(browseBtn)
                expect(contextHolder.current.navigate).toHaveBeenCalledWith('auction-list')
            })
        })

        it('AC10: xử lý trường hợp phiên đấu giá kết thúc nhưng không có người đặt giá (No Bids)', async () => {
            const noBidsAuction: Auction = {
                id: 'test-auc-no-bids',
                productId: 'p2',
                product: PRODUCTS[1],
                sellerId: 'u2',
                sellerName: 'TechZone Official',
                status: 'ended',
                startingPrice: 500,
                currentBid: 500,
                minIncrement: 20,
                startTime: new Date(Date.now() - 7200000).toISOString(),
                endTime: new Date(Date.now() - 3600000).toISOString(),
                bidCount: 0,
                visibility: 'public',
                bids: [],
            }

            auctionsApi.addCreatedAuction(noBidsAuction)
            contextHolder.current.pageParams = { id: 'test-auc-no-bids' }

            render(<AuctionDetailPage />)

            await waitFor(() => {
                expect(screen.getByText(/CHƯA CÓ NGƯỜI THẮNG/i)).toBeInTheDocument()
                expect(screen.getByText(/Phiên kết thúc — chưa có người đặt giá/i)).toBeInTheDocument()
                expect(screen.getByText(/Không có lượt trả giá hợp lệ nào/i)).toBeInTheDocument()
            })
        })

        it('AC10: xử lý trường hợp phiên kết thúc nhưng chưa đạt mức giá sàn (Reserve Not Met)', async () => {
            const reserveAuction: Auction = {
                id: 'test-auc-reserve-not-met',
                productId: 'p2',
                product: PRODUCTS[1],
                sellerId: 'u2',
                sellerName: 'TechZone Official',
                status: 'ended',
                startingPrice: 100,
                currentBid: 150,
                reservePrice: 300, // Giá sàn là 300 nhưng mới bid tới 150
                minIncrement: 10,
                startTime: new Date(Date.now() - 7200000).toISOString(),
                endTime: new Date(Date.now() - 3600000).toISOString(),
                bidCount: 2,
                visibility: 'public',
                bids: [
                    {
                        id: 'b1',
                        bidderId: 'u99',
                        bidderName: 'Stranger',
                        amount: 150,
                        timestamp: new Date(Date.now() - 3700000).toISOString(),
                    },
                ],
            }

            auctionsApi.addCreatedAuction(reserveAuction)
            contextHolder.current.pageParams = { id: 'test-auc-reserve-not-met' }

            render(<AuctionDetailPage />)

            await waitFor(() => {
                expect(screen.getByText(/CHƯA ĐẠT GIÁ SÀN/i)).toBeInTheDocument()
                expect(screen.getByText(/Chưa đạt mức giá sàn của người bán/i)).toBeInTheDocument()
            })
        })
    })

    // ─────────────────────────────────────────────────────────────────────────────
    // 4. Thanh toán trúng đấu giá: Tạo Order với contract sourceType 'AUCTION' (AC 9)
    // ─────────────────────────────────────────────────────────────────────────────
    describe('AuctionPaymentPage: Settlement Order Creation (AC 9)', () => {
        it('AC9: hoàn tất thanh toán tạo thành công bản ghi Order có sourceType: AUCTION và điều hướng', async () => {
            const paymentAuction: Auction = {
                id: 'auc-settle-pay',
                productId: 'p1',
                product: PRODUCTS[0],
                sellerId: 'u2',
                sellerName: 'TechZone Official',
                status: 'ended',
                startingPrice: 100,
                currentBid: 280,
                minIncrement: 10,
                startTime: new Date(Date.now() - 7200000).toISOString(),
                endTime: new Date(Date.now() - 3600000).toISOString(),
                bidCount: 6,
                visibility: 'public',
                winnerId: DEMO_BUYER.id,
                winnerName: DEMO_BUYER.fullName,
                bids: [],
            }

            auctionsApi.addCreatedAuction(paymentAuction)
            contextHolder.current.pageParams = { auctionId: 'auc-settle-pay' }

            render(<AuctionPaymentPage />)

            // Kiểm tra hiển thị thông tin thanh toán đấu giá
            expect(screen.getByText(/Chúc mừng! Bạn đã thắng phiên đấu giá này/i)).toBeInTheDocument()
            expect(screen.getByText(/Hoàn tất thanh toán trong 24 giờ/i)).toBeInTheDocument()

            // Click nút thanh toán
            const payBtn = screen.getByRole('button', { name: /Hoàn tất thanh toán/i })
            fireEvent.click(payBtn)

            await waitFor(
                () => {
                    const savedOrders = readLocalMockOrders()
                    expect(savedOrders.length).toBeGreaterThan(0)
                    const createdOrder = savedOrders[0]
                    expect(createdOrder.sourceType).toBe('AUCTION')
                    expect(createdOrder.isAuctionOrder).toBe(true)
                    expect(createdOrder.auctionId).toBe('auc-settle-pay')
                    expect(createdOrder.subtotal).toBe(280)
                    expect(contextHolder.current.navigate).toHaveBeenCalledWith('order-detail', {
                        id: createdOrder.id,
                    })
                },
                { timeout: 3000 },
            )
        })
    })
})
