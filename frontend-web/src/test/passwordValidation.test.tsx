import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import {
    validatePasswordPolicy,
    PasswordRequirementsChecklist,
    PasswordConfirmIndicator,
} from '../features/auth/PasswordValidation'
import { RegisterPage, ForgotPasswordPage, LoginPage } from '../features/auth/AuthPages'
import { ProfilePage } from '../features/profile/ProfilePage'
import { extractFieldErrors, ApiClientError } from '../api/client'

// Mock AppContext
const mockNavigate = vi.fn()
const mockSetUser = vi.fn()
const mockShowToast = vi.fn()

vi.mock('../context', () => ({
    useApp: () => ({
        lang: 'vi',
        navigate: mockNavigate,
        user: { id: 'user-1', email: 'test@nexus.vn', fullName: 'Tester', role: 'buyer' },
        setUser: mockSetUser,
        showToast: mockShowToast,
        cartCount: 0,
        toasts: [],
    }),
}))

describe('Chính sách kiểm tra mật khẩu (validatePasswordPolicy)', () => {
    it('chấp nhận mật khẩu hợp lệ đầy đủ 4 điều kiện', () => {
        // Mật khẩu mẫu chuẩn
        const res1 = validatePasswordPolicy('Nexus12@')
        expect(res1.isValid).toBe(true)
        expect(res1.length).toBe(true)
        expect(res1.hasUppercase).toBe(true)
        expect(res1.hasDigit).toBe(true)
        expect(res1.hasSpecialChar).toBe(true)

        // Chữ tiếng Việt như Đ, Â... KHÔNG được tính là chữ in hoa (chỉ tính A-Z)
        const resDang = validatePasswordPolicy('Đang')
        expect(resDang.isValid).toBe(false)
        expect(resDang.hasUppercase).toBe(false) // 'Đ' không thuộc [A-Z]
        expect(resDang.length).toBe(false) // 4 < 8

        const resVietnameseUpper = validatePasswordPolicy('Đabcdef1!')
        expect(resVietnameseUpper.hasUppercase).toBe(false) // 'Đ' không tính là chữ in hoa
        expect(resVietnameseUpper.isValid).toBe(false)

        // Biên tối đa 100 ký tự (3 ký tự đầu + 97 ký tự = 100)
        const resMax = validatePasswordPolicy('A1!' + 'a'.repeat(97))
        expect(resMax.isValid).toBe(true)
        expect(resMax.length).toBe(true)
    })

    it('từ chối mật khẩu không đạt độ dài 8–100 ký tự', () => {
        // Dưới 8 ký tự (7 ký tự)
        const shortPass = validatePasswordPolicy('Nex12@')
        expect(shortPass.length).toBe(false)
        expect(shortPass.isValid).toBe(false)

        // Quá 100 ký tự (101 ký tự)
        const longPass = validatePasswordPolicy('A1!' + 'a'.repeat(98))
        expect(longPass.length).toBe(false)
        expect(longPass.isValid).toBe(false)
    })

    it('từ chối mật khẩu thiếu chữ in hoa', () => {
        const noUpper = validatePasswordPolicy('nexus12@abc')
        expect(noUpper.hasUppercase).toBe(false)
        expect(noUpper.isValid).toBe(false)
    })

    it('từ chối mật khẩu thiếu chữ số', () => {
        const noDigit = validatePasswordPolicy('NexusSpecial@')
        expect(noDigit.hasDigit).toBe(false)
        expect(noDigit.isValid).toBe(false)
    })

    it('từ chối mật khẩu thiếu ký tự đặc biệt và khoảng trắng KHÔNG tính là ký tự đặc biệt', () => {
        // Không có ký tự đặc biệt
        const noSpecial = validatePasswordPolicy('Nexus12345')
        expect(noSpecial.hasSpecialChar).toBe(false)
        expect(noSpecial.isValid).toBe(false)

        // Có khoảng trắng nhưng không có ký tự đặc biệt: khoảng trắng thuộc \p{Z}, không tính là ký tự đặc biệt
        const spaceOnly = validatePasswordPolicy('Nexus 12345')
        expect(spaceOnly.hasSpecialChar).toBe(false)
        expect(spaceOnly.isValid).toBe(false)

        // Kết thúc bằng khoảng trắng nhưng không có ký hiệu Unicode
        const trailingSpace = validatePasswordPolicy('Abcdef12 ')
        expect(trailingSpace.hasSpecialChar).toBe(false)
        expect(trailingSpace.isValid).toBe(false)

        // Các ký tự đặc biệt hợp lệ khác nhau: @, !, #, $, %, &, *, -, _, +, =, ?
        expect(validatePasswordPolicy('Nexus12#').hasSpecialChar).toBe(true)
        expect(validatePasswordPolicy('Nexus12$').hasSpecialChar).toBe(true)
        expect(validatePasswordPolicy('Nexus12%').hasSpecialChar).toBe(true)
        expect(validatePasswordPolicy('Nexus12&').hasSpecialChar).toBe(true)
        expect(validatePasswordPolicy('Nexus12*').hasSpecialChar).toBe(true)
        expect(validatePasswordPolicy('Nexus12_').hasSpecialChar).toBe(true)
        expect(validatePasswordPolicy('Nexus12+').hasSpecialChar).toBe(true)
    })
})

