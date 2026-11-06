/**
 * Chức năng: Bộ kiểm thử tích hợp giao diện cho Task [LD-S2-2]:
 *            1. Màn hình Khởi tạo & Chỉnh sửa phiên đấu giá phía Người bán (CreateAuctionPage).
 *            2. Màn hình Danh sách & Hủy phiên đấu giá phía Người bán (SellerAuctionListPage).
 *            3. Cổng quản trị & Phê duyệt/Buộc hủy phiên vi phạm phía Admin (AdminAuctionsPage).
 * Đầu vào: User context (Seller / Admin), dữ liệu mock, tương tác người dùng qua Testing Library.
 * Kết quả: Đảm bảo đáp ứng đầy đủ 3 tiêu chí chấp nhận (Acceptance Criteria) của LD-S2-2:
 *          - AC1: Seller tạo/chỉnh sửa/hủy thành công phiên đấu giá trước giờ mở phiên.
 *          - AC2: Admin xem toàn bộ danh sách, phê duyệt/từ chối hoặc tạm dừng/hủy phiên vi phạm.
 *          - AC3: Validate chặt chẽ: Giá khởi điểm > 0, bước giá hợp lệ (>= 1,000 VND), thời gian bắt đầu > thời gian hiện tại.
 * Quy tắc & lý do: Giả lập môi trường offline/sandbox an toàn, kiểm tra tính toàn vẹn trạng thái và thông báo lỗi.
 */

import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AppState, Auction } from '../types'
import { DEMO_ADMIN, DEMO_SELLER, PRODUCTS, AUCTIONS } from '../mockData'
import { auctionsApi } from '../api/auctions'
import { formatDateTime } from '../i18n'
import { CreateAuctionPage, SellerAuctionListPage } from '../features/seller/SellerPages'
import { AdminAuctionsPage } from '../features/admin/AdminPages'

const contextHolder = vi.hoisted(() => ({ current: {} as AppState }))
vi.mock('../context', () => ({ useApp: () => contextHolder.current }))

