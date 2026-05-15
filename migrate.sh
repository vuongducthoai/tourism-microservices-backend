#!/bin/bash
set -e

LOCAL="PGPASSWORD=Thoai12309@ psql -U postgres -h 127.0.0.1 -p 5432 -d tourism"
DOCKER_IAM="docker exec -i tourism-postgres psql -U postgres -d iam_db"
DOCKER_TOUR="docker exec -i tourism-postgres psql -U postgres -d tour_catalog_db"
DOCKER_BOOKING="docker exec -i tourism-postgres psql -U postgres -d booking_db"
DOCKER_PAYMENT="docker exec -i tourism-postgres psql -U postgres -d payment_db"
DOCKER_FORUM="docker exec -i tourism-postgres psql -U postgres -d forum_db"
DOCKER_NOTIF="docker exec -i tourism-postgres psql -U postgres -d notification_db"

echo ""
echo "=========================================="
echo " TOURISM MICROSERVICES DATA MIGRATION"
echo "=========================================="

# ============================================================
# IAM_DB: users, refresh_tokens
# ============================================================
echo ""
echo "[1/6] Migrating iam_db..."

eval "$LOCAL" -c "COPY (SELECT userid, created_at, is_deleted, updated_at, avatar, coin_balance, date_of_birth, district_code, district_name, email, full_name, is_email_verified, last_active_at, password, phone, province_code, province_name, role, status, verification_token, verification_token_expiry FROM users) TO STDOUT WITH CSV" \
| eval "$DOCKER_IAM" -c "SET session_replication_role = replica; COPY users (userid, created_at, is_deleted, updated_at, avatar, coin_balance, date_of_birth, district_code, district_name, email, full_name, is_email_verified, last_active_at, password, phone, province_code, province_name, role, status, verification_token, verification_token_expiry) FROM STDIN WITH CSV"
echo "  -> users: OK"

eval "$LOCAL" -c "COPY (SELECT id, expiry_date, token, user_id FROM refresh_tokens) TO STDOUT WITH CSV" \
| eval "$DOCKER_IAM" -c "SET session_replication_role = replica; COPY refresh_tokens (id, expiry_date, token, user_id) FROM STDIN WITH CSV"
echo "  -> refresh_tokens: OK"

eval "$DOCKER_IAM" -c "SELECT setval('users_userid_seq', (SELECT MAX(userid) FROM users)); SELECT setval('refresh_tokens_id_seq', (SELECT MAX(id) FROM refresh_tokens));"
echo "  -> sequences reset: OK"

# ============================================================
# TOUR_CATALOG_DB
# ============================================================
echo ""
echo "[2/6] Migrating tour_catalog_db..."

eval "$LOCAL" -c "COPY (SELECT locationid, created_at, is_deleted, updated_at, airport_code, airport_name, description, image, name, region, slug, status FROM locations) TO STDOUT WITH CSV" \
| eval "$DOCKER_TOUR" -c "SET session_replication_role = replica; COPY locations (locationid, created_at, is_deleted, updated_at, airport_code, airport_name, description, image, name, region, slug, status) FROM STDIN WITH CSV"
echo "  -> locations: OK"

eval "$LOCAL" -c "COPY (SELECT contactid, created_at, is_deleted, updated_at, address, branch_name, email, is_head_office, phone FROM branch_contacts) TO STDOUT WITH CSV" \
| eval "$DOCKER_TOUR" -c "SET session_replication_role = replica; COPY branch_contacts (contactid, created_at, is_deleted, updated_at, address, branch_name, email, is_head_office, phone) FROM STDIN WITH CSV"
echo "  -> branch_contacts: OK"