describe('Component hiển thị điều kiện mật khẩu (PasswordRequirementsChecklist & Confirm)', () => {
    it('đánh dấu điều kiện đạt/chưa đạt theo thời gian thực khi gõ', () => {
        const { rerender } = render(<PasswordRequirementsChecklist password="" lang="vi" />)
        expect(screen.getByTestId('criteria-length')).toHaveAttribute('data-met', 'false')
        expect(screen.getByTestId('criteria-hasUppercase')).toHaveAttribute('data-met', 'false')
        expect(screen.getByTestId('criteria-hasDigit')).toHaveAttribute('data-met', 'false')
        expect(screen.getByTestId('criteria-hasSpecialChar')).toHaveAttribute('data-met', 'false')

        // Gõ mật khẩu có 8 ký tự và chữ hoa
        rerender(<PasswordRequirementsChecklist password="Password" lang="vi" />)
        expect(screen.getByTestId('criteria-length')).toHaveAttribute('data-met', 'true')
        expect(screen.getByTestId('criteria-hasUppercase')).toHaveAttribute('data-met', 'true')
        expect(screen.getByTestId('criteria-hasDigit')).toHaveAttribute('data-met', 'false')
        expect(screen.getByTestId('criteria-hasSpecialChar')).toHaveAttribute('data-met', 'false')

        // Thêm chữ số và ký tự đặc biệt
        rerender(<PasswordRequirementsChecklist password="Password123!" lang="vi" />)
        expect(screen.getByTestId('criteria-length')).toHaveAttribute('data-met', 'true')
        expect(screen.getByTestId('criteria-hasUppercase')).toHaveAttribute('data-met', 'true')
        expect(screen.getByTestId('criteria-hasDigit')).toHaveAttribute('data-met', 'true')
        expect(screen.getByTestId('criteria-hasSpecialChar')).toHaveAttribute('data-met', 'true')
    })

    it('hiển thị trạng thái khớp hoặc không khớp của mật khẩu xác nhận', () => {
        const { rerender } = render(
            <PasswordConfirmIndicator
                password="Password123!"
                confirmPassword="PasswordMismatch"
                lang="vi"
            />,
        )
        const indicator = screen.getByTestId('confirm-password-indicator')
        expect(indicator).toHaveAttribute('data-match', 'false')
        expect(screen.getByText('Mật khẩu xác nhận chưa khớp')).toBeInTheDocument()

        rerender(
            <PasswordConfirmIndicator
                password="Password123!"
                confirmPassword="Password123!"
                lang="vi"
            />,
        )
        expect(screen.getByTestId('confirm-password-indicator')).toHaveAttribute(
            'data-match',
            'true',
        )
        expect(screen.getByText('Mật khẩu xác nhận trùng khớp')).toBeInTheDocument()
    })
})

