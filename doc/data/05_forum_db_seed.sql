-- =============================================================
-- DATABASE: forum_db
-- File: 05_forum_db_seed.sql
-- Mô tả: Dữ liệu mẫu cho forum (categories, tags, posts, comments, likes)
-- Lưu ý: user_id tham chiếu iam_db, tour_id tham chiếu tour_catalog_db
-- =============================================================

-- ============================================================
-- CLEANUP (order: child tables first, parent tables last)
-- ============================================================
TRUNCATE TABLE post_likes     RESTART IDENTITY CASCADE;
TRUNCATE TABLE post_tags      RESTART IDENTITY CASCADE;
TRUNCATE TABLE post_comments  RESTART IDENTITY CASCADE;
TRUNCATE TABLE forum_posts    RESTART IDENTITY CASCADE;
TRUNCATE TABLE tags           RESTART IDENTITY CASCADE;
TRUNCATE TABLE post_categories RESTART IDENTITY CASCADE;

-- ============================================================
-- 1. POST CATEGORIES
-- ============================================================
INSERT INTO post_categories (categoryid, name, slug, description, icon_url, display_order,
                              created_at, updated_at, is_deleted)
VALUES
(1, 'Kinh nghiệm du lịch', 'kinh-nghiem-du-lich',
 'Chia sẻ kinh nghiệm, tips du lịch hữu ích từ cộng đồng',
 'https://picsum.photos/seed/category1/100/100', 1, NOW(), NOW(), false),
(2, 'Review Tour', 'review-tour',
 'Đánh giá chi tiết các tour du lịch đã trải nghiệm',
 'https://picsum.photos/seed/category2/100/100', 2, NOW(), NOW(), false),
(3, 'Hỏi đáp & Tư vấn', 'hoi-dap-tu-van',
 'Đặt câu hỏi và nhận tư vấn từ cộng đồng du lịch',
 'https://picsum.photos/seed/category3/100/100', 3, NOW(), NOW(), false),
(4, 'Cẩm nang địa điểm', 'cam-nang-dia-diem',
 'Hướng dẫn chi tiết về các điểm đến du lịch nổi tiếng',
 'https://picsum.photos/seed/category4/100/100', 4, NOW(), NOW(), false);

SELECT setval('post_categories_categoryid_seq', 4);

-- ============================================================
-- 2. TAGS
-- ============================================================
INSERT INTO tags (tagid, name, slug, created_at, updated_at, is_deleted)
VALUES
(1, 'Biển đảo',   'bien-dao',   NOW(), NOW(), false),
(2, 'Núi rừng',   'nui-rung',   NOW(), NOW(), false),
(3, 'Văn hóa',    'van-hoa',    NOW(), NOW(), false),
(4, 'Ẩm thực',    'am-thuc',    NOW(), NOW(), false),
(5, 'Mua sắm',    'mua-sam',    NOW(), NOW(), false),
(6, 'Gia đình',   'gia-dinh',   NOW(), NOW(), false),
(7, 'Cặp đôi',    'cap-doi',    NOW(), NOW(), false),
(8, 'Backpacker', 'backpacker', NOW(), NOW(), false),
(9, 'Quốc tế',    'quoc-te',    NOW(), NOW(), false),
(10,'Tiết kiệm',  'tiet-kiem',  NOW(), NOW(), false);

SELECT setval('tags_tagid_seq', 10);

-- ============================================================
-- 3. FORUM POSTS (10 bài viết)
-- ============================================================
INSERT INTO forum_posts (postid, user_id, tour_id, category_id,
  title, content, summary, thumbnail_url,
  post_type, view_count, like_count, comment_count, bookmark_count, share_count,
  is_pinned, is_featured, status, published_at,
  created_at, updated_at, is_deleted)