eval "$LOCAL" -c "COPY (SELECT policy_templateid, created_at, is_deleted, updated_at, child_pricing_notes, force_majeure_rules, holiday_cancellation_rules, packing_list, payment_conditions, registration_conditions, regular_day_cancellation_rules, status, template_name, tour_price_excludes, tour_price_includes, contact_id FROM policy_templates) TO STDOUT WITH CSV" \
| eval "$DOCKER_TOUR" -c "SET session_replication_role = replica; COPY policy_templates (policy_templateid, created_at, is_deleted, updated_at, child_pricing_notes, force_majeure_rules, holiday_cancellation_rules, packing_list, payment_conditions, registration_conditions, regular_day_cancellation_rules, status, template_name, tour_price_excludes, tour_price_includes, contact_id) FROM STDIN WITH CSV"
echo "  -> policy_templates: OK"

eval "$LOCAL" -c "COPY (SELECT tourid, created_at, is_deleted, updated_at, attractions, duration, hotel, ideal_time, meals, status, suitable_customer, tour_code, tour_name, transportation, trip_transportation, end_location_id, start_location_id FROM tours) TO STDOUT WITH CSV" \
| eval "$DOCKER_TOUR" -c "SET session_replication_role = replica; COPY tours (tourid, created_at, is_deleted, updated_at, attractions, duration, hotel, ideal_time, meals, status, suitable_customer, tour_code, tour_name, transportation, trip_transportation, end_location_id, start_location_id) FROM STDIN WITH CSV"
echo "  -> tours: OK"

eval "$LOCAL" -c "COPY (SELECT imageid, created_at, is_deleted, updated_at, image_url, tour_id FROM tour_images) TO STDOUT WITH CSV" \
| eval "$DOCKER_TOUR" -c "SET session_replication_role = replica; COPY tour_images (tour_imageid, created_at, is_deleted, updated_at, image_url, tour_id) FROM STDIN WITH CSV"
echo "  -> tour_images: OK"

eval "$LOCAL" -c "COPY (SELECT media_id, created_at, is_deleted, updated_at, media_url, tour_id FROM tour_media) TO STDOUT WITH CSV" \
| eval "$DOCKER_TOUR" -c "SET session_replication_role = replica; COPY tour_media (tour_mediaid, created_at, is_deleted, updated_at, media_url, tour_id) FROM STDIN WITH CSV"
echo "  -> tour_media: OK"

eval "$LOCAL" -c "COPY (SELECT itinerary_dayid, created_at, is_deleted, updated_at, day_number, details, meals, title, tour_id FROM itinerary_days) TO STDOUT WITH CSV" \
| eval "$DOCKER_TOUR" -c "SET session_replication_role = replica; COPY itinerary_days (itinerary_dayid, created_at, is_deleted, updated_at, day_number, description, meals, title, tour_id) FROM STDIN WITH CSV"
echo "  -> itinerary_days: OK"

eval "$LOCAL" -c "COPY (SELECT departureid, created_at, is_deleted, updated_at, available_slots, departure_date, status, tour_guide_info, coupon_id, policy_template_id, tour_id FROM tour_departures) TO STDOUT WITH CSV" \
| eval "$DOCKER_TOUR" -c "SET session_replication_role = replica; COPY tour_departures (departureid, created_at, is_deleted, updated_at, available_slots, departure_date, status, tour_guide_info, coupon_id, policy_template_id, tour_id) FROM STDIN WITH CSV"
echo "  -> tour_departures: OK"

eval "$LOCAL" -c "COPY (SELECT pricingid, created_at, is_deleted, updated_at, original_price, passenger_type, sale_price, departure_id, age_description FROM departure_pricing) TO STDOUT WITH CSV" \
| eval "$DOCKER_TOUR" -c "SET session_replication_role = replica; COPY departure_pricings (pricingid, created_at, is_deleted, updated_at, original_price, passenger_type, sale_price, departure_id, age_description) FROM STDIN WITH CSV"
echo "  -> departure_pricings: OK"

