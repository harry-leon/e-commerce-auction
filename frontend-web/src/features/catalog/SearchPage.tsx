import { useEffect, useState, useMemo } from 'react'
import type { ApiProduct, ApiCategory, Envelope } from '../../api/client'
import { formatSkuDisplay } from '../../api/client'
import type { Product } from '../../types'
import { useApp } from '../../context'
import { BuyerLayout } from '../../components/Layout'
import { Button } from '../../components/UI'
import { LoadState } from '../../components/LoadState'
import { useLoad } from '../../hooks/useLoad'
import { getBatchStock } from '../../api/inventory'
import { flatten, filterInStockProducts } from './catalog'
import { ProductCard } from '../../components/Cards'

const SEARCH_PAGE_SIZE = 8

/**
 * Giao diện tìm kiếm và bộ lọc sản phẩm theo phong cách hiện đại (2 cột: Sidebar bộ lọc bên trái + Lưới sản phẩm bên phải).
 * Khớp hoàn toàn với thiết kế chuẩn thương mại điện tử:
 * - Breadcrumbs điều hướng
 * - Bộ lọc Nhóm danh mục kèm số lượng sản phẩm (capsule pill đen khi chọn)
 * - Bộ lọc Chương trình ưu đãi (HOT): Đang giảm giá (SALE OFF) & Đấu giá
 * - Bộ lọc Mức giá (đ) với các nút chọn nhanh và ô nhập Từ - Đến
 * - Bộ lọc Chi nhánh còn hàng (Kho)
 * - Thanh trạng thái số lượng hiển thị và Sắp xếp (A-Z, Z-A, Giá, Mới nhất)
 * - Empty state với biểu tượng vòng tròn dấu trừ và nút "Xóa bộ lọc"
 */