describe('Đọc lỗi 422 từ BE (error.details[] và extractFieldErrors)', () => {
    it('trích xuất đúng field và message từ response 422 có envelope chuẩn', () => {
        const error = new ApiClientError('Dữ liệu yêu cầu không hợp lệ', 'VALIDATION_ERROR', 422, [
            { field: 'password', code: 'SIZE', message: 'Mật khẩu phải từ 8 đến 100 ký tự' },
            {
                field: 'password',
                code: 'UPPERCASE_REQUIRED',
                message: 'Mật khẩu cần ít nhất 1 chữ in hoa',
            },
            { field: 'email', code: 'INVALID', message: 'Email không hợp lệ' },
        ])

        const fieldErrors = extractFieldErrors(error)
        expect(fieldErrors.password).toBe(
            'Mật khẩu phải từ 8 đến 100 ký tự. Mật khẩu cần ít nhất 1 chữ in hoa',
        )
        expect(fieldErrors.email).toBe('Email không hợp lệ')
    })

    it('trích xuất từ error object thô có details[]', () => {
        const raw = {
            details: [
                { field: 'newPassword', message: 'Mật khẩu mới cần ít nhất 1 ký tự đặc biệt' },
                { field: 'currentPassword', message: 'Mật khẩu hiện tại không đúng' },
            ],
        }
        const fieldErrors = extractFieldErrors(raw)
        expect(fieldErrors.newPassword).toBe('Mật khẩu mới cần ít nhất 1 ký tự đặc biệt')
        expect(fieldErrors.currentPassword).toBe('Mật khẩu hiện tại không đúng')
    })
})

describe('Form Đăng ký (RegisterPage)', () => {
    beforeEach(() => {
        vi.stubGlobal('fetch', vi.fn())
        mockNavigate.mockClear()
        mockShowToast.mockClear()
    })
    afterEach(() => vi.unstubAllGlobals())

    it('chặn submit bước 3 nếu mật khẩu không đạt policy và hiển thị lỗi', async () => {
        const ui = userEvent.setup()
        render(<RegisterPage />)

        // Bước 1
        await ui.type(screen.getByLabelText(/Email/i), 'buyer@test.vn')
        await ui.type(screen.getByLabelText(/Phone|Số điện thoại/i), '0901234567')
        await ui.click(screen.getByRole('button', { name: /Next|Tiếp theo/i }))

        // Bước 2
        await ui.type(screen.getByLabelText(/Full Name|Họ và tên/i), 'Nguyễn Văn A')
        await ui.type(screen.getByLabelText(/ID Number|Số CMND/i), '079203001234')
        await ui.type(screen.getByLabelText(/Date of Birth|Ngày sinh/i), '2000-01-01')
        await ui.click(screen.getByRole('button', { name: /Next|Tiếp theo/i }))

        // Bước 3: Nhập mật khẩu yếu (chỉ 6 ký tự, không số, không ký tự đặc biệt)
        const passwordInput = screen.getByLabelText(/^Mật khẩu$|^Password$/i)
        await ui.type(passwordInput, 'Weak')
        const confirmInput = screen.getByLabelText(/Confirm Password|Xác nhận mật khẩu/i)
        await ui.type(confirmInput, 'Weak')

        await ui.click(screen.getByRole('button', { name: /Register|Đăng ký/i }))

        // Bị chặn và hiển thị thông báo lỗi
        expect(
            screen.getByText(/Mật khẩu mới từ 8 đến 100 ký tự, có ít nhất 1 chữ in hoa/i),
        ).toBeInTheDocument()
        expect(fetch).not.toHaveBeenCalled()
    })

    it('hiển thị lỗi từng field khi BE trả lỗi 422 kèm error.details[]', async () => {
        const ui = userEvent.setup()

        // Mock BE trả 422 Unprocessable Entity kèm error.details
        vi.stubGlobal(
            'fetch',
            vi.fn().mockResolvedValue(
                new Response(
                    JSON.stringify({
                        success: false,
                        error: {
                            code: 'VALIDATION_ERROR',
                            message: 'Mật khẩu chưa đạt yêu cầu',
                            details: [
                                {
                                    field: 'password',
                                    code: 'SYMBOL_REQUIRED',
                                    message:
                                        'Mật khẩu cần ít nhất 1 ký tự đặc biệt (ví dụ @, !, #)',
                                },
                            ],
                        },
                    }),
                    { status: 422 },
                ),
            ),
        )

        render(<RegisterPage />)

        // Bước 1
        await ui.type(screen.getByLabelText(/Email/i), 'buyer@test.vn')
        await ui.type(screen.getByLabelText(/Phone|Số điện thoại/i), '0901234567')
        await ui.click(screen.getByRole('button', { name: /Next|Tiếp theo/i }))

        // Bước 2
        await ui.type(screen.getByLabelText(/Full Name|Họ và tên/i), 'Nguyễn Văn A')
        await ui.type(screen.getByLabelText(/ID Number|Số CMND/i), '079203001234')
        await ui.type(screen.getByLabelText(/Date of Birth|Ngày sinh/i), '2000-01-01')
        await ui.click(screen.getByRole('button', { name: /Next|Tiếp theo/i }))

        // Bước 3: Nhập pass hợp lệ ở FE nhưng BE giả định từ chối 422
        const passwordInput = screen.getByLabelText(/^Mật khẩu$|^Password$/i)
        await ui.type(passwordInput, 'ValidPass123!')
        const confirmInput = screen.getByLabelText(/Confirm Password|Xác nhận mật khẩu/i)
        await ui.type(confirmInput, 'ValidPass123!')

        await ui.click(screen.getByRole('button', { name: /Register|Đăng ký/i }))

        // Đọc error.details[] và hiển thị lỗi cụ thể của field password
        await waitFor(() => {
            expect(
                screen.getByText('Mật khẩu cần ít nhất 1 ký tự đặc biệt (ví dụ @, !, #)'),
            ).toBeInTheDocument()
        })
        expect(mockShowToast).toHaveBeenCalledWith({
            type: 'error',
            message: 'Mật khẩu cần ít nhất 1 ký tự đặc biệt (ví dụ @, !, #)',
        })
    })
})

