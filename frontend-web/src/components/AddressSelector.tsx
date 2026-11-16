import { useState, useEffect } from 'react'
import { Input } from './UI'

/**
 * Cấu trúc đơn vị hành chính chuẩn trả về từ Open API Tỉnh thành Việt Nam.
 */
interface AdministrativeDivision {
    code: number
    name: string
}

interface AddressSelectorProps {
    value: string
    onChange: (fullAddress: string) => void
    lang?: 'vi' | 'en'
    error?: string
}

// Bộ nhớ cache tạm trong phiên để tránh gọi API lặp lại khi người dùng chuyển đổi nhiều lần
const provinceCache: AdministrativeDivision[] = []
const districtCache = new Map<number, AdministrativeDivision[]>()
const wardCache = new Map<number, AdministrativeDivision[]>()

/**
 * Component lựa chọn địa chỉ hành chính phân cấp 3 cấp (Tỉnh/Thành -> Quận/Huyện -> Phường/Xã).
 *
 * 1. Chức năng:
 *    - Gọi Open API địa giới hành chính Việt Nam (provinces.open-api.vn) để người dùng chọn Tỉnh/Thành,
 *      Quận/Huyện, Phường/Xã và nhập số nhà/tên đường thay vì phải gõ tay toàn bộ chuỗi địa chỉ.
 * 2. Đầu vào và kết quả:
 *    - Đầu vào: props value (chuỗi địa chỉ đầy đủ hiện tại), onChange callback, lang, error.
 *    - Kết quả: Tự động ghép thành chuỗi hoàn chỉnh dạng: "Số nhà/Tên đường, Phường/Xã, Quận/Huyện, Tỉnh/Thành"
 *      và gửi ra ngoài qua callback onChange.
 * 3. Luồng xử lý:
 *    - Khi mount: Tải danh sách 63 Tỉnh/Thành phố từ API.
 *    - Khi chọn Tỉnh/Thành: Tự động gọi API lấy danh sách Quận/Huyện trực thuộc.
 *    - Khi chọn Quận/Huyện: Tự động gọi API lấy danh sách Phường/Xã trực thuộc.
 *    - Bất kỳ khi nào người dùng chọn xong hoặc gõ số nhà: Tự động ghép chuỗi và gọi onChange().
 * 4. Quy tắc và lý do:
 *    - Chuẩn hóa địa chỉ giúp người mua dễ nhập liệu, phục vụ tính cước phí giao hàng (Fulfillment/GHN/GHTK).
 *    - Hỗ trợ nút chuyển đổi "Nhập tay (Manual)" để người dùng linh hoạt hoặc khi API bên ngoài bị lỗi/chặn mạng.
 * 5. Lỗi và giới hạn:
 *    - Nếu gọi Open API thất bại (offline/lỗi mạng), tự động chuyển sang chế độ nhập tự do (manual) mà không gây crash form.
 */