eval "$LOCAL" -c "COPY (SELECT transportid, created_at, is_deleted, updated_at, arrival_time, depart_time, end_point, start_point, transport_code, type, vehicle_name, vehicle_type, departure_id FROM departure_transports) TO STDOUT WITH CSV" \
| eval "$DOCKER_TOUR" -c "SET session_replication_role = replica; COPY departure_transports (transportid, created_at, is_deleted, updated_at, arrival_time, depart_time, end_point, start_point, transport_code, transport_type, vehicle_name, vehicle_type, departure_id) FROM STDIN WITH CSV"
echo "  -> departure_transports: OK"

eval "$LOCAL" -c "COPY (SELECT favoriteid, created_at, is_deleted, updated_at, tour_id, user_id FROM favorite_tours) TO STDOUT WITH CSV" \
| eval "$DOCKER_TOUR" -c "SET session_replication_role = replica; COPY favorite_tours (favoriteid, created_at, is_deleted, updated_at, tour_id, user_id) FROM STDIN WITH CSV"
echo "  -> favorite_tours: OK"

eval "$LOCAL" -c "COPY (SELECT reviewid, created_at, is_deleted, updated_at, booking_id, comment, is_visible, rating, user_id, tour_id FROM reviews) TO STDOUT WITH CSV" \
| eval "$DOCKER_TOUR" -c "SET session_replication_role = replica; COPY reviews (reviewid, created_at, is_deleted, updated_at, booking_id, comment, is_visible, rating, user_id, tour_id) FROM STDIN WITH CSV"
echo "  -> reviews: OK"

eval "$LOCAL" -c "COPY (SELECT image_reviewid, created_at, is_deleted, updated_at, image, review_id FROM review_images) TO STDOUT WITH CSV" \
| eval "$DOCKER_TOUR" -c "SET session_replication_role = replica; COPY image_reviews (image_reviewid, created_at, is_deleted, updated_at, image_url, review_id) FROM STDIN WITH CSV"
echo "  -> image_reviews: OK"

eval "$DOCKER_TOUR" -c "
  SELECT setval('locations_locationid_seq', (SELECT MAX(locationid) FROM locations));
  SELECT setval('branch_contacts_contactid_seq', (SELECT MAX(contactid) FROM branch_contacts));
  SELECT setval('policy_templates_policy_templateid_seq', (SELECT MAX(policy_templateid) FROM policy_templates));
  SELECT setval('tours_tourid_seq', (SELECT MAX(tourid) FROM tours));
  SELECT setval('tour_images_tour_imageid_seq', (SELECT MAX(tour_imageid) FROM tour_images));
  SELECT setval('tour_media_tour_mediaid_seq', (SELECT MAX(tour_mediaid) FROM tour_media));
  SELECT setval('itinerary_days_itinerary_dayid_seq', (SELECT MAX(itinerary_dayid) FROM itinerary_days));
  SELECT setval('tour_departures_departureid_seq', (SELECT MAX(departureid) FROM tour_departures));
  SELECT setval('departure_pricings_pricingid_seq', (SELECT MAX(pricingid) FROM departure_pricings));
  SELECT setval('departure_transports_transportid_seq', (SELECT MAX(transportid) FROM departure_transports));
  SELECT setval('favorite_tours_favoriteid_seq', (SELECT MAX(favoriteid) FROM favorite_tours));
  SELECT setval('reviews_reviewid_seq', (SELECT MAX(reviewid) FROM reviews));
  SELECT setval('image_reviews_image_reviewid_seq', (SELECT MAX(image_reviewid) FROM image_reviews));
"
echo "  -> sequences reset: OK"

# ============================================================
# BOOKING_DB
# ============================================================
echo ""
echo "[3/6] Migrating booking_db..."