describe('Form Quên mật khẩu (ForgotPasswordPage)', () => {
    beforeEach(() => {
        vi.stubGlobal(
            'fetch',
            vi.fn().mockResolvedValue(new Response(JSON.stringify({ message: 'OK' }))),
        )
        mockNavigate.mockClear()
    })
    afterEach(() => vi.unstubAllGlobals())

    it('gửi email thành công và hiển thị hướng dẫn mở link tới Keycloak (bỏ token form cũ)', async () => {
        const ui = userEvent.setup()
        render(<ForgotPasswordPage />)

        await ui.type(screen.getByPlaceholderText('you@example.com'), 'buyer@test.vn')
        await ui.click(
            screen.getByRole('button', { name: /Gửi liên kết đặt lại|Send Reset Link/i }),
        )

        // Hiển thị hướng dẫn mở link Keycloak
        expect(await screen.findByRole('status')).toHaveTextContent(/Keycloak/i)
        // Không còn ô nhập reset token cũ
        expect(screen.queryByLabelText(/Reset token/i)).not.toBeInTheDocument()
    })
})

describe('Form Đăng nhập (LoginPage)', () => {
    it('không áp dụng policy mật khẩu mới vào ô mật khẩu đăng nhập', () => {
        render(<LoginPage />)
        // Ô password ở Login không có PasswordRequirementsChecklist
        expect(screen.queryByTestId('password-requirements')).not.toBeInTheDocument()
    })
})