VALUES
-- Post 1: Blog kinh nghiệm Hạ Long
(1, 3, 1, 1,
 'Kinh nghiệm đặt tour Vịnh Hạ Long - Những điều cần biết trước khi đi',
 E'Vịnh Hạ Long - một trong 7 kỳ quan thiên nhiên thế giới mới - luôn là điểm đến trong danh sách ước mơ của nhiều du khách. Sau chuyến đi vừa rồi với VietOur, mình muốn chia sẻ những kinh nghiệm thực tế để các bạn có chuyến đi hoàn hảo nhất.\n\n## Thời điểm đẹp nhất để đi Hạ Long\n\nMùa hè (tháng 4-8): Trời nắng đẹp, biển trong xanh nhưng cũng là mùa cao điểm nên giá cao và đông khách. Mùa thu (tháng 9-11): Thời tiết mát mẻ, ít mưa, biển êm - thời điểm lý tưởng nhất! Mùa đông (tháng 12-3): Có thể có sương mù tạo cảnh quan huyền bí nhưng hơi lạnh.\n\n## Nên chọn tàu cruise hay tàu thường?\n\nMình khuyên mạnh nên chọn tàu cruise ít nhất 3 sao để có trải nghiệm ngủ đêm trên vịnh. Cảm giác thức dậy giữa vịnh Hạ Long khi sương sớm tan dần, nghe tiếng sóng vỗ mạn tàu nhẹ nhàng - là điều không có tiền nào mua được!\n\n## Những điểm không thể bỏ qua\n\n1. **Hang Sửng Sốt** - hang động đẹp nhất vịnh với nhũ đá muôn hình\n2. **Đảo Titop** - leo 400 bậc thang để ngắm toàn cảnh vịnh từ trên cao\n3. **Chèo kayak** qua các hang động nhỏ huyền bí\n4. **Câu mực đêm** - trải nghiệm thú vị cùng ngư dân địa phương\n\n## Lưu ý khi đặt tour\n\n- Book trước ít nhất 2-3 tuần, đặc biệt mùa hè cần book sớm hơn\n- Hỏi rõ loại tàu, số phòng, menu ăn uống trước khi đặt\n- Mang theo áo phao, thuốc say sóng nếu bạn dễ say\n- Tuyệt đối không vứt rác xuống biển để bảo vệ môi trường\n\nNếu bạn chưa biết đặt tour ở đâu, mình đặt qua VietOur và rất hài lòng về dịch vụ. Giá cả cạnh tranh, hướng dẫn viên chuyên nghiệp và nhiệt tình.',
 'Chia sẻ kinh nghiệm thực tế sau chuyến đi Vịnh Hạ Long 4N3Đ - những tips không thể bỏ qua cho chuyến đi hoàn hảo!',
 'https://picsum.photos/seed/post1halong/800/450',
 'BLOG', 1250, 89, 12, 45, 28,
 false, true, 'PUBLISHED', '2026-02-18 08:00:00',
 '2026-02-18 08:00:00', NOW(), false),

-- Post 2: Review tour Phú Quốc
(2, 4, 2, 2,
 'Review chi tiết Tour Phú Quốc 4N3Đ với VietOur - Xứng đáng từng đồng!',
 E'Mình vừa đi tour Phú Quốc tuần trăng mật với chồng và muốn viết review chi tiết nhất cho những ai đang cân nhắc.\n\n## Tổng quan về tour\n\nTour do VietOur tổ chức, bay VJ Airlines, ở Vinpearl Resort. Tất cả mọi thứ đều đúng như cam kết, không có phần nào gây thất vọng.\n\n## Cáp treo Hòn Thơm - WOW!\n\nĐây là trải nghiệm đáng nhớ nhất! Cáp treo 3 dây vượt biển dài nhất thế giới 7.9km, nhìn xuống biển xanh trong vắt, thấy đảo san hô từ trên cao - đẹp đến nín thở. Cab ride mất khoảng 20 phút mỗi chiều.\n\n## VinWonders - Công viên giải trí đẳng cấp\n\nCông viên nước rất to, nhiều trò chơi cho cả người lớn lẫn trẻ em. Mình và chồng chơi quá giờ nên bỏ lỡ một số điểm khác. Lưu ý: nên đến sớm buổi sáng, buổi chiều đông hơn nhiều.\n\n## Bãi Sao - Thiên đường cát trắng\n\nĐây là bãi biển đẹp nhất Phú Quốc trong mắt mình. Nước biển xanh trong như pha lê, cát trắng mịn như bột, không có rác. Chiều tối hoàng hôn trên Bãi Sao là khoảnh khắc không thể quên!\n\n## Ẩm thực Phú Quốc\n\n- **Ghẹ hấp sả tại Làng Hàm Ninh**: Ngon nhất từ trước đến nay!\n- **Chợ đêm Phú Quốc**: Đa dạng hải sản nướng, giá hợp lý\n- **Nước mắm Phú Quốc chính gốc**: Mua về làm quà cho gia đình\n\n## Điểm trừ duy nhất\n\nThời gian hơi gấp, mình muốn ở thêm 1-2 ngày nữa. Nhưng 4 ngày là đủ để trải nghiệm những điểm chính.',
 'Review tour Phú Quốc 4N3Đ tuần trăng mật - từ cáp treo Hòn Thơm đến Bãi Sao cát trắng. Mọi thứ đều xứng đáng!',
 'https://picsum.photos/seed/post2phuquoc/800/450',
 'REVIEW', 980, 67, 8, 32, 19,
 false, true, 'PUBLISHED', '2026-02-22 10:00:00',
 '2026-02-22 10:00:00', NOW(), false),

