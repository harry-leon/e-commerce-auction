import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import test from 'node:test'
import { schemaToMermaid } from './generate-erd.mjs'

// Bảo vệ hồi quy hồ sơ V5: ERD phải phản ánh kiểu UUID và cột mới, bỏ credential/IAM đã xóa.
test('user ERD reflects the complete Flyway migration chain', () => {
  const directory = 'backend/user-service/src/main/resources/db/migration'
  const sql = readdirSync(directory).filter(file => file.endsWith('.sql'))
    .sort((a, b) => a.localeCompare(b, 'en', { numeric: true }))
    .map(file => readFileSync(`${directory}/${file}`, 'utf8')).join('\n')
  const diagram = schemaToMermaid(sql)
  assert.match(diagram, /uuid keycloak_id/)
  assert.match(diagram, /timestamp updated_at/)
  // ERD đã chốt đặt audit tại notification.event_logs, không giữ bảng riêng trong User.
  assert.doesNotMatch(diagram, /audit_logs \{/)
  assert.doesNotMatch(diagram, /password_hash|failed_login_attempts|locked_until|token_version/)
  assert.doesNotMatch(diagram, /\n  (roles|privileges|reputations|ratings|penalties|password_reset_tokens) \{/)
})

// Chuỗi migration Catalog phải kết thúc với một name theo ERD, giữ nguyên quan hệ sản phẩm.
test('catalog ERD removes the translated category column after V4', () => {
  const directory = 'backend/catalog-service/src/main/resources/db/migration'
  const diagram = schemaToMermaid(['V2__init_schema.sql', 'V3__category_translations.sql', 'V4__align_categories_with_final_erd.sql']
    .map(file => readFileSync(`${directory}/${file}`, 'utf8')).join('\n'))
  assert.doesNotMatch(diagram, /varchar name_vi/)
  assert.match(diagram, /categories \|\|--o\{ products : category_id/)
})

// CREATE sau DROP phải tạo lại bảng; không giữ quan hệ tới bảng đã xóa hay âm thầm bỏ qua RENAME.
test('DDL order is preserved and unsupported structural alterations fail explicitly', () => {
  const sql = `CREATE TABLE parents (
    id uuid
  );
  CREATE TABLE children (
    id uuid,
    parent_id uuid,
    FOREIGN KEY (parent_id) REFERENCES parents(id)
  );
  DROP TABLE parents;
  DROP TABLE children;
  CREATE TABLE children (
    id uuid
  );`
  assert.equal(schemaToMermaid(sql), 'erDiagram\n  children {\n    uuid id\n  }\n')
  assert.throws(() => schemaToMermaid(sql + 'ALTER TABLE children RENAME COLUMN id TO child_id;'),
    /Unsupported ERD alteration/)
})
