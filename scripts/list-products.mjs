import { Client } from 'pg';

const client = new Client({
  host: 'aws-0-ap-northeast-1.pooler.supabase.com',
  port: 5432,
  database: 'postgres',
  user: 'postgres.qhwdgeohaxdwxebuhcka',
  password: 'db-nexus-lab',
  ssl: { rejectUnauthorized: false }
});

async function main() {
  await client.connect();
  const q = `
    SELECT p.id, p.name, p.description, p.status, c.name as category_name,
           json_agg(json_build_object('id', pi.id, 'url', pi.url, 'sort_order', pi.sort_order)) filter (where pi.id is not null) as images
    FROM nexus_catalog.products p
    LEFT JOIN nexus_catalog.categories c ON p.category_id = c.id
    LEFT JOIN nexus_catalog.product_images pi ON p.id = pi.product_id
    GROUP BY p.id, p.name, p.description, p.status, c.name, p.created_at
    ORDER BY p.created_at ASC
  `;
  const res = await client.query(q);
  console.log('Total products:', res.rows.length);
  res.rows.forEach((r, idx) => {
    console.log(`[${idx + 1}] ID: ${r.id} | Name: "${r.name}" | Category: "${r.category_name}" | Status: ${r.status}`);
    console.log(`    Images: ${r.images ? r.images.length : 0} image(s)`);
    if (r.images) {
      r.images.forEach(img => console.log(`      - [${img.sort_order}] ${img.url.slice(0, 80)}...`));
    }
  });
  await client.end();
}

main().catch(console.error);