-- Post 3: Blog Sapa
(3, 5, 4, 1,
 'Sapa mùa lúa vàng tháng 9 - Cẩm nang du lịch 3N2Đ đầy đủ nhất',
 E'Tháng 9 về, lúa trổ vàng khắp các sườn núi Sapa - đây là thời điểm đẹp nhất trong năm để khám phá vùng đất này. Mình đã đi Sapa 3 lần và lần này đi tháng 9 là đẹp nhất!\n\n## Tại sao nên chọn tháng 9?\n\nLúa bắt đầu chín từ đầu tháng 9, đến giữa tháng vàng rực rỡ nhất. Nếu đi cuối tháng 9 đầu tháng 10 có thể gặp lễ hội thu hoạch của người H''Mông. Trời thường không mưa như mùa hè, mây mù ít hơn, ánh sáng đẹp cho ảnh.\n\n## Lịch trình 3 ngày 2 đêm tối ưu\n\n**Ngày 1**: Xe Limousine 6h sáng từ HN → Sapa 11h. Chiều tham quan Bản Cát Cát, chợ đêm Sapa. Tối ăn thắng cố, uống rượu táo mèo.\n\n**Ngày 2**: Sáng lên Fansipan bằng cáp treo (nên đặt vé trước). Chiều trekking ruộng bậc thang Mường Hoa, thăm bản H''Mông, xem múa xòe Thái. Tối lửa trại.\n\n**Ngày 3**: Sáng tham quan Thác Bạc, mua sắm đặc sản. 14h xe về HN.\n\n## Kinh nghiệm mặc đồ\n\nSapa tháng 9 ban ngày ~22-25°C, ban đêm xuống 15-18°C. Mang áo gió, áo len mỏng. Giày trekking có độ bám tốt vì đường đất đôi khi trơn.\n\n## Đặc sản không thể bỏ qua\n\n- Thắng cố dê/ngựa bản địa\n- Lợn cắp nách nướng than hoa\n- Mèn mén (bánh ngô nướng)\n- Rượu táo mèo\n- Mật ong rừng Sapa',
 'Hướng dẫn đi Sapa tháng 9 mùa lúa vàng 3N2Đ - lịch trình, kinh nghiệm và những điều cần biết',
 'https://picsum.photos/seed/post3sapa/800/450',
 'GUIDE', 2100, 145, 23, 78, 56,
 true, true, 'PUBLISHED', '2026-03-12 09:00:00',
 '2026-03-12 09:00:00', NOW(), false),

-- Post 4: Hỏi đáp về Singapore
(4, 6, 11, 3,
 'Lần đầu đi Singapore cần chuẩn bị gì? Xin tư vấn từ A-Z',
 E'Chào mọi người, mình sắp đi Singapore lần đầu vào tháng 7 này và hơi lo vì chưa quen đi nước ngoài. Mình muốn hỏi:\n\n**1. Tiền tệ**\nĐổi SGD ở đâu tỷ giá tốt nhất? Có cần đổi nhiều không hay dùng thẻ visa là đủ?\n\n**2. Phương tiện đi lại**\nMRT và bus có phức tạp không? Mình nghe nói app Citymapper khá hữu dụng?\n\n**3. Ẩm thực**\nNên ăn ở Hawker Centre hay Restaurant? Budget bao nhiêu/ngày là đủ?\n\n**4. Thời tiết**\nTháng 7 Singapore có mưa nhiều không? Cần mang áo mưa không?\n\n**5. Những điểm phải đến**\nNgoài Gardens by the Bay, Marina Bay Sands, Sentosa thì còn điểm nào nên đi?\n\nAi đã đi Singapore rồi, xin tư vấn giúp mình với! Cảm ơn trước nhé!',
 'Tìm kiếm tư vấn du lịch Singapore lần đầu - tiền tệ, đi lại, ăn uống và những điểm phải check-in',
 'https://picsum.photos/seed/post4singapore/800/450',
 'QUESTION', 445, 28, 15, 12, 8,
 false, false, 'PUBLISHED', '2026-03-22 14:00:00',
 '2026-03-22 14:00:00', NOW(), false),

