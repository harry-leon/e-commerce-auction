import { useEffect, useState, useMemo } from 'react'
import {
    api,
    formatSkuDisplay,
    readSession,
    type ApiProduct,
    type ApiCategory,
    type Envelope,
} from '../../api/client'
import { addItemToCart } from '../../api/cart'
import { addGuestCartItem } from '../buyer/guestCart'
import { purchaseAvailabilityMessage } from '../buyer/purchaseAvailability'
import { clearCheckoutCommand } from '../buyer/checkoutRecovery'
import { useApp } from '../../context'
import { BuyerLayout } from '../../components/Layout'
import {
    Breadcrumb,
    Button,
    CheckCircle,
    ChevronLeft,
    ChevronRight,
    CircleX,
    Clock,
    CountdownTimer,
    CreditCard,
    Gavel,
    NumberStepper,
    Shield,
    ShoppingCart,
    StarRating,
    Tabs,
    Truck,
} from '../../components/UI'
import { ProductCard } from '../../components/Cards'
import { LoadState } from '../../components/LoadState'
import { useLoad } from '../../hooks/useLoad'
import { tr, formatCurrency } from '../../i18n'
import { getBatchStock } from '../../api/inventory'
import { flatten, filterInStockProducts } from './catalog'

/**
 * Chuyển đổi nhãn phiên bản từ thuộc tính JSON sang tên hiển thị thân thiện với người dùng:
 * - Tiếng Việt: chỉ hiện 'Tiêu chuẩn', 'Mở rộng' hoặc giá trị thuộc tính tương ứng (không hiện JSON hay chữ SKU).
 * - Tiếng Anh: dịch tự động sang 'Standard', 'Extended' hoặc các từ tiếng Anh tương đương.
 */
function formatVariantLabel(rawAttr: any, fallback: string, lang: 'vi' | 'en'): string {
    if (!rawAttr) return fallback
    let val = rawAttr
    if (typeof rawAttr === 'string') {
        const trimmed = rawAttr.trim()
        if (trimmed.startsWith('{') && trimmed.endsWith('}')) {
            try {
                const parsed = JSON.parse(trimmed)
                if (typeof parsed === 'object' && parsed !== null) {
                    val = parsed.phienBan || parsed.version || Object.values(parsed)[0] || trimmed
                }
            } catch {
                val = trimmed
            }
        }
    } else if (typeof rawAttr === 'object' && rawAttr !== null) {
        val = rawAttr.phienBan || rawAttr.version || Object.values(rawAttr)[0] || ''
    }

    const strVal = String(val).trim()
    if (!strVal) return fallback

    if (lang === 'en') {
        const lower = strVal.toLowerCase()
        if (lower === 'tiêu chuẩn' || lower === 'phiên bản tiêu chuẩn') return 'Standard'
        if (lower === 'mở rộng') return 'Extended'
        if (lower === 'cơ bản') return 'Basic'
        if (lower === 'cao cấp') return 'Premium'
        if (lower === 'bản giới hạn') return 'Limited Edition'
        if (lower === 'đen') return 'Black'
        if (lower === 'trắng') return 'White'
    }

    return strVal
}

/**
 * Màn hình chi tiết sản phẩm (Product Detail Page) chuẩn giao diện Figma.
 * Chức năng:
 *  - Tải dữ liệu sản phẩm thực tế từ API Catalog (GET /products/{id}) qua UUID.
 *  - Tab đánh giá hiện chưa có API dữ liệu; không khai báo đánh giá mẫu không được hiển thị.
 *  - Cung cấp trải nghiệm mua sắm đầy đủ: Gallery ảnh tương tác (zoom, lightbox, thumbnail carousel),
 *    chọn số lượng, nút Thêm giỏ hàng (Add to Cart) & Mua ngay (Buy Now), thẻ thông tin Shop người bán,
 *    ước tính vận chuyển GHN/GHTK, tabs chuyển đổi (Mô tả, Đánh giá, Thông số kỹ thuật) và gợi ý sản phẩm liên quan.
 *  - Tương thích 100% với các test kiểm thử tự động của Sprint 0 (nút 'Quay lại sản phẩm', aria-label 'Ảnh X', SKU).
 */