eval "$LOCAL" -c "COPY (SELECT couponid, created_at, is_deleted, updated_at, coupon_code, coupon_type, departure_id, description, discount_amount, end_date, min_order_value, start_date, usage_count, usage_limit FROM coupons) TO STDOUT WITH CSV" \
| eval "$DOCKER_BOOKING" -c "SET session_replication_role = replica; COPY coupons (couponid, created_at, is_deleted, updated_at, coupon_code, coupon_type, departure_id, description, discount_amount, end_date, min_order_value, start_date, usage_count, usage_limit) FROM STDIN WITH CSV"
echo "  -> coupons: OK"

eval "$LOCAL" -c "COPY (SELECT bookingid, created_at, is_deleted, updated_at, applied_coupon_codes, booking_code, booking_date, booking_status, cancel_reason, contact_address, contact_email, contact_full_name, contact_phone, coupon_discount, customer_note, departure_id, paid_by_coin, refund_amount, subtotal_price, surcharge, total_passengers, total_price, user_id FROM bookings) TO STDOUT WITH CSV" \
| eval "$DOCKER_BOOKING" -c "SET session_replication_role = replica; COPY bookings (bookingid, created_at, is_deleted, updated_at, applied_coupon_codes, booking_code, booking_date, booking_status, cancel_reason, contact_address, contact_email, contact_full_name, contact_phone, coupon_discount, customer_note, departure_id, paid_by_coin, refund_amount, subtotal_price, surcharge, total_passengers, total_price, user_id) FROM STDIN WITH CSV"
echo "  -> bookings: OK"

eval "$LOCAL" -c "COPY (SELECT booking_passengerid, created_at, is_deleted, updated_at, full_name, passenger_type, booking_id, base_price, gender, requires_single_room, single_room_surcharge, date_of_birth FROM booking_passengers) TO STDOUT WITH CSV" \
| eval "$DOCKER_BOOKING" -c "SET session_replication_role = replica; COPY booking_passengers (passengerid, created_at, is_deleted, updated_at, full_name, passenger_type, booking_id, base_price, gender, requires_single_room, single_room_surcharge, date_of_birth) FROM STDIN WITH CSV"
echo "  -> booking_passengers: OK"

eval "$LOCAL" -c "COPY (SELECT refundid, created_at, is_deleted, updated_at, account_name, account_number, booking_id FROM refund_information) TO STDOUT WITH CSV" \
| eval "$DOCKER_BOOKING" -c "SET session_replication_role = replica; COPY refund_information (refundid, created_at, is_deleted, updated_at, account_name, account_number, booking_id) FROM STDIN WITH CSV"
echo "  -> refund_information: OK"

eval "$DOCKER_BOOKING" -c "
  SELECT setval('coupons_couponid_seq', (SELECT MAX(couponid) FROM coupons));
  SELECT setval('bookings_bookingid_seq', (SELECT MAX(bookingid) FROM bookings));
  SELECT setval('booking_passengers_passengerid_seq', (SELECT MAX(passengerid) FROM booking_passengers));
  SELECT setval('refund_information_refundid_seq', (SELECT MAX(refundid) FROM refund_information));
"
echo "  -> sequences reset: OK"

# ============================================================
# PAYMENT_DB
# ============================================================
echo ""
echo "[4/6] Migrating payment_db..."

eval "$LOCAL" -c "COPY (SELECT paymentid, created_at, is_deleted, updated_at, account_name, account_number, amount, bank_code, bank_transaction_no, booking_id, payment_date, payment_description, payment_method, status, time_limit, transaction_datetime, transaction_id FROM payments) TO STDOUT WITH CSV" \
| eval "$DOCKER_PAYMENT" -c "SET session_replication_role = replica; COPY payments (paymentid, created_at, is_deleted, updated_at, account_name, account_number, amount, bank_code, bank_transaction_no, booking_id, payment_date, payment_description, payment_method, status, time_limit, transaction_datetime, transaction_id) FROM STDIN WITH CSV"
echo "  -> payments: OK"

eval "$DOCKER_PAYMENT" -c "SELECT setval('payments_paymentid_seq', (SELECT MAX(paymentid) FROM payments));"
echo "  -> sequences reset: OK"

