/**
 * Chức năng: Động cơ tính giá tạm tính phía Frontend (Client-side Pricing Engine).
 * Mục đích:
 *   1. Tự động tính toán giá tạm thời trên UI (Cart, Checkout) để tối ưu trải nghiệm người dùng,
 *      không gửi request liên tục lên Backend khi người dùng chỉ xem hoặc chỉnh sửa giỏ hàng.
 *   2. Hỗ trợ các quy tắc định giá động thương mại điện tử hiện đại:
 *      - Chiết khấu số lượng cùng nhà sản xuất / người bán (Merchant Volume Discount).
 *      - Khuyến mãi đặc thù theo danh mục sản phẩm (Category / Item-based Promotion, ví dụ: mua cua giảm 10%, tôm giảm 20%).
 *      - Phụ phí / điều chỉnh khung giờ cao điểm (Peak-hour / Surge Adjustment).
 *      - Kiểm tra giá sàn tối thiểu (Min Price Validation) để chặn người bán phá giá dưới giá vốn.
 * Nguyên tắc kiến trúc:
 *   - Zero-Trust: Giá hiển thị ở FE chỉ là "Tạm tính" (Estimated Amount).
 *   - Khi Buyer bấm thanh toán, Backend sẽ tự tính toán độc lập để chốt giá chính thức (Captured Amount).
 */

export interface PricingCartItem {
    id: string
    skuId: string
    skuCode: string
    productName: string
    unitPrice: number
    quantity: number
    sellerId?: string
    sellerName?: string
    categoryId?: string
    categoryName?: string
}

export interface PricingBreakdown {
    /** Tổng tiền hàng ban đầu (chưa giảm giá, chưa thuế phí) */
    baseSubtotal: number
    /** Giảm giá khi mua nhiều sản phẩm của cùng một nhà bán / nhà sản xuất */
    merchantVolumeDiscount: number
    /** Khuyến mãi ngành hàng cụ thể (ví dụ: hải sản, thiết bị điện tử) */
    categoryDiscount: number
    /** Giảm giá từ mã Voucher toàn sàn */
    voucherDiscount: number
    /** Phụ phí / Điều chỉnh khung giờ cao điểm (nếu rơi vào peak hours) */
    peakHourFee: number
    /** Trạng thái có đang trong khung giờ cao điểm hay không */
    isPeakHour: boolean
    /** Mô tả khung giờ cao điểm */
    peakHourMessage: string
    /** Phí vận chuyển ước tính */
    estimatedShipping: number
    /** Thuế ước tính (VAT 8%) */
    estimatedTax: number
    /** Tổng tiền tạm tính đến tay khách hàng */
    estimatedTotal: number
    /** Danh sách các chương trình khuyến mãi được áp dụng tự động */
    appliedPromotions: {
        code: string
        title: string
        amount: number
    }[]
}

/**
 * Kiểm tra xem thời điểm hiện tại có thuộc khung giờ cao điểm (Peak Hours) hay không.
 * Khung giờ cao điểm: 11:30 - 13:00 (giờ trưa) và 18:00 - 20:30 (giờ tối).
 */
export function checkPeakHour(date: Date = new Date()): { isPeak: boolean; label: string; surcharge: number } {
    const hours = date.getHours()
    const minutes = date.getMinutes()
    const timeVal = hours * 60 + minutes

    // Trưa: 11:30 (690) đến 13:00 (780)
    const isLunchPeak = timeVal >= 690 && timeVal <= 780
    // Tối: 18:00 (1080) đến 20:30 (1230)
    const isDinnerPeak = timeVal >= 1080 && timeVal <= 1230

    if (isLunchPeak) {
        return { isPeak: true, label: 'Khung giờ trưa cao điểm (11:30 - 13:00)', surcharge: 5000 }
    }
    if (isDinnerPeak) {
        return { isPeak: true, label: 'Khung giờ tối cao điểm (18:00 - 20:30)', surcharge: 10000 }
    }
    return { isPeak: false, label: 'Khung giờ tiêu chuẩn', surcharge: 0 }
}

