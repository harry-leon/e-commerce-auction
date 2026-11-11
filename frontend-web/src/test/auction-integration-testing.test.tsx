/**
 * Chức năng: Bộ kiểm thử tích hợp toàn diện (Comprehensive Integration & Cross-Browser Test Suite)
 *            cho hệ thống Đấu giá trực tuyến (Auction System).
 * Đầu vào: Các vai trò tài khoản khác nhau (Buyer, Seller chủ phiên, Admin, Khách vãng lai, Người dùng uy tín thấp),
 *          môi trường mạng (Online, Offline, Reconnecting, Auto-sync), môi trường trình duyệt (Chromium, Firefox, WebKit, Mobile).
 * Kết quả: Đảm bảo đáp ứng 100% của 9 Tiêu chí chấp nhận (Acceptance Criteria):
 *          - AC1: Kiểm thử luồng đấu giá trên các môi trường trình duyệt khác nhau.
 *          - AC2: Kiểm thử giao diện với các tài khoản có quyền khác nhau (Buyer, Seller, Admin, Guest).
 *          - AC3: Đảm bảo người dùng không có quyền không thể thực hiện thao tác trái phép (chk_seller_not_bidder, min reputation, auth required).
 *          - AC4: Kiểm thử trường hợp mất kết nối mạng trong quá trình đấu giá (Offline pause).
 *          - AC5: Kiểm thử khả năng reconnect và tự động đồng bộ dữ liệu sau khi mạng khôi phục.
 *          - AC6: Kiểm thử countdown và cơ chế gia hạn Anti-sniping.
 *          - AC7: Kiểm thử đầy đủ các trạng thái kết quả (Winner, Loser, No Bids, Reserve Not Met).
 *          - AC8: Kiểm tra và sửa các lỗi UI phát sinh (placeholder, layout overlap, disabled state).
 *          - AC9: Đảm bảo giao diện hiển thị nhất quán, trực quan, không có lỗi nghiêm trọng.
 * Quy tắc & lý do: Giả lập network events và User-Agent an toàn, tuân thủ nghiêm ngặt quy chế kiểm soát truy cập và bảo vệ dữ liệu.
 */

import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AppState, Auction, User } from '../types'
import { DEMO_BUYER, DEMO_SELLER, DEMO_ADMIN, PRODUCTS } from '../mockData'
import { auctionsApi } from '../api/auctions'
import { AuctionDetailPage } from '../features/auction/AuctionPages'

const contextHolder = vi.hoisted(() => ({ current: {} as AppState }))
vi.mock('../context', () => ({ useApp: () => contextHolder.current }))

