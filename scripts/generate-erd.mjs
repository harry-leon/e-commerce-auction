import { readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// Sinh ERD từ Flyway theo thứ tự phiên bản, áp dụng CREATE/DROP và ALTER cột hiện có.
// Chỉ mô tả cột/quan hệ, không thay PostgreSQL kiểm chứng constraint, dữ liệu hoặc transaction.
const services = ['user', 'catalog', 'commerce', 'auction', 'fulfillment', 'notification', 'reputation']

/** Đọc DDL hiện dùng trong repo, trả Mermaid sau ALTER; bỏ qua DML vì không đổi cấu trúc ERD. */
export function schemaToMermaid(sql) {
  const tables = new Map()
  // Bỏ comment trước khi tách câu lệnh để ví dụ SQL trong comment không tạo bảng/cột giả.
  // Khối DO chỉ bảo vệ dữ liệu khi nâng cấp; bỏ nguyên khối để dấu ; bên trong không phá tách DDL.
  const statements = sql.replace(/\/\*[\s\S]*?\*\//g, '').replace(/--[^\r\n]*/g, '')
    .replace(/\bDO\s+(\$\w*\$)[\s\S]*?\1\s*;/gi, '').split(';')
  for (const statement of statements) {
    const create = statement.match(/^\s*CREATE TABLE\s+(?:IF NOT EXISTS\s+)?(\w+)\s*\(([\s\S]*)\)\s*$/i)
    if (create) {
      const [, name, body] = create
      const fields = new Map()
      for (const line of body.split('\n')) {
        const column = line.trim().match(/^(\w+)\s+(uuid|varchar|text|integer|bigint|boolean|numeric|timestamp|jsonb|date)\b/i)
        if (column) fields.set(column[1], column[2].toLowerCase())
      }
      const relations = [...body.matchAll(/FOREIGN KEY\s*\((\w+)\)\s*REFERENCES\s+(\w+)/gi)]
        .map(([, column, target]) => ({ column, target }))
      tables.set(name, { fields, relations })
      continue
    }
    const drop = statement.match(/^\s*DROP TABLE\s+(?:IF\s+EXISTS\s+)?(\w+)/i)
    if (drop) {
      tables.delete(drop[1])
      continue
    }
    const alter = statement.match(/^\s*ALTER TABLE\s+(\w+)\s+([\s\S]*)/i)
    if (!alter) continue
    const table = tables.get(alter[1])
    if (!table) throw new Error('ALTER references unknown table: ' + alter[1])
    const add = alter[2].match(/^ADD COLUMN\s+(?:IF NOT EXISTS\s+)?(\w+)\s+(\w+)/i)
    const remove = alter[2].match(/^DROP COLUMN\s+(?:IF EXISTS\s+)?(\w+)/i)
    const type = alter[2].match(/^ALTER COLUMN\s+(\w+)\s+TYPE\s+(\w+)/i)
    if (add) table.fields.set(add[1], add[2].toLowerCase())
    else if (remove) {
      table.fields.delete(remove[1])
      table.relations = table.relations.filter(({ column }) => column !== remove[1])
    } else if (type) table.fields.set(type[1], type[2].toLowerCase())
    // NULL/default không đổi hình ERD; cú pháp đổi cấu trúc khác phải được hỗ trợ rõ ràng.
    // Constraint không thêm/bớt cột hay quan hệ được Mermaid mô tả; PostgreSQL smoke kiểm chứng phần này.
    else if (/^(?:ADD|DROP) CONSTRAINT\b/i.test(alter[2])) continue
    else if (!/^ALTER COLUMN\s+\w+\s+(?:SET|DROP)\s+(?:NOT NULL|DEFAULT)\b/i.test(alter[2])) {
      throw new Error('Unsupported ERD alteration: ' + statement.trim())
    }
  }

  const entities = []
  const relations = new Set()
  for (const [name, table] of tables) {
    const fields = [...table.fields].map(([column, type]) => `    ${type} ${column}`)
    entities.push(`  ${name} {\n${fields.join('\n')}\n  }`)
    for (const { column, target } of table.relations) {
      if (tables.has(target)) relations.add(`  ${target} ||--o{ ${name} : ${column}`)
    }
  }
  return `erDiagram\n${entities.join('\n')}\n${[...relations].join('\n')}`
}

/** Đọc migration theo phiên bản số (V2 trước V10), ghi lại tài liệu ERD; không truy cập DB. */
function generateErd() {
  let output = '# Database ERD\n\nGenerated from service-owned Flyway migrations by `node scripts/generate-erd.mjs`, including column ADD/DROP/TYPE changes. Each diagram is a physical database boundary. IDs referring to another service are logical references, not cross-database foreign keys. The approved Nerum V2 ERD defines the business tables; these migrations implement it. This diagram does not describe constraints or data backfills.\n'
  for (const service of services) {
    const directory = `backend/${service}-service/src/main/resources/db/migration`
    const sql = readdirSync(directory).filter(file => /^V[\d._]+__.*\.sql$/.test(file))
      .sort((a, b) => a.localeCompare(b, 'en', { numeric: true }))
      .map(file => readFileSync(`${directory}/${file}`, 'utf8')).join('\n')
    output += `\n## ${service}-service\n\n\x60\x60\x60mermaid\n${schemaToMermaid(sql)}\n\x60\x60\x60\n`
  }
  writeFileSync('docs/database/ERD.md', output)
}

// Import trong test không ghi tài liệu; chỉ chạy CLI mới sinh lại ERD.
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) generateErd()
