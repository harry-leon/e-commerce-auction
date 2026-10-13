-- V3__iam_enhancements.sql
-- Bo sung failed_login_attempts cho users, bang password_reset_tokens, audit_logs
-- Seed danh sach roles va privileges theo SRS 2.7

-- 1. Cap nhat bang users
ALTER TABLE users ADD COLUMN IF NOT EXISTS failed_login_attempts integer NOT NULL DEFAULT 0;

-- 2. Bang password_reset_tokens
CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    token_hash varchar(255) NOT NULL,
    expires_at timestamp NOT NULL,
    used boolean NOT NULL DEFAULT false,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_reset_token_user ON password_reset_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_reset_token_hash ON password_reset_tokens(token_hash);

-- 3. Bang audit_logs cho IAM
CREATE TABLE IF NOT EXISTS audit_logs (
    id uuid NOT NULL,
    user_id uuid,
    action varchar(100) NOT NULL,
    description text,
    ip_address varchar(100),
    user_agent varchar(255),
    status varchar(50) NOT NULL,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_audit_logs_user ON audit_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_action ON audit_logs(action);
CREATE INDEX IF NOT EXISTS idx_audit_logs_created_at ON audit_logs(created_at);

-- 4. Seed Privileges theo SRS muc 2.7
INSERT INTO privileges (id, code, description) VALUES
  (gen_random_uuid(), 'AUTH.LOGIN', 'Login to the system'),
  (gen_random_uuid(), 'AUTH.LOGOUT', 'Logout from the system'),
  (gen_random_uuid(), 'PROFILE.VIEW', 'View own profile'),
  (gen_random_uuid(), 'PROFILE.UPDATE', 'Update own profile'),
  (gen_random_uuid(), 'PROFILE.CHANGE_PASSWORD', 'Change own password'),
  (gen_random_uuid(), 'PROFILE.RESET_PASSWORD', 'Reset forgotten password'),
  (gen_random_uuid(), 'USER.CREATE', 'Create user account'),
  (gen_random_uuid(), 'USER.UPDATE', 'Update user information'),
  (gen_random_uuid(), 'USER.DELETE', 'Delete user account'),
  (gen_random_uuid(), 'USER.VIEW', 'View user details'),
  (gen_random_uuid(), 'USER.LIST', 'View user list'),
  (gen_random_uuid(), 'USER.CHANGE_PASSWORD', 'Change user password'),
  (gen_random_uuid(), 'USER.REPUTATION.VIEW', 'View user reputation'),
  (gen_random_uuid(), 'USER.REPUTATION.ADJUST', 'Adjust user reputation'),
  (gen_random_uuid(), 'ROLE.CREATE', 'Create role'),
  (gen_random_uuid(), 'ROLE.UPDATE', 'Update role'),
  (gen_random_uuid(), 'ROLE.DELETE', 'Delete role'),
  (gen_random_uuid(), 'ROLE.VIEW', 'View role details'),
  (gen_random_uuid(), 'ROLE.LIST', 'View role list'),
  (gen_random_uuid(), 'PRODUCT.CREATE', 'Create product'),
  (gen_random_uuid(), 'PRODUCT.UPDATE', 'Update product'),
  (gen_random_uuid(), 'PRODUCT.DELETE', 'Delete product'),
  (gen_random_uuid(), 'PRODUCT.VIEW', 'View product details'),
  (gen_random_uuid(), 'PRODUCT.LIST', 'View product list'),
  (gen_random_uuid(), 'PRODUCT.SEARCH', 'Search products'),
  (gen_random_uuid(), 'CATEGORY.CREATE', 'Create category'),
  (gen_random_uuid(), 'CATEGORY.UPDATE', 'Update category'),
  (gen_random_uuid(), 'CATEGORY.DELETE', 'Delete category'),
  (gen_random_uuid(), 'CATEGORY.VIEW', 'View category details'),
  (gen_random_uuid(), 'CATEGORY.LIST', 'View category list'),
  (gen_random_uuid(), 'CART.CREATE', 'Create shopping cart'),
  (gen_random_uuid(), 'CART.UPDATE', 'Update cart items'),
  (gen_random_uuid(), 'CART.REMOVE_ITEM', 'Remove cart item'),
  (gen_random_uuid(), 'CART.VIEW', 'View shopping cart'),
  (gen_random_uuid(), 'CHECKOUT.START', 'Start checkout process'),
  (gen_random_uuid(), 'ORDER.CREATE', 'Create order'),
  (gen_random_uuid(), 'ORDER.CANCEL', 'Cancel order'),
  (gen_random_uuid(), 'ORDER.VIEW', 'View order details'),
  (gen_random_uuid(), 'ORDER.LIST', 'View order list/history'),
  (gen_random_uuid(), 'ORDER.REFUND', 'Request or process refund'),
  (gen_random_uuid(), 'AUCTION.CREATE', 'Create auction'),
  (gen_random_uuid(), 'AUCTION.UPDATE', 'Update auction configuration'),
  (gen_random_uuid(), 'AUCTION.CANCEL', 'Cancel auction')
ON CONFLICT (code) DO NOTHING;

-- 5. Seed Roles
INSERT INTO roles (id, code, name, description) VALUES
  (gen_random_uuid(), 'ADMIN', 'Administrator', 'Full system management access'),
  (gen_random_uuid(), 'SELLER', 'Seller', 'Merchant who sells and auctions products'),
  (gen_random_uuid(), 'BUYER', 'Buyer', 'Standard customer who purchases and bids'),
  (gen_random_uuid(), 'SUPPORT', 'Support Staff', 'Customer support and dispute handler')
ON CONFLICT (code) DO NOTHING;

-- 6. Seed Role - Privileges (N-N) cho BUYER
INSERT INTO role_privileges (role_id, privilege_id)
SELECT r.id, p.id FROM roles r, privileges p
WHERE r.code = 'BUYER' AND p.code IN (
    'AUTH.LOGIN', 'AUTH.LOGOUT', 'PROFILE.VIEW', 'PROFILE.UPDATE',
    'PROFILE.CHANGE_PASSWORD', 'PROFILE.RESET_PASSWORD',
    'PRODUCT.VIEW', 'PRODUCT.LIST', 'PRODUCT.SEARCH',
    'CATEGORY.VIEW', 'CATEGORY.LIST',
    'CART.CREATE', 'CART.UPDATE', 'CART.REMOVE_ITEM', 'CART.VIEW',
    'CHECKOUT.START', 'ORDER.CREATE', 'ORDER.CANCEL', 'ORDER.VIEW', 'ORDER.LIST'
)
ON CONFLICT DO NOTHING;

-- 7. Seed Role - Privileges (N-N) cho SELLER
INSERT INTO role_privileges (role_id, privilege_id)
SELECT r.id, p.id FROM roles r, privileges p
WHERE r.code = 'SELLER' AND p.code IN (
    'AUTH.LOGIN', 'AUTH.LOGOUT', 'PROFILE.VIEW', 'PROFILE.UPDATE',
    'PROFILE.CHANGE_PASSWORD', 'PROFILE.RESET_PASSWORD',
    'PRODUCT.CREATE', 'PRODUCT.UPDATE', 'PRODUCT.DELETE', 'PRODUCT.VIEW', 'PRODUCT.LIST', 'PRODUCT.SEARCH',
    'CATEGORY.VIEW', 'CATEGORY.LIST',
    'ORDER.VIEW', 'ORDER.LIST',
    'AUCTION.CREATE', 'AUCTION.UPDATE', 'AUCTION.CANCEL'
)
ON CONFLICT DO NOTHING;

-- 8. Seed Role - Privileges (N-N) cho ADMIN (toan bo quyen)
INSERT INTO role_privileges (role_id, privilege_id)
SELECT r.id, p.id FROM roles r, privileges p
WHERE r.code = 'ADMIN'
ON CONFLICT DO NOTHING;

-- 9. Seed Role - Privileges (N-N) cho SUPPORT
INSERT INTO role_privileges (role_id, privilege_id)
SELECT r.id, p.id FROM roles r, privileges p
WHERE r.code = 'SUPPORT' AND p.code IN (
    'AUTH.LOGIN', 'AUTH.LOGOUT', 'PROFILE.VIEW', 'PROFILE.UPDATE',
    'PROFILE.CHANGE_PASSWORD', 'PROFILE.RESET_PASSWORD',
    'USER.UPDATE', 'USER.VIEW', 'USER.LIST', 'USER.REPUTATION.VIEW',
    'ROLE.VIEW', 'ROLE.LIST',
    'PRODUCT.VIEW', 'PRODUCT.LIST', 'PRODUCT.SEARCH',
    'CATEGORY.VIEW', 'CATEGORY.LIST',
    'ORDER.CANCEL', 'ORDER.VIEW', 'ORDER.LIST', 'ORDER.REFUND'
)
ON CONFLICT DO NOTHING;
