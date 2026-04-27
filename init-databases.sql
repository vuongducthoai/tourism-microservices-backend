-- ============================================================
-- Tourism Microservices - PostgreSQL Database Initialization
-- Run this script to create all databases needed by services
-- ============================================================

-- Database cho IAM Service (User, Auth, JWT)
CREATE DATABASE iam_db;

-- Database cho Tour Catalog Service (Tour, Location, Departure, Review)
CREATE DATABASE tour_catalog_db;

-- Database cho Booking Service (Booking, Coupon, Refund)
CREATE DATABASE booking_db;

-- Database cho Payment Service (Payment, VNPay, PayOS, SePay)
CREATE DATABASE payment_db;

-- Database cho Forum Service (Post, Comment, Like, Tag)
CREATE DATABASE forum_db;

-- Database cho Notification Service (Notification, UserNotification)
CREATE DATABASE notification_db;

-- Database cho Analytics Service (Dashboard, Stats)
CREATE DATABASE analytics_db;

-- ============================================================
-- Verify databases created
-- ============================================================
\l