-- Post 5: Blog Hội An
(5, 7, 3, 4,
 'Cẩm nang du lịch Hội An - Phố cổ đèn lồng mơ màng',
 E'Hội An - nơi thời gian như đứng lại, đèn lồng lung linh phản chiếu trên mặt sông Hoài - là một trong những điểm đến huyền ảo nhất Việt Nam. Mình sẽ chia sẻ cẩm nang đầy đủ cho chuyến đi Hội An của bạn.\n\n## Thời điểm lý tưởng\n\nTháng 2-4 và tháng 7-8 là mùa khô đẹp nhất. Tránh tháng 9-11 vì Hội An hay bị ngập lụt, mưa nhiều.\n\n## Ngày Rằm đặc biệt\n\nVào ngày 14 âm lịch hàng tháng, Hội An tắt hết đèn điện, chỉ thắp đèn lồng - đây là trải nghiệm không thể bỏ qua nếu có cơ hội!\n\n## Ăn uống ở Hội An\n\n- **Cao lầu**: Mỳ chỉ có ở Hội An, nước giếng cổ Bá Lễ đặc biệt\n- **Bánh mỳ Phượng**: Bánh mỳ ngon nhất thế giới (Anthony Bourdain chứng nhận)\n- **Cơm gà Hội An**: Cơm vàng thơm lừng với gà ta luộc chấm muối ớt\n- **Hoành thánh chiên**: Giòn giòn ngon ngon\n\n## Mua gì ở Hội An\n\n- Áo dài đo may nhanh chỉ trong 1-2 ngày, giá từ 400,000 VNĐ\n- Đèn lồng các màu sắc\n- Tranh thêu, đồ thủ công\n- Quà lưu niệm gốm Thanh Hà\n\n## Đi bộ hay thuê xe?\n\nPhố cổ Hội An phù hợp nhất để đi bộ. Thuê xe đạp (40,000-60,000 VNĐ/ngày) để khám phá làng rau Trà Quế và làng gốm Thanh Hà cách phố cổ vài km.',
 'Cẩm nang du lịch Hội An từ A đến Z - thời điểm, ẩm thực, mua sắm và trải nghiệm đặc sắc',
 'https://picsum.photos/seed/post5hoian/800/450',
 'GUIDE', 3200, 210, 31, 125, 89,
 false, true, 'PUBLISHED', '2026-04-02 08:00:00',
 '2026-04-02 08:00:00', NOW(), false),

-- Post 6: Review Bangkok
(6, 8, 10, 2,
 'Review Tour Bangkok - Pattaya 5N4Đ - Mua sắm, Ăn uống, Vui chơi',
 E'Vừa đi Bangkok - Pattaya với VietOur về, mình muốn viết review chi tiết cho ai đang cân nhắc!\n\n## Điểm cộng\n\n**Cung điện Hoàng gia**: Đẹp hơn kỳ vọng! Wat Phra Kaew (Chùa Phật Ngọc) tráng lệ, màu vàng chói lọi dưới nắng. Nên thuê quần áo che vai/gối tại cổng nếu ăn mặc không phù hợp.\n\n**Pattaya về đêm**: Cực kỳ sôi động. Show Alcazar xem xong vẫn muốn xem thêm - vũ công tài năng, phục trang lộng lẫy. Đi bộ dọc Walking Street nếm thử street food Thái đêm khuya.\n\n**Mua sắm**: Chatuchak Weekend Market - chợ trời lớn nhất châu Á, hơn 15,000 gian hàng. Giá rất tốt nếu biết mặc cả. Đặc biệt hàng thời trang, đồ handmade.\n\n## Ẩm thực Thái - Đỉnh!\n\n- Pad Thai đường phố: 40-60 Baht (chỉ ~30,000 VNĐ)\n- Tom Yum Kung: Chua cay đậm đà\n- Green/Red Curry: Cay nhẹ thơm lừng nước cốt dừa\n- Mango Sticky Rice: Ngon nhất trong đời mình!\n\n## Lưu ý đi Thái Lan\n\n- Đổi tiền Baht tại Superrich (tỷ giá tốt nhất Bangkok)\n- Tôn trọng văn hóa, không chỉ tay vào tượng Phật\n- Mặc kín khi vào chùa\n- Tránh túi quá nhiều tiền mặt, dễ bị móc túi tại chợ đông người',
 'Review tour Bangkok-Pattaya 5N4Đ - chia sẻ trải nghiệm thực tế về cung điện, mua sắm và ẩm thực đường phố Thái',
 'https://picsum.photos/seed/post6bangkok/800/450',
 'REVIEW', 756, 54, 9, 21, 15,
 false, false, 'PUBLISHED', '2026-04-07 11:00:00',
 '2026-04-07 11:00:00', NOW(), false),