describe('Form Đổi mật khẩu (ProfilePage)', () => {
    beforeEach(() => {
        vi.stubGlobal(
            'fetch',
            vi.fn((input: string) => {
                const path = String(input)
                if (path.includes('/categories')) {
                    return Promise.resolve(
                        new Response(JSON.stringify({ success: true, data: [] })),
                    )
                }
                if (path.includes('/users/me/activity-history')) {
                    return Promise.resolve(new Response(JSON.stringify({ content: [] })))
                }
                if (path.includes('/users/me')) {
                    return Promise.resolve(
                        new Response(
                            JSON.stringify({
                                id: 'user-1',
                                email: 'buyer@nexus.vn',
                                fullName: 'Nguyễn Văn A',
                                status: 'ACTIVE',
                            }),
                        ),
                    )
                }
                return Promise.resolve(new Response(JSON.stringify({ message: 'OK' })))
            }),
        )
        mockNavigate.mockClear()
        mockSetUser.mockClear()
        mockShowToast.mockClear()
    })
    afterEach(() => vi.unstubAllGlobals())

    it('hiển thị checklist yêu cầu mật khẩu khi người dùng gõ mật khẩu mới', async () => {
        const ui = userEvent.setup()
        render(<ProfilePage />)

        await screen.findByDisplayValue('Nguyễn Văn A')
        expect(screen.getByTestId('change-password-form')).toBeInTheDocument()

        const newPassInput = screen.getByLabelText('Mật khẩu mới')
        await ui.type(newPassInput, 'NewPass')

        // Checklist điều kiện hiển thị ngay dưới ô nhập
        expect(screen.getByTestId('criteria-length')).toBeInTheDocument()
    })

    it('từ chối khi mật khẩu mới không đạt policy mà không gọi BE', async () => {
        const ui = userEvent.setup()
        render(<ProfilePage />)

        await screen.findByDisplayValue('Nguyễn Văn A')
        await ui.type(screen.getByLabelText('Mật khẩu hiện tại'), 'OldPass123')
        await ui.type(screen.getByLabelText('Mật khẩu mới'), 'short')
        await ui.type(screen.getByLabelText('Xác nhận mật khẩu'), 'short')

        await ui.click(screen.getByRole('button', { name: 'Đổi mật khẩu' }))

        expect(
            screen.getByText(
                'Mật khẩu mới từ 8 đến 100 ký tự, có ít nhất 1 chữ in hoa (A-Z), 1 chữ số, 1 ký tự đặc biệt',
            ),
        ).toBeInTheDocument()
        const changePassCalls = vi
            .mocked(fetch)
            .mock.calls.filter(([url]) => String(url).includes('/auth/change-password'))
        expect(changePassCalls).toHaveLength(0)
    })

    it('gửi currentPassword và newPassword tới POST /api/v1/auth/change-password khi hợp lệ', async () => {
        const ui = userEvent.setup()
        render(<ProfilePage />)

        await screen.findByDisplayValue('Nguyễn Văn A')
        await ui.type(screen.getByLabelText('Mật khẩu hiện tại'), 'OldPass123!')
        await ui.type(screen.getByLabelText('Mật khẩu mới'), 'NexusPass123@')
        await ui.type(screen.getByLabelText('Xác nhận mật khẩu'), 'NexusPass123@')

        await ui.click(screen.getByRole('button', { name: 'Đổi mật khẩu' }))

        await waitFor(() => {
            const changePassCall = vi
                .mocked(fetch)
                .mock.calls.find(([url]) => String(url).includes('/auth/change-password'))
            expect(changePassCall).toBeDefined()
            const body = JSON.parse(String(changePassCall?.[1]?.body))
            expect(body).toEqual({
                currentPassword: 'OldPass123!',
                newPassword: 'NexusPass123@',
            })
        })

        // Thành công: đăng xuất và chuyển về login
        await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('login'))
        expect(mockSetUser).toHaveBeenCalledWith(null)
    })

    it('hiển thị từng lỗi cụ thể trên field khi BE trả lỗi 422 kèm error.details[]', async () => {
        const ui = userEvent.setup()
        vi.stubGlobal(
            'fetch',
            vi.fn((input: string) => {
                const path = String(input)
                if (path.includes('/categories')) {
                    return Promise.resolve(
                        new Response(JSON.stringify({ success: true, data: [] })),
                    )
                }
                if (path.includes('/users/me/activity-history')) {
                    return Promise.resolve(new Response(JSON.stringify({ content: [] })))
                }
                if (path.includes('/users/me')) {
                    return Promise.resolve(
                        new Response(
                            JSON.stringify({
                                id: 'user-1',
                                email: 'buyer@nexus.vn',
                                fullName: 'Nguyễn Văn A',
                                status: 'ACTIVE',
                            }),
                        ),
                    )
                }
                if (path.includes('/auth/change-password')) {
                    return Promise.resolve(
                        new Response(
                            JSON.stringify({
                                success: false,
                                error: {
                                    code: 'VALIDATION_ERROR',
                                    message: 'Dữ liệu không hợp lệ',
                                    details: [
                                        {
                                            field: 'newPassword',
                                            code: 'UPPERCASE_REQUIRED',
                                            message: 'Mật khẩu mới cần ít nhất 1 chữ in hoa',
                                        },
                                        {
                                            field: 'currentPassword',
                                            code: 'INVALID',
                                            message: 'Mật khẩu hiện tại không chính xác',
                                        },
                                    ],
                                },
                            }),
                            { status: 422 },
                        ),
                    )
                }
                return Promise.resolve(new Response(JSON.stringify({ message: 'OK' })))
            }),
        )

        render(<ProfilePage />)

        await screen.findByDisplayValue('Nguyễn Văn A')
        await ui.type(screen.getByLabelText('Mật khẩu hiện tại'), 'WrongPass123!')
        await ui.type(screen.getByLabelText('Mật khẩu mới'), 'ValidPass123!')
        await ui.type(screen.getByLabelText('Xác nhận mật khẩu'), 'ValidPass123!')

        await ui.click(screen.getByRole('button', { name: 'Đổi mật khẩu' }))

        // Hiển thị lỗi tương ứng trên từng trường
        await waitFor(() => {
            expect(screen.getByText('Mật khẩu mới cần ít nhất 1 chữ in hoa')).toBeInTheDocument()
            expect(screen.getByText('Mật khẩu hiện tại không chính xác')).toBeInTheDocument()
        })
    })
})
