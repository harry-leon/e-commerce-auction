import { test, expect } from '@playwright/test'

const user = { id: '00000000-0000-0000-0000-000000000001', fullName: 'Sprint Buyer', email: 'buyer@example.com', status: 'ACTIVE', roles: ['BUYER'], createdAt: '2026-10-19', gender: 'MALE' }
const product = { id: '00000000-0000-0000-0000-000000000002', name: 'Real API Laptop', description: 'Backend description', categoryId: '00000000-0000-0000-0000-000000000003', sellerId: user.id, status: 'ACTIVE', auctionEnabled: false, createdAt: '2026-10-19', images: [], skus: [{ id: 'sku-1', skuCode: 'LAPTOP-1', price: 500000, status: 'ACTIVE' }] }

// Fixture chặn API để kiểm tra kết nối giữa UI và request; dữ liệu này chỉ dùng trong test, không được ứng dụng fallback khi lỗi mạng.
test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/categories*', route => route.fulfill({ json: { success: true, data: [] } }))
  await page.route('**/api/v1/products?*', route => route.fulfill({ json: { success: true, data: [product], meta: { total: 1, page: 0, pageSize: 12 } } }))
  await page.route(`**/api/v1/products/${product.id}`, route => route.fulfill({ json: { success: true, data: product } }))
})

test('search opens API product and browser back restores results', async ({ page }) => {
  await page.goto('/search?q=Laptop')
  await expect(page.getByText('Real API Laptop')).toBeVisible()
  await page.getByRole('button', { name: /Real API Laptop/ }).click()
  await expect(page).toHaveURL(new RegExp(`/product-detail\\?id=${product.id}`))
  await expect(page.getByText('Backend description')).toBeVisible()
  await page.reload()
  await expect(page.getByText('Backend description')).toBeVisible()
  await page.goBack()
  await expect(page.getByText('Real API Laptop')).toBeVisible()
})

test('login sends credentials, persists session and saves profile through API', async ({ page }) => {
  await page.route('**/api/v1/auth/login', async route => {
    expect(route.request().postDataJSON()).toEqual({ email: user.email, password: 'StrongPassword1!' })
    await route.fulfill({ json: { accessToken: 'test-access', refreshToken: 'test-refresh', user } })
  })
  await page.route('**/api/v1/users/me/activity-history*', route => route.fulfill({ json: { content: [] } }))
  await page.route('**/api/v1/users/me', async route => {
    if (route.request().method() === 'PUT') {
      const submitted = route.request().postDataJSON()
      expect(submitted.fullName).toBe('Updated Buyer')
      expect(route.request().headers().authorization).toBe('Bearer test-access')
      await route.fulfill({ json: { ...user, ...submitted } })
    } else await route.fulfill({ json: user })
  })
  await page.goto('/login')
  await page.getByLabel('Email', { exact: true }).fill(user.email)
  await page.getByLabel('Password', { exact: true }).fill('StrongPassword1!')
  await page.getByRole('button', { name: 'Login', exact: true }).click()
  await expect(page).toHaveURL('/home')
  await page.goto('/profile')
  await page.getByLabel('Họ tên').fill('Updated Buyer')
  await page.getByRole('button', { name: 'Lưu thông tin' }).click()
  await expect(page.getByText('Đã lưu thay đổi')).toBeVisible()
})

test('email reset link submits token and new password to reset endpoint', async ({ page }) => {
  await page.route('**/api/v1/auth/reset-password', async route => {
    expect(route.request().postDataJSON()).toEqual({ token: 'email-token', newPassword: 'NewPassword1!' })
    await route.fulfill({ json: { message: 'Password reset' } })
  })
  await page.goto('/forgot-password?token=email-token')
  await page.getByLabel('New Password', { exact: true }).fill('NewPassword1!')
  await page.getByLabel('Confirm Password', { exact: true }).fill('NewPassword1!')
  await page.getByRole('button', { name: 'Reset Password', exact: true }).click()
  await expect(page.getByText('Password reset successfully!', { exact: true })).toBeVisible()
})

test('backend failures are displayed without showing demo products', async ({ page }) => {
  await page.route('**/api/v1/products?*', route => route.fulfill({ status: 503, json: { message: 'Catalog unavailable' } }))
  await page.goto('/search')
  await expect(page.getByText('Catalog unavailable')).toBeVisible()
  await expect(page.getByText('Real API Laptop')).toHaveCount(0)
})

test('admin category form persists create, update and delete through the API', async ({ page }) => {
  await page.addInitScript(() => sessionStorage.setItem('nexus.session', JSON.stringify({
    accessToken: 'admin-token', refreshToken: 'refresh',
    user: { id: 'admin', fullName: 'Administrator', email: 'admin@example.com', role: 'admin', avatar: '/avatar.svg' },
  })))
  let categories: { id: string; name: string; nameVi: string; level: number; parentId: null; status: string; productCount: number; children: never[] }[] = []
  await page.route('**/api/v1/categories**', async route => {
    const method = route.request().method()
    if (method === 'POST') {
      const body = route.request().postDataJSON()
      expect(body).toMatchObject({ name: 'Electronics', nameVi: 'Điện tử', parentId: null, status: 'ACTIVE' })
      categories = [{ ...body, id: 'category-1', level: 1, productCount: 0, children: [] }]
      await route.fulfill({ status: 201, json: { success: true, data: categories[0] } })
    } else if (method === 'PUT') {
      categories[0] = { ...categories[0], ...route.request().postDataJSON() }
      await route.fulfill({ json: { success: true, data: categories[0] } })
    } else if (method === 'DELETE') {
      categories = []
      await route.fulfill({ status: 204 })
    } else await route.fulfill({ json: { success: true, data: categories } })
  })
  await page.goto('/admin-categories')
  await page.getByLabel('Tên tiếng Anh').fill('Electronics')
  await page.getByLabel('Tên tiếng Việt').fill('Điện tử')
  await page.getByRole('button', { name: 'Lưu', exact: true }).click()
  await expect(page.getByText('Điện tử · 0 sản phẩm · ACTIVE')).toBeVisible()
  await page.getByRole('button', { name: 'Sửa', exact: true }).click()
  await page.getByLabel('Tên tiếng Việt').fill('Thiết bị điện tử')
  await page.getByRole('button', { name: 'Lưu', exact: true }).click()
  await expect(page.getByText('Thiết bị điện tử · 0 sản phẩm · ACTIVE')).toBeVisible()
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: 'Xóa', exact: true }).click()
  await expect(page.getByText('Thiết bị điện tử · 0 sản phẩm · ACTIVE')).toHaveCount(0)
})