-- Post 7: Hỏi đáp về gia đình đi Phú Quốc
(7, 9, 2, 3,
 'Đi Phú Quốc với con nhỏ 5 tuổi có phù hợp không?',
 E'Mình đang lên kế hoạch đi Phú Quốc vào hè này với con trai 5 tuổi. Muốn hỏi kinh nghiệm từ các ba/mẹ đã đi cùng con nhỏ:\n\n1. **Cáp treo Hòn Thơm** có cho trẻ 5 tuổi đi không? Có nguy hiểm không?\n\n2. **VinWonders** với trẻ 5 tuổi có nhiều trò phù hợp không? Hay chỉ phù hợp cho trẻ lớn hơn?\n\n3. **Khách sạn** nên đặt ở đâu để tiện nhất? Nghe nói Vinpearl có khu riêng cho gia đình với trẻ em?\n\n4. **Ăn uống** - con mình hơi khó ăn, Phú Quốc có nhiều lựa chọn cho trẻ không?\n\n5. **Bãi biển** nào an toàn và sóng nhẹ nhất cho trẻ nhỏ bơi lội?\n\nCảm ơn mọi người nhiều lắm!',
 'Xin tư vấn kinh nghiệm đi Phú Quốc cùng con nhỏ 5 tuổi - cáp treo, VinWonders, khách sạn gia đình',
 'https://picsum.photos/seed/post7phuquoc/800/450',
 'QUESTION', 334, 19, 11, 8, 5,
 false, false, 'PUBLISHED', '2026-04-12 09:00:00',
 '2026-04-12 09:00:00', NOW(), false),

-- Post 8: Blog Đà Lạt
(8, 10, 6, 1,
 'Đà Lạt tháng 12 - Thiên đường hoa dã quỳ vàng rực rỡ',
 E'Tháng 12 là thời điểm ma thuật nhất ở Đà Lạt khi hoa dã quỳ nở vàng rực khắp các sườn đồi. Mình vừa có chuyến đi tuyệt vời 3N2Đ và muốn chia sẻ!\n\n## Hoa dã quỳ ở đâu đẹp nhất?\n\n- **Đèo Prenn**: Hoa dã quỳ hai bên đường, chụp ảnh cực đẹp\n- **Đường đi Đơn Dương (QL27)**: Cung đường hoa vàng rực dài 30km\n- **Khu vực Hồ Tuyền Lâm**: Rừng thông xen kẽ hoa dã quỳ\n\n## Đồi chè Cầu Đất Farm\n\nMình ở resort ngay trong farm chè, sáng sớm ra ngắm sương mù giăng trên đồi chè xanh rì là một trong những khoảnh khắc đẹp nhất trong đời. Tour trải nghiệm hái chè và uống trà ngay tại vườn rất thú vị.\n\n## Ẩm thực Đà Lạt không thể bỏ qua\n\n- **Bơ Đà Lạt** - ngon và béo khác hẳn nơi khác\n- **Bánh tráng nướng** - ăn khi vừa nóng hổi mới ngon\n- **Hồng sấy dẻo** - đặc sản mang về\n- **Sữa đậu nành nóng** - buổi sáng sương mù uống 1 ly là tuyệt!\n- **Bò né Đà Lạt** - không giống bò né Sài Gòn, có vị đặc trưng riêng\n\n## Mặc gì đi Đà Lạt tháng 12?\n\nBan ngày ~18-22°C, ban đêm xuống 12-15°C. Cần áo khoác dày, khăn choàng cổ. Nhưng cũng mang theo áo mỏng vì ban ngày đôi khi ấm.',
 'Trải nghiệm Đà Lạt tháng 12 - mùa hoa dã quỳ vàng, đồi chè Cầu Đất và ẩm thực đặc sắc',
 'https://picsum.photos/seed/post8dalat/800/450',
 'BLOG', 1680, 112, 17, 67, 41,
 false, true, 'PUBLISHED', '2026-04-17 08:00:00',
 '2026-04-17 08:00:00', NOW(), false),