# ============================================================
# NOTIFICATION_DB
# ============================================================
echo ""
echo "[5/6] Migrating notification_db..."

eval "$LOCAL" -c "COPY (SELECT notificationid, created_at, is_deleted, updated_at, message, metadata, title, type, user_id FROM notifications) TO STDOUT WITH CSV" \
| eval "$DOCKER_NOTIF" -c "SET session_replication_role = replica; COPY notifications (notificationid, created_at, is_deleted, updated_at, message, metadata, title, type, user_id) FROM STDIN WITH CSV"
echo "  -> notifications: OK"

eval "$LOCAL" -c "COPY (SELECT id, created_at, is_read, notification_id, user_id FROM user_notifications) TO STDOUT WITH CSV" \
| eval "$DOCKER_NOTIF" -c "SET session_replication_role = replica; COPY user_notifications (user_notificationid, created_at, is_read, notification_id, user_id) FROM STDIN WITH CSV"
echo "  -> user_notifications: OK"

eval "$DOCKER_NOTIF" -c "
  SELECT setval('notifications_notificationid_seq', (SELECT MAX(notificationid) FROM notifications));
  SELECT setval('user_notifications_user_notificationid_seq', (SELECT MAX(user_notificationid) FROM user_notifications));
"
echo "  -> sequences reset: OK"

# ============================================================
# FORUM_DB
# ============================================================
echo ""
echo "[6/6] Migrating forum_db..."

eval "$LOCAL" -c "COPY (SELECT categoryid, created_at, is_deleted, updated_at, description, display_order, icon, slug, category_name FROM post_categories) TO STDOUT WITH CSV" \
| eval "$DOCKER_FORUM" -c "SET session_replication_role = replica; COPY post_categories (categoryid, created_at, is_deleted, updated_at, description, display_order, icon_url, slug, name) FROM STDIN WITH CSV"
echo "  -> post_categories: OK"

eval "$LOCAL" -c "COPY (SELECT tagid, created_at, is_deleted, updated_at, slug, tag_name FROM tags) TO STDOUT WITH CSV" \
| eval "$DOCKER_FORUM" -c "SET session_replication_role = replica; COPY tags (tagid, created_at, is_deleted, updated_at, slug, name) FROM STDIN WITH CSV"
echo "  -> tags: OK"

eval "$LOCAL" -c "COPY (SELECT postid, created_at, is_deleted, updated_at, bookmark_count, comment_count, content, is_featured, is_pinned, like_count, post_type, published_at, share_count, status, summary, thumbnail_url, title, tour_id, user_id, view_count, category_id FROM forum_posts) TO STDOUT WITH CSV" \
| eval "$DOCKER_FORUM" -c "SET session_replication_role = replica; COPY forum_posts (postid, created_at, is_deleted, updated_at, bookmark_count, comment_count, content, is_featured, is_pinned, like_count, post_type, published_at, share_count, status, summary, thumbnail_url, title, tour_id, user_id, view_count, category_id) FROM STDIN WITH CSV"
echo "  -> forum_posts: OK"

eval "$LOCAL" -c "COPY (SELECT post_tagid, created_at, post_id, tag_id FROM post_tags) TO STDOUT WITH CSV" \
| eval "$DOCKER_FORUM" -c "SET session_replication_role = replica; COPY post_tags (post_tagid, created_at, post_id, tag_id) FROM STDIN WITH CSV"
echo "  -> post_tags: OK"

eval "$LOCAL" -c "COPY (SELECT imageid, created_at, is_deleted, updated_at, display_order, image_url, post_id FROM post_images) TO STDOUT WITH CSV" \
| eval "$DOCKER_FORUM" -c "SET session_replication_role = replica; COPY post_images (post_imageid, created_at, is_deleted, updated_at, display_order, image_url, post_id) FROM STDIN WITH CSV"
echo "  -> post_images: OK"