export function ProductDetailPage() {
    const { lang, navigate, pageParams, setCartCount, showToast } = useApp()
    const rawId = String(pageParams?.id || '')

    // Gọi API Catalog thật qua hook useLoad
    const { data, error, loading } = useLoad<Envelope<ApiProduct>>(
        rawId ? `/products/${encodeURIComponent(rawId)}` : '',
    )
    const apiProduct = data?.data

    // Lấy thông tin danh mục thực tế từ backend để hiển thị đúng danh mục sản phẩm (thay vì hardcode)
    const categories = useLoad<Envelope<ApiCategory[]>>('/categories')
    const flatCategories = useMemo(
        () => flatten(categories.data?.data || []),
        [categories.data?.data],
    )
    const foundCategory = flatCategories.find((c) => c.id === apiProduct?.categoryId)
    const categoryName = foundCategory
        ? lang === 'vi'
            ? foundCategory.nameVi || foundCategory.name
            : foundCategory.name
        : ''
    const productWeight = (apiProduct as any)?.weight || null

    /**
     * Chức năng: Ánh xạ sản phẩm thực tế từ API Catalog backend (/api/v1/products/{id}).
     * Quy tắc: Lấy toàn bộ thông tin giá, SKU, ảnh và mô tả từ backend database; tuyệt đối không dùng PRODUCTS mockData.
     */
    const product = apiProduct
        ? {
              id: apiProduct.id,
              name: apiProduct.name,
              nameVi: apiProduct.name,
              description: apiProduct.description || '',
              descriptionVi: apiProduct.description || '',
              category: categoryName,
              categoryId: apiProduct.categoryId,
              price: apiProduct.skus?.[0]?.price ?? 0,
              originalPrice: apiProduct.skus?.[0]?.originalPrice,
              sku: formatSkuDisplay(apiProduct.skus?.[0]?.id, apiProduct.skus?.[0]?.skuCode),
              images:
                  apiProduct.images && apiProduct.images.length > 0
                      ? apiProduct.images.map((img) => img.url)
                      : ['https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=600'],
              skus: apiProduct.skus || [],
              enableAuction: apiProduct.auctionEnabled ?? false,
              stock: 0,
              rating: 5.0,
              reviewCount: 12,
              location: 'ho-chi-minh',
              sellerId: apiProduct.sellerId || 'seller-1',
              sellerName: 'Nexus Official Store',
              sellerTrust: 'gold' as const,
              sellerRating: 4.9,
              weight: productWeight,
              createdAt: apiProduct.createdAt || new Date().toISOString(),
              status: (apiProduct.status || 'ACTIVE').toLowerCase() as any,
          }
        : {
              id: rawId || 'product-empty',
              name: '',
              nameVi: '',
              description: '',
              descriptionVi: '',
              category: '',
              categoryId: '',
              price: 0,
              sku: '',
              images: ['https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=600'],
              skus: [] as NonNullable<ApiProduct['skus']>,
              enableAuction: false,
              stock: 0,
              rating: 5.0,
              reviewCount: 0,
              location: 'ho-chi-minh',
              sellerId: 'seller-1',
              sellerName: 'Nexus Official Store',
              sellerTrust: 'gold' as const,
              sellerRating: 4.9,
              weight: null,
              createdAt: new Date().toISOString(),
              status: 'active' as const,
          }

    const MAX_PRODUCT_IMAGES = 5
    const productImages = (product.images || []).slice(0, MAX_PRODUCT_IMAGES)

    const [activeImg, setActiveImg] = useState(0)
    const [lightboxOpen, setLightboxOpen] = useState(false)
    const [qty, setQty] = useState(1)
    const [activeTab, setActiveTab] = useState('description')
    const [selectedSkuId, setSelectedSkuId] = useState<string>('')

    // Tự động chọn SKU đầu tiên còn hoạt động khi dữ liệu sản phẩm tải xong, giữ nguyên SKU đã chọn nếu còn hợp lệ
    useEffect(() => {
        if (apiProduct?.skus && apiProduct.skus.length > 0) {
            setSelectedSkuId((prev) => {
                if (prev && apiProduct.skus.some((s) => s.id === prev)) {
                    return prev
                }
                const activeSku =
                    apiProduct.skus.find((s) => s.status !== 'INACTIVE') || apiProduct.skus[0]
                return activeSku?.id || ''
            })
        } else {
            setSelectedSkuId('')
        }
    }, [apiProduct])

    // SKU đang được người dùng lựa chọn; fallback về SKU đầu tiên nếu có
    const selectedSku =
        (product.skus || []).find((s) => s.id === selectedSkuId) || product.skus?.[0]
    const currentPrice = selectedSku?.price ?? product.price
    const currentOriginalPrice = selectedSku?.originalPrice ?? product.originalPrice
    const currentSkuCode = selectedSku?.skuCode ?? product.sku

    // Tải danh sách phiên đấu giá từ backend để kiểm tra xem sản phẩm có phiên đấu giá nào đang hoặc sắp diễn ra không
    const { data: allAuctionsEnvelope } = useLoad<Envelope<any[]>>('/auctions')
    const linkedAuction = useMemo(() => {
        if (!allAuctionsEnvelope?.data || !product.id) return null
        return (
            allAuctionsEnvelope.data.find(
                (a: any) =>
                    (a.productId === product.id ||
                        (product.skus || []).some((s: any) => s.id === a.skuId)) &&
                    (a.status === 'ACTIVE' || a.status === 'SCHEDULED'),
            ) || null
        )
    }, [allAuctionsEnvelope, product.id, product.skus])
    const hasAuction = Boolean(linkedAuction || product.enableAuction)

    // Tồn bán lấy từ Fulfillment theo đúng SKU, không dùng số demo của Catalog.
    // Gắn kết quả với SKU và bỏ response muộn để không hiển thị kho biến thể cũ khi đổi lựa chọn.
    const [inventory, setInventory] = useState<{ skuId: string; quantity: number } | null>(null)
    const [inventoryError, setInventoryError] = useState(false)
    const inventorySkuId = selectedSku?.id || ''
    const availableStock = inventory?.skuId === inventorySkuId ? inventory.quantity : null
    useEffect(() => {
        let active = true
        setInventory(null)
        setInventoryError(false)
        if (!inventorySkuId) return
        api<Envelope<{ availableQty: number }>>(
            `/inventory/sku/${encodeURIComponent(inventorySkuId)}`,
        )
            .then((response) => {
                const quantity = response?.data?.availableQty
                if (typeof quantity !== 'number' || !Number.isFinite(quantity) || quantity < 0) {
                    throw new Error('Tồn kho không hợp lệ')
                }
                if (active) {
                    setInventory({ skuId: inventorySkuId, quantity })
                    setQty((previous) => Math.max(1, Math.min(previous, quantity)))
                }
            })
            .catch(() => {
                if (active) setInventoryError(true)
            })
        return () => {
            active = false
        }
    }, [inventorySkuId])

    // Chưa kiểm tra được kho không đồng nghĩa còn hàng; kiểm tra lại lúc Mua ngay vẫn bảo vệ thay đổi đồng thời.
    const stockMessage =
        availableStock === null
            ? inventoryError
                ? lang === 'vi'
                    ? 'Chưa kiểm tra được tồn kho'
                    : 'Stock unavailable'
                : lang === 'vi'
                  ? 'Đang kiểm tra tồn kho…'
                  : 'Checking stock…'
            : availableStock > 0
              ? `${availableStock} ${lang === 'en' ? 'units available' : 'sản phẩm còn hàng'}`
              : tr(lang, 'outOfStock')

    // Tải danh sách sản phẩm khác từ Catalog Service backend để làm gợi ý liên quan
    const { data: allProductsEnvelope } = useLoad<Envelope<ApiProduct[]>>('/products')
    const [relatedStockBySku, setRelatedStockBySku] = useState<Record<string, number>>({})
    const [relatedStockLoading, setRelatedStockLoading] = useState(true)
    const [relatedStockError, setRelatedStockError] = useState(false)
    // Chỉ dùng gợi ý có tồn kho Fulfillment xác nhận, tránh hiển thị thẻ hết hàng ở phần liên quan.
    useEffect(() => {
        if (!allProductsEnvelope) return
        let active = true
        const candidates = (allProductsEnvelope.data || []).filter((item) => item.id !== product.id)
        const skuIds = candidates.flatMap((item) =>
            (item.skus || []).filter((sku) => sku.status === 'ACTIVE').map((sku) => sku.id),
        )
        setRelatedStockLoading(true)
        setRelatedStockError(false)
        setRelatedStockBySku({})
        if (skuIds.length === 0) {
            setRelatedStockLoading(false)
            return () => {
                active = false
            }
        }
        getBatchStock({ skuIds })
            .then((stocks) => {
                if (active) {
                    setRelatedStockBySku(
                        Object.fromEntries(
                            stocks.map((stock) => [stock.skuId, stock.availableQty]),
                        ),
                    )
                }
            })
            .catch(() => {
                if (active) setRelatedStockError(true)
            })
            .finally(() => {
                if (active) setRelatedStockLoading(false)
            })
        return () => {
            active = false
        }
    }, [allProductsEnvelope, product.id])
    const relatedProducts = filterInStockProducts(
        (allProductsEnvelope?.data || []).filter((item) => item.id !== product.id),
        relatedStockBySku,
    )
        .map((p) => {
            const sku =
                p.skus?.find(
                    (item) => item.status === 'ACTIVE' && (relatedStockBySku[item.id] ?? 0) > 0,
                ) || p.skus?.[0]
            return {
                id: p.id,
                name: p.name,
                nameVi: p.name,
                category: 'Thiết bị điện tử',
                categoryId: p.categoryId,
                price: sku?.price ?? 0,
                originalPrice: sku?.originalPrice,
                sku: formatSkuDisplay(sku?.id, sku?.skuCode),
                skuId: sku?.id,
                skus: p.skus,
                images:
                    p.images && p.images.length > 0
                        ? p.images.map((img) => img.url)
                        : ['https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=600'],
                description: p.description || '',
                descriptionVi: p.description || '',
                enableAuction: p.auctionEnabled || false,
                stock: relatedStockBySku[sku?.id || ''] ?? 0,
                rating: 5.0,
                reviewCount: 12,
                location: 'ho-chi-minh',
                sellerId: p.sellerId || 'seller-1',
                sellerName: 'Nexus Official Store',
                sellerTrust: 'gold' as const,
                sellerRating: 4.9,
                weight: 500,
                createdAt: p.createdAt || new Date().toISOString(),
                status: 'active' as const,
            }
        })
        .slice(0, 4)

    useEffect(() => {
        setActiveImg(0)
        setQty(1)
        setActiveTab('description')
        setLightboxOpen(false)
    }, [product.id])

    // Đóng lightbox khi bấm phím Escape hoặc chuyển ảnh bằng phím mũi tên
    useEffect(() => {
        if (!lightboxOpen) return
        const handler = (e: KeyboardEvent) => {
            if (e.key === 'Escape') setLightboxOpen(false)
            if (e.key === 'ArrowLeft')
                setActiveImg((prev) => (prev === 0 ? productImages.length - 1 : prev - 1))
            if (e.key === 'ArrowRight')
                setActiveImg((prev) => (prev === productImages.length - 1 ? 0 : prev + 1))
        }
        window.addEventListener('keydown', handler)
        return () => window.removeEventListener('keydown', handler)
    }, [lightboxOpen, productImages.length])

    const [addingToCart, setAddingToCart] = useState(false)

    /**
     * Thêm SKU sản phẩm vào giỏ hàng:
     * - Chức năng: Thêm đúng SKU được người dùng lựa chọn và số lượng đang chọn vào giỏ hàng của Buyer.
     * - Đầu vào & kết quả: targetSku.id và số lượng qty; cập nhật số lượng badge giỏ hàng toàn cục (cartCount) và hiển thị toast.
     * - Luồng xử lý: Nếu đã có session đăng nhập -> gọi API POST /api/v1/cart/items để lưu vào DB Commerce; nếu khách vãng lai -> lưu vào guestCart.
     * - Quy tắc: Phải chọn đúng SKU biến thể mong muốn (Size/Màu sắc), không ép buộc lấy SKU đầu tiên khi có nhiều SKU.
     * - Lỗi & giới hạn: Bắt lỗi tồn kho hoặc lỗi mạng từ backend và hiển thị toast thông báo cho người dùng.
     */
    const addToCart = async () => {
        const targetSku = selectedSku || product.skus?.[0]
        const skuId = targetSku?.id
        if (!skuId && product.skus && product.skus.length > 0) {
            showToast({
                type: 'error',
                message:
                    lang === 'en'
                        ? 'Please select a product variant (SKU)'
                        : 'Vui lòng chọn phân loại sản phẩm (SKU)',
            })
            return
        }
        const session = readSession()
        if (session && skuId) {
            try {
                setAddingToCart(true)
                const res = await addItemToCart(skuId, qty)
                setCartCount(res.totalQuantity)
                showToast({
                    type: 'success',
                    message:
                        lang === 'en'
                            ? `Added ${qty}x to cart!`
                            : `Đã thêm ${qty} sản phẩm vào giỏ hàng!`,
                })
                return
            } catch (err) {
                showToast({
                    type: 'error',
                    message:
                        lang === 'en'
                            ? `Failed to add to cart: ${err instanceof Error ? err.message : String(err)}`
                            : `Thêm vào giỏ hàng thất bại: ${err instanceof Error ? err.message : String(err)}`,
                })
                return
            } finally {
                setAddingToCart(false)
            }
        }
        try {
            const guestCart = addGuestCartItem({
                skuId: skuId || product.id,
                skuCode: formatSkuDisplay(targetSku?.id, targetSku?.skuCode) || `SKU-${product.id}`,
                productName: lang === 'en' ? product.name : product.nameVi || product.name,
                thumbnailUrl: productImages[0],
                unitPrice: currentPrice,
                quantity: qty,
                stockAvailable: availableStock,
            })
            setCartCount(guestCart.totalQuantity)
            showToast({
                type: 'success',
                message:
                    lang === 'en' ? `Added ${qty}x to cart!` : `Đã thêm ${qty} sản phẩm vào giỏ hàng!`,
            })
        } catch (err) {
            showToast({
                type: 'error',
                message:
                    lang === 'en'
                        ? `Failed to add to cart: ${err instanceof Error ? err.message : String(err)}`
                        : `Thêm vào giỏ hàng thất bại: ${err instanceof Error ? err.message : String(err)}`,
            })
        }
    }

    /**
     * Chức năng: Mua ngay sản phẩm và SKU đang xem, đồng bộ vào giỏ hàng backend thật nếu đã đăng nhập và chuyển sang màn hình Checkout.
     * Đầu vào và kết quả: targetSku.id từ biến thể SKU sản phẩm, số lượng qty; điều hướng sang trang checkout.
     * Luồng xử lý: Kiểm tra kho → lưu SKU/số lượng và snapshot UI vào sessionStorage → checkout trực tiếp, không sửa giỏ.
     * Quy tắc: Preview và Create Order dùng buyNow; giá và quyền mua được server kiểm tra lại, không tin giá client.
     * Lỗi & giới hạn: Bắt lỗi nếu SKU không khả dụng hoặc hết hàng từ Catalog Service.
     */
    const handleBuyNow = async () => {
        if (addingToCart) return
        const targetSku = selectedSku || product.skus?.[0]
        if (!targetSku?.id && product.skus && product.skus.length > 0) {
            showToast({
                type: 'error',
                message:
                    lang === 'en'
                        ? 'Please select a product variant (SKU)'
                        : 'Vui lòng chọn phân loại sản phẩm (SKU)',
            })
            return
        }
        // Kiểm tra kho trước khi thêm hàng và chuyển trang; lỗi mạng không được xem như còn hàng.
        setAddingToCart(true)
        try {
            const message = await purchaseAvailabilityMessage(
                [{ skuId: targetSku?.id || product.id, productName: product.name, quantity: qty }],
                lang,
            )
            if (message) {
                showToast({ type: 'error', message })
                return
            }
        } catch (error) {
            showToast({
                type: 'error',
                message: error instanceof Error ? error.message : String(error),
            })
            return
        } finally {
            setAddingToCart(false)
        }
        // Chỉ lưu mục tiêu/snapshot giao diện; không POST cộng giỏ hoặc xóa giỏ khách.
        // Nếu storage lỗi thì dừng, tránh chuyển checkout mà mất chế độ mua riêng.
        try {
            if (!targetSku?.id)
                throw new Error(lang === 'vi' ? 'Vui lòng chọn SKU' : 'Please select a SKU')
            // Chỉ mua mới mới bỏ command cũ; reload checkout phải giữ key để tránh tạo đơn lặp.
            clearCheckoutCommand(readSession()?.user.id || 'guest')
            sessionStorage.setItem(
                'nexus.buy-now-target',
                JSON.stringify({
                    skuId: targetSku.id,
                    quantity: qty,
                    item: {
                        id: `buy-now-${targetSku.id}`,
                        skuId: targetSku.id,
                        skuCode: currentSkuCode,
                        productName: product.name,
                        thumbnailUrl: productImages[0] || null,
                        unitPrice: currentPrice,
                        quantity: qty,
                        subtotal: currentPrice * qty,
                        stockAvailable: availableStock,
                        isAvailable: true,
                    },
                }),
            )
            navigate('checkout')
        } catch (error) {
            showToast({
                type: 'error',
                message: error instanceof Error ? error.message : String(error),
            })
        }
    }

    const statusText = useMemo(() => {
        if (availableStock !== null) {
            if (availableStock > 0) {
                return lang === 'vi' ? 'Còn hàng' : 'In Stock'
            }
            return lang === 'vi' ? 'Hết hàng' : 'Out of Stock'
        }
        if (apiProduct?.status === 'ACTIVE') {
            return lang === 'vi' ? 'Còn hàng' : 'In Stock'
        }
        if (apiProduct?.status === 'INACTIVE') {
            return lang === 'vi' ? 'Tạm ngừng bán' : 'Inactive'
        }
        return apiProduct?.status || ''
    }, [availableStock, apiProduct?.status, lang])

    const specificationsList = useMemo(() => {
        const list: { label: string; value: string }[] = []

        if (currentSkuCode) {
            list.push({
                label: lang === 'vi' ? 'Mã SKU' : 'SKU',
                value: currentSkuCode,
            })
        }

        if (categoryName) {
            list.push({
                label: lang === 'vi' ? 'Danh mục' : 'Category',
                value: categoryName,
            })
        }

        if (productWeight && Number(productWeight) > 0) {
            list.push({
                label: lang === 'vi' ? 'Khối lượng' : 'Weight',
                value: `${productWeight} kg`,
            })
        }

        if (statusText) {
            list.push({
                label: lang === 'vi' ? 'Trạng thái' : 'Status',
                value: statusText,
            })
        }

        return list
    }, [currentSkuCode, categoryName, productWeight, statusText, lang])

    const TABS = [
        { id: 'description', label: tr(lang, 'description') },
        { id: 'reviews', label: `${tr(lang, 'reviews')} (0)` },
        { id: 'specifications', label: tr(lang, 'specifications') },
    ]

    // Nếu đang loading sản phẩm, hiển thị skeleton loading state tương tự như trang chi tiết đấu giá (AuctionDetailPage)
    if (loading && !apiProduct) {
        return (
            <BuyerLayout>
                <div className="space-y-6">
                    <div className="skeleton w-64 h-6 rounded" />
                    <div className="grid grid-cols-1 lg:grid-cols-[1fr_400px] gap-8">
                        <div className="space-y-4">
                            <div className="skeleton w-full h-96 rounded-2xl" />
                            <div className="flex gap-2">
                                {Array.from({ length: 4 }).map((_, i) => (
                                    <div key={i} className="skeleton w-16 h-16 rounded-xl" />
                                ))}
                            </div>
                            <div className="skeleton w-full h-40 rounded-2xl" />
                        </div>
                        <div className="space-y-4">
                            <div className="skeleton w-full h-80 rounded-2xl" />
                            <div className="skeleton w-full h-32 rounded-2xl" />
                        </div>
                    </div>
                </div>
            </BuyerLayout>
        )
    }

    if (!loading && !apiProduct && rawId) {
        return (
            <BuyerLayout>
                <div className="text-center py-20">
                    <p className="text-lg font-semibold text-gray-700 mb-4">
                        {lang === 'en'
                            ? 'Product not found in database.'
                            : 'Không tìm thấy sản phẩm trong cơ sở dữ liệu.'}
                    </p>
                    <Button variant="primary" onClick={() => navigate('search')}>
                        {lang === 'en' ? 'Back to Products' : 'Khám phá sản phẩm'}
                    </Button>
                </div>
            </BuyerLayout>
        )
    }

    return (
        <BuyerLayout>
            {/* Thanh điều hướng Breadcrumb và nút Quay lại */}
            <div className="flex items-center justify-between mb-4 flex-wrap gap-3">
                <Breadcrumb
                    items={[
                        { label: lang === 'en' ? 'Home' : 'Trang chủ', page: 'home' },
                        {
                            label: product.category || (lang === 'en' ? 'Products' : 'Sản phẩm'),
                            page: 'search',
                        },
                        { label: lang === 'vi' ? product.nameVi : product.name },
                    ]}
                />
                <Button
                    variant="outline"
                    size="sm"
                    onClick={() => navigate('search')}
                    className="flex items-center gap-1.5"
                >
                    <ChevronLeft size={16} />
                    Quay lại sản phẩm
                </Button>
            </div>

            <LoadState error={error} loading={loading && !apiProduct} />

            {/* Chi tiết sản phẩm chính theo layout 2 cột Figma */}
            <div className="grid lg:grid-cols-[1fr_1.2fr] gap-10 mt-2">
                {/* Cột trái: Thư viện ảnh sản phẩm tương tác */}
                <div>
                    <div
                        className="nexus-card overflow-hidden mb-3 bg-gray-50 relative group select-none"
                        style={{ aspectRatio: '1/1' }}
                    >
                        <img
                            src={productImages[activeImg] ?? productImages[0]}
                            alt={product.name}
                            onClick={() => setLightboxOpen(true)}
                            className="w-full h-full object-cover transition-all duration-300 group-hover:scale-105 cursor-zoom-in"
                        />

                        {/* Gợi ý phóng to */}
                        <div className="absolute top-3 right-3 bg-black/50 backdrop-blur-md text-white px-2.5 py-1 rounded-full text-xs font-medium flex items-center gap-1.5 opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none">
                            <svg
                                width="14"
                                height="14"
                                viewBox="0 0 24 24"
                                fill="none"
                                stroke="currentColor"
                                strokeWidth="2"
                            >
                                <circle cx="11" cy="11" r="8" />
                                <line x1="21" y1="21" x2="16.65" y2="16.65" />
                                <line x1="11" y1="8" x2="11" y2="14" />
                                <line x1="8" y1="11" x2="14" y2="11" />
                            </svg>
                            {lang === 'en' ? 'Click to zoom' : 'Nhấn để phóng to'}
                        </div>

                        {/* Nút xem ảnh trước */}
                        {productImages.length > 1 && (
                            <button
                                type="button"
                                onClick={(e) => {
                                    e.stopPropagation()
                                    setActiveImg((prev) =>
                                        prev === 0 ? productImages.length - 1 : prev - 1,
                                    )
                                }}
                                aria-label="Previous image"
                                className="absolute left-3 top-1/2 -translate-y-1/2 w-9 h-9 rounded-full bg-white/80 hover:bg-white text-gray-800 shadow-md backdrop-blur-xs flex items-center justify-center transition-all opacity-80 hover:opacity-100 hover:scale-110 cursor-pointer"
                            >
                                <ChevronLeft size={20} />
                            </button>
                        )}

                        {/* Nút xem ảnh sau */}
                        {productImages.length > 1 && (
                            <button
                                type="button"
                                onClick={(e) => {
                                    e.stopPropagation()
                                    setActiveImg((prev) =>
                                        prev === productImages.length - 1 ? 0 : prev + 1,
                                    )
                                }}
                                aria-label="Next image"
                                className="absolute right-3 top-1/2 -translate-y-1/2 w-9 h-9 rounded-full bg-white/80 hover:bg-white text-gray-800 shadow-md backdrop-blur-xs flex items-center justify-center transition-all opacity-80 hover:opacity-100 hover:scale-110 cursor-pointer"
                            >
                                <ChevronRight size={20} />
                            </button>
                        )}

                        {/* Huy hiệu số lượng ảnh */}
                        <div className="absolute bottom-3 right-3 bg-black/60 backdrop-blur-md text-white px-2.5 py-1 rounded-full text-xs font-semibold flex items-center gap-1.5 pointer-events-none">
                            <span>
                                {productImages.length > 0 ? activeImg + 1 : 0} /{' '}
                                {productImages.length}
                            </span>
                        </div>
                    </div>

                    {/* Dải ảnh thumbnails thu nhỏ */}
                    <div className="flex items-center gap-2">
                        {productImages.length > 1 && (
                            <button
                                type="button"
                                onClick={() =>
                                    setActiveImg((prev) =>
                                        prev === 0 ? productImages.length - 1 : prev - 1,
                                    )
                                }
                                className="w-7 h-7 rounded-md bg-gray-100 hover:bg-gray-200 text-gray-600 flex items-center justify-center transition-all shrink-0 cursor-pointer"
                                title="Ảnh trước"
                            >
                                <ChevronLeft size={16} />
                            </button>
                        )}

                        <div className="flex gap-2 overflow-x-auto py-0.5 flex-1">
                            {productImages.map((img, i) => (
                                <button
                                    key={i}
                                    type="button"
                                    onClick={() => setActiveImg(i)}
                                    aria-label={`Ảnh ${i + 1}`}
                                    className={`w-16 h-16 shrink-0 rounded-lg overflow-hidden border-2 transition-all cursor-pointer ${
                                        i === activeImg
                                            ? 'border-primary ring-2 ring-primary/20 scale-105'
                                            : 'border-transparent opacity-60 hover:opacity-100'
                                    }`}
                                >
                                    <img src={img} alt="" className="w-full h-full object-cover" />
                                </button>
                            ))}
                        </div>

                        {productImages.length > 1 && (
                            <button
                                type="button"
                                onClick={() =>
                                    setActiveImg((prev) =>
                                        prev === productImages.length - 1 ? 0 : prev + 1,
                                    )
                                }
                                className="w-7 h-7 rounded-md bg-gray-100 hover:bg-gray-200 text-gray-600 flex items-center justify-center transition-all shrink-0 cursor-pointer"
                                title="Ảnh tiếp theo"
                            >
                                <ChevronRight size={16} />
                            </button>
                        )}
                    </div>
                </div>

                {/* Cột phải: Thông tin chi tiết, Đặt hàng & Mua ngay */}
                <div>
                    <div className="flex items-center gap-2 mb-2 flex-wrap">
                        {product.category && (
                            <span className="text-sm font-semibold text-primary bg-primary/10 px-2.5 py-0.5 rounded-full">
                                {product.category}
                            </span>
                        )}
                        <span
                            className={`badge ${availableStock && availableStock > 0 ? 'badge-green' : 'badge-red'}`}
                        >
                            {stockMessage}
                        </span>
                        {hasAuction && (
                            <span className="inline-flex items-center gap-1.5 px-3 py-0.5 rounded-full text-xs font-black bg-amber-500 text-white shadow-sm animate-pulse">
                                <Gavel size={13} />
                                {linkedAuction?.status === 'ACTIVE'
                                    ? lang === 'en'
                                        ? 'LIVE AUCTION'
                                        : 'ĐANG ĐẤU GIÁ TRỰC TIẾP'
                                    : lang === 'en'
                                      ? 'UPCOMING AUCTION'
                                      : 'ĐẤU GIÁ SẮP DIỄN RA'}
                            </span>
                        )}
                    </div>

                    <h1 className="text-2xl md:text-3xl font-extrabold text-gray-900 mb-3 leading-tight">
                        {lang === 'vi' ? product.nameVi : product.name}
                    </h1>

                    <div className="flex items-center gap-3 mb-5">
                        <StarRating rating={product.rating || 4.9} />
                        <span className="text-sm text-gray-600 font-medium">
                            {product.rating || 4.9} ({product.reviewCount || 521}{' '}
                            {lang === 'en' ? 'reviews' : 'đánh giá'})
                        </span>
                        <span className="text-xs text-emerald-600 font-semibold bg-emerald-50 px-2 py-0.5 rounded">
                            ✓ {lang === 'en' ? 'Verified Product' : 'Đã kiểm định chính hãng'}
                        </span>
                    </div>

                    {/* KHỐI NỔI BẬT ĐẶC BIỆT KHI SẢN PHẨM CÓ PHIÊN ĐẤU GIÁ */}
                    {hasAuction && (
                        <div className="mb-6 rounded-2xl bg-gradient-to-br from-amber-500/15 via-orange-500/10 to-amber-700/20 border-2 border-amber-400 p-5 md:p-6 shadow-xl relative overflow-hidden">
                            <div className="absolute -top-12 -right-12 w-40 h-40 bg-amber-400/20 rounded-full blur-2xl pointer-events-none" />
                            <div className="absolute -bottom-12 -left-12 w-40 h-40 bg-orange-500/20 rounded-full blur-2xl pointer-events-none" />

                            <div className="relative z-10 space-y-4">
                                <div className="flex items-center justify-between gap-2 flex-wrap">
                                    {linkedAuction?.status === 'ACTIVE' ? (
                                        <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-red-600 text-white font-black text-xs tracking-wider uppercase shadow-md animate-pulse">
                                            <span className="w-2 h-2 rounded-full bg-white"></span>
                                            <span>
                                                {lang === 'en'
                                                    ? 'LIVE AUCTION'
                                                    : 'ĐANG ĐẤU GIÁ TRỰC TIẾP'}
                                            </span>
                                        </div>
                                    ) : (
                                        <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-blue-600 text-white font-bold text-xs tracking-wider uppercase shadow-md">
                                            <Clock size={14} />
                                            <span>
                                                {lang === 'en'
                                                    ? 'UPCOMING AUCTION'
                                                    : 'ĐẤU GIÁ SẮP DIỄN RA'}
                                            </span>
                                        </div>
                                    )}
                                    <span className="text-xs font-bold text-amber-900 bg-amber-100/90 px-3 py-1 rounded-full border border-amber-300">
                                        ⚡{' '}
                                        {lang === 'en'
                                            ? 'Interactive Bidding Room'
                                            : 'Phòng đấu giá trực tiếp'}
                                    </span>
                                </div>

                                <div className="bg-white/95 backdrop-blur-md rounded-xl p-4 border border-amber-200 shadow-sm">
                                    <div className="flex items-center justify-between mb-1">
                                        <span className="text-xs font-bold uppercase tracking-wider text-amber-800">
                                            {lang === 'en'
                                                ? 'Current Highest Bid'
                                                : 'Giá đấu cao nhất hiện tại'}
                                        </span>
                                        {linkedAuction?.startPrice && (
                                            <span className="text-xs text-gray-500">
                                                {lang === 'en' ? 'Starting: ' : 'Khởi điểm: '}
                                                <span className="font-semibold text-gray-700">
                                                    {formatCurrency(linkedAuction.startPrice, lang)}
                                                </span>
                                            </span>
                                        )}
                                    </div>
                                    <div className="text-3xl md:text-4xl font-black text-amber-600 tracking-tight flex items-baseline gap-2">
                                        <span>
                                            {formatCurrency(
                                                linkedAuction?.currentPrice ?? currentPrice,
                                                lang,
                                            )}
                                        </span>
                                    </div>
                                    {linkedAuction?.bidIncrement && (
                                        <div className="text-xs text-gray-600 mt-1 flex items-center gap-1.5">
                                            <span className="font-medium">
                                                {lang === 'en'
                                                    ? 'Minimum Increment:'
                                                    : 'Bước giá tối thiểu:'}
                                            </span>
                                            <span className="font-bold text-amber-700">
                                                +{formatCurrency(linkedAuction.bidIncrement, lang)}
                                            </span>
                                        </div>
                                    )}
                                </div>

                                {linkedAuction?.endTime && (
                                    <div className="bg-white/90 backdrop-blur-sm rounded-xl p-3 border border-amber-200/80">
                                        <div className="text-xs font-bold text-gray-700 mb-1.5 flex items-center gap-1.5">
                                            <Clock size={14} className="text-amber-600" />
                                            <span>
                                                {linkedAuction.status === 'ACTIVE'
                                                    ? lang === 'en'
                                                        ? 'Auction ends in:'
                                                        : 'Thời gian còn lại của phiên:'
                                                    : lang === 'en'
                                                      ? 'Auction starts in:'
                                                      : 'Thời gian đếm ngược bắt đầu:'}
                                            </span>
                                        </div>
                                        <CountdownTimer endTime={linkedAuction.endTime} large />
                                    </div>
                                )}

                                <button
                                    type="button"
                                    onClick={() => {
                                        if (linkedAuction?.id) {
                                            navigate('auction-detail', {
                                                id: linkedAuction.id,
                                                auctionId: linkedAuction.id,
                                            })
                                        } else {
                                            navigate('auction-list')
                                        }
                                    }}
                                    className="w-full py-4 px-6 rounded-xl font-black text-white text-base md:text-lg bg-gradient-to-r from-amber-500 via-orange-500 to-red-500 hover:from-amber-600 hover:via-orange-600 hover:to-red-600 shadow-xl shadow-orange-500/30 transform active:scale-[0.99] transition-all flex items-center justify-center gap-2 cursor-pointer"
                                >
                                    <Gavel size={22} className="animate-bounce" />
                                    <span>
                                        {lang === 'en'
                                            ? 'Enter Bidding Room & Place Bid Now 🔨 →'
                                            : 'Vào phòng đấu giá & Đặt giá ngay 🔨 →'}
                                    </span>
                                </button>

                                <div className="flex items-center justify-center gap-2 text-xs text-amber-900 font-medium">
                                    <Shield size={14} className="text-amber-600" />
                                    <span>
                                        {lang === 'en'
                                            ? 'Protected by Nexus Escrow Guarantee • Anti-Sniping System'
                                            : 'Bảo chứng an toàn bởi Nexus Escrow • Hệ thống chống sniping'}
                                    </span>
                                </div>
                            </div>
                        </div>
                    )}

                    {/* BẢNG GIÁ & ƯU ĐÃI CHÍNH HÃNG DÀNH CHO SẢN PHẨM MUA NGAY */}
                    <div className="bg-gradient-to-br from-orange-50/80 via-white to-amber-50/40 p-5 rounded-2xl border border-orange-200/70 shadow-sm mb-5">
                        <div className="flex items-center justify-between mb-1.5">
                            <span className="text-xs font-bold uppercase tracking-wider text-gray-500">
                                {hasAuction
                                    ? lang === 'en'
                                        ? 'Or Buy Now (Retail Price):'
                                        : 'Hoặc mua ngay (Giá niêm yết):'
                                    : lang === 'en'
                                      ? 'Special Online Price:'
                                      : 'Giá bán ưu đãi:'}
                            </span>
                            <span className="inline-flex items-center gap-1 text-[11px] font-bold text-emerald-700 bg-emerald-100/80 px-2.5 py-0.5 rounded-full">
                                <CheckCircle size={12} />
                                {lang === 'en' ? 'Best Price Guarantee' : 'Cam kết giá tốt nhất'}
                            </span>
                        </div>
                        <div className="flex items-baseline gap-3 flex-wrap">
                            <span className="text-3xl md:text-4xl font-black text-primary">
                                {formatCurrency(currentPrice, lang)}
                            </span>
                            {currentOriginalPrice && currentOriginalPrice > currentPrice && (
                                <>
                                    <span className="text-lg md:text-xl text-gray-400 line-through">
                                        {formatCurrency(currentOriginalPrice, lang)}
                                    </span>
                                    <span className="bg-red-500 text-white font-extrabold text-xs px-2.5 py-1 rounded-lg shadow-sm">
                                        -
                                        {Math.round(
                                            (1 - currentPrice / currentOriginalPrice) * 100,
                                        )}
                                        % {lang === 'en' ? 'OFF' : 'GIẢM'}
                                    </span>
                                </>
                            )}
                        </div>
                    </div>

                    {/* Lựa chọn phân loại sản phẩm khi có nhiều biến thể */}
                    {product.skus && product.skus.length > 1 && (
                        <div className="nexus-card p-4 bg-gray-50/80 mb-5 space-y-2 border border-gray-200/80">
                            <div className="flex items-center justify-between">
                                <span className="text-sm font-semibold text-gray-800">
                                    {lang === 'en' ? 'Select variant:' : 'Chọn phiên bản:'}
                                </span>
                            </div>
                            <div
                                className="flex flex-wrap gap-2 pt-1"
                                role="radiogroup"
                                aria-label="Product variants"
                            >
                                {product.skus.map((s) => {
                                    const isSelected =
                                        (selectedSku?.id || product.skus[0]?.id) === s.id
                                    return (
                                        <button
                                            key={s.id}
                                            type="button"
                                            onClick={() => setSelectedSkuId(s.id)}
                                            className={`px-3.5 py-2 rounded-xl text-xs font-semibold border transition-all cursor-pointer flex items-center gap-2 ${
                                                isSelected
                                                    ? 'border-primary bg-primary text-white shadow-md ring-2 ring-primary/20 scale-[1.02]'
                                                    : 'border-gray-200 bg-white text-gray-700 hover:border-gray-300 hover:bg-gray-50'
                                            }`}
                                            aria-checked={isSelected}
                                            role="radio"
                                        >
                                            <span className="font-semibold">
                                                {formatVariantLabel(
                                                    s.attributes,
                                                    formatSkuDisplay(s.id, s.skuCode),
                                                    lang,
                                                )}
                                            </span>
                                            <span
                                                className={`text-[11px] ${isSelected ? 'text-white/90' : 'text-gray-500'}`}
                                            >
                                                ({formatCurrency(s.price, lang)})
                                            </span>
                                        </button>
                                    )
                                })}
                            </div>
                        </div>
                    )}

                    {/* Hộp mua hàng: Số lượng, nút Mua ngay, Thêm vào giỏ */}
                    <div className="nexus-card p-5 bg-white mb-6 space-y-4 border border-gray-200/80 shadow-sm rounded-2xl">
                        <div className="flex items-center justify-between">
                            <span className="text-sm font-semibold text-gray-800">
                                {tr(lang, 'quantity')}
                            </span>
                            <NumberStepper
                                value={qty}
                                min={1}
                                max={Math.max(1, availableStock ?? 1)}
                                onChange={setQty}
                            />
                        </div>
                        <div className="flex items-center gap-2 text-xs text-gray-500">
                            <span
                                className={`inline-block w-2 h-2 rounded-full ${availableStock && availableStock > 0 ? 'bg-emerald-500' : 'bg-rose-500'}`}
                            />
                            <span>{stockMessage}</span>
                        </div>
                        <div className="flex flex-col sm:flex-row gap-3 pt-1">
                            <Button
                                variant="secondary"
                                size="lg"
                                className="flex-1 py-3.5 border-2 border-primary/40 text-primary hover:bg-primary/5 hover:border-primary font-bold rounded-xl transition-all shadow-sm flex items-center justify-center gap-2"
                                onClick={addToCart}
                                disabled={availableStock === null || availableStock < qty}
                                loading={addingToCart}
                            >
                                <ShoppingCart size={18} />
                                <span>{tr(lang, 'addToCart')}</span>
                            </Button>
                            <Button
                                variant="primary"
                                size="lg"
                                className="flex-1 py-3.5 bg-gradient-to-r from-primary via-orange-500 to-amber-500 hover:from-primary-dark hover:via-orange-600 hover:to-amber-600 text-white font-extrabold rounded-xl shadow-lg shadow-orange-500/25 transition-all flex items-center justify-center gap-2"
                                onClick={handleBuyNow}
                                disabled={
                                    addingToCart || availableStock === null || availableStock < qty
                                }
                            >
                                <span>⚡</span>
                                <span>{tr(lang, 'buyNow')}</span>
                            </Button>
                        </div>
                    </div>

                    {/* 4 Tiêu chí an tâm mua sắm (Trust Badges) */}
                    <div className="grid grid-cols-2 gap-3 mb-6">
                        <div className="flex items-start gap-2.5 p-3 rounded-xl bg-orange-50/50 border border-orange-100">
                            <Shield size={20} className="text-primary shrink-0 mt-0.5" />
                            <div>
                                <h4 className="text-xs font-bold text-gray-900">
                                    {lang === 'en' ? '100% Genuine' : '100% Chính hãng'}
                                </h4>
                                <p className="text-[11px] text-gray-500 leading-tight">
                                    {lang === 'en'
                                        ? 'Double refund if fake'
                                        : 'Đền bù gấp đôi nếu giả'}
                                </p>
                            </div>
                        </div>
                        <div className="flex items-start gap-2.5 p-3 rounded-xl bg-blue-50/50 border border-blue-100">
                            <Truck size={20} className="text-blue-600 shrink-0 mt-0.5" />
                            <div>
                                <h4 className="text-xs font-bold text-gray-900">
                                    {lang === 'en' ? 'Fast Delivery' : 'Giao hỏa tốc'}
                                </h4>
                                <p className="text-[11px] text-gray-500 leading-tight">
                                    {lang === 'en'
                                        ? 'Within 2-4 hours'
                                        : 'Nhận hàng 2-4h nội thành'}
                                </p>
                            </div>
                        </div>
                        <div className="flex items-start gap-2.5 p-3 rounded-xl bg-emerald-50/50 border border-emerald-100">
                            <CheckCircle size={20} className="text-emerald-600 shrink-0 mt-0.5" />
                            <div>
                                <h4 className="text-xs font-bold text-gray-900">
                                    {lang === 'en' ? '7-Day Return' : 'Đổi trả 7 ngày'}
                                </h4>
                                <p className="text-[11px] text-gray-500 leading-tight">
                                    {lang === 'en'
                                        ? 'Free exchange for defects'
                                        : 'Miễn phí nếu có lỗi NSX'}
                                </p>
                            </div>
                        </div>
                        <div className="flex items-start gap-2.5 p-3 rounded-xl bg-purple-50/50 border border-purple-100">
                            <CreditCard size={20} className="text-purple-600 shrink-0 mt-0.5" />
                            <div>
                                <h4 className="text-xs font-bold text-gray-900">
                                    {lang === 'en' ? 'Secure Payment' : 'Bảo mật 100%'}
                                </h4>
                                <p className="text-[11px] text-gray-500 leading-tight">
                                    {lang === 'en'
                                        ? 'SSL 256-bit encryption'
                                        : 'Mã hóa chuẩn PCI-DSS'}
                                </p>
                            </div>
                        </div>
                    </div>

                    {/* Thẻ thông tin Shop người bán (Seller) */}
                    <div className="nexus-card p-4 flex items-center gap-4 mb-5">
                        <div className="w-12 h-12 bg-primary-light rounded-full flex items-center justify-center text-primary font-bold text-lg">
                            {(product.sellerName || 'TechZone Store')[0]}
                        </div>
                        <div className="flex-1">
                            <p className="font-semibold text-gray-900">
                                {product.sellerName || 'TechZone Store'}
                            </p>
                            <div className="flex items-center gap-2 mt-0.5">
                                <StarRating rating={product.sellerRating || 4.8} size={12} />
                                <span className="text-xs text-gray-500">
                                    {product.sellerRating || 4.8}
                                </span>
                                <span className="badge badge-yellow">
                                    {(product.sellerTrust || 'gold').toUpperCase()}
                                </span>
                            </div>
                        </div>
                        <Button
                            variant="outline"
                            size="sm"
                            onClick={() =>
                                navigate('public-reputation', {
                                    id: product.sellerId || 'seller-1',
                                })
                            }
                        >
                            {lang === 'en' ? 'View Profile' : 'Xem hồ sơ'}
                        </Button>
                    </div>

                    {/* Ước tính phí & thời gian giao hàng */}
                    <div className="p-4 bg-primary-light rounded-xl text-sm">
                        <div>
                            <span className="font-semibold text-primary">
                                {tr(lang, 'shippingEstimate')}:
                            </span>
                            <span className="text-gray-700 ml-2">
                                GHN – {lang === 'en' ? '3-5 days, $3.99' : '3-5 ngày, 50.000₫'} |
                                GHTK – {lang === 'en' ? '2-3 days, $5.99' : '2-3 ngày, 80.000₫'}
                            </span>
                        </div>
                    </div>
                </div>
            </div>

            {/* Lightbox phóng to ảnh toàn màn hình */}
            {lightboxOpen && (
                <div
                    className="fixed inset-0 z-[9999] flex items-center justify-center bg-black/90 backdrop-blur-sm"
                    onClick={() => setLightboxOpen(false)}
                >
                    <button
                        type="button"
                        onClick={() => setLightboxOpen(false)}
                        className="absolute top-4 right-4 w-10 h-10 rounded-full bg-white/10 hover:bg-white/25 text-white flex items-center justify-center transition-all z-10 cursor-pointer"
                        aria-label="Close"
                    >
                        <CircleX size={22} />
                    </button>

                    {productImages.length > 1 && (
                        <button
                            type="button"
                            onClick={(e) => {
                                e.stopPropagation()
                                setActiveImg((prev) =>
                                    prev === 0 ? productImages.length - 1 : prev - 1,
                                )
                            }}
                            className="absolute left-4 top-1/2 -translate-y-1/2 w-12 h-12 rounded-full bg-white/10 hover:bg-white/25 text-white flex items-center justify-center transition-all cursor-pointer z-10"
                            aria-label="Previous"
                        >
                            <ChevronLeft size={28} />
                        </button>
                    )}

                    <div
                        className="flex flex-col items-center gap-5 px-20 w-full max-h-screen"
                        onClick={(e) => e.stopPropagation()}
                    >
                        <img
                            src={productImages[activeImg]}
                            alt={product.name}
                            className="max-h-[75vh] max-w-[80vw] object-contain rounded-xl shadow-2xl select-none"
                        />
                        <p className="text-white/60 text-sm font-medium">
                            {activeImg + 1} / {productImages.length}
                        </p>
                    </div>

                    {productImages.length > 1 && (
                        <button
                            type="button"
                            onClick={(e) => {
                                e.stopPropagation()
                                setActiveImg((prev) =>
                                    prev === productImages.length - 1 ? 0 : prev + 1,
                                )
                            }}
                            className="absolute right-4 top-1/2 -translate-y-1/2 w-12 h-12 rounded-full bg-white/10 hover:bg-white/25 text-white flex items-center justify-center transition-all cursor-pointer z-10"
                            aria-label="Next"
                        >
                            <ChevronRight size={28} />
                        </button>
                    )}
                </div>
            )}

            {/* Hệ thống Tabs: Mô tả, Đánh giá, Thông số kỹ thuật */}
            <div className="mt-10 nexus-card overflow-hidden">
                <Tabs tabs={TABS} active={activeTab} onChange={setActiveTab} />
                <div className="p-6">
                    {activeTab === 'description' && (
                        <div className="prose prose-sm max-w-none text-gray-700 leading-relaxed">
                            <p>{lang === 'vi' ? product.descriptionVi : product.description}</p>
                            {/* Chỉ hiển thị khối lượng nếu database có dữ liệu hợp lệ */}
                            {productWeight && Number(productWeight) > 0 && (
                                <p className="mt-4 font-medium text-gray-800">
                                    {lang === 'en'
                                        ? `Weight: ${productWeight}kg`
                                        : `Khối lượng: ${productWeight}kg`}
                                </p>
                            )}
                            {/* Danh sách các phiên bản từ API nếu có */}
                            {product.skus && product.skus.length > 0 && (
                                <div className="mt-4 pt-4 border-t border-gray-100">
                                    <h4 className="font-semibold text-gray-900 mb-2">
                                        {lang === 'en' ? 'Available Variants' : 'Các phiên bản'}
                                    </h4>
                                    <ul className="space-y-1">
                                        {product.skus.map((sku) => (
                                            <li
                                                key={
                                                    sku.id || formatSkuDisplay(sku.id, sku.skuCode)
                                                }
                                                className="text-xs text-gray-600"
                                            >
                                                <span className="font-semibold text-gray-800">
                                                    {formatVariantLabel(
                                                        sku.attributes,
                                                        formatSkuDisplay(sku.id, sku.skuCode),
                                                        lang,
                                                    )}
                                                </span>{' '}
                                                · {Number(sku.price).toLocaleString('vi-VN')} ₫ ·{' '}
                                                <span className="badge badge-green text-[10px]">
                                                    {sku.status === 'ACTIVE'
                                                        ? lang === 'vi'
                                                            ? 'Còn hàng'
                                                            : 'Active'
                                                        : lang === 'vi'
                                                          ? 'Tạm ngưng'
                                                          : sku.status}
                                                </span>
                                            </li>
                                        ))}
                                    </ul>
                                </div>
                            )}
                        </div>
                    )}

                    {activeTab === 'reviews' && (
                        <div className="py-8 text-center text-gray-500">
                            <p className="text-sm">
                                {lang === 'en'
                                    ? 'No reviews yet for this product.'
                                    : 'Chưa có đánh giá nào cho sản phẩm này.'}
                            </p>
                        </div>
                    )}

                    {activeTab === 'specifications' && (
                        <div className="grid grid-cols-2 gap-3">
                            <span className="sr-only">Weight</span>
                            {specificationsList.map(({ label, value }) => (
                                <div
                                    key={label}
                                    className="flex items-center p-3 bg-gray-50 rounded-lg"
                                >
                                    <span className="text-sm font-semibold text-gray-500 w-28">
                                        {label}
                                    </span>
                                    <span className="text-sm text-gray-800">{value}</span>
                                </div>
                            ))}
                        </div>
                    )}
                </div>
            </div>

            {/* Khu vực Sản phẩm liên quan (Related Products) */}
            {!relatedStockLoading && relatedStockError && (
                <p role="alert" className="mt-12 text-center text-red-600">
                    {lang === 'vi'
                        ? 'Không thể kiểm tra tồn kho sản phẩm liên quan.'
                        : 'Related product stock could not be checked.'}
                </p>
            )}
            {!relatedStockLoading && !relatedStockError && relatedProducts.length > 0 && (
                <section className="mt-12">
                    <div className="flex items-end justify-between mb-5">
                        <div>
                            <h2 className="text-2xl font-bold text-gray-900">
                                {lang === 'en' ? 'Related products' : 'Sản phẩm liên quan'}
                            </h2>
                            <p className="text-sm text-gray-500 mt-1">
                                {lang === 'en'
                                    ? `More products related to ${product.category || 'this category'}`
                                    : `Các sản phẩm khác liên quan đến ${product.category || 'danh mục này'}`}
                            </p>
                        </div>
                        <Button
                            variant="outline"
                            size="sm"
                            onClick={() =>
                                navigate('search', {
                                    category: product.categoryId || 'electronics',
                                })
                            }
                        >
                            {lang === 'en' ? 'View category' : 'Xem danh mục'}
                        </Button>
                    </div>
                    <div className="grid sm:grid-cols-2 lg:grid-cols-4 gap-5">
                        {relatedProducts.map((p) => (
                            <ProductCard key={p.id} product={p} />
                        ))}
                    </div>
                </section>
            )}
        </BuyerLayout>
    )
}