-- Post 9: Cẩm nang Phuket
(9, 3, 9, 4,
 'Phuket - Phi Phi Full Guide: Từ lập kế hoạch đến trải nghiệm thực tế',
 E'Phuket là điểm đến quốc tế yêu thích nhất của người Việt Nam. Mình vừa có chuyến đi 5N4Đ hoàn hảo và muốn tổng hợp guide đầy đủ nhất!\n\n## Visa Thái Lan\n\nTin vui: Người Việt Nam được miễn visa Thái Lan 30 ngày (từ 11/2023). Chỉ cần hộ chiếu còn hạn >6 tháng.\n\n## Bay đến Phuket\n\nCó chuyến bay thẳng HN/HCM - HKT của Vietjet, VietNam Airlines, Thai Airways. Giá từ 3-6 triệu đồng khứ hồi nếu book sớm.\n\n## Tiền tệ\n\n1 THB ≈ 680-720 VNĐ. Đổi tại Superrich (tỷ giá tốt nhất, nhiều chi nhánh khắp Phuket và Bangkok). Nên đổi $200-300 USD thành Baht là đủ cho 5 ngày.\n\n## Đảo Phi Phi và Vịnh Maya\n\nĐây là MUST-DO khi đến Phuket. Tàu cao tốc từ Rassada Pier đến Phi Phi mất khoảng 1.5 tiếng, giá 600-800 Baht/chiều. Book tour speedboat trọn ngày sẽ tiết kiệm hơn.\n\nVịnh Maya đẹp nhất buổi sáng sớm khi ít khách nhất. Nếu ở lại đảo Phi Phi qua đêm, sáng sớm bơi ra vịnh khi chỉ có mình bạn - đây là trải nghiệm không thể diễn tả!\n\n## Bãi biển tốt nhất Phuket\n\n- **Bãi Kata Noi**: Nhỏ xinh, ít người, view đẹp\n- **Bãi Karon**: Cát trắng, nước xanh, rộng hơn Patong\n- **Bãi Patong**: Sôi động nhất, nightlife tốt nhất nhưng đông nhất',
 'Hướng dẫn du lịch Phuket - Phi Phi đầy đủ: visa, bay, tiền tệ, đảo Phi Phi và vịnh Maya',
 'https://picsum.photos/seed/post9phuket/800/450',
 'GUIDE', 4500, 280, 42, 190, 134,
 false, true, 'PUBLISHED', '2026-04-22 10:00:00',
 '2026-04-22 10:00:00', NOW(), false),

-- Post 10: Blog Nha Trang
(10, 4, 5, 1,
 'Nha Trang 4N3Đ - Trải nghiệm lặn biển đỉnh nhất Đảo Hòn Mun',
 E'Nha Trang nổi tiếng là thiên đường lặn biển của Việt Nam. Mình vừa hoàn thành khóa học PADI Open Water 3 ngày và trải nghiệm lặn tại Hòn Mun - xin chia sẻ!\n\n## Tại sao Hòn Mun?\n\nHòn Mun là khu bảo tồn biển quốc gia, san hô ở đây được bảo vệ nghiêm ngặt nên rất phong phú và còn nguyên vẹn. Độ trong của nước biển lên đến 15-20m vào mùa khô.\n\n## Lặn biển ở đâu tốt nhất Nha Trang?\n\n- **Hòn Mun**: Tốt nhất, san hô nguyên sinh, cá nhiều màu sắc\n- **Hòn Nội**: Cũng đẹp, ít khách hơn\n- **Bãi Tre**: Tốt cho người mới học lặn\n\n## Kinh nghiệm đặt tour lặn biển\n\nCó 2 loại: lặn snorkel (không cần chứng chỉ, 150k-250k/lần) và lặn scuba (cần học 2-4 tiếng trước, 700k-1.2 triệu). Mình khuyên nên lặn scuba để xuống sâu hơn và gần san hô hơn.\n\n## Ăn hải sản Nha Trang\n\nĐừng ăn ở khu vực Bãi Trước đông du khách - giá cao và không ngon. Đến chợ Đầm ăn bún cá, bánh căn, nem nướng Ninh Hòa mới đúng điệu địa phương!',
 'Chia sẻ kinh nghiệm lặn biển tại Đảo Hòn Mun Nha Trang - địa điểm, chi phí và những điều cần biết',
 'https://picsum.photos/seed/post10nhatrang/800/450',
 'BLOG', 892, 73, 14, 38, 22,
 false, false, 'PUBLISHED', '2026-04-25 09:00:00',
 '2026-04-25 09:00:00', NOW(), false);