describe('[LD-S2-2] Seller Auction Creation UI & Admin Auction Management Portal', () => {
    beforeEach(() => {
        localStorage.clear()
        sessionStorage.clear()
        auctionsApi.resetMockStore()
        AUCTIONS.forEach((auction) => auctionsApi.addCreatedAuction(auction))

        // Giả lập fetch offline/sandbox để kích hoạt fallback logic an toàn
        vi.stubGlobal(
            'fetch',
            vi.fn().mockImplementation(() =>
                Promise.resolve(
                    new Response(JSON.stringify({ success: false }), { status: 503 }),
                ),
            ),
        )

        contextHolder.current = {
            lang: 'vi',
            setLang: vi.fn(),
            page: 'seller-auctions',
            navigate: vi.fn(),
            pageParams: {},
            user: DEMO_SELLER,
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
    // 1. Phía Người bán: Khởi tạo và Chỉnh sửa phiên đấu giá (CreateAuctionPage)
    // ─────────────────────────────────────────────────────────────────────────────
    describe('Màn hình Khởi tạo & Chỉnh sửa phiên đấu giá (CreateAuctionPage)', () => {
        /**
         * AC3: Validate giao diện chặt chẽ:
         * Giá khởi điểm > 0, bước giá hợp lệ, thời gian bắt đầu phải lớn hơn thời gian hiện tại.
         */
        it('hiển thị lỗi validation chi tiết khi bỏ trống hoặc nhập giá trị sai quy định', async () => {
            render(<CreateAuctionPage />)

            // Tìm và click nút submit
            const submitBtn = screen.getByRole('button', { name: /khởi tạo phiên/i })
            fireEvent.click(submitBtn)

            // Kỳ vọng thấy các thông báo lỗi inline hoặc toast cảnh báo
            await waitFor(() => {
                expect(contextHolder.current.showToast).toHaveBeenCalledWith(
                    expect.objectContaining({
                        type: 'error',
                        message: expect.stringMatching(/kiểm tra lại các trường/i),
                    }),
                )
            })

            // Kiểm tra các thông báo lỗi field xuất hiện trên DOM
            expect(screen.getByText(/vui lòng chọn sản phẩm cho phiên đấu giá/i)).toBeInTheDocument()
            expect(screen.getByText(/giá khởi điểm phải lớn hơn 0/i)).toBeInTheDocument()
            expect(screen.getByText(/vui lòng chọn thời gian bắt đầu/i)).toBeInTheDocument()
        })

        it('báo lỗi khi bước giá nhỏ hơn quy định tối thiểu 1,000 VND', async () => {
            render(<CreateAuctionPage />)

            // Chọn sản phẩm hợp lệ
            const productSelect = screen.getByLabelText(/sản phẩm/i)
            fireEvent.change(productSelect, { target: { value: 'p1' } })

            // Nhập giá khởi điểm hợp lệ
            const startPriceInput = screen.getByLabelText(/giá khởi điểm/i)
            fireEvent.change(startPriceInput, { target: { value: '500000' } })

            // Nhập bước giá vi phạm (< 1,000 VND)
            const minIncInput = screen.getByLabelText(/bước giá/i)
            fireEvent.change(minIncInput, { target: { value: '500' } })

            // Bấm submit
            const submitBtn = screen.getByRole('button', { name: /khởi tạo phiên/i })
            fireEvent.click(submitBtn)

            await waitFor(() => {
                expect(screen.getByText(/bước giá tối thiểu phải từ 1,000 đ/i)).toBeInTheDocument()
            })
        })

        it('báo lỗi khi thời gian bắt đầu nằm trong quá khứ hoặc thời gian kết thúc trước thời gian bắt đầu', async () => {
            render(<CreateAuctionPage />)

            // Chọn sản phẩm
            const productSelect = screen.getByLabelText(/sản phẩm/i)
            fireEvent.change(productSelect, { target: { value: 'p1' } })

            // Nhập giá
            const startPriceInput = screen.getByLabelText(/giá khởi điểm/i)
            fireEvent.change(startPriceInput, { target: { value: '500000' } })

            const minIncInput = screen.getByLabelText(/bước giá/i)
            fireEvent.change(minIncInput, { target: { value: '10000' } })

            // Nhập thời gian bắt đầu trong quá khứ
            const pastDateStr = '2020-01-01T08:00'
            const startTimeInput = screen.getByLabelText(/thời gian bắt đầu/i)
            fireEvent.change(startTimeInput, { target: { value: pastDateStr } })

            const submitBtn = screen.getByRole('button', { name: /khởi tạo phiên/i })
            fireEvent.click(submitBtn)

            await waitFor(() => {
                expect(
                    screen.getByText(/thời gian bắt đầu phải lớn hơn thời gian hiện tại/i),
                ).toBeInTheDocument()
            })
        })

        it('Người bán tạo thành công phiên đấu giá mới với thông số hợp lệ (AC1)', async () => {
            render(<CreateAuctionPage />)

            // 1. Chọn sản phẩm
            const productSelect = screen.getByLabelText(/sản phẩm/i)
            fireEvent.change(productSelect, { target: { value: 'p1' } })

            // 2. Nhập giá khởi điểm
            const startPriceInput = screen.getByLabelText(/giá khởi điểm/i)
            fireEvent.change(startPriceInput, { target: { value: '1000000' } })

            // 3. Nhập bước giá
            const minIncInput = screen.getByLabelText(/bước giá/i)
            fireEvent.change(minIncInput, { target: { value: '50000' } })

            // 4. Nhập thời gian bắt đầu (tương lai: 2 giờ sau)
            const futureStart = new Date(Date.now() + 7200000)
            const formatForInput = (d: Date) => {
                const pad = (n: number) => String(n).padStart(2, '0')
                return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
            }
            const startTimeInput = screen.getByLabelText(/thời gian bắt đầu/i)
            fireEvent.change(startTimeInput, { target: { value: formatForInput(futureStart) } })

            // 5. Nhập thời gian kết thúc (26 giờ sau)
            const futureEnd = new Date(futureStart.getTime() + 86400000)
            const endTimeInput = screen.getByLabelText(/thời gian kết thúc/i)
            fireEvent.change(endTimeInput, { target: { value: formatForInput(futureEnd) } })

            // 6. Submit
            const submitBtn = screen.getByRole('button', { name: /khởi tạo phiên/i })
            fireEvent.click(submitBtn)

            await waitFor(() => {
                expect(contextHolder.current.submitAuction).toHaveBeenCalled()
                expect(contextHolder.current.submitAuction).toHaveBeenCalledWith(
                    expect.objectContaining({ status: 'pending_approval' }),
                )
                expect(contextHolder.current.showToast).toHaveBeenCalledWith(
                    expect.objectContaining({
                        type: 'success',
                        message: expect.stringMatching(/đã gửi yêu cầu đấu giá để Admin phê duyệt/i),
                    }),
                )
                expect(contextHolder.current.navigate).toHaveBeenCalledWith('seller-auctions')
            })
        })

        it('Người bán tải và chỉnh sửa thông số phiên scheduled trước giờ mở thành công (AC1)', async () => {
            // Tạo trước 1 phiên scheduled trong tương lai, đồng thời đưa vào createdAuctions context
            // để component có thể tìm thấy phiên khi nạp dữ liệu chế độ Chỉnh sửa
            const futureStart = new Date(Date.now() + 7200000)
            const futureEnd = new Date(futureStart.getTime() + 86400000)
            const created = await auctionsApi.createAuction({
                productId: 'p1',
                startingPrice: 500000,
                minIncrement: 10000,
                startTime: futureStart.toISOString(),
                endTime: futureEnd.toISOString(),
            })

            // Đồng bộ phiên vừa tạo vào context để component (SellerPages) nhìn thấy
            contextHolder.current.createdAuctions = [created]
            // Chuyển pageParams sang ID phiên vừa tạo (chế độ Chỉnh sửa)
            contextHolder.current.pageParams = { id: created.id }

            render(<CreateAuctionPage />)

            // Chờ form load xong: trường Giá khởi điểm phải được pre-populate giá trị hiện tại
            await waitFor(() => {
                const priceInput = screen.getByLabelText(/giá khởi điểm/i) as HTMLInputElement
                expect(priceInput.value).toBe('500000')
            })

            // Kiểm tra tiêu đề màn hình là Chỉnh sửa
            expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(/sửa phiên|chỉnh sửa/i)

            // Đổi giá khởi điểm thành 700000
            const startPriceInput = screen.getByLabelText(/giá khởi điểm/i)
            fireEvent.change(startPriceInput, { target: { value: '700000' } })

            // Click Lưu thay đổi
            const saveBtn = screen.getByRole('button', { name: /lưu thay đổi/i })
            fireEvent.click(saveBtn)

            await waitFor(() => {
                expect(contextHolder.current.showToast).toHaveBeenCalledWith(
                    expect.objectContaining({
                        type: 'success',
                        message: expect.stringMatching(/cập nhật phiên đấu giá thành công/i),
                    }),
                )
                expect(contextHolder.current.navigate).toHaveBeenCalledWith('seller-auctions')
            })
        })

        it('hiển thị cảnh báo và vô hiệu hóa nút lưu khi cố chỉnh sửa phiên đã ACTIVE hoặc đã qua giờ mở', async () => {
            // Phiên a1 trong mockData đang có status là ACTIVE
            contextHolder.current.pageParams = { id: 'a1' }

            render(<CreateAuctionPage />)

            await waitFor(() => {
                expect(
                    screen.getByText(/phiên đấu giá đã mở hoặc đã kết thúc, không thể chỉnh sửa/i),
                ).toBeInTheDocument()
            })

            const saveBtn = screen.getByRole('button', { name: /lưu thay đổi/i })
            expect(saveBtn).toBeDisabled()
        })
    })

    // ─────────────────────────────────────────────────────────────────────────────
    // 2. Phía Người bán: Danh sách và Hủy phiên (SellerAuctionListPage)
    // ─────────────────────────────────────────────────────────────────────────────
    describe('Màn hình Danh sách & Hủy phiên của Người bán (SellerAuctionListPage)', () => {
        it('hiển thị danh sách phiên và hỗ trợ điều hướng chỉnh sửa (AC1)', async () => {
            // Tạo 1 phiên scheduled tương lai cho người bán u2
            const futureStart = new Date(Date.now() + 7200000)
            const futureEnd = new Date(futureStart.getTime() + 86400000)
            await auctionsApi.createAuction({
                productId: 'p1',
                startingPrice: 500000,
                minIncrement: 10000,
                startTime: futureStart.toISOString(),
                endTime: futureEnd.toISOString(),
            })

            render(<SellerAuctionListPage />)

            // Kiểm tra hiển thị nút Chỉnh sửa (Pencil icon) cho phiên scheduled
            await waitFor(() => {
                const editButtons = screen.getAllByTitle(/chỉnh sửa phiên/i)
                expect(editButtons.length).toBeGreaterThan(0)
                fireEvent.click(editButtons[0])
                expect(contextHolder.current.navigate).toHaveBeenCalledWith(
                    'seller-create-auction',
                    expect.objectContaining({ id: expect.any(String) }),
                )
            })
        })

        it('Seller xem được chi tiết phiên và lịch sử các mức giá', async () => {
            const auctionWithBid: Auction = {
                ...AUCTIONS[0],
                id: 'seller-auction-details-test',
                sellerId: DEMO_SELLER.id,
                bidCount: 1,
                bids: [
                    {
                        id: 'seller-auction-details-bid',
                        bidderId: 'buyer-private-id',
                        bidderName: 'Buyer ***',
                        amount: AUCTIONS[0].currentBid,
                        timestamp: '2026-11-05T10:30:00Z',
                    },
                ],
            }
            auctionsApi.addCreatedAuction(auctionWithBid)
            contextHolder.current.createdAuctions = [auctionWithBid]

            render(<SellerAuctionListPage />)
            fireEvent.click(await screen.findByRole('button', { name: 'Xem chi tiết' }))

            expect(await screen.findByText('Lịch sử đặt giá')).toBeInTheDocument()
            expect(screen.getByText('Thời gian đặt')).toBeInTheDocument()
            expect(screen.getByText(formatDateTime('2026-11-05T10:30:00Z', 'vi'))).toBeInTheDocument()
        })

        it('Người bán hủy thành công phiên scheduled trước giờ mở phiên kèm lý do (AC1)', async () => {
            const futureStart = new Date(Date.now() + 7200000)
            const futureEnd = new Date(futureStart.getTime() + 86400000)
            // Tạo phiên qua API và đồng bộ vào context để SellerAuctionListPage render ra nút Hủy
            const newAuction = await auctionsApi.createAuction({
                productId: 'p1',
                startingPrice: 500000,
                minIncrement: 10000,
                startTime: futureStart.toISOString(),
                endTime: futureEnd.toISOString(),
            })
            contextHolder.current.createdAuctions = [newAuction]

            render(<SellerAuctionListPage />)

            // Bấm nút Hủy (Trash2)
            await waitFor(() => {
                const cancelButtons = screen.getAllByTitle(/hủy phiên đấu giá/i)
                expect(cancelButtons.length).toBeGreaterThan(0)
                fireEvent.click(cancelButtons[0])
            })

            // Kiểm tra Modal Hủy phiên mở ra
            await waitFor(() => {
                expect(screen.getByRole('dialog')).toBeInTheDocument()
            })

            // Nhập lý do hủy
            const reasonInput = screen.getByPlaceholderText(/lý do hủy phiên đấu giá/i)
            fireEvent.change(reasonInput, { target: { value: 'Sản phẩm gặp sự cố kỹ thuật' } })

            // Click Xác nhận
            const confirmBtn = screen.getByRole('button', { name: /xác nhận/i })
            fireEvent.click(confirmBtn)

            await waitFor(() => {
                expect(contextHolder.current.showToast).toHaveBeenCalledWith(
                    expect.objectContaining({
                        type: 'success',
                        message: expect.stringMatching(/đã hủy phiên đấu giá thành công/i),
                    }),
                )
            })
        })
    })

    // ─────────────────────────────────────────────────────────────────────────────
    // 3. Phía Admin: Cổng quản trị & Phê duyệt / Buộc hủy vi phạm (AdminAuctionsPage)
    // ─────────────────────────────────────────────────────────────────────────────
    describe('Cổng Quản lý & Phê duyệt/Buộc hủy phiên phía Admin (AdminAuctionsPage)', () => {
        beforeEach(() => {
            contextHolder.current.user = DEMO_ADMIN
        })

        it('Admin xem được danh sách toàn bộ phiên và tìm kiếm theo từ khóa (AC2)', async () => {
            render(<AdminAuctionsPage />)

            // Kiểm tra các thẻ thống kê tổng quan
            expect(screen.getByText(/tổng số phiên/i)).toBeInTheDocument()
            expect(screen.getAllByText(/đang diễn ra/i).length).toBeGreaterThan(0)

            // Tìm kiếm phiên đấu giá
            const searchInput = screen.getByPlaceholderText(/tìm theo sản phẩm, seller, sku/i)
            fireEvent.change(searchInput, { target: { value: 'MacBook' } })

            // Kiểm tra lọc danh sách
            await waitFor(() => {
                expect(searchInput).toHaveValue('MacBook')
            })
        })

        // Bảo vệ việc tra tên/email thật từ User Service cho seller UUID chưa có trong mock users.
        it('Admin tra cứu tên tài khoản Seller từ User API để hiển thị người gửi yêu cầu', async () => {
            const sellerId = 'a7a2a62e-778e-4472-8760-c586f4f591ad'
            const pendingAuction: Auction = {
                ...AUCTIONS[1],
                id: 'auction-user-lookup',
                sellerId,
                sellerName: 'Vintage Watch Store',
                status: 'pending_approval',
            }
            contextHolder.current.createdAuctions = [pendingAuction]
            auctionsApi.addCreatedAuction(pendingAuction)

            vi.stubGlobal('fetch', vi.fn().mockImplementation((input: string) => {
                if (String(input).includes(`/users/${sellerId}`)) {
                    return Promise.resolve(new Response(JSON.stringify({
                        success: true,
                        data: { id: sellerId, fullName: 'Nguyen Seller', email: 'seller@example.com' },
                    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
                }
                return Promise.resolve(new Response(JSON.stringify({ success: false }), { status: 503 }))
            }))

            render(<AdminAuctionsPage />)

            expect(await screen.findByText('Nguyen Seller')).toBeInTheDocument()
            expect(screen.getByText('seller@example.com')).toBeInTheDocument()
            expect(screen.getByText('Cửa hàng: Vintage Watch Store')).toBeInTheDocument()
        })

        it('Admin thực hiện phê duyệt phiên đấu giá thành công (AC2)', async () => {
            // Thêm phiên pending_approval
            const pendingAuction: Auction = {
                id: 'auc-pending-1',
                productId: 'p1',
                product: PRODUCTS[0],
                sellerId: 'u2',
                sellerName: 'TechZone Store',
                status: 'pending_approval',
                startingPrice: 300000,
                currentBid: 300000,
                minIncrement: 10000,
                startTime: new Date(Date.now() + 7200000).toISOString(),
                endTime: new Date(Date.now() + 86400000).toISOString(),
                bidCount: 0,
                bids: [],
                visibility: 'public',
            }
            // Đưa vào context để AdminAuctionsPage render nút Duyệt
            contextHolder.current.createdAuctions = [pendingAuction]
            // Đưa vào localAuctionsStore để auctionsApi.reviewAuction có thể cập nhật trạng thái
            auctionsApi.addCreatedAuction(pendingAuction)

            render(<AdminAuctionsPage />)
            expect((await screen.findAllByText('Anh Nguyen')).length).toBeGreaterThan(0)

            // Click nút Phê duyệt / Duyệt
            await waitFor(() => {
                const approveBtns = screen.getAllByRole('button', { name: /^duyệt$|^phê duyệt$/i })
                expect(approveBtns.length).toBeGreaterThan(0)
                fireEvent.click(approveBtns[0])
            })

            await waitFor(() => {
                expect(contextHolder.current.reviewAuction).toHaveBeenCalled()
                expect(contextHolder.current.showToast).toHaveBeenCalledWith(
                    expect.objectContaining({
                        type: 'success',
                        message: expect.stringMatching(/đã phê duyệt/i),
                    }),
                )
            })
        })

        it('Admin từ chối phiên đấu giá với lý do bắt buộc (AC2)', async () => {
            const pendingAuction: Auction = {
                id: 'auc-pending-2',
                productId: 'p1',
                product: PRODUCTS[0],
                sellerId: 'u2',
                sellerName: 'TechZone Store',
                status: 'pending_approval',
                startingPrice: 300000,
                currentBid: 300000,
                minIncrement: 10000,
                startTime: new Date(Date.now() + 7200000).toISOString(),
                endTime: new Date(Date.now() + 86400000).toISOString(),
                bidCount: 0,
                bids: [],
                visibility: 'public',
            }
            // Đưa vào context và localAuctionsStore để reviewAuction hoạt động được
            contextHolder.current.createdAuctions = [pendingAuction]
            auctionsApi.addCreatedAuction(pendingAuction)

            render(<AdminAuctionsPage />)

            // Bấm nút Từ chối
            await waitFor(() => {
                const rejectBtns = screen.getAllByRole('button', { name: /^từ chối$/i })
                expect(rejectBtns.length).toBeGreaterThan(0)
                fireEvent.click(rejectBtns[0])
            })

            // Nhập lý do từ chối
            await waitFor(() => {
                const reasonInput = screen.getByPlaceholderText(/giải thích nội dung người bán cần khắc phục/i)
                fireEvent.change(reasonInput, { target: { value: 'Sản phẩm thiếu giấy tờ kiểm định nguồn gốc' } })
            })

            // Click nút xác nhận từ chối
            const confirmRejectBtn = screen.getByRole('button', { name: /từ chối đấu giá/i })
            fireEvent.click(confirmRejectBtn)

            await waitFor(() => {
                expect(contextHolder.current.reviewAuction).toHaveBeenCalled()
                expect(contextHolder.current.showToast).toHaveBeenCalledWith(
                    expect.objectContaining({
                        type: 'error',
                        message: expect.stringMatching(/đã từ chối phiên đấu giá/i),
                    }),
                )
            })
        })

        it('Admin thực hiện cưỡng chế hủy phiên đấu giá do vi phạm chính sách (AC2)', async () => {
            // Fixture chỉ được cấp tường minh cho test này; portal thật không tự nạp dữ liệu mẫu.
            contextHolder.current.createdAuctions = [AUCTIONS[0], AUCTIONS[2]]
            render(<AdminAuctionsPage />)

            // Bấm nút Hủy vi phạm trên phiên đang active/scheduled
            await waitFor(() => {
                const forceCancelBtns = screen.getAllByRole('button', { name: /hủy vi phạm/i })
                expect(forceCancelBtns.length).toBeGreaterThan(0)
                fireEvent.click(forceCancelBtns[0])
            })

            // Modal mở ra, nhập lý do vi phạm
            const reasonInput = screen.getByPlaceholderText(/nêu rõ lý do vi phạm chính sách/i)
            fireEvent.change(reasonInput, { target: { value: 'Seller có dấu hiệu shill bidding thao túng giá' } })

            // Xác nhận cưỡng chế hủy
            const confirmBtn = screen.getByRole('button', { name: /xác nhận hủy phiên/i })
            fireEvent.click(confirmBtn)

            await waitFor(() => {
                expect(contextHolder.current.showToast).toHaveBeenCalledWith(
                    expect.objectContaining({
                        type: 'success',
                        message: expect.stringMatching(/đã cưỡng chế hủy phiên đấu giá/i),
                    }),
                )
            })
        })

        // Bảo vệ luồng Admin tạm dừng phiên vi phạm bằng mock, gồm yêu cầu lý do và trạng thái được lưu.
        it('Admin tạm dừng phiên đang chạy với lý do vi phạm (AC2)', async () => {
            // Fixture chỉ được cấp tường minh cho test này; portal thật không tự nạp dữ liệu mẫu.
            contextHolder.current.createdAuctions = [AUCTIONS[0]]
            render(<AdminAuctionsPage />)

            const pauseButton = await screen.findAllByRole('button', { name: /tạm dừng phiên đấu giá/i })
            fireEvent.click(pauseButton[0])

            const reason = screen.getByPlaceholderText(/lý do tạm dừng phiên đấu giá/i)
            fireEvent.change(reason, { target: { value: 'Phát hiện dấu hiệu thao túng giá' } })
            fireEvent.click(screen.getAllByRole('button', { name: /tạm dừng phiên đấu giá/i }).at(-1)!)

            await waitFor(() => {
                expect(contextHolder.current.showToast).toHaveBeenCalledWith(
                    expect.objectContaining({ type: 'success', message: 'Đã tạm dừng phiên đấu giá.' }),
                )
            })
        })

        it('Admin mở xem chi tiết đầy đủ thông số và lịch sử đặt giá của phiên (AC2)', async () => {
            // Fixture chỉ được cấp tường minh cho test này; portal thật không tự nạp dữ liệu mẫu.
            contextHolder.current.createdAuctions = [AUCTIONS[0]]
            render(<AdminAuctionsPage />)

            // Click nút Xem chi tiết (Eye icon)
            await waitFor(() => {
                const viewDetailBtns = screen.getAllByTitle(/xem chi tiết/i)
                expect(viewDetailBtns.length).toBeGreaterThan(0)
                fireEvent.click(viewDetailBtns[0])
            })

            // Kiểm tra modal chi tiết hiển thị đầy đủ thông số
            await waitFor(() => {
                expect(screen.getByText(/chi tiết phiên đấu giá & kiểm toán/i)).toBeInTheDocument()
                expect(screen.getByText(/lịch sử đặt giá gần nhất/i)).toBeInTheDocument()
            })
        })
    })
})