/**
 * Kiểm tra ràng buộc Giá bán của Seller không được thấp hơn Giá nhập tối thiểu (Min Price / Cost Price).
 * Đầu vào: sellingPrice (giá bán định niêm yết), minCostPrice (giá vốn tối thiểu).
 * Kết quả: { valid: boolean, message?: string }
 */
export function validateMinPrice(
    sellingPrice: number,
    minCostPrice: number,
    lang: 'en' | 'vi' = 'vi',
): { valid: boolean; message?: string } {
    if (minCostPrice > 0 && sellingPrice < minCostPrice) {
        return {
            valid: false,
            message:
                lang === 'en'
                    ? `Selling price ($${sellingPrice}) cannot be lower than the minimum cost price ($${minCostPrice}).`
                    : `Giá bán (${sellingPrice.toLocaleString('vi-VN')} đ) không được thấp hơn giá nhập tối thiểu (${minCostPrice.toLocaleString('vi-VN')} đ).`,
        }
    }
    return { valid: true }
}

/**
 * Chức năng: Động cơ tính giá tạm tính đầy đủ cho Giỏ hàng và Checkout.
 * Luồng xử lý:
 *   1. Tính Base Subtotal = tổng (unitPrice * quantity).
 *   2. Nhóm các món theo sellerId/sellerName: Nếu mua >= 3 món cùng shop -> chiết khấu 5%, >= 5 món -> chiết khấu 10%.
 *   3. Phân loại theo tên món/danh mục để áp dụng khuyến mãi nhóm:
 *      - Tên chứa 'cua' / 'crab' / hải sản đặc thù với số lượng >= 5: giảm 10% cho nhóm đó.
 *      - Tên chứa 'tôm' / 'shrimp' với số lượng >= 3: giảm 15% cho nhóm đó.
 *   4. Kiểm tra giờ cao điểm: nếu đang trong giờ cao điểm thì áp dụng mức phí điều phối vận hành.
 *   5. Áp dụng Voucher (nếu có mã hợp lệ như NEXUS10, FREESHIP...).
 *   6. Tính thuế và phí vận chuyển (miễn phí ship nếu đơn >= 500k hoặc $100).
 */
