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
  assert.match(diagram, /audit_logs \{/)
  assert.doesNotMatch(diagram, /password_hash|failed_login_attempts|locked_until|token_version/)
  assert.doesNotMatch(diagram, /\n  (roles|privileges|reputations|ratings|penalties|password_reset_tokens) \{/)
})

// Catalog V3 backfill tên không được che mất cột name_vi trong sơ đồ dùng để đối chiếu entity.
test('catalog ERD includes the translated category name', () => {
  const directory = 'backend/catalog-service/src/main/resources/db/migration'
  const diagram = schemaToMermaid(['V2__init_schema.sql', 'V3__category_translations.sql']
    .map(file => readFileSync(`${directory}/${file}`, 'utf8')).join('\n'))
  assert.match(diagram, /varchar name_vi/)
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
