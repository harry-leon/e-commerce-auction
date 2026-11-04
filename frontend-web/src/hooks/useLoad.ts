import { useEffect, useState } from 'react'
import { api, errorMessage } from '../api/client'

/**
 * Bộ nhớ đệm SWR (Stale-While-Revalidate) trong phiên làm việc của ứng dụng web.
 * Chức năng: Lưu trữ kết quả API GET (danh mục, sản phẩm...) theo path.
 * Quy tắc: Giữ dữ liệu thực tế giữa các lần chuyển trang (như từ login về home) để hiển thị ngay lập tức (0ms),
 * tránh tình trạng giao diện bị trắng hoặc hiển thị trống/ảo trong 1-2s chờ mạng.
 */
const memoryCache = new Map<string, unknown>()

/** Xóa bộ nhớ cache dùng khi cần làm mới toàn diện hoặc reset trong test */
export function clearLoadCache() {
    memoryCache.clear()
}

/**
 * Hook tải dữ liệu API hỗ trợ cơ chế Stale-While-Revalidate (SWR):
 * 1. Chức năng: Quản lý vòng đời tải dữ liệu từ API endpoint theo đường dẫn `path`.
 * 2. Đầu vào và kết quả: Nhận `path` (chuỗi endpoint); trả về `{ data, error, loading }`.
 * 3. Luồng xử lý: Nếu có cache, trả ngay dữ liệu cũ và đánh dấu loading=false; đồng thời gửi request ngầm để làm mới. Nếu chưa có cache, bật loading và tải dữ liệu mới.
 * 4. Quy tắc và lý do: Ngăn chặn layout shift và độ trễ 1-2s từ Supabase Cloud khi người dùng điều hướng giữa các trang.
 * 5. Giới hạn: Cache trong bộ nhớ RAM của tab, tự reset khi người dùng reload trang (F5).
 */
export function useLoad<T>(path: string) {
    const cached = path ? (memoryCache.get(path) as T | undefined) : undefined
    const [data, setData] = useState<T | null>(cached ?? null)
    const [error, setError] = useState('')
    const [loading, setLoading] = useState(!cached)

    useEffect(() => {
        if (!path) return
        const controller = new AbortController()

        // Chỉ đưa về trạng thái loading ban đầu nếu chưa từng có dữ liệu trong cache
        if (!memoryCache.has(path)) {
            setLoading(true)
            setData(null)
        }
        setError('')

        api<T>(path, { signal: controller.signal })
            .then((res) => {
                if (!controller.signal.aborted) {
                    memoryCache.set(path, res)
                    setData(res)
                }
            })
            .catch((err) => {
                if (!controller.signal.aborted) {
                    setError(errorMessage(err))
                }
            })
            .finally(() => {
                if (!controller.signal.aborted) {
                    setLoading(false)
                }
            })

        return () => controller.abort()
    }, [path])

    return { data, error, loading }
}
