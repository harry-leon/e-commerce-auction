/**
 * Module xử lý mã hóa và băm dữ liệu bảo mật tại Client (Frontend).
 *
 * Chức năng:
 * - Băm mật khẩu người dùng bằng thuật toán SHA-256 trước khi gửi qua API xuống Gateway/Keycloak.
 * - Đầu vào: Chuỗi mật khẩu gốc (plain-text) do người dùng nhập trên form.
 * - Kết quả: Chuỗi băm hex 64 ký tự an toàn.
 * - Quy tắc và lý do:
 *   + Mật khẩu thô không bao giờ được phép xuất hiện trên đường truyền mạng HTTP hay trong RAM/log của Backend.
 *   + Sử dụng chuẩn Web Crypto API có sẵn trên mọi trình duyệt hiện đại (Chrome, Firefox, Safari, Edge),
 *     hoạt động độc lập, không phụ thuộc thư viện bên ngoài và được tối ưu hóa ở tầng C++ của browser engine.
 */
export async function hashPasswordClient(password: string): Promise<string> {
    if (!password) {
        return ''
    }
    const encoder = new TextEncoder()
    const data = encoder.encode(password)
    const hashBuffer = await window.crypto.subtle.digest('SHA-256', data)
    const hashArray = Array.from(new Uint8Array(hashBuffer))
    return hashArray.map((b) => b.toString(16).padStart(2, '0')).join('')
}
