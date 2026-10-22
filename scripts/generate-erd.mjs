import { readFileSync, readdirSync, writeFileSync } from 'node:fs'

// Đọc CREATE TABLE từ Flyway của từng service → trừ đi các DROP TABLE → tách cột/khóa ngoại → xuất Mermaid theo ranh giới database.
// Đây là sơ đồ từ câu lệnh CREATE; các cột thêm bằng ALTER vẫn phải đối chiếu migration tương ứng.
const services = ['user', 'catalog', 'commerce', 'auction', 'fulfillment', 'notification', 'reputation']
let output = '# Database ERD\n\nGenerated from service-owned Flyway CREATE TABLE statements by `node scripts/generate-erd.mjs`. Each diagram is a physical database boundary. IDs referring to another service are logical references, not cross-database foreign keys. Later ALTER migrations remain authoritative for added columns.\n'
for (const service of services) {
  const directory = `backend/${service}-service/src/main/resources/db/migration`
  const sql = readdirSync(directory).filter(file => file.endsWith('.sql'))
    .map(file => readFileSync(`${directory}/${file}`, 'utf8')).join('\n')

  const droppedTables = new Set()
  for (const match of sql.matchAll(/DROP TABLE\s+(?:IF\s+EXISTS\s+)?(\w+)/gi)) {
    droppedTables.add(match[1])
  }

  const entities = []
  const relations = new Set()
  for (const table of sql.matchAll(/CREATE TABLE\s+(?:IF NOT EXISTS\s+)?(\w+)\s*\(([\s\S]*?)\n\);/gi)) {
    const [, name, body] = table
    if (droppedTables.has(name)) continue
    const fields = []
    for (const line of body.split('\n')) {
      const column = line.trim().match(/^(\w+)\s+(uuid|varchar|text|integer|bigint|boolean|numeric|timestamp|jsonb|date)\b/i)
      if (column) {
        fields.push(`    ${column[2]} ${column[1]}`)
        const reference = line.match(/REFERENCES\s+(\w+)/i)
        if (reference && !droppedTables.has(reference[1])) relations.add(`  ${reference[1]} ||--o{ ${name} : ${column[1]}`)
      }
    }
    entities.push(`  ${name} {\n${fields.join('\n')}\n  }`)
    for (const reference of body.matchAll(/FOREIGN KEY\s*\((\w+)\)\s*REFERENCES\s+(\w+)/gi)) {
      if (!droppedTables.has(reference[2])) {
        relations.add(`  ${reference[2]} ||--o{ ${name} : ${reference[1]}`)
      }
    }
  }
  output += `\n## ${service}-service\n\n\x60\x60\x60mermaid\nerDiagram\n${entities.join('\n')}\n${[...relations].join('\n')}\n\x60\x60\x60\n`
}
writeFileSync('docs/database/ERD.md', output)
