package com.tourism.notification.service.impl;

import com.tourism.notification.dto.BookingEventDTO;
import com.tourism.notification.dto.UserStatusEventDTO;
import com.tourism.notification.dto.VerificationEmailRequest;
import com.tourism.notification.service.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Mirrors monolith's MailServiceImpl.
 * sendRefundRequestNotification → email to ADMIN_EMAIL
 * sendCancellationCoinEmail     → email to customer (coin-path cancel)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailServiceImpl implements MailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Value("${app.admin.email:22110431@student.hcmute.edu.vn}")
    private String adminEmail;

    private static final NumberFormat VND_FMT =
            NumberFormat.getCurrencyInstance(new Locale("vi", "VN"));

    // ── Gửi email cho admin khi khách hàng submit refund request ───────────────
    @Async
    @Override
    public void sendRefundRequestNotification(BookingEventDTO event) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromEmail);
            msg.setTo(adminEmail);
            msg.setSubject("YÊU CẦU HOÀN TIỀN MỚI: Booking Code " + event.getBookingCode());

            BigDecimal refund = event.getRefundAmount() != null ? event.getRefundAmount() : BigDecimal.ZERO;

            String body = String.format(
                "Xin chào,\n\n" +
                "Hệ thống nhận được một yêu cầu hủy và hoàn tiền mới.\n\n" +
                "--- THÔNG TIN BOOKING ---\n" +
                "Mã Booking  : %s\n" +
                "Tên Tour    : %s\n" +
                "Mã Tour     : %s\n" +
                "Trạng thái  : Chờ hoàn tiền\n\n" +
                "--- LIÊN HỆ KHÁCH HÀNG ---\n" +
                "Họ & Tên    : %s\n" +
                "Email       : %s\n" +
                "Điện thoại  : %s\n" +
                "Địa chỉ     : %s\n\n" +
                "--- THÔNG TIN HOÀN TIỀN ---\n" +
                "Số tiền hoàn: %s\n" +
                "Tên TK      : %s\n" +
                "Số TK       : %s\n" +
                "Ngân hàng   : %s\n\n" +
                "Vui lòng xử lý yêu cầu này trong hệ thống quản trị.\n\n" +
                "Trân trọng,\nFuture Travel System",
                event.getBookingCode(),
                nvl(event.getTourName()),
                nvl(event.getTourCode()),
                nvl(event.getContactFullName()),
                nvl(event.getContactEmail()),
                nvl(event.getContactPhone()),
                nvl(event.getContactAddress()),
                VND_FMT.format(refund),
                nvl(event.getRefundAccountName()),
                nvl(event.getRefundAccountNumber()),
                nvl(event.getRefundBank())
            );

            msg.setText(body);
            mailSender.send(msg);
            log.info("Refund notification email sent to admin for booking: {}", event.getBookingCode());

        } catch (Exception e) {
            log.error("Failed to send refund notification email for booking {}: {}",
                    event.getBookingCode(), e.getMessage());
        }
    }

    // ── Gửi email xác nhận hủy tour (coin path) cho khách hàng ───────────────
    @Async
    @Override
    public void sendCancellationCoinEmail(BookingEventDTO event) {
        if (event.getContactEmail() == null) return;
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromEmail);
            msg.setTo(event.getContactEmail());
            msg.setSubject("XÁC NHẬN HỦY TOUR: Booking Code " + event.getBookingCode());

            BigDecimal coins = event.getCoinRefundAmount() != null ? event.getCoinRefundAmount() : BigDecimal.ZERO;
            BigDecimal vnd   = coins.multiply(new BigDecimal("1000"));

            String body = String.format(
                "Kính gửi Quý khách %s,\n\n" +
                "Tour của Quý khách đã được hủy thành công.\n\n" +
                "--- THÔNG TIN BOOKING ---\n" +
                "Mã Booking  : %s\n" +
                "Tên Tour    : %s\n" +
                "Trạng thái  : Đã hủy\n\n" +
                "--- HOÀN ĐIỂM CÁ NHÂN ---\n" +
                "Số điểm hoàn: %s điểm (~%s)\n\n" +
                "Điểm đã được cộng vào tài khoản của Quý khách.\n\n" +
                "Nếu có thắc mắc, vui lòng liên hệ:\n" +
                "Email: trananhthu270904@gmail.com  |  ĐT: 0339263066\n\n" +
                "Trân trọng,\nFuture Travel Team",
                nvl(event.getContactFullName()),
                event.getBookingCode(),
                nvl(event.getTourName()),
                coins.toPlainString(),
                VND_FMT.format(vnd)
            );

            msg.setText(body);
            mailSender.send(msg);
            log.info("Cancellation (coin) email sent to {} for booking: {}",
                    event.getContactEmail(), event.getBookingCode());

        } catch (Exception e) {
            log.error("Failed to send cancellation email for booking {}: {}",
                    event.getBookingCode(), e.getMessage());
        }
    }

    // ── Gửi email thông báo trạng thái booking cho khách hàng ────────────────
    @Async
    @Override
    public void sendCancellationEmail(BookingEventDTO event) {
        if (event.getContactEmail() == null || event.getContactEmail().isBlank()) {
            log.warn("sendCancellationEmail: no customer email for booking {}", event.getBookingCode());
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromEmail);
            msg.setTo(event.getContactEmail());
            msg.setSubject("THÔNG BÁO HỦY TOUR: Booking Code " + nvl(event.getBookingCode()));

            String body = String.format(
                    "Kính gửi Quý khách %s,\n\n" +
                    "Tour của Quý khách đã bị hủy.\n\n" +
                    "--- THÔNG TIN BOOKING ---\n" +
                    "Mã Booking    : %s\n" +
                    "Tên Tour      : %s\n" +
                    "Mã Tour       : %s\n" +
                    "Ngày khởi hành: %s\n" +
                    "Trạng thái    : Đã hủy\n\n" +
                    "--- LÝ DO HỦY ---\n" +
                    "%s\n\n" +
                    "Nếu có thắc mắc, vui lòng liên hệ: %s\n\n" +
                    "Trân trọng,\nFuture Travel Team",
                    nvl(event.getContactFullName()),
                    nvl(event.getBookingCode()),
                    nvl(event.getTourName()),
                    nvl(event.getTourCode()),
                    event.getDepartureDate() != null ? event.getDepartureDate().toString() : "N/A",
                    nvl(event.getCancelReason()),
                    adminEmail
            );

            msg.setText(body);
            mailSender.send(msg);
            log.info("Cancellation email sent to {} for booking {}",
                    event.getContactEmail(), event.getBookingCode());
        } catch (Exception e) {
            log.error("Failed to send cancellation email for booking {}: {}",
                    event.getBookingCode(), e.getMessage());
        }
    }

    @Async
    @Override
    public void sendCancellationWithRefundEmail(BookingEventDTO event) {
        if (event.getContactEmail() == null || event.getContactEmail().isBlank()) {
            log.warn("sendCancellationWithRefundEmail: no customer email for booking {}", event.getBookingCode());
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromEmail);
            msg.setTo(event.getContactEmail());
            msg.setSubject("THÔNG BÁO HỦY TOUR VÀ HOÀN TIỀN: Booking Code " + nvl(event.getBookingCode()));

            BigDecimal refund = event.getRefundAmount() != null ? event.getRefundAmount() : BigDecimal.ZERO;
            BigDecimal paidByCoin = event.getPaidByCoin() != null ? event.getPaidByCoin() : BigDecimal.ZERO;
            String refundAccountInfo = buildRefundAccountInfo(event);

            String body = String.format(
                    "Kính gửi Quý khách %s,\n\n" +
                    "Tour của Quý khách đã bị hủy và hệ thống đã ghi nhận thông tin hoàn tiền.\n\n" +
                    "--- THÔNG TIN BOOKING ---\n" +
                    "Mã Booking    : %s\n" +
                    "Tên Tour      : %s\n" +
                    "Mã Tour       : %s\n" +
                    "Ngày khởi hành: %s\n" +
                    "Trạng thái    : Đã hủy\n\n" +
                    "--- LÝ DO HỦY ---\n" +
                    "%s\n\n" +
                    "--- THÔNG TIN HOÀN TIỀN ---\n" +
                    "Số tiền hoàn: %s\n" +
                    "Giá trị điểm cá nhân đã dùng được tính vào hoàn: %s\n" +
                    "%s\n\n" +
                    "Số tiền hoàn là số cuối cùng hệ thống ghi nhận trong đơn, bao gồm giá trị điểm cá nhân nếu đơn có sử dụng.\n\n" +
                    "Nếu có thắc mắc, vui lòng liên hệ: %s\n\n" +
                    "Trân trọng,\nFuture Travel Team",
                    nvl(event.getContactFullName()),
                    nvl(event.getBookingCode()),
                    nvl(event.getTourName()),
                    nvl(event.getTourCode()),
                    event.getDepartureDate() != null ? event.getDepartureDate().toString() : "N/A",
                    nvl(event.getCancelReason()),
                    VND_FMT.format(refund),
                    VND_FMT.format(paidByCoin),
                    refundAccountInfo,
                    adminEmail
            );

            msg.setText(body);
            mailSender.send(msg);
            log.info("Cancellation refund email sent to {} for booking {}",
                    event.getContactEmail(), event.getBookingCode());
        } catch (Exception e) {
            log.error("Failed to send cancellation refund email for booking {}: {}",
                    event.getBookingCode(), e.getMessage());
        }
    }

    @Async
    @Override
    public void sendBookingStatusEmail(BookingEventDTO event) {
        // Placeholder: có thể mở rộng sau
        log.info("sendBookingStatusEmail called for booking {} status {}",
                event.getBookingCode(), event.getBookingStatus());
    }

    // ── Gửi email thông báo hủy tour (coin path) cho admin ───────────────────
    @Async
    @Override
    public void sendCancellationAdminNotification(BookingEventDTO event) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromEmail);
            msg.setTo(adminEmail);
            msg.setSubject("THÔNG BÁO HỦY TOUR: Booking Code " + event.getBookingCode());

            BigDecimal coins = event.getCoinRefundAmount() != null ? event.getCoinRefundAmount() : BigDecimal.ZERO;
            BigDecimal vnd   = coins.multiply(new BigDecimal("1000"));
            BigDecimal refund = event.getRefundAmount() != null ? event.getRefundAmount() : BigDecimal.ZERO;

            String body = String.format(
                "Xin chào Admin,\n\n" +
                "Khách hàng vừa hủy tour thành công qua hệ thống.\n\n" +
                "--- THÔNG TIN BOOKING ---\n" +
                "Mã Booking  : %s\n" +
                "Tên Tour    : %s\n" +
                "Mã Tour     : %s\n" +
                "Ngày khởi hành: %s\n" +
                "Trạng thái  : Đã hủy\n" +
                "Lý do hủy   : %s\n\n" +
                "--- THÔNG TIN KHÁCH HÀNG ---\n" +
                "Họ & Tên    : %s\n" +
                "Email       : %s\n" +
                "Điện thoại  : %s\n\n" +
                "--- THÔNG TIN HOÀN TIỀN ---\n" +
                "Tổng tiền booking: %s\n" +
                "Số tiền hoàn    : %s\n" +
                "Điểm hoàn       : %s điểm (~%s)\n\n" +
                "Trân trọng,\nFuture Travel System",
                event.getBookingCode(),
                nvl(event.getTourName()),
                nvl(event.getTourCode()),
                event.getDepartureDate() != null ? event.getDepartureDate().toString() : "N/A",
                nvl(event.getCancelReason()),
                nvl(event.getContactFullName()),
                nvl(event.getContactEmail()),
                nvl(event.getContactPhone()),
                VND_FMT.format(event.getTotalPrice() != null ? event.getTotalPrice() : BigDecimal.ZERO),
                VND_FMT.format(refund),
                coins.toPlainString(),
                VND_FMT.format(vnd)
            );

            msg.setText(body);
            mailSender.send(msg);
            log.info("Cancellation admin email sent for booking: {}", event.getBookingCode());

        } catch (Exception e) {
            log.error("Failed to send cancellation admin email for booking {}: {}",
                    event.getBookingCode(), e.getMessage());
        }
    }

    // ── Gửi email xác thực tài khoản cho người dùng mới ──────────────────────────
    @Async
    @Override
    public void sendVerificationEmail(VerificationEmailRequest request) {
        if (request.getEmail() == null || request.getEmail().isBlank()) {
            log.warn("sendVerificationEmail: no email provided");
            return;
        }

        // Nếu có OTP → gửi email HTML đẹp với mã 6 số
        if (request.getOtpCode() != null && !request.getOtpCode().isBlank()) {
            sendOtpEmailHtml(request);
            return;
        }

        // Fallback: link-based verification cũ
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromEmail);
            msg.setTo(request.getEmail());
            msg.setSubject("XÁC THỰC TÀI KHOẢN - FUTURE TRAVEL");
            String body = String.format(
                "Xin chào %s,\n\nCảm ơn bạn đã đăng ký tài khoản trên Future Travel.\n" +
                "Vui lòng xác thực email của bạn bằng cách click vào link dưới đây:\n\n%s\n\n" +
                "Link này có hiệu lực trong 24 giờ.\n\nTrân trọng,\nFuture Travel Team",
                nvl(request.getFullName()), request.getVerificationUrl()
            );
            msg.setText(body);
            mailSender.send(msg);
        } catch (Exception e) {
            log.error("Failed to send verification email to {}: {}", request.getEmail(), e.getMessage());
        }
    }

    /** Gửi email OTP định dạng HTML đẹp. */
    private void sendOtpEmailHtml(VerificationEmailRequest request) {
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
            helper.setFrom(fromEmail, "Future Travel");
            helper.setTo(request.getEmail());
            helper.setSubject("Mã xác thực Future Travel: " + request.getOtpCode());

            int expiry = request.getOtpExpiryMinutes() != null ? request.getOtpExpiryMinutes() : 5;
            String html = buildOtpHtml(nvl(request.getFullName()), request.getOtpCode(), expiry);
            helper.setText(html, true);

            mailSender.send(mime);
            log.info("OTP email sent to: {}", request.getEmail());
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            log.error("Failed to send OTP email to {}: {}", request.getEmail(), e.getMessage());
        }
    }

    private String buildOtpHtml(String fullName, String otp, int expiryMinutes) {
        // Tách 6 ký tự để hiện theo từng ô đẹp
        StringBuilder otpBoxes = new StringBuilder();
        for (char c : otp.toCharArray()) {
            otpBoxes.append("<span style=\"display:inline-block;width:48px;height:60px;margin:0 4px;")
                .append("background:#ffffff;border:2px solid #2563eb;border-radius:10px;")
                .append("line-height:60px;font-size:28px;font-weight:800;color:#0f172a;")
                .append("font-family:'Courier New',monospace;text-align:center;")
                .append("box-shadow:0 2px 6px rgba(37,99,235,0.15);\">")
                .append(c).append("</span>");
        }

        return "<!DOCTYPE html><html lang=\"vi\"><head><meta charset=\"UTF-8\"></head>" +
            "<body style=\"margin:0;padding:0;background:#f1f5f9;font-family:'Segoe UI',Roboto,Arial,sans-serif;\">" +
            "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f1f5f9;padding:32px 16px;\">" +
            "<tr><td align=\"center\">" +
            "<table width=\"560\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:560px;background:#fff;" +
            "border-radius:16px;overflow:hidden;box-shadow:0 8px 32px rgba(15,23,42,0.08);\">" +

            // Header gradient
            "<tr><td style=\"background:linear-gradient(135deg,#3b82f6 0%,#1d4ed8 100%);padding:32px 32px 28px;text-align:center;\">" +
            "<div style=\"display:inline-block;width:56px;height:56px;background:rgba(255,255,255,0.2);" +
            "border-radius:14px;line-height:56px;margin-bottom:14px;font-size:28px;\">✈️</div>" +
            "<h1 style=\"margin:0;color:#fff;font-size:22px;font-weight:800;letter-spacing:0.5px;\">FUTURE TRAVEL</h1>" +
            "<p style=\"margin:6px 0 0 0;color:rgba(255,255,255,0.85);font-size:13px;\">Xác thực tài khoản của bạn</p>" +
            "</td></tr>" +

            // Body
            "<tr><td style=\"padding:32px;\">" +
            "<h2 style=\"margin:0 0 12px 0;color:#0f172a;font-size:20px;font-weight:700;\">Xin chào " + fullName + "!</h2>" +
            "<p style=\"margin:0 0 24px 0;color:#475569;font-size:14.5px;line-height:1.6;\">" +
            "Cảm ơn bạn đã đăng ký tài khoản tại <strong>Future Travel</strong>. " +
            "Vui lòng nhập mã OTP dưới đây để xác thực email và kích hoạt tài khoản:" +
            "</p>" +

            // OTP box
            "<div style=\"text-align:center;background:linear-gradient(135deg,#eff6ff 0%,#dbeafe 100%);" +
            "border:1px solid #bfdbfe;border-radius:14px;padding:28px 16px;margin-bottom:24px;\">" +
            "<div style=\"font-size:11px;font-weight:700;color:#1e3a8a;text-transform:uppercase;" +
            "letter-spacing:1.5px;margin-bottom:14px;\">Mã xác thực OTP</div>" +
            "<div>" + otpBoxes + "</div>" +
            "<div style=\"margin-top:14px;color:#475569;font-size:12.5px;\">" +
            "⏱ Mã có hiệu lực trong <strong style=\"color:#dc2626;\">" + expiryMinutes + " phút</strong>" +
            "</div></div>" +

            // Warning
            "<div style=\"background:#fffbeb;border:1px solid #fde68a;border-radius:10px;padding:12px 14px;" +
            "margin-bottom:24px;color:#78350f;font-size:13px;line-height:1.5;\">" +
            "<strong>⚠ Lưu ý bảo mật:</strong><br>" +
            "Không chia sẻ mã OTP này với bất kỳ ai, kể cả nhân viên Future Travel. " +
            "Nếu bạn không đăng ký tài khoản, vui lòng bỏ qua email này." +
            "</div>" +

            "<p style=\"margin:0;color:#64748b;font-size:13px;line-height:1.6;\">" +
            "Nếu bạn gặp vấn đề, liên hệ hỗ trợ: " +
            "<a href=\"mailto:" + adminEmail + "\" style=\"color:#2563eb;text-decoration:none;font-weight:600;\">" +
            adminEmail + "</a></p>" +
            "</td></tr>" +

            // Footer
            "<tr><td style=\"background:#f8fafc;padding:20px 32px;text-align:center;border-top:1px solid #e2e8f0;\">" +
            "<p style=\"margin:0;color:#94a3b8;font-size:12px;line-height:1.5;\">" +
            "© 2025 <strong style=\"color:#475569;\">Future Travel</strong> · Công ty TNHH Future Việt Nam<br>" +
            "117 Lý Chính Thắng, Q.3, TP.HCM · Hotline: 1900 1234" +
            "</p></td></tr>" +

            "</table></td></tr></table></body></html>";
    }

    private String buildRefundAccountInfo(BookingEventDTO event) {
        if (!hasText(event.getRefundBank())
                && !hasText(event.getRefundAccountNumber())
                && !hasText(event.getRefundAccountName())) {
            return "Thông tin tài khoản hoàn tiền: Chưa cung cấp";
        }
        return String.format(
                "Ngân hàng    : %s\n" +
                "Số tài khoản : %s\n" +
                "Chủ tài khoản: %s",
                nvl(event.getRefundBank()),
                nvl(event.getRefundAccountNumber()),
                nvl(event.getRefundAccountName())
        );
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private String nvl(String s) {
        return s != null ? s : "N/A";
    }

    // ── Gửi email xác nhận đặt tour thành công cho khách hàng ──────────────────
    @Async
    @Override
    public void sendPaymentConfirmationEmail(BookingEventDTO event) {
        if (event.getContactEmail() == null || event.getContactEmail().isBlank()) {
            log.warn("sendPaymentConfirmationEmail: no customer email for booking {}",
                    event.getBookingCode());
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromEmail);
            msg.setTo(event.getContactEmail());
            msg.setSubject("ĐẶT TOUR THÀNH CÔNG: " + nvl(event.getBookingCode()));

            BigDecimal total     = event.getTotalPrice()  != null ? event.getTotalPrice()  : BigDecimal.ZERO;
            BigDecimal coinPaid  = event.getPaidByCoin()  != null ? event.getPaidByCoin()  : BigDecimal.ZERO;

            String body = String.format(
                "Xin chào %s,\n\n" +
                "Đặt tour của bạn đã được xác nhận thành công!\n" +
                "Dưới đây là thông tin chi tiết:\n\n" +
                "--- THÔNG TIN ĐẶT TOUR ---\n" +
                "Mã Booking  : %s\n" +
                "Tour        : %s\n" +
                "Mã Tour     : %s\n" +
                "Ngày khởi hành: %s\n" +
                "Trạng thái  : ĐÃ THANH TOÁN\n\n" +
                "--- THANH TOÁN ---\n" +
                "Tổng tiền tour  : %s\n" +
                "Thanh toán bằng coin: %s\n\n" +
                "Cảm ơn bạn đã tin tưởng và đặt tour của chúng tôi.\n" +
                "Nếu có thắc mắc, vui lòng liên hệ: %s\n\n" +
                "Chúc bạn có chuyến đi thật tuyệt vời!\n",
                nvl(event.getContactFullName()),
                nvl(event.getBookingCode()),
                nvl(event.getTourName()),
                nvl(event.getTourCode()),
                event.getDepartureDate() != null ? event.getDepartureDate().toString() : "N/A",
                VND_FMT.format(total),
                VND_FMT.format(coinPaid),
                adminEmail
            );

            msg.setText(body);
            mailSender.send(msg);
            log.info("Payment confirmation email sent to {} for booking {}",
                    event.getContactEmail(), event.getBookingCode());
        } catch (Exception e) {
            log.error("Failed to send payment confirmation email for booking {}: {}",
                    event.getBookingCode(), e.getMessage());
        }
    }

    // ── Gửi email thông báo khóa / mở khóa tài khoản cho người dùng ──────────
    @Async
    @Override
    public void sendAccountStatusEmail(UserStatusEventDTO event) {
        if (event.getEmail() == null || event.getEmail().isBlank()) {
            log.warn("sendAccountStatusEmail: no email for userId={}", event.getUserID());
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromEmail);
            msg.setTo(event.getEmail());

            boolean locked = Boolean.FALSE.equals(event.getStatus());
            String action  = locked ? "KHÓA" : "MỞ KHÓA";
            msg.setSubject("THÔNG BÁO " + action + " TÀI KHOẢN - FUTURE TRAVEL");

            String content = String.format(
                    "Xin chào %s,\n\n" +
                    "Tài khoản của bạn đã được %s.\n\n" +
                    "--- THÔNG TIN TÀI KHOẢN ---\n" +
                    "Họ tên: %s\n" +
                    "Email: %s\n" +
                    "Số điện thoại: %s\n" +
                    "Ngày sinh: %s\n\n" +
                    "--- LÝ DO ---\n%s\n\n" +
                    "Nếu có bất kỳ thắc mắc nào, vui lòng liên hệ:\n" +
                    "Email: %s\n" +
                    "Điện thoại: 0339263066\n\n" +
                    "Trân trọng,\nFuture Travel Team",
                    nvl(event.getFullName()),
                    locked ? "tạm khóa" : "mở khóa hoạt động trở lại",
                    nvl(event.getFullName()),
                    nvl(event.getEmail()),
                    nvl(event.getPhone()),
                    event.getDateOfBirth() != null ? event.getDateOfBirth().toString() : "N/A",
                    nvl(event.getReason()),
                    adminEmail
            );

            msg.setText(content);
            mailSender.send(msg);
            log.info("Account status ({}) email sent to userId={} <{}>",
                    action, event.getUserID(), event.getEmail());
        } catch (Exception e) {
            log.error("Failed to send account status email to userId={}: {}",
                    event.getUserID(), e.getMessage());
        }
    }
}
