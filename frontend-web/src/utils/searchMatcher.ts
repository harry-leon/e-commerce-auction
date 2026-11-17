/**
 * Module tối ưu tìm kiếm sản phẩm bằng từ khóa (Smart Search & Vietnamese Diacritics Matcher).
 *
 * Chức năng:
 * - Chuẩn hóa tiếng Việt có dấu và không dấu (bỏ dấu, chuyển đ/Đ thành d/D).
 * - Tìm kiếm đa từ khóa (Multi-token search): "đèn led" khớp với "Đèn bàn học LED" dù các từ không nằm liền kề.
 * - Khớp không phân biệt thứ tự từ: "led đèn" hay "đèn bàn" đều tìm thấy "Đèn bàn học LED".
 * - Chấm điểm độ phù hợp (Relevance Scoring) để ưu tiên sản phẩm khớp tên, khớp nguyên cụm từ lên đầu.
 */

/**
 * Xóa dấu tiếng Việt và chuẩn hóa chuỗi về dạng ký tự ASCII viết thường.
 * Ví dụ: "Đèn bàn học LED" -> "den ban hoc led"
 */
export function removeVietnameseTones(str: string): string {
    if (!str) return ''
    return str
        .normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '')
        .replace(/đ/g, 'd')
        .replace(/Đ/g, 'D')
        .toLowerCase()
        .trim()
}

/**
 * Tách từ khóa tìm kiếm thành mảng các token có nghĩa.
 */
export function tokenizeQuery(query: string): string[] {
    if (!query) return []
    return query
        .trim()
        .toLowerCase()
        .split(/\s+/)
        .filter((token) => token.length > 0)
}

/**
 * Kiểm tra xem văn bản mục tiêu có khớp với từ khóa tìm kiếm hay không.
 *
 * Quy tắc khớp:
 * 1. Khớp nguyên cụm từ (có dấu hoặc không dấu): "đèn bàn" in "Đèn bàn học LED" -> true
 * 2. Khớp toàn bộ các từ (Multi-token): "đèn led" in "Đèn bàn học LED" -> true (vì có cả "đèn" và "led")
 * 3. Khớp không dấu: "den led" in "Đèn bàn học LED" -> true
 *
 * @param targetText Chuỗi cần tìm (tên sản phẩm, mô tả, danh mục, SKU)
 * @param searchQuery Từ khóa người dùng nhập vào
 */
export function matchesSearchKeyword(targetText: string, searchQuery: string): boolean {
    if (!searchQuery || !searchQuery.trim()) return true
    if (!targetText) return false

    const rawTarget = targetText.toLowerCase()
    const normTarget = removeVietnameseTones(targetText)

    const rawQuery = searchQuery.trim().toLowerCase()
    const normQuery = removeVietnameseTones(searchQuery)

    // 1. Khớp nguyên cụm từ trực tiếp
    if (rawTarget.includes(rawQuery) || normTarget.includes(normQuery)) {
        return true
    }

    // 2. Khớp đa từ khóa (Tất cả các từ trong query đều phải xuất hiện trong target)
    const rawTokens = tokenizeQuery(rawQuery)
    const normTokens = tokenizeQuery(normQuery)

    if (rawTokens.length > 1) {
        const allTokensMatch = normTokens.every((token, index) => {
            const rawToken = rawTokens[index]
            return rawTarget.includes(rawToken) || normTarget.includes(token)
        })
        if (allTokensMatch) {
            return true
        }
    }

    return false
}

/**
 * Tính điểm độ liên quan (Relevance Score) của sản phẩm so với từ khóa tìm kiếm.
 * Điểm càng cao thì sản phẩm càng liên quan mật thiết và được xếp lên đầu.
 */
export function scoreProductSearch(
    product: {
        name?: string
        nameVi?: string
        description?: string
        descriptionVi?: string
        category?: string
        sku?: string
        skus?: Array<{ skuCode?: string | null | undefined }>
    },
    searchQuery: string,
): number {
    if (!searchQuery || !searchQuery.trim()) return 1

    const rawQuery = searchQuery.trim().toLowerCase()
    const normQuery = removeVietnameseTones(searchQuery)
    const rawTokens = tokenizeQuery(rawQuery)
    const normTokens = tokenizeQuery(normQuery)

    const titleRaw = (product.name || '').toLowerCase()
    const titleNorm = removeVietnameseTones(product.name || '')
    const titleViRaw = (product.nameVi || '').toLowerCase()
    const titleViNorm = removeVietnameseTones(product.nameVi || '')

    const descRaw = (product.description || '').toLowerCase()
    const descNorm = removeVietnameseTones(product.description || '')
    const descViRaw = (product.descriptionVi || '').toLowerCase()
    const descViNorm = removeVietnameseTones(product.descriptionVi || '')

    const combinedTitle = `${titleRaw} ${titleNorm} ${titleViRaw} ${titleViNorm}`
    const combinedDesc = `${descRaw} ${descNorm} ${descViRaw} ${descViNorm}`

    let score = 0

    // 1. Tên sản phẩm khớp chính xác 100% với từ khóa
    if (titleRaw === rawQuery || titleNorm === normQuery) {
        return 1000
    }

    // 2. Tên sản phẩm bắt đầu bằng từ khóa
    if (titleRaw.startsWith(rawQuery) || titleNorm.startsWith(normQuery)) {
        score += 800
    }

    // 3. Tên sản phẩm chứa nguyên cụm từ khóa liên tục
    if (combinedTitle.includes(rawQuery) || combinedTitle.includes(normQuery)) {
        score += 600
    }

    // 4. Tên sản phẩm chứa TẤT CẢ các từ trong từ khóa (ví dụ "Đèn bàn học LED" chứa cả "đèn" và "led")
    if (rawTokens.length > 1) {
        const titleTokensMatch = normTokens.every((token, idx) => {
            const rawToken = rawTokens[idx]
            return combinedTitle.includes(rawToken) || combinedTitle.includes(token)
        })
        if (titleTokensMatch) {
            score += 500
        }
    }

    // 5. Mô tả chứa nguyên cụm từ khóa
    if (combinedDesc.includes(rawQuery) || combinedDesc.includes(normQuery)) {
        score += 200
    }

    // 6. Mô tả chứa tất cả các từ trong từ khóa
    if (rawTokens.length > 1) {
        const descTokensMatch = normTokens.every((token, idx) => {
            const rawToken = rawTokens[idx]
            return (
                combinedTitle.includes(token) ||
                combinedDesc.includes(token) ||
                combinedTitle.includes(rawToken) ||
                combinedDesc.includes(rawToken)
            )
        })
        if (descTokensMatch && score < 500) {
            score += 300
        }
    }

    // 7. Điểm thưởng cho từng từ khóa khớp trong tên sản phẩm
    for (let i = 0; i < normTokens.length; i++) {
        const token = normTokens[i]
        const rawToken = rawTokens[i]
        if (combinedTitle.includes(token) || combinedTitle.includes(rawToken)) {
            score += 50
        } else if (combinedDesc.includes(token) || combinedDesc.includes(rawToken)) {
            score += 15
        }
    }

    return score
}