export function calculateEstimatedPricing(
    items: PricingCartItem[],
    voucherCode?: string | null,
): PricingBreakdown {
    const appliedPromotions: { code: string; title: string; amount: number }[] = []

    // 1. Tạm tính ban đầu
    const baseSubtotal = items.reduce((sum, it) => sum + it.unitPrice * it.quantity, 0)
    if (baseSubtotal <= 0 || items.length === 0) {
        return {
            baseSubtotal: 0,
            merchantVolumeDiscount: 0,
            categoryDiscount: 0,
            voucherDiscount: 0,
            peakHourFee: 0,
            isPeakHour: false,
            peakHourMessage: '',
            estimatedShipping: 0,
            estimatedTax: 0,
            estimatedTotal: 0,
            appliedPromotions: [],
        }
    }

    // 2. Chiết khấu theo số lượng của cùng người bán / nhà sản xuất (Merchant Volume Discount)
    const sellerQtyMap = new Map<string, { totalQty: number; sellerTotal: number; name: string }>()
    for (const it of items) {
        const sKey = it.sellerId || it.sellerName || 'default_seller'
        const curr = sellerQtyMap.get(sKey) || {
            totalQty: 0,
            sellerTotal: 0,
            name: it.sellerName || 'Shop',
        }
        curr.totalQty += it.quantity
        curr.sellerTotal += it.unitPrice * it.quantity
        sellerQtyMap.set(sKey, curr)
    }

    let merchantVolumeDiscount = 0
    sellerQtyMap.forEach((data) => {
        if (data.totalQty >= 5) {
            const disc = Math.round(data.sellerTotal * 0.1) // Giảm 10%
            merchantVolumeDiscount += disc
            appliedPromotions.push({
                code: 'BULK_SHOP_10',
                title: `Ưu đãi mua sỉ từ ${data.name} (>=5 sản phẩm): Giảm 10%`,
                amount: disc,
            })
        } else if (data.totalQty >= 3) {
            const disc = Math.round(data.sellerTotal * 0.05) // Giảm 5%
            merchantVolumeDiscount += disc
            appliedPromotions.push({
                code: 'BULK_SHOP_5',
                title: `Ưu đãi mua nhiều từ ${data.name} (>=3 sản phẩm): Giảm 5%`,
                amount: disc,
            })
        }
    })

    // 3. Khuyến mãi theo nhóm sản phẩm (Category / Item Promotion)
    let categoryDiscount = 0
    for (const it of items) {
        const lowerName = (it.productName || '').toLowerCase()
        if (lowerName.includes('cua') || lowerName.includes('crab')) {
            if (it.quantity >= 5) {
                const disc = Math.round(it.unitPrice * it.quantity * 0.1) // 10%
                categoryDiscount += disc
                appliedPromotions.push({
                    code: 'SEAFOOD_CRAB_10',
                    title: `Khuyến mãi đặc sản cua (>=5 con): Giảm 10%`,
                    amount: disc,
                })
            }
        } else if (lowerName.includes('tôm') || lowerName.includes('shrimp')) {
            if (it.quantity >= 3) {
                const disc = Math.round(it.unitPrice * it.quantity * 0.15) // 15%
                categoryDiscount += disc
                appliedPromotions.push({
                    code: 'SEAFOOD_SHRIMP_15',
                    title: `Khuyến mãi tôm tươi (>=3 phần): Giảm 15%`,
                    amount: disc,
                })
            }
        }
    }

    // 4. Giảm giá từ Voucher
    let voucherDiscount = 0
    if (voucherCode) {
        const cleanVoucher = voucherCode.trim().toUpperCase()
        if (cleanVoucher === 'NEXUS10') {
            voucherDiscount = Math.round(baseSubtotal * 0.1)
            appliedPromotions.push({
                code: cleanVoucher,
                title: 'Voucher NEXUS10: Giảm 10% toàn bill',
                amount: voucherDiscount,
            })
        } else if (cleanVoucher === 'WELCOME20') {
            voucherDiscount = Math.round(baseSubtotal * 0.2)
            appliedPromotions.push({
                code: cleanVoucher,
                title: 'Voucher WELCOME20: Giảm 20% cho thành viên mới',
                amount: voucherDiscount,
            })
        } else if (cleanVoucher === 'VIP50') {
            voucherDiscount = Math.min(50000, Math.round(baseSubtotal * 0.15))
            appliedPromotions.push({
                code: cleanVoucher,
                title: 'Voucher VIP50: Giảm 15% tối đa 50,000 đ',
                amount: voucherDiscount,
            })
        }
    }

    // 5. Khung giờ cao điểm
    const peakInfo = checkPeakHour()
    const peakHourFee = peakInfo.isPeak ? peakInfo.surcharge : 0

    // 6. Phí vận chuyển ước tính
    const estimatedShipping = baseSubtotal >= 100 ? 0 : 8.99

    // 7. Thuế ước tính (8%)
    const netSubtotal = Math.max(0, baseSubtotal - merchantVolumeDiscount - categoryDiscount - voucherDiscount)
    const estimatedTax = Number((netSubtotal * 0.08).toFixed(2))

    // 8. Tổng thanh toán tạm tính
    const estimatedTotal = Number((netSubtotal + estimatedShipping + estimatedTax + peakHourFee).toFixed(2))

    return {
        baseSubtotal,
        merchantVolumeDiscount,
        categoryDiscount,
        voucherDiscount,
        peakHourFee,
        isPeakHour: peakInfo.isPeak,
        peakHourMessage: peakInfo.label,
        estimatedShipping,
        estimatedTax,
        estimatedTotal,
        appliedPromotions,
    }
}

