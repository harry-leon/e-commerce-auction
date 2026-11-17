import { Client } from 'pg';
import { v2 as cloudinary } from 'cloudinary';

// 1. Cấu hình dịch vụ Cloudinary
cloudinary.config({
  cloud_name: 'f4wgawlg',
  api_key: '184937136559179',
  api_secret: 'ILrwWU8z2t9uWYqh0MnwtlMeKVo'
});

// 2. Cấu hình kết nối Supabase PostgreSQL
const dbClient = new Client({
  host: 'aws-0-ap-northeast-1.pooler.supabase.com',
  port: 5432,
  database: 'postgres',
  user: 'postgres.qhwdgeohaxdwxebuhcka',
  password: 'db-nexus-lab',
  ssl: { rejectUnauthorized: false }
});

// Danh sách ánh xạ ảnh sản phẩm với độ phân giải cao và phân cấp thư mục rõ ràng
const PRODUCT_MAPPINGS = [
  {
    id: 'f5b74cc1-ba86-556e-ac9c-c7e6053c3361',
    name: 'Tai nghe không dây N1',
    category: 'electronics',
    slug: 'tai-nghe-khong-day-n1',
    images: [
      'https://images.unsplash.com/photo-1505740420928-5e560c06d30e',
      'https://images.unsplash.com/photo-1484704849700-f032a568e944'
    ]
  },
  {
    id: 'ffe2f777-2384-51cd-b356-99029a9b4525',
    name: 'Bàn phím cơ K87',
    category: 'electronics',
    slug: 'ban-phim-co-k87',
    images: [
      'https://images.unsplash.com/photo-1587829741301-dc798b83add3',
      'https://images.unsplash.com/photo-1618384887929-16ec33fab9ef'
    ]
  },
  {
    id: 'b6ab8d61-74c2-51bf-ae8b-fc257016d0c0',
    name: 'Chuột không dây M2',
    category: 'electronics',
    slug: 'chuot-khong-day-m2',
    images: [
      'https://images.unsplash.com/photo-1527864550417-7fd91fc51a46',
      'https://images.unsplash.com/photo-1605773527852-c546a8584ea3'
    ]
  },
  {
    id: '4d1ccfeb-574c-4c98-9522-d984a671c081',
    name: 'Chuot Gaming Logitech G102',
    category: 'electronics',
    slug: 'chuot-gaming-logitech-g102',
    images: [
      'https://images.unsplash.com/photo-1615663245857-ac93bb7c39e7',
      'https://images.unsplash.com/photo-1629429408209-1f912961dbd8'
    ]
  },
  {
    id: '5b63d956-db10-4eb4-aaae-d1f730062430',
    name: 'Tai nghe Gaming Razer BlackShark V2',
    category: 'electronics',
    slug: 'tai-nghe-gaming-razer-blackshark-v2',
    images: [
      'https://images.unsplash.com/photo-1546435770-a3e426bf472b',
      'https://images.unsplash.com/photo-1590658268037-6bf12165a8df'
    ]
  },
  {
    id: 'd4e4bbd5-f551-47ea-b0ad-fc79bddf6655',
    name: 'Man hinh Gaming ASUS ROG Swift 27 inch 240Hz',
    category: 'electronics',
    slug: 'man-hinh-gaming-asus-rog-swift',
    images: [
      'https://images.unsplash.com/photo-1527443224154-c4a3942d3acf',
      'https://images.unsplash.com/photo-1547082299-de196ea013d6'
    ]
  },
  {
    id: '095d985e-9000-4e19-b006-7bfd9e941951',
    name: 'Laptop MSI Modern 15 F1MG-1264VN',
    category: 'electronics',
    slug: 'laptop-msi-modern-15',
    images: [
      'https://images.unsplash.com/photo-1588872657578-7efd1f1555ed',
      'https://images.unsplash.com/photo-1496181133206-80ce9b88a853'
    ]
  },
  {
    id: '02994692-3ec8-4d36-8ddd-3d6fe032786f',
    name: 'iPhone 18 Pro Max 256GB',
    category: 'electronics',
    slug: 'iphone-18-pro-max-256gb',
    images: [
      'https://images.unsplash.com/photo-1591337676887-a217a6970a8a',
      'https://images.unsplash.com/photo-1511707171634-5f897ff02aa9'
    ]
  },
  {
    id: '3d7af149-d9a9-510e-a0fa-8bd0365817ba',
    name: 'Áo thun cotton thường ngày',
    category: 'fashion',
    slug: 'ao-thun-cotton-thuong-ngay',
    images: [
      'https://images.unsplash.com/photo-1521572267360-ee0c2909d518',
      'https://images.unsplash.com/photo-1583743814966-8936f5b7be1a'
    ]
  },
  {
    id: '2e25c7a5-4953-550a-a938-57491b5c4188',
    name: 'Áo khoác gió nhẹ',
    category: 'fashion',
    slug: 'ao-khoac-gio-nhe',
    images: [
      'https://images.unsplash.com/photo-1544441893-675973e31985',
      'https://images.unsplash.com/photo-1551028719-00167b16eac5'
    ]
  },
  {
    id: '650a7d48-bc1e-50b0-b97a-6beea91243c8',
    name: 'Ba lô đi học 20L',
    category: 'fashion',
    slug: 'ba-lo-di-hoc-20l',
    images: [
      'https://images.unsplash.com/photo-1553062407-98eeb64c6a62',
      'https://images.unsplash.com/photo-1622560480605-d83c853bc5c3'
    ]
  },
  {
    id: 'a5f07812-f808-5c23-b922-92c6da428243',
    name: 'Đèn bàn học LED',
    category: 'home-living',
    slug: 'den-ban-hoc-led',
    images: [
      'https://images.unsplash.com/photo-1507473885765-e6ed057f782c',
      'https://images.unsplash.com/photo-1534349762230-e0cadf78f5da'
    ]
  },
  {
    id: '576c6792-8ef3-5b55-8fe7-b24507439e4b',
    name: 'Bình giữ nhiệt 500ml',
    category: 'home-living',
    slug: 'binh-giu-nhiet-500ml',
    images: [
      'https://images.unsplash.com/photo-1602143407151-7111542de6e8',
      'https://images.unsplash.com/photo-1570554886111-e80fcca6a029'
    ]
  },
  {
    id: 'a434d512-86b5-556e-8b52-eb0ea5c11a36',
    name: 'Bộ hộp thực phẩm 3 món',
    category: 'home-living',
    slug: 'bo-hop-thuc-pham-3-mon',
    images: [
      'https://images.unsplash.com/photo-1584269600464-37b1b58a9fe7',
      'https://images.unsplash.com/photo-1546069901-ba9599a7e63c'
    ]
  },
  {
    id: 'af67d290-91d3-5149-a2de-0829c57367b8',
    name: 'Thảm tập yoga 6mm',
    category: 'sports',
    slug: 'tham-tap-yoga-6mm',
    images: [
      'https://images.unsplash.com/photo-1601925260368-ae2f83cf8b7f',
      'https://images.unsplash.com/photo-1544367567-0f2fcb009e0b'
    ]
  },
  {
    id: '6c632068-13e1-511c-9fa0-7c4c42f4308c',
    name: 'Dây kháng lực tập luyện',
    category: 'sports',
    slug: 'day-khang-luc-tap-luyen',
    images: [
      'https://images.unsplash.com/photo-1598289431512-b97b0917affc',
      'https://images.unsplash.com/photo-1517838277536-f5f99be501cd'
    ]
  },
  {
    id: 'c45f0475-c342-5c47-b7da-0c536fb43fca',
    name: 'Túi thể thao cuối tuần',
    category: 'sports',
    slug: 'tui-the-thao-cuoi-tuan',
    images: [
      'https://images.unsplash.com/photo-1553062407-98eeb64c6a62',
      'https://images.unsplash.com/photo-1548036328-c9fa89d128fa'
    ]
  },
  {
    id: '5bd68f91-8818-43e1-91c2-c61dc482254f',
    name: "Men's running shoes (5-42km) - Kipcore Premium gray/yellow/orange",
    category: 'sports',
    slug: 'giay-chay-bo-nam-kipcore',
    images: [
      'https://images.unsplash.com/photo-1542291026-7eec264c27ff',
      'https://images.unsplash.com/photo-1595950653106-6c9ebd614d3a'
    ]
  },
  {
    id: 'f37cd911-964b-549f-b001-6faf3dbbc0fd',
    name: 'Sổ tay giấy chấm A5',
    category: 'stationery',
    slug: 'so-tay-giay-cham-a5',
    images: [
      'https://images.unsplash.com/photo-1544716278-ca5e3f4abd8c',
      'https://images.unsplash.com/photo-1531346878377-a5be20888e57'
    ]
  },
  {
    id: 'ec5db095-fd70-5fc0-9080-5115585ed3b0',
    name: 'Bộ bút viết 5 màu',
    category: 'stationery',
    slug: 'bo-but-viet-5-mau',
    images: [
      'https://images.unsplash.com/photo-1583485088034-697b5bc54ccd',
      'https://images.unsplash.com/photo-1569683795645-b62e50fbf103'
    ]
  },
  {
    id: '4d53729c-6f3b-56d2-b451-8526023673e3',
    name: 'Giá đỡ sách để bàn',
    category: 'stationery',
    slug: 'gia-do-sach-de-ban',
    images: [
      'https://images.unsplash.com/photo-1512820790803-83ca734da794',
      'https://images.unsplash.com/photo-1497633762265-9d179a990aa6'
    ]
  },
  {
    id: '0d7b97d7-348e-402c-b71b-797a0b90b6f3',
    name: 'test 1',
    category: 'stationery',
    slug: 'sach-hoc-tap-chuyen-de',
    images: [
      'https://images.unsplash.com/photo-1495446815901-a7297e633e8d'
    ]
  },
  {
    id: '23369cb4-ce34-5156-8788-111ad8aac844',
    name: 'Mô hình gỗ lắp ghép',
    category: 'collectibles',
    slug: 'mo-hinh-go-lap-ghep',
    images: [
      'https://images.unsplash.com/photo-1563245372-f21724e3856d',
      'https://images.unsplash.com/photo-1513519245088-0e12902e5a38'
    ]
  },
  {
    id: '44936e73-8646-5f60-a5d1-f5031654dec3',
    name: 'Tranh in nghệ thuật A4',
    category: 'collectibles',
    slug: 'tranh-in-nghe-thuat-a4',
    images: [
      'https://images.unsplash.com/photo-1579783900882-c0d3dad7b119',
      'https://images.unsplash.com/photo-1582561424760-0321d75e81fa'
    ]
  },
  {
    id: 'f34eea18-38cd-5fa4-87c8-944985c293fe',
    name: 'Bộ thiệp thủ công 6 mẫu',
    category: 'collectibles',
    slug: 'bo-thiep-thu-cong-6-mau',
    images: [
      'https://images.unsplash.com/photo-1513519245088-0e12902e5a38',
      'https://images.unsplash.com/photo-1586075010923-2dd4570fb338'
    ]
  },
  {
    id: 'cfab12f7-14c3-43d6-83c0-0f748c634d0b',
    name: 'test',
    category: 'collectibles',
    slug: 'vat-pham-decor-thu-cong',
    images: [
      'https://images.unsplash.com/photo-1513519245088-0e12902e5a38'
    ]
  }
];