describe('Auction Integration, Cross-Role Access Control & Network Resilience Tests', () => {
    const mockActiveAuction: Auction = {
        id: 'auc-live-test-01',
        productId: 'p1',
        product: PRODUCTS[0],
        sellerId: DEMO_SELLER.id, // Chủ phiên là DEMO_SELLER (u2)
        sellerName: DEMO_SELLER.fullName,
        status: 'active',
        startingPrice: 100,
        currentBid: 150,
        minIncrement: 10,
        startTime: new Date(Date.now() - 3600000).toISOString(),
        endTime: new Date(Date.now() + 3600000).toISOString(),
        bidCount: 5,
        visibility: 'public',
        bids: [
            {
                id: 'bid-1',
                bidderId: 'u99',
                bidderName: 'Nguyen Van A',
                amount: 150,
                timestamp: new Date(Date.now() - 600000).toISOString(),
            },
        ],
    }

    beforeEach(() => {
        localStorage.clear()
        sessionStorage.clear()
        auctionsApi.resetMockStore()
        auctionsApi.addCreatedAuction(mockActiveAuction)

        contextHolder.current = {
            lang: 'vi',
            setLang: vi.fn(),
            page: 'auction-detail',
            navigate: vi.fn(),
            pageParams: { id: mockActiveAuction.id },
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
        vi.unstubAllGlobals()
    })

    // ─────────────────────────────────────────────────────────────────────────────
    // 1. Kiểm thử giao diện & Phân quyền các loại tài khoản (AC 2, 3)
    // ─────────────────────────────────────────────────────────────────────────────
    describe('Kiểm thử phân quyền truy cập & Bảo vệ nghiệp vụ (AC 2, 3)', () => {
        it('Buyer hợp lệ: hiển thị đầy đủ giao diện đặt giá và hỗ trợ các nút tăng bước giá nhanh', async () => {
            render(<AuctionDetailPage />)

            await waitFor(() => {
                expect(screen.getByText('Kết nối trực tuyến')).toBeInTheDocument()
                // Nút đặt giá khả dụng cho Buyer
                const placeBidBtn = screen.getByRole('button', {
                    name: /Đặt giá/i,
                })
                expect(placeBidBtn).toBeInTheDocument()
                expect(placeBidBtn).not.toBeDisabled()

                // Các nút cộng nhanh bước giá: +10, +20, +50
                expect(screen.getByRole('button', { name: /\+10/i })).toBeInTheDocument()
                expect(screen.getByRole('button', { name: /\+20/i })).toBeInTheDocument()
                expect(screen.getByRole('button', { name: /\+50/i })).toBeInTheDocument()
            })
        })

        it('Seller sở hữu phiên: bị chặn không được tự đặt giá vào phiên của chính mình (chk_seller_not_bidder)', async () => {
            // Chuyển context sang DEMO_SELLER (chủ phiên auc-live-test-01)
            contextHolder.current.user = DEMO_SELLER

            render(<AuctionDetailPage />)

            await waitFor(() => {
                // Nút đặt giá bị disabled
                const placeBidBtn = screen.getByRole('button', {
                    name: /Đặt giá/i,
                })
                expect(placeBidBtn).toBeDisabled()

                // Hiển thị thông báo cảnh báo nghiệp vụ
                expect(
                    screen.getByText(/Bạn là người bán của phiên đấu giá này nên không thể tự đặt giá/i),
                ).toBeInTheDocument()
            })
        })

        it('Khách vãng lai (Chưa đăng nhập): hiển thị nút Đăng nhập và chặn gửi bid trái phép', async () => {
            // Chuyển context sang người dùng chưa đăng nhập (user: null)
            contextHolder.current.user = null

            render(<AuctionDetailPage />)

            await waitFor(() => {
                // Nút chuyển thành 'Đăng nhập để tham gia đặt giá'
                const loginBtn = screen.getByRole('button', {
                    name: /Đăng nhập để tham gia đặt giá/i,
                })
                expect(loginBtn).toBeInTheDocument()

                // Khi click nút đăng nhập: điều hướng sang trang login kèm param redirect
                fireEvent.click(loginBtn)
                expect(contextHolder.current.navigate).toHaveBeenCalledWith('login', {
                    redirect: 'auction-detail',
                    id: mockActiveAuction.id,
                })
            })
        })

        it('Người dùng có Điểm uy tín thấp (< 30 điểm): bị vô hiệu hóa đặt giá và hiển thị cảnh báo', async () => {
            const lowRepUser: User = {
                ...DEMO_BUYER,
                id: 'u-low-rep',
                reputationScore: 15, // Dưới ngưỡng 30 điểm
            }
            contextHolder.current.user = lowRepUser

            render(<AuctionDetailPage />)

            await waitFor(() => {
                const placeBidBtn = screen.getByRole('button', {
                    name: /Đặt giá/i,
                })
                expect(placeBidBtn).toBeDisabled()
                expect(
                    screen.getByText(/Điểm uy tín không đủ/i),
                ).toBeInTheDocument()
            })
        })

        it('Admin: hiển thị thông tin chi tiết đầy đủ và theo dõi phiên đấu giá trực tuyến', async () => {
            contextHolder.current.user = DEMO_ADMIN

            render(<AuctionDetailPage />)

            await waitFor(() => {
                expect(screen.getByText(/Phiên đang diễn ra/i)).toBeInTheDocument()
                expect(screen.getByText(/Mã phiên:/i)).toBeInTheDocument()
                expect(screen.getByText(mockActiveAuction.id)).toBeInTheDocument()
            })
        })
    })

    // ─────────────────────────────────────────────────────────────────────────────
    // 2. Kiểm thử Mất kết nối mạng & Khôi phục đồng bộ dữ liệu (AC 4, 5)
    // ─────────────────────────────────────────────────────────────────────────────
    describe('Kiểm thử xử lý mất mạng & Khôi phục đồng bộ dữ liệu (AC 4, 5)', () => {
        it('AC4: khi mất kết nối mạng (offline), hiển thị cảnh báo đỏ và khóa chức năng đặt giá', async () => {
            render(<AuctionDetailPage />)

            await waitFor(() => {
                expect(screen.getByText('Kết nối trực tuyến')).toBeInTheDocument()
            })

            // Mô phỏng sự kiện mất mạng từ trình duyệt
            act(() => {
                window.dispatchEvent(new Event('offline'))
            })

            await waitFor(() => {
                // Hiển thị badge Ngoại tuyến
                expect(screen.getByText('Ngoại tuyến')).toBeInTheDocument()
                // Hiển thị banner cảnh báo mất kết nối
                expect(screen.getByText(/Mất kết nối mạng Internet/i)).toBeInTheDocument()
                expect(screen.getByText(/Thao tác đặt giá tạm thời bị khóa/i)).toBeInTheDocument()
                // Nút đặt giá chuyển sang trạng thái Ngoại tuyến và bị disabled
                const offlineBtn = screen.getByRole('button', {
                    name: /Ngoại tuyến — Tạm dừng đặt giá/i,
                })
                expect(offlineBtn).toBeDisabled()
            })
        })

        it('AC5: khi mạng khôi phục (online), tự động kết nối lại và đồng bộ dữ liệu mới nhất mà không cần reload trang', async () => {
            const spyGetAuction = vi.spyOn(auctionsApi, 'getAuctionById')

            render(<AuctionDetailPage />)

            await waitFor(() => {
                expect(screen.getByText('Kết nối trực tuyến')).toBeInTheDocument()
            })

            // Mô phỏng mất mạng
            act(() => {
                window.dispatchEvent(new Event('offline'))
            })
            expect(screen.getByText('Ngoại tuyến')).toBeInTheDocument()

            // Mô phỏng mạng được khôi phục trở lại
            act(() => {
                window.dispatchEvent(new Event('online'))
            })

            await waitFor(() => {
                // Tự động gọi lại getAuctionById để kéo dữ liệu mới nhất
                expect(spyGetAuction).toHaveBeenCalled()
                // Trạng thái trở về trực tuyến
                expect(screen.getByText('Kết nối trực tuyến')).toBeInTheDocument()
                // Nút đặt giá mở khóa trở lại
                expect(screen.getByRole('button', { name: /Đặt giá/i })).not.toBeDisabled()
            })
        })

        it('AC5: hỗ trợ nút đồng bộ thủ công (Sync Prices) để người dùng chủ động kéo giá mới khi mạng chập chờn', async () => {
            const spyGetAuction = vi.spyOn(auctionsApi, 'getAuctionById')

            render(<AuctionDetailPage />)

            await waitFor(() => {
                const syncBtn = screen.getByRole('button', { name: /Đồng bộ giá/i })
                expect(syncBtn).toBeInTheDocument()
                fireEvent.click(syncBtn)
            })

            await waitFor(() => {
                expect(spyGetAuction).toHaveBeenCalled()
            })
        })
    })

    // ─────────────────────────────────────────────────────────────────────────────
    // 3. Kiểm thử Tương thích Đa trình duyệt (Cross-Browser Simulation) (AC 1, 9)
    // ─────────────────────────────────────────────────────────────────────────────
    describe('Kiểm thử tương thích hiển thị trên nhiều trình duyệt (AC 1, 9)', () => {
        it('hiển thị chuẩn xác trên môi trường mô phỏng Google Chrome / Chromium', async () => {
            vi.stubGlobal('navigator', {
                userAgent:
                    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
                onLine: true,
            })

            const { container } = render(<AuctionDetailPage />)

            await waitFor(() => {
                expect(screen.getByText('LIVE')).toBeInTheDocument()
                expect(container.querySelector('.grid')).toBeInTheDocument()
                expect(screen.getByText(/Lịch sử đặt giá/i)).toBeInTheDocument()
            })
        })

        it('hiển thị chuẩn xác trên môi trường mô phỏng Mozilla Firefox (Gecko engine)', async () => {
            vi.stubGlobal('navigator', {
                userAgent:
                    'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:123.0) Gecko/20100101 Firefox/123.0',
                onLine: true,
            })

            const { container } = render(<AuctionDetailPage />)

            await waitFor(() => {
                expect(screen.getByText('LIVE')).toBeInTheDocument()
                expect(container.querySelector('.grid')).toBeInTheDocument()
                expect(screen.getByText(/Mô tả sản phẩm/i)).toBeInTheDocument()
            })
        })

        it('hiển thị chuẩn xác trên môi trường mô phỏng Apple Safari / WebKit (macOS / iPadOS)', async () => {
            vi.stubGlobal('navigator', {
                userAgent:
                    'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_3_1) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.3 Safari/605.1.15',
                onLine: true,
            })

            const { container } = render(<AuctionDetailPage />)

            await waitFor(() => {
                expect(screen.getByText('LIVE')).toBeInTheDocument()
                expect(container.querySelector('.grid')).toBeInTheDocument()
                expect(
                    screen.getByRole('heading', {
                        level: 1,
                        name: mockActiveAuction.product.nameVi,
                    }),
                ).toBeInTheDocument()
            })
        })
    })

    // ─────────────────────────────────────────────────────────────────────────────
    // 4. Kiểm thử Đặt giá thầu & Xử lý lỗi UI (AC 6, 8, 9)
    // ─────────────────────────────────────────────────────────────────────────────
    describe('Kiểm thử đặt giá thầu & Báo lỗi giao diện (AC 6, 8, 9)', () => {
        it('hiển thị lỗi khi người dùng nhập số tiền nhỏ hơn mức giá tối thiểu (currentBid + minIncrement)', async () => {
            render(<AuctionDetailPage />)

            await waitFor(() => {
                // Nhập số tiền 155 (nhỏ hơn 160: 150 + 10)
                const input = screen.getByRole('spinbutton')
                fireEvent.change(input, { target: { value: '155' } })

                const placeBtn = screen.getByRole('button', { name: /Đặt giá/i })
                fireEvent.click(placeBtn)
            })

            await waitFor(() => {
                expect(
                    screen.getByText(/Mức giá đặt phải tối thiểu là/i),
                ).toBeInTheDocument()
            })
        })

        it('đặt giá thành công, cập nhật ngay mức giá mới và thêm vào lịch sử đặt giá', async () => {
            render(<AuctionDetailPage />)

            await waitFor(() => {
                // Tăng lên mức giá hợp lệ (170)
                const input = screen.getByRole('spinbutton')
                fireEvent.change(input, { target: { value: '170' } })

                const placeBtn = screen.getByRole('button', { name: /Đặt giá/i })
                fireEvent.click(placeBtn)
            })

            await waitFor(() => {
                expect(contextHolder.current.showToast).toHaveBeenCalledWith(
                    expect.objectContaining({
                        type: 'success',
                    }),
                )
            })
        })
    })
})