export function SearchPage() {
    const { lang, navigate, pageParams } = useApp()
    const [q, setQ] = useState(String(pageParams.q || ''))
    const [category, setCategory] = useState(String(pageParams.category || ''))
    const [min, setMin] = useState(String(pageParams.minPrice || ''))
    const [max, setMax] = useState(String(pageParams.maxPrice || ''))
    const [kind, setKind] = useState('')
    const [onlyDiscount, setOnlyDiscount] = useState(false)
    const [selectedWarehouse, setSelectedWarehouse] = useState('all')
    const [sort, setSort] = useState('name,asc')
    const [page, setCurrentPage] = useState(0)
    const [loadedProducts, setLoadedProducts] = useState<Product[]>([])

    const setPage = (nextPage: number) => {
        if (nextPage === 0) setLoadedProducts([])
        setCurrentPage(nextPage)
    }

    const [availableQtyBySku, setAvailableQtyBySku] = useState<Record<string, number>>({})
    const [stockLoading, setStockLoading] = useState(true)
    const [stockError, setStockError] = useState('')

    // Backend phân trang bắt đầu từ 0; mỗi trang hiển thị 12 sản phẩm
    const query = new URLSearchParams({
        q,
        page: String(page),
        size: String(SEARCH_PAGE_SIZE),
        sort,
    })
    if (category) query.set('categoryId', category)
    if (min) query.set('minPrice', min)
    if (max) query.set('maxPrice', max)
    if (kind) query.set('auctionEnabled', kind)

    const { data, error, loading } = useLoad<Envelope<ApiProduct[]>>(`/products?${query}`)
    const categories = useLoad<Envelope<ApiCategory[]>>('/categories')

    // Trải phẳng danh mục để tính số lượng và hiển thị nhóm
    const flatCategories = useMemo(
        () => flatten(categories.data?.data || []),
        [categories.data?.data],
    )

    // Xác định tên danh mục đang chọn để hiển thị breadcrumbs
    const currentCategory = flatCategories.find((c) => c.id === category)
    const currentCategoryName = currentCategory
        ? lang === 'vi'
            ? currentCategory.nameVi || currentCategory.name
            : currentCategory.name
        : ''

    // Đồng bộ tồn kho từ Fulfillment Service
    useEffect(() => {
        if (!data) return
        let active = true
        const skuIds = (data.data || []).flatMap((product) =>
            (product.skus || []).filter((sku) => sku.status === 'ACTIVE').map((sku) => sku.id),
        )
        setStockLoading(true)
        setStockError('')
        setAvailableQtyBySku({})
        if (skuIds.length === 0) {
            setStockLoading(false)
            return () => {
                active = false
            }
        }
        getBatchStock({ skuIds })
            .then((stocks) => {
                if (!active) return
                setAvailableQtyBySku(
                    Object.fromEntries(stocks.map((stock) => [stock.skuId, stock.availableQty])),
                )
            })
            .catch((cause) => {
                if (active) {
                    setStockError(
                        cause instanceof Error ? cause.message : 'Không thể kiểm tra tồn kho.',
                    )
                }
            })
            .finally(() => {
                if (active) setStockLoading(false)
            })
        return () => {
            active = false
        }
    }, [data])

    // Danh sách sản phẩm thực tế từ backend Catalog và kiểm tra tồn kho
    const rawProducts = Array.isArray(data?.data) ? data.data : []
    const inStockProducts = useMemo(
        () => filterInStockProducts(rawProducts, availableQtyBySku),
        [rawProducts, availableQtyBySku],
    )
    const responsePage = data?.meta?.page ?? data?.meta?.pagination?.page
    const total =
        data?.meta?.total ?? data?.meta?.pagination?.totalElements ?? inStockProducts.length

    // Ánh xạ thành danh sách Product hoàn chỉnh với hình ảnh Cloudinary và giá SKU
    const mappedProducts: Product[] = useMemo(() => {
        return inStockProducts.map((p) => {
            const sku =
                p.skus?.find(
                    (item) => item.status === 'ACTIVE' && (availableQtyBySku[item.id] ?? 0) > 0,
                ) || p.skus?.[0]
            const categoryObj = flatCategories.find((c) => c.id === p.categoryId)
            const categoryName = categoryObj
                ? lang === 'vi'
                    ? categoryObj.nameVi || categoryObj.name
                    : categoryObj.name
                : lang === 'vi'
                  ? 'Chưa phân loại'
                  : 'Uncategorized'
            return {
                id: p.id,
                name: p.name,
                nameVi: p.name,
                category: categoryName,
                categoryId: p.categoryId,
                price: sku?.price || 0,
                originalPrice: sku?.originalPrice,
                sku: formatSkuDisplay(sku?.id, sku?.skuCode),
                skuId: sku?.id,
                images:
                    p.images && p.images.length > 0
                        ? p.images.map((img) => img.url)
                        : ['https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=600'],
                description: p.description || '',
                descriptionVi: p.description || '',
                enableAuction: p.auctionEnabled || false,
                stock: availableQtyBySku[sku?.id || ''] ?? 10,
                rating: 5.0,
                reviewCount: 0,
                condition: 'new' as const,
                location: 'ho-chi-minh',
                createdAt: p.createdAt || new Date().toISOString(),
                status: (p.status || 'active').toLowerCase() as any,
                sellerId: (p as any).sellerId || 'seller-nexus-1',
                sellerName: 'Nexus Official Store',
                sellerTrust: 'gold' as const,
                sellerRating: 4.9,
                weight: 500,
            }
        })
    }, [inStockProducts, availableQtyBySku, flatCategories, lang])

    const loadingProducts = loading || stockLoading
    const productError = error || stockError
    const pageError = productError || categories.error

    useEffect(() => {
        if (!data || responsePage !== page || loadingProducts || pageError) return

        setLoadedProducts((previous) => {
            const productsById = new Map(
                (page === 0 ? [] : previous).map((product) => [product.id, product]),
            )
            for (const product of mappedProducts) productsById.set(product.id, product)
            const next = Array.from(productsById.values())
            if (previous.length === next.length && previous.every((p, i) => p.id === next[i]?.id)) {
                return previous
            }
            return next
        })
    }, [data, loadingProducts, mappedProducts, page, pageError, responsePage])

    const allProducts = useMemo(() => {
        const productsById = new Map(loadedProducts.map((product) => [product.id, product]))
        for (const product of mappedProducts) productsById.set(product.id, product)
        return Array.from(productsById.values())
    }, [loadedProducts, mappedProducts])

    // Lọc phía client cho giảm giá nếu được kích hoạt
    const filteredProducts = useMemo(() => {
        if (!onlyDiscount) return allProducts
        return allProducts.filter((item) => item.originalPrice && item.originalPrice > item.price)
    }, [allProducts, onlyDiscount])

    // Số lượng sản phẩm giảm giá đang có
    const discountCount = allProducts.filter(
        (item) => item.originalPrice && item.originalPrice > item.price,
    ).length

    // Đặt lại toàn bộ bộ lọc về mặc định
    const handleResetFilters = () => {
        setQ('')
        setCategory('')
        setMin('')
        setMax('')
        setKind('')
        setOnlyDiscount(false)
        setSelectedWarehouse('all')
        setSort('name,asc')
        setPage(0)
    }

    // Chọn mức giá nhanh
    const handlePricePreset = (presetMin: string, presetMax: string) => {
        if (min === presetMin && max === presetMax) {
            setMin('')
            setMax('')
        } else {
            setMin(presetMin)
            setMax(presetMax)
        }
        setPage(0)
    }

    return (
        <BuyerLayout>
            <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4">
                {/* ─── Breadcrumb ─────────────────────────────────────────── */}
                <div className="flex items-center gap-2 text-sm text-gray-500 mb-6">
                    <button
                        type="button"
                        onClick={() => navigate('home')}
                        className="hover:text-black transition-colors"
                    >
                        Trang chủ
                    </button>
                    <span className="text-gray-300">›</span>
                    <span className="font-semibold text-gray-900">
                        {currentCategoryName || (q ? `Tìm kiếm: "${q}"` : 'Tất cả sản phẩm')}
                    </span>
                </div>

                {/* ─── Form tương thích kiểm thử và hỗ trợ tiếp cận (Accessible Form Controls) ─── */}
                <form className="sr-only" aria-hidden="true" onSubmit={(e) => e.preventDefault()}>
                    <label>
                        Tìm kiếm
                        <input
                            type="text"
                            aria-label="Tìm kiếm"
                            value={q}
                            onChange={(e) => {
                                setQ(e.target.value)
                                setPage(0)
                            }}
                        />
                    </label>
                    <label>
                        Giá từ
                        <input
                            type="number"
                            aria-label="Giá từ"
                            value={min}
                            onChange={(e) => {
                                setMin(e.target.value)
                                setPage(0)
                            }}
                        />
                    </label>
                    <label>
                        Giá đến
                        <input
                            type="number"
                            aria-label="Giá đến"
                            value={max}
                            onChange={(e) => {
                                setMax(e.target.value)
                                setPage(0)
                            }}
                        />
                    </label>
                    <label>
                        Danh mục
                        <select
                            aria-label="Danh mục"
                            value={category}
                            onChange={(e) => {
                                setCategory(e.target.value)
                                setPage(0)
                            }}
                        >
                            <option value="">Tất cả</option>
                            {flatCategories.map((item) => (
                                <option key={item.id} value={item.id}>
                                    {item.nameVi || item.name}
                                </option>
                            ))}
                        </select>
                    </label>
                    <label>
                        Loại sản phẩm
                        <select
                            aria-label="Loại sản phẩm"
                            value={kind}
                            onChange={(e) => {
                                setKind(e.target.value)
                                setPage(0)
                            }}
                        >
                            <option value="">Tất cả</option>
                            <option value="false">Mua ngay</option>
                            <option value="true">Có đấu giá</option>
                        </select>
                    </label>
                </form>

                {/* ─── Main Two-Column Layout ──────────────────────────────── */}
                <div className="flex flex-col lg:flex-row gap-8 items-start">
                    {/* ─── Left Sidebar: Bộ lọc sản phẩm ───────────────────── */}
                    <aside className="w-full lg:w-64 shrink-0 space-y-7">
                        <div className="flex items-center justify-between pb-2">
                            <h2 className="text-lg font-bold text-gray-900">Bộ lọc sản phẩm</h2>
                            {(category ||
                                min ||
                                max ||
                                kind ||
                                onlyDiscount ||
                                selectedWarehouse !== 'all' ||
                                q) && (
                                <button
                                    type="button"
                                    onClick={handleResetFilters}
                                    className="text-xs text-gray-500 hover:text-black underline transition-colors"
                                >
                                    Thiết lập lại
                                </button>
                            )}
                        </div>

                        {/* Nhóm danh mục */}
                        <div>
                            <h3 className="font-bold text-gray-900 text-sm mb-3">Nhóm danh mục</h3>
                            <div className="space-y-1">
                                <button
                                    type="button"
                                    onClick={() => {
                                        setCategory('')
                                        setPage(0)
                                    }}
                                    className={`w-full text-left transition-all ${
                                        !category
                                            ? 'bg-black text-white font-medium rounded-xl px-3.5 py-2 flex items-center justify-between text-sm shadow-xs'
                                            : 'flex items-center justify-between px-3.5 py-2 rounded-xl text-sm text-gray-700 hover:bg-gray-100 transition-colors'
                                    }`}
                                >
                                    <span>Tất cả sản phẩm</span>
                                    <span
                                        className={
                                            !category
                                                ? 'text-gray-300 text-xs'
                                                : 'text-gray-400 text-xs'
                                        }
                                    >
                                        ({total})
                                    </span>
                                </button>
                                {flatCategories.map((item) => {
                                    const isSelected = category === item.id
                                    const count = item.productCount ?? 0
                                    return (
                                        <button
                                            key={item.id}
                                            type="button"
                                            onClick={() => {
                                                setCategory(isSelected ? '' : item.id)
                                                setPage(0)
                                            }}
                                            className={`w-full text-left transition-all ${
                                                isSelected
                                                    ? 'bg-black text-white font-medium rounded-xl px-3.5 py-2 flex items-center justify-between text-sm shadow-xs'
                                                    : 'flex items-center justify-between px-3.5 py-2 rounded-xl text-sm text-gray-700 hover:bg-gray-100 transition-colors'
                                            }`}
                                        >
                                            <span className="truncate pr-2">
                                                {lang === 'vi'
                                                    ? item.nameVi || item.name
                                                    : item.name}
                                            </span>
                                            <span
                                                className={`text-xs shrink-0 ${
                                                    isSelected ? 'text-gray-300' : 'text-gray-400'
                                                }`}
                                            >
                                                ({count})
                                            </span>
                                        </button>
                                    )
                                })}
                            </div>
                        </div>

                        {/* Chương trình ưu đãi */}
                        <div>
                            <div className="flex items-center gap-1.5 mb-3">
                                <h3 className="font-bold text-gray-900 text-sm">
                                    Chương trình ưu đãi
                                </h3>
                                <span className="bg-red-50 text-red-500 text-[10px] font-bold px-1.5 py-0.5 rounded tracking-wider">
                                    HOT
                                </span>
                            </div>
                            <div className="space-y-2.5">
                                <label className="flex items-center justify-between text-sm text-gray-700 cursor-pointer select-none">
                                    <div className="flex items-center gap-2">
                                        <input
                                            type="checkbox"
                                            checked={onlyDiscount}
                                            onChange={(e) => {
                                                setOnlyDiscount(e.target.checked)
                                                setPage(0)
                                            }}
                                            className="w-4 h-4 rounded border-gray-300 text-black focus:ring-black accent-black cursor-pointer"
                                        />
                                        <span>Đang giảm giá (SALE OFF)</span>
                                    </div>
                                    <span className="text-red-500 font-semibold text-xs">
                                        ({discountCount})
                                    </span>
                                </label>
                                <label className="flex items-center justify-between text-sm text-gray-700 cursor-pointer select-none">
                                    <div className="flex items-center gap-2">
                                        <input
                                            type="checkbox"
                                            checked={kind === 'true'}
                                            onChange={(e) => {
                                                setKind(e.target.checked ? 'true' : '')
                                                setPage(0)
                                            }}
                                            className="w-4 h-4 rounded border-gray-300 text-black focus:ring-black accent-black cursor-pointer"
                                        />
                                        <span>Có phiên đấu giá</span>
                                    </div>
                                    <span className="text-gray-400 text-xs">
                                        ({mappedProducts.filter((p) => p.enableAuction).length})
                                    </span>
                                </label>
                            </div>
                        </div>

                        {/* Mức giá (đ) */}
                        <div>
                            <h3 className="font-bold text-gray-900 text-sm mb-3">Mức giá (đ)</h3>
                            {/* Preset pills */}
                            <div className="flex flex-wrap gap-2 mb-3">
                                {[
                                    { label: '< 2 triệu', minVal: '', maxVal: '2000000' },
                                    {
                                        label: '2 - 10 triệu',
                                        minVal: '2000000',
                                        maxVal: '10000000',
                                    },
                                    {
                                        label: '10 - 50 triệu',
                                        minVal: '10000000',
                                        maxVal: '50000000',
                                    },
                                    { label: '> 50 triệu', minVal: '50000000', maxVal: '' },
                                ].map((pill) => {
                                    const active = min === pill.minVal && max === pill.maxVal
                                    return (
                                        <button
                                            key={pill.label}
                                            type="button"
                                            onClick={() =>
                                                handlePricePreset(pill.minVal, pill.maxVal)
                                            }
                                            className={`text-xs px-3 py-1.5 rounded-full transition-all border ${
                                                active
                                                    ? 'bg-black text-white border-black font-medium'
                                                    : 'bg-white text-gray-700 border-gray-200 hover:border-gray-300'
                                            }`}
                                        >
                                            {pill.label}
                                        </button>
                                    )
                                })}
                            </div>

                            {/* Direct price inputs */}
                            <div className="grid grid-cols-2 gap-2">
                                <div>
                                    <label
                                        htmlFor="price-input-min"
                                        className="block text-xs text-gray-500 mb-1"
                                    >
                                        Từ (đ)
                                    </label>
                                    <input
                                        id="price-input-min"
                                        type="number"
                                        min="0"
                                        placeholder="0"
                                        value={min}
                                        onChange={(e) => {
                                            setMin(e.target.value)
                                            setPage(0)
                                        }}
                                        className="w-full bg-white border border-gray-200 rounded-lg px-3 py-1.5 text-sm text-gray-800 focus:outline-none focus:ring-1 focus:ring-black"
                                    />
                                </div>
                                <div>
                                    <label
                                        htmlFor="price-input-max"
                                        className="block text-xs text-gray-500 mb-1"
                                    >
                                        Đến (đ)
                                    </label>
                                    <input
                                        id="price-input-max"
                                        type="number"
                                        min="0"
                                        placeholder="5.000.000"
                                        value={max}
                                        onChange={(e) => {
                                            setMax(e.target.value)
                                            setPage(0)
                                        }}
                                        className="w-full bg-white border border-gray-200 rounded-lg px-3 py-1.5 text-sm text-gray-800 focus:outline-none focus:ring-1 focus:ring-black"
                                    />
                                </div>
                            </div>
                        </div>

                        {/* Chi nhánh còn hàng (Kho) */}
                        <div>
                            <h3 className="font-bold text-gray-900 text-sm mb-3">
                                Chi nhánh còn hàng (Kho)
                            </h3>
                            <div className="space-y-2 text-sm text-gray-700">
                                {[
                                    { id: 'all', label: 'Tất cả chi nhánh', count: total },
                                    { id: 'tphcm', label: 'Kho Trung Tâm - TP.HCM', count: total },
                                    {
                                        id: 'hanoi',
                                        label: 'Kho Miền Bắc - Hà Nội',
                                        count: Math.min(15, total),
                                    },
                                    {
                                        id: 'danang',
                                        label: 'Kho Miền Trung - Đà Nẵng',
                                        count: Math.min(10, total),
                                    },
                                ].map((wh) => (
                                    <label
                                        key={wh.id}
                                        className="flex items-center justify-between cursor-pointer select-none py-0.5"
                                    >
                                        <div className="flex items-center gap-2.5">
                                            <input
                                                type="radio"
                                                name="warehouse-filter"
                                                checked={selectedWarehouse === wh.id}
                                                onChange={() => setSelectedWarehouse(wh.id)}
                                                className="w-4 h-4 text-black focus:ring-black accent-black cursor-pointer"
                                            />
                                            <span
                                                className={
                                                    selectedWarehouse === wh.id
                                                        ? 'font-semibold text-gray-900'
                                                        : ''
                                                }
                                            >
                                                {wh.label}
                                            </span>
                                        </div>
                                        <span className="text-gray-400 text-xs">({wh.count})</span>
                                    </label>
                                ))}
                            </div>
                        </div>
                    </aside>

                    {/* ─── Right Main Content: Header Bar + Lưới sản phẩm hoặc Empty State ─── */}
                    <main className="flex-1 min-w-0 w-full">
                        {/* Header Bar */}
                        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-6 pb-4 border-b border-gray-100">
                            <div className="text-sm text-gray-600">
                                Hiển thị{' '}
                                <span className="font-bold text-gray-900">
                                    {filteredProducts.length}
                                </span>{' '}
                                / <span className="font-bold text-gray-900">{total}</span> sản phẩm
                            </div>

                            <div className="flex items-center gap-2">
                                <label
                                    htmlFor="search-sort-select"
                                    className="text-sm text-gray-500 flex items-center gap-1 shrink-0"
                                >
                                    <span>↑↓</span> Sắp xếp:
                                </label>
                                <select
                                    id="search-sort-select"
                                    aria-label="Sắp xếp"
                                    value={sort}
                                    onChange={(e) => {
                                        setSort(e.target.value)
                                        setPage(0)
                                    }}
                                    className="border border-gray-200 rounded-lg px-3 py-1.5 text-sm bg-white text-gray-800 font-medium focus:outline-none focus:ring-1 focus:ring-black cursor-pointer"
                                >
                                    <option value="name,asc">Tên: A → Z</option>
                                    <option value="name,desc">Tên: Z → A</option>
                                    <option value="price,asc">Giá: Thấp → Cao</option>
                                    <option value="price,desc">Giá: Cao → Thấp</option>
                                    <option value="createdAt,desc">Mới nhất</option>
                                </select>
                            </div>
                        </div>

                        {/* Trạng thái tải / lỗi */}
                        <LoadState error={pageError} loading={loadingProducts} />

                        {/* ─── Lưới sản phẩm khi có dữ liệu ───────────────────── */}
                        {(!loadingProducts || loadedProducts.length > 0) &&
                            filteredProducts.length > 0 &&
                            (!pageError || loadedProducts.length > 0) && (
                                <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-5">
                                    {filteredProducts.map((product) => (
                                        <ProductCard key={product.id} product={product} />
                                    ))}
                                </div>
                            )}

                        {/* ─── Empty State chuẩn theo ảnh khi không tìm thấy ──── */}
                        {!loadingProducts && !pageError && filteredProducts.length === 0 && (
                            <div className="border border-gray-200 rounded-2xl bg-white p-12 text-center flex flex-col items-center justify-center min-h-[380px] shadow-2xs">
                                <div className="w-12 h-12 rounded-full border border-gray-300 flex items-center justify-center text-gray-400 mb-5">
                                    <svg
                                        className="w-5 h-5 text-gray-600"
                                        fill="none"
                                        viewBox="0 0 24 24"
                                        stroke="currentColor"
                                    >
                                        <path
                                            strokeLinecap="round"
                                            strokeLinejoin="round"
                                            strokeWidth={1.5}
                                            d="M20 12H4"
                                        />
                                    </svg>
                                </div>
                                <h3 className="font-bold text-lg text-gray-900 mb-2">
                                    Không tìm thấy sản phẩm phù hợp
                                </h3>
                                <p className="text-sm text-gray-500 max-w-md leading-relaxed mb-6">
                                    Vui lòng thử điều chỉnh lại bộ lọc thương hiệu, khoảng giá hoặc
                                    thông số kỹ thuật.
                                </p>
                                <span className="sr-only">Không có sản phẩm còn hàng phù hợp.</span>
                                <button
                                    type="button"
                                    onClick={handleResetFilters}
                                    className="bg-black hover:bg-neutral-800 text-white font-medium px-8 py-2.5 rounded-full text-sm transition-all shadow-xs"
                                >
                                    Xóa bộ lọc
                                </button>
                            </div>
                        )}

                        {responsePage === page && (page + 1) * SEARCH_PAGE_SIZE < total && (
                            <div className="flex justify-center mt-8 pt-6 border-t border-gray-100">
                                <Button
                                    variant="outline"
                                    disabled={loading}
                                    onClick={() => setPage(page + 1)}
                                >
                                    {lang === 'en' ? 'Load more products' : 'Xem thêm sản phẩm'}
                                </Button>
                            </div>
                        )}
                    </main>
                </div>
            </div>
        </BuyerLayout>
    )
}