export function AddressSelector({ value, onChange, lang = 'vi', error }: AddressSelectorProps) {
    const [provinces, setProvinces] = useState<AdministrativeDivision[]>(provinceCache)
    const [districts, setDistricts] = useState<AdministrativeDivision[]>([])
    const [wards, setWards] = useState<AdministrativeDivision[]>([])

    const [selectedProvinceCode, setSelectedProvinceCode] = useState<number | ''>('')
    const [selectedDistrictCode, setSelectedDistrictCode] = useState<number | ''>('')
    const [selectedWardCode, setSelectedWardCode] = useState<number | ''>('')
    const [street, setStreet] = useState<string>('')

    const [loadingProvinces, setLoadingProvinces] = useState<boolean>(false)
    const [loadingDistricts, setLoadingDistricts] = useState<boolean>(false)
    const [loadingWards, setLoadingWards] = useState<boolean>(false)
    const [manualMode, setManualMode] = useState<boolean>(false)

    // Tải danh sách Tỉnh / Thành phố khi khởi chạy component
    useEffect(() => {
        if (provinceCache.length > 0) {
            setProvinces(provinceCache)
            return
        }

        let isMounted = true
        setLoadingProvinces(true)
        fetch('https://provinces.open-api.vn/api/p/')
            .then((res) => {
                if (!res.ok) throw new Error('Không thể tải danh sách Tỉnh/Thành')
                return res.json()
            })
            .then((data) => {
                if (!isMounted) return
                if (Array.isArray(data)) {
                    provinceCache.push(...data)
                    setProvinces(data)
                }
            })
            .catch((_err) => {
                // Khi không gọi được API tỉnh thành bên ngoài, tự động fallback sang chế độ nhập tay
                if (isMounted) setManualMode(true)
            })
            .finally(() => {
                if (isMounted) setLoadingProvinces(false)
            })

        return () => {
            isMounted = false
        }
    }, [])

    // Tải danh sách Quận / Huyện khi đổi Tỉnh / Thành phố
    useEffect(() => {
        if (!selectedProvinceCode) {
            setDistricts([])
            setWards([])
            setSelectedDistrictCode('')
            setSelectedWardCode('')
            return
        }

        if (districtCache.has(selectedProvinceCode)) {
            setDistricts(districtCache.get(selectedProvinceCode) || [])
            return
        }

        let isMounted = true
        setLoadingDistricts(true)
        fetch(`https://provinces.open-api.vn/api/p/${selectedProvinceCode}?depth=2`)
            .then((res) => res.json())
            .then((data) => {
                if (!isMounted) return
                const list = data?.districts || []
                districtCache.set(selectedProvinceCode, list)
                setDistricts(list)
            })
            .catch(() => {
                if (isMounted) setDistricts([])
            })
            .finally(() => {
                if (isMounted) setLoadingDistricts(false)
            })

        return () => {
            isMounted = false
        }
    }, [selectedProvinceCode])

    // Tải danh sách Phường / Xã khi đổi Quận / Huyện
    useEffect(() => {
        if (!selectedDistrictCode) {
            setWards([])
            setSelectedWardCode('')
            return
        }

        if (wardCache.has(selectedDistrictCode)) {
            setWards(wardCache.get(selectedDistrictCode) || [])
            return
        }

        let isMounted = true
        setLoadingWards(true)
        fetch(`https://provinces.open-api.vn/api/d/${selectedDistrictCode}?depth=2`)
            .then((res) => res.json())
            .then((data) => {
                if (!isMounted) return
                const list = data?.wards || []
                wardCache.set(selectedDistrictCode, list)
                setWards(list)
            })
            .catch(() => {
                if (isMounted) setWards([])
            })
            .finally(() => {
                if (isMounted) setLoadingWards(false)
            })

        return () => {
            isMounted = false
        }
    }, [selectedDistrictCode])

    // Tự động ghép chuỗi địa chỉ hoàn chỉnh khi bất kỳ thành phần nào thay đổi
    const handleComposeAddress = (
        newProvinceCode = selectedProvinceCode,
        newDistrictCode = selectedDistrictCode,
        newWardCode = selectedWardCode,
        newStreet = street,
    ) => {
        const provName = provinces.find((p) => p.code === newProvinceCode)?.name || ''
        const distName = districts.find((d) => d.code === newDistrictCode)?.name || ''
        const wardName = wards.find((w) => w.code === newWardCode)?.name || ''

        const parts = [newStreet.trim(), wardName, distName, provName].filter(Boolean)
        const combined = parts.join(', ')
        onChange(combined)
    }

    if (manualMode) {
        return (
            <div className="space-y-1.5">
                <div className="flex items-center justify-between">
                    <label className="text-sm font-semibold text-gray-700">
                        {lang === 'en' ? 'Address' : 'Địa chỉ'}
                    </label>
                    <button
                        type="button"
                        onClick={() => setManualMode(false)}
                        className="text-xs text-primary hover:underline font-medium"
                    >
                        {lang === 'en' ? 'Select from list' : 'Chọn theo Tỉnh/Quận/Xã'}
                    </button>
                </div>
                <Input
                    value={value}
                    onChange={(e) => onChange(e.target.value)}
                    placeholder={
                        lang === 'en'
                            ? 'e.g. 123 Nguyen Hue, Ben Nghe, District 1, HCMC'
                            : 'Ví dụ: 123 Nguyễn Huệ, Phường Bến Nghé, Quận 1, TP. Hồ Chí Minh'
                    }
                    error={error}
                />
            </div>
        )
    }

    return (
        <div className="space-y-3">
            <div className="flex items-center justify-between">
                <label className="text-sm font-semibold text-gray-700">
                    {lang === 'en'
                        ? 'Address (Provinces API)'
                        : 'Địa chỉ (Chọn theo Tỉnh / Quận / Xã)'}
                </label>
                <button
                    type="button"
                    onClick={() => setManualMode(true)}
                    className="text-xs text-gray-500 hover:text-primary hover:underline"
                >
                    {lang === 'en' ? 'Type freely' : 'Nhập tự do'}
                </button>
            </div>

            {/* 3 Dropdown chọn phân cấp hành chính */}
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-2.5">
                {/* 1. Tỉnh / Thành phố */}
                <div className="flex flex-col gap-1">
                    <select
                        aria-label={lang === 'en' ? 'Province / City' : 'Tỉnh / Thành phố'}
                        value={selectedProvinceCode}
                        disabled={loadingProvinces}
                        onChange={(e) => {
                            const code = e.target.value ? Number(e.target.value) : ''
                            setSelectedProvinceCode(code)
                            setSelectedDistrictCode('')
                            setSelectedWardCode('')
                            handleComposeAddress(code, '', '', street)
                        }}
                        className="nexus-input text-xs py-2 px-2.5"
                    >
                        <option value="">
                            {loadingProvinces
                                ? lang === 'en'
                                    ? 'Loading...'
                                    : 'Đang tải...'
                                : lang === 'en'
                                  ? '-- Province/City --'
                                  : '-- Tỉnh / Thành phố --'}
                        </option>
                        {provinces.map((p) => (
                            <option key={p.code} value={p.code}>
                                {p.name}
                            </option>
                        ))}
                    </select>
                </div>

                {/* 2. Quận / Huyện */}
                <div className="flex flex-col gap-1">
                    <select
                        aria-label={lang === 'en' ? 'District' : 'Quận / Huyện'}
                        value={selectedDistrictCode}
                        disabled={!selectedProvinceCode || loadingDistricts}
                        onChange={(e) => {
                            const code = e.target.value ? Number(e.target.value) : ''
                            setSelectedDistrictCode(code)
                            setSelectedWardCode('')
                            handleComposeAddress(selectedProvinceCode, code, '', street)
                        }}
                        className="nexus-input text-xs py-2 px-2.5"
                    >
                        <option value="">
                            {loadingDistricts
                                ? lang === 'en'
                                    ? 'Loading...'
                                    : 'Đang tải...'
                                : lang === 'en'
                                  ? '-- District --'
                                  : '-- Quận / Huyện --'}
                        </option>
                        {districts.map((d) => (
                            <option key={d.code} value={d.code}>
                                {d.name}
                            </option>
                        ))}
                    </select>
                </div>

                {/* 3. Phường / Xã */}
                <div className="flex flex-col gap-1">
                    <select
                        aria-label={lang === 'en' ? 'Ward' : 'Phường / Xã'}
                        value={selectedWardCode}
                        disabled={!selectedDistrictCode || loadingWards}
                        onChange={(e) => {
                            const code = e.target.value ? Number(e.target.value) : ''
                            setSelectedWardCode(code)
                            handleComposeAddress(
                                selectedProvinceCode,
                                selectedDistrictCode,
                                code,
                                street,
                            )
                        }}
                        className="nexus-input text-xs py-2 px-2.5"
                    >
                        <option value="">
                            {loadingWards
                                ? lang === 'en'
                                    ? 'Loading...'
                                    : 'Đang tải...'
                                : lang === 'en'
                                  ? '-- Ward --'
                                  : '-- Phường / Xã --'}
                        </option>
                        {wards.map((w) => (
                            <option key={w.code} value={w.code}>
                                {w.name}
                            </option>
                        ))}
                    </select>
                </div>
            </div>

            {/* Ô nhập số nhà, tên đường */}
            <Input
                placeholder={
                    lang === 'en'
                        ? 'House number, street name (e.g. 123 Nguyen Hue)'
                        : 'Số nhà, tên đường (Ví dụ: 123 Nguyễn Huệ)'
                }
                value={street}
                onChange={(e) => {
                    const newStreet = e.target.value
                    setStreet(newStreet)
                    handleComposeAddress(
                        selectedProvinceCode,
                        selectedDistrictCode,
                        selectedWardCode,
                        newStreet,
                    )
                }}
            />

            {/* Hiển thị bản xem trước địa chỉ đầy đủ */}
            {value && (
                <p className="text-xs text-gray-500 bg-gray-50 px-2.5 py-1.5 rounded-lg border border-gray-100">
                    <span className="font-medium text-gray-700">
                        {lang === 'en' ? 'Full address:' : 'Địa chỉ đầy đủ:'}
                    </span>{' '}
                    {value}
                </p>
            )}

            {error && <p className="text-xs text-danger font-medium mt-1">{error}</p>}
        </div>
    )
}
