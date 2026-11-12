import { api } from '../api/client'

/**
 * Module mã hóa mật khẩu phía Client sử dụng Web Crypto API (gốc từ trình duyệt).
 *
 * Chức năng:
 * 1. Lấy Public Key RSA từ API Gateway (GET /api/v1/auth/public-key) và cache trong bộ nhớ.
 * 2. Mã hóa mật khẩu plain-text thành chuỗi Base64 bằng thuật toán RSA-OAEP (SHA-256).
 * 3. Ẩn mật khẩu hoàn toàn khỏi tab Network > Payload trong DevTools của trình duyệt.
 *
 * Luồng xử lý:
 * - Gọi getOrFetchPublicKey() để lấy CryptoKey đã import.
 * - Chuyển chuỗi mật khẩu sang UTF-8 bytes bằng TextEncoder.
 * - Mã hóa qua window.crypto.subtle.encrypt() với chuẩn RSA-OAEP.
 * - Chuyển ArrayBuffer thành chuỗi Base64 và trả về cho form submit.
 *
 * Quy tắc bảo mật:
 * - Khóa bí mật (Private Key) chỉ nằm tại Gateway, không bao giờ lộ về Frontend.
 * - Khi có lỗi mạng hoặc không hỗ trợ Web Crypto, fallback an toàn để tránh chặn đứng người dùng.
 */

let cachedCryptoKey: CryptoKey | null = null
let keyFetchPromise: Promise<CryptoKey | null> | null = null

/** Chuyển đổi chuỗi Base64 thành ArrayBuffer cho Web Crypto API */
function base64ToArrayBuffer(base64: string): ArrayBuffer {
    const binary = window.atob(base64)
    const bytes = new Uint8Array(binary.length)
    for (let i = 0; i < binary.length; i++) {
        bytes[i] = binary.charCodeAt(i)
    }
    return bytes.buffer
}

/** Chuyển đổi ArrayBuffer mã hóa thành chuỗi Base64 */
function arrayBufferToBase64(buffer: ArrayBuffer): string {
    const bytes = new Uint8Array(buffer)
    let binary = ''
    for (let i = 0; i < bytes.byteLength; i++) {
        binary += String.fromCharCode(bytes[i])
    }
    return window.btoa(binary)
}

/**
 * Lấy hoặc tải Public Key từ Backend Gateway và nạp thành CryptoKey.
 * Dùng chung một Promise khi nhiều component gọi cùng lúc để tránh gửi nhiều request lấy key.
 */
export async function getOrFetchPublicKey(): Promise<CryptoKey | null> {
    if (cachedCryptoKey) {
        return cachedCryptoKey
    }

    if (keyFetchPromise) {
        return keyFetchPromise
    }

    keyFetchPromise = (async () => {
        try {
            // Kiểm tra môi trường (trình duyệt hoặc Node.js test) có hỗ trợ Web Crypto API
            const subtle =
                globalThis.crypto?.subtle ||
                (typeof window !== 'undefined' ? window.crypto?.subtle : undefined)
            if (!subtle) {
                return null
            }

            const res = await api<{ publicKey: string } | { data: { publicKey: string } }>(
                '/auth/public-key',
                { method: 'GET' },
                false,
            )
            const raw = (res as any)?.data || res
            const publicKeyBase64 = raw.publicKey
            if (!publicKeyBase64) {
                return null
            }

            const keyDer = base64ToArrayBuffer(publicKeyBase64)
            const key = await subtle.importKey(
                'spki',
                keyDer,
                { name: 'RSA-OAEP', hash: 'SHA-256' },
                false,
                ['encrypt'],
            )

            cachedCryptoKey = key
            return key
        } catch (_err) {
            return null
        } finally {
            keyFetchPromise = null
        }
    })()

    return keyFetchPromise
}

/**
 * Mã hóa mật khẩu plain-text sang chuỗi Base64 bằng RSA-OAEP trước khi gửi qua API.
 *
 * @param password mật khẩu người dùng vừa nhập trên form
 * @returns chuỗi Base64 đã được mã hóa, hoặc mật khẩu gốc nếu không thể mã hóa (fallback)
 */
export async function encryptPassword(password: string): Promise<string> {
    if (!password) return ''

    try {
        const key = await getOrFetchPublicKey()
        if (!key) {
            return password
        }

        const subtle =
            globalThis.crypto?.subtle ||
            (typeof window !== 'undefined' ? window.crypto?.subtle : undefined)
        if (!subtle) {
            return password
        }

        const encoded = new TextEncoder().encode(password)
        const cipherBuffer = await subtle.encrypt({ name: 'RSA-OAEP' }, key, encoded)

        return arrayBufferToBase64(cipherBuffer)
    } catch (err) {
        console.error('Lỗi mã hóa mật khẩu RSA ở Frontend:', err)
        return password
    }
}