eval "$LOCAL" -c "COPY (SELECT commentid, created_at, is_deleted, updated_at, content, like_count, user_id, parent_comment_id, post_id FROM post_comments) TO STDOUT WITH CSV" \
| eval "$DOCKER_FORUM" -c "SET session_replication_role = replica; COPY post_comments (commentid, created_at, is_deleted, updated_at, content, like_count, user_id, parent_comment_id, post_id) FROM STDIN WITH CSV"
echo "  -> post_comments: OK"

eval "$LOCAL" -c "COPY (SELECT likeid, created_at, user_id, post_id FROM post_likes) TO STDOUT WITH CSV" \
| eval "$DOCKER_FORUM" -c "SET session_replication_role = replica; COPY post_likes (post_likeid, created_at, user_id, post_id) FROM STDIN WITH CSV"
echo "  -> post_likes: OK"

eval "$LOCAL" -c "COPY (SELECT likeid, created_at, user_id, comment_id FROM comment_likes) TO STDOUT WITH CSV" \
| eval "$DOCKER_FORUM" -c "SET session_replication_role = replica; COPY comment_likes (comment_likeid, created_at, user_id, comment_id) FROM STDIN WITH CSV"
echo "  -> comment_likes: OK"

eval "$LOCAL" -c "COPY (SELECT bookmarkid, created_at, user_id, post_id FROM post_bookmarks) TO STDOUT WITH CSV" \
| eval "$DOCKER_FORUM" -c "SET session_replication_role = replica; COPY post_bookmarks (bookmarkid, created_at, user_id, post_id) FROM STDIN WITH CSV"
echo "  -> post_bookmarks: OK"

eval "$LOCAL" -c "COPY (SELECT viewid, created_at, ip_address, user_id, post_id FROM post_views) TO STDOUT WITH CSV" \
| eval "$DOCKER_FORUM" -c "SET session_replication_role = replica; COPY post_views (viewid, created_at, ip_address, user_id, post_id) FROM STDIN WITH CSV"
echo "  -> post_views: OK"

eval "$LOCAL" -c "COPY (SELECT followerid, followed_at, follower_user_id, following_id FROM followers) TO STDOUT WITH CSV" \
| eval "$DOCKER_FORUM" -c "SET session_replication_role = replica; COPY followers (followerid, created_at, follower_user_id, following_user_id) FROM STDIN WITH CSV"
echo "  -> followers: OK"

eval "$DOCKER_FORUM" -c "
  SELECT setval('post_categories_categoryid_seq', (SELECT MAX(categoryid) FROM post_categories));
  SELECT setval('tags_tagid_seq', (SELECT MAX(tagid) FROM tags));
  SELECT setval('forum_posts_postid_seq', (SELECT MAX(postid) FROM forum_posts));
  SELECT setval('post_tags_post_tagid_seq', (SELECT MAX(post_tagid) FROM post_tags));
  SELECT setval('post_images_post_imageid_seq', (SELECT MAX(post_imageid) FROM post_images));
  SELECT setval('post_comments_commentid_seq', (SELECT MAX(commentid) FROM post_comments));
  SELECT setval('post_likes_post_likeid_seq', (SELECT MAX(post_likeid) FROM post_likes));
  SELECT setval('comment_likes_comment_likeid_seq', (SELECT MAX(comment_likeid) FROM comment_likes));
  SELECT setval('post_bookmarks_bookmarkid_seq', (SELECT MAX(bookmarkid) FROM post_bookmarks));
  SELECT setval('post_views_viewid_seq', (SELECT MAX(viewid) FROM post_views));
  SELECT setval('followers_followerid_seq', (SELECT MAX(followerid) FROM followers));
"
echo "  -> sequences reset: OK"

echo ""
echo "=========================================="
echo " MIGRATION COMPLETED SUCCESSFULLY!"
echo "=========================================="
