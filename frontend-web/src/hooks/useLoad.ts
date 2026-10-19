import { useEffect, useState } from 'react'
import { api, errorMessage } from '../api/client'

/** Hook tải lại khi URL API đổi; xóa dữ liệu cũ, báo loading/lỗi và hủy request khi rời màn hình. */
export function useLoad<T>(path: string) {
    const [data, setData] = useState<T | null>(null)
    const [error, setError] = useState('')
    const [loading, setLoading] = useState(true)
    useEffect(() => {
        const controller = new AbortController()
        setLoading(true)
        setError('')
        setData(null)
        api<T>(path, { signal: controller.signal })
            .then(setData)
            .catch((error) => {
                if (!controller.signal.aborted) setError(errorMessage(error))
            })
            .finally(() => {
                if (!controller.signal.aborted) setLoading(false)
            })
        return () => controller.abort()
    }, [path])
    return { data, error, loading }
}