SELECT setval('forum_posts_postid_seq', 10);

-- ============================================================
-- 4. POST COMMENTS
-- ============================================================
INSERT INTO post_comments (commentid, content, like_count, user_id, post_id, parent_comment_id,
                            created_at, updated_at, is_deleted)
VALUES
-- Post 1: Hạ Long
(1, 'Bài viết rất hữu ích! Mình đang plan đi Hạ Long tháng 8, bạn có recommend tàu cruise cụ thể nào không? Budget khoảng 5-6 triệu/người.', 8, 4, 1, NULL, '2026-02-20 10:00:00', NOW(), false),
(2, 'Mình suggest Heritage Cruise hoặc Paradise Cruise trong tầm giá đó, khá tốt. Nhớ book sớm nhé vì mùa hè cháy phòng sớm lắm!', 5, 3, 1, 1, '2026-02-20 11:30:00', NOW(), false),
(3, 'Cảm ơn review! Mình đi Hạ Long tháng 5 rồi, thời tiết đẹp lắm bạn ơi. Chỉ hơi nắng thôi.', 3, 5, 1, NULL, '2026-02-21 14:00:00', NOW(), false),

-- Post 2: Phú Quốc
(4, 'Bạn đi Vinpearl nhà mình thấy review khác nhau lắm. Bạn nhận xét thế nào về cơ sở vật chất và dịch vụ khách sạn?', 6, 5, 2, NULL, '2026-02-23 09:00:00', NOW(), false),
(5, 'Mình ở Vinpearl Resort & Spa, phòng rộng, sạch sẽ, view biển đẹp. Bể bơi vô cực nhìn ra biển cực chill. Service tốt, nhân viên thân thiện. Đáng tiền lắm!', 12, 4, 2, 4, '2026-02-23 10:00:00', NOW(), false),

-- Post 3: Sapa
(6, 'Bài viết quá hay và đầy đủ! Mình muốn hỏi riêng về vé cáp treo Fansipan, có cần book trước không?', 15, 6, 3, NULL, '2026-03-14 08:00:00', NOW(), false),
(7, 'Nhất định phải book trước 1-2 tuần qua app Sun World hoặc website sunworld.vn, đặc biệt cuối tuần và mùa lễ. Giá vé combo khứ hồi khoảng 750k người lớn.', 22, 5, 3, 6, '2026-03-14 09:00:00', NOW(), false),
(8, 'Mình đi Sapa tháng 9 năm ngoái, đúng là đẹp như bạn nói. Nhưng trekking bị trơn vì còn chút mưa đầu tháng. Bạn nên mang giày bám tốt!', 8, 7, 3, NULL, '2026-03-15 14:00:00', NOW(), false),

-- Post 4: Singapore (hỏi đáp)
(9, 'Dùng EZ-Link card cho MRT rất tiện, mua tại sân bay Changi. Về ẩm thực, Hawker Centre ăn ngon mà rẻ (4-7 SGD/phần), tốt hơn restaurant nhiều. Google Maps chỉ MRT rất chuẩn!', 19, 7, 4, NULL, '2026-03-23 09:00:00', NOW(), false),
(10, 'Tháng 7 Singapore có thể mưa bất thường nhưng thường chỉ mưa 30 phút rồi tạnh. Mang áo mưa mỏng là đủ. Hawker Centre nổi tiếng: Maxwell Food Centre, Lau Pa Sat, Newton Food Centre.', 14, 8, 4, NULL, '2026-03-23 11:00:00', NOW(), false),

-- Post 5: Hội An
(11, 'Bài viết đúng quá! Bánh mỳ Phượng ngon thật, mình xếp hàng 20 phút mà vẫn thấy xứng đáng. Cao lầu cũng phải thử!', 18, 9, 5, NULL, '2026-04-03 10:00:00', NOW(), false),
(12, 'Thêm tip: Nên mua vé phố cổ (120k/vé 5 điểm tham quan) thay vì mua lẻ. Và đi sớm buổi sáng trước 9h khi chưa đông khách, ánh sáng cũng đẹp hơn để chụp ảnh.', 25, 10, 5, NULL, '2026-04-04 08:00:00', NOW(), false),