async function main() {
  console.log('=== BẮT ĐẦU ĐẨY ẢNH LÊN CLOUDINARY VÀ CẬP NHẬT DATABASE ===');
  await dbClient.connect();
  console.log('✓ Đã kết nối Supabase PostgreSQL thành công.');

  const results = [];

  for (let i = 0; i < PRODUCT_MAPPINGS.length; i++) {
    const p = PRODUCT_MAPPINGS[i];
    const folderPath = `Nexus/${p.category}/${p.slug}`;
    console.log(`\n[${i + 1}/${PRODUCT_MAPPINGS.length}] Đang xử lý sản phẩm: "${p.name}" -> Folder: "${folderPath}"`);

    const uploadedUrls = [];

    for (let imgIndex = 0; imgIndex < p.images.length; imgIndex++) {
      const srcUrl = p.images[imgIndex];
      const publicId = `${p.slug}-${imgIndex === 0 ? 'main' : 'detail-' + imgIndex}`;
      
      try {
        console.log(`  - Đang upload ảnh ${imgIndex + 1}/${p.images.length}...`);
        const uploadRes = await cloudinary.uploader.upload(srcUrl, {
          folder: folderPath,
          public_id: publicId,
          overwrite: true,
          resource_type: 'image',
          transformation: [
            { width: 1000, height: 1000, crop: 'limit', quality: 'auto:good', fetch_format: 'auto' }
          ]
        });

        console.log(`    ✓ Upload thành công: ${uploadRes.secure_url}`);
        uploadedUrls.push({
          url: uploadRes.secure_url,
          sortOrder: imgIndex
        });
      } catch (uploadErr) {
        console.error(`    ✗ Lỗi khi upload ảnh ${srcUrl}:`, uploadErr.message);
      }
    }

    if (uploadedUrls.length > 0) {
      // Cập nhật DB: Xóa ảnh cũ của sản phẩm này và thêm các ảnh mới từ Cloudinary
      await dbClient.query('DELETE FROM nexus_catalog.product_images WHERE product_id = $1', [p.id]);

      for (const item of uploadedUrls) {
        await dbClient.query(
          'INSERT INTO nexus_catalog.product_images (id, product_id, url, sort_order) VALUES (gen_random_uuid(), $1, $2, $3)',
          [p.id, item.url, item.sortOrder]
        );
      }
      console.log(`  ✓ Đã cập nhật ${uploadedUrls.length} ảnh vào DB cho sản phẩm: "${p.name}"`);
    }

    results.push({
      product: p.name,
      folder: folderPath,
      imageCount: uploadedUrls.length,
      urls: uploadedUrls.map(u => u.url)
    });
  }

  await dbClient.end();
  console.log('\n=== HOÀN THÀNH TẤT CẢ SẢN PHẨM ===');
  console.log(`Đã xử lý thành công ${results.length} sản phẩm.`);
}

main().catch(err => {
  console.error('Lỗi nghiêm trọng:', err);
  process.exit(1);
});