-- Post 9: Phuket
(13, 'Guide xịn nhất mình từng đọc về Phuket! Bạn có biết chỗ thuê xe máy uy tín ở Phuket không? Mình muốn tự khám phá.', 11, 6, 9, NULL, '2026-04-23 14:00:00', NOW(), false),
(14, 'Có thể thuê ở khu vực Patong Beach, giá khoảng 200-250 Baht/ngày. Nhớ chụp ảnh xe trước khi thuê và kiểm tra kỹ tình trạng xe để tránh tranh chấp khi trả. Đường bên trái nhé!', 9, 3, 9, 13, '2026-04-23 16:00:00', NOW(), false);

SELECT setval('post_comments_commentid_seq', 14);

-- ============================================================
-- 5. POST TAGS
-- ============================================================
INSERT INTO post_tags (post_tagid, post_id, tag_id, created_at, updated_at, is_deleted)
VALUES
(1,  1, 1, NOW(), NOW(), false),   -- Hạ Long - Biển đảo
(2,  1, 6, NOW(), NOW(), false),   -- Hạ Long - Gia đình
(3,  2, 1, NOW(), NOW(), false),   -- Phú Quốc - Biển đảo
(4,  2, 7, NOW(), NOW(), false),   -- Phú Quốc - Cặp đôi
(5,  3, 2, NOW(), NOW(), false),   -- Sapa - Núi rừng
(6,  3, 3, NOW(), NOW(), false),   -- Sapa - Văn hóa
(7,  4, 9, NOW(), NOW(), false),   -- Singapore - Quốc tế
(8,  4, 5, NOW(), NOW(), false),   -- Singapore - Mua sắm
(9,  5, 3, NOW(), NOW(), false),   -- Hội An - Văn hóa
(10, 5, 4, NOW(), NOW(), false),   -- Hội An - Ẩm thực
(11, 6, 9, NOW(), NOW(), false),   -- Bangkok - Quốc tế
(12, 6, 5, NOW(), NOW(), false),   -- Bangkok - Mua sắm
(13, 7, 1, NOW(), NOW(), false),   -- Phú Quốc hỏi đáp - Biển đảo
(14, 7, 6, NOW(), NOW(), false),   -- Phú Quốc hỏi đáp - Gia đình
(15, 8, 7, NOW(), NOW(), false),   -- Đà Lạt - Cặp đôi
(16, 8, 4, NOW(), NOW(), false),   -- Đà Lạt - Ẩm thực
(17, 9, 9, NOW(), NOW(), false),   -- Phuket - Quốc tế
(18, 9, 1, NOW(), NOW(), false),   -- Phuket - Biển đảo
(19, 10, 1, NOW(), NOW(), false),  -- Nha Trang - Biển đảo
(20, 10, 8, NOW(), NOW(), false);  -- Nha Trang - Backpacker

SELECT setval('post_tags_post_tagid_seq', 20);

-- ============================================================
-- 6. POST LIKES
-- ============================================================
INSERT INTO post_likes (post_likeid, post_id, user_id, created_at, updated_at, is_deleted)
VALUES
(1,  1, 4,  NOW(), NOW(), false),
(2,  1, 5,  NOW(), NOW(), false),
(3,  1, 6,  NOW(), NOW(), false),
(4,  2, 5,  NOW(), NOW(), false),
(5,  2, 7,  NOW(), NOW(), false),
(6,  3, 4,  NOW(), NOW(), false),
(7,  3, 6,  NOW(), NOW(), false),
(8,  3, 7,  NOW(), NOW(), false),
(9,  3, 8,  NOW(), NOW(), false),
(10, 5, 3,  NOW(), NOW(), false),
(11, 5, 6,  NOW(), NOW(), false),
(12, 5, 9,  NOW(), NOW(), false),
(13, 8, 3,  NOW(), NOW(), false),
(14, 8, 5,  NOW(), NOW(), false),
(15, 9, 3,  NOW(), NOW(), false),
(16, 9, 4,  NOW(), NOW(), false),
(17, 9, 5,  NOW(), NOW(), false),
(18, 9, 6,  NOW(), NOW(), false),
(19, 10, 3, NOW(), NOW(), false),
(20, 10, 9, NOW(), NOW(), false);

SELECT setval('post_likes_post_likeid_seq', 20);
