# Hướng dẫn tích hợp Grafana + Prometheus vào Tourism Microservices

## Tổng quan kiến trúc

```
Spring Boot Services  ──►  /actuator/prometheus  ──►  Prometheus (scrape mỗi 15s)  ──►  Grafana (dashboard)
```

Các service hiện tại đã có `spring-boot-starter-actuator`. Chỉ cần:
1. Thêm dependency `micrometer-registry-prometheus` vào từng service
2. Expose endpoint `/actuator/prometheus` trong `application.yml`
3. Thêm Prometheus + Grafana vào `docker-compose.yml`
4. Cấu hình Prometheus scrape targets
5. Import dashboard vào Grafana

---

## Bước 1 — Thêm dependency Micrometer vào mỗi service

Thêm vào `pom.xml` của **tất cả 8 service** (api-gateway, iam-service, tour-catalog-service, booking-service, payment-service, forum-service, notification-service, analytics-service):

```xml
<!-- pom.xml — thêm vào <dependencies> -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

> **Lý do**: `micrometer-registry-prometheus` là "adapter" chuyển đổi metrics của Spring Boot
> (JVM heap, HTTP request count, response time, v.v.) sang định dạng mà Prometheus hiểu được.
> Spring Boot quản lý version tự động qua BOM — không cần ghi `<version>`.

---

## Bước 2 — Expose `/actuator/prometheus` trong application.yml

Thêm vào `application.yml` của **từng service**:

```yaml
man  agement:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    prometheus:
      enabled: true
  metrics:
    tags:
      application: ${spring.application.name}   # label phân biệt service trong Grafana
```

> `application: ${spring.application.name}` gắn tên service (ví dụ `booking-service`) vào mọi
> metric — giúp Grafana filter "chỉ hiện metrics của booking-service".

Ví dụ cụ thể cho `booking-service/src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: booking-service
# ... các config khác ...

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    prometheus:
      enabled: true
  metrics:
    tags:
      application: ${spring.application.name}
```

Làm tương tự cho 7 service còn lại, chỉ thay `name:` theo tên đúng của từng service.

**Kiểm tra**: sau khi build, mở `http://localhost:8083/actuator/prometheus` — phải thấy text dạng:
```
# HELP jvm_memory_used_bytes ...
jvm_memory_used_bytes{area="heap",...} 1.23456789E8
http_server_requests_seconds_count{...} 42.0
```

---

## Bước 3 — Tạo file cấu hình Prometheus

Tạo thư mục và file tại `d:\Tourism_Microservices\monitoring\prometheus.yml`:

```yaml
# monitoring/prometheus.yml
global:
  scrape_interval: 15s          # Scrape metrics mỗi 15 giây
  evaluation_interval: 15s

scrape_configs:

  - job_name: 'api-gateway'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['tourism-api-gateway:8080']

  - job_name: 'iam-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['tourism-iam-service:8081']

  - job_name: 'tour-catalog-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['tourism-tour-catalog-service:8082']

  - job_name: 'booking-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['tourism-booking-service:8083']

  - job_name: 'payment-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['tourism-payment-service:8084']

  - job_name: 'forum-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['tourism-forum-service:8085']

  - job_name: 'notification-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['tourism-notification-service:8086']

  - job_name: 'analytics-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['tourism-analytics-service:8087']
```

> **Tại sao dùng tên container thay vì `localhost`?**
> Trong Docker network, các container gọi nhau qua tên container (DNS nội bộ).
> Prometheus chạy trong container nên phải dùng `tourism-booking-service:8083`,
> không phải `localhost:8083` (localhost ở đây là Prometheus container, không phải máy host).

---

## Bước 4 — Thêm Prometheus + Grafana vào docker-compose.yml

Thêm 2 service sau vào cuối file `docker-compose.yml` (trước dòng cuối `networks:`):

```yaml
  # ─── Monitoring ────────────────────────────────────────────────────────────

  prometheus:
    image: prom/prometheus:v2.51.0
    container_name: tourism-prometheus
    ports:
      - "9090:9090"
    volumes:
      - ./monitoring/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prometheus_data:/prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'
      - '--web.console.libraries=/etc/prometheus/console_libraries'
      - '--web.console.templates=/etc/prometheus/consoles'
      - '--storage.tsdb.retention.time=15d'   # giữ data 15 ngày
      - '--web.enable-lifecycle'
    restart: unless-stopped
    networks:
      - tourism-network

  grafana:
    image: grafana/grafana:10.4.0
    container_name: tourism-grafana
    ports:
      - "3001:3000"             # 3000 có thể đã dùng cho React dev server
    environment:
      - GF_SECURITY_ADMIN_USER=admin
      - GF_SECURITY_ADMIN_PASSWORD=admin123
      - GF_USERS_ALLOW_SIGN_UP=false
    volumes:
      - grafana_data:/var/lib/grafana
      - ./monitoring/grafana/provisioning:/etc/grafana/provisioning:ro
    depends_on:
      - prometheus
    restart: unless-stopped
    networks:
      - tourism-network
```

Thêm volumes vào phần `volumes:` cuối file:

```yaml
volumes:
  # ... các volume hiện có ...
  prometheus_data:
  grafana_data:
```

---

## Bước 5 — Provisioning tự động (Grafana tự nhận Prometheus)

Tạo 2 file để Grafana tự biết Prometheus là datasource và tự load dashboard:

### `monitoring/grafana/provisioning/datasources/prometheus.yml`

```yaml
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://tourism-prometheus:9090
    isDefault: true
    editable: false
```

### `monitoring/grafana/provisioning/dashboards/dashboard.yml`

```yaml
apiVersion: 1

providers:
  - name: 'Tourism Microservices'
    orgId: 1
    folder: 'Tourism'
    type: file
    disableDeletion: false
    updateIntervalSeconds: 30
    options:
      path: /etc/grafana/provisioning/dashboards
```

> Provisioning giúp Grafana tự cấu hình khi khởi động — không cần click tay mỗi lần restart container.

---

## Bước 6 — Cấu trúc thư mục monitoring

Sau khi tạo xong, cấu trúc sẽ là:

```
d:\Tourism_Microservices\
├── monitoring\
│   ├── prometheus.yml
│   └── grafana\
│       └── provisioning\
│           ├── datasources\
│           │   └── prometheus.yml
│           └── dashboards\
│               └── dashboard.yml
├── docker-compose.yml
└── ...
```

---

## Bước 7 — Build và chạy

```bash
# 1. Build lại tất cả service (vì đã thêm dependency)
cd d:\Tourism_Microservices

# Build từng service (hoặc dùng PowerShell loop)
cd booking-service    && mvn package -DskipTests && cd ..
cd notification-service && mvn package -DskipTests && cd ..
cd forum-service      && mvn package -DskipTests && cd ..
# ... làm tương tự cho 5 service còn lại ...

# 2. Khởi động thêm Prometheus + Grafana
docker-compose up -d prometheus grafana

# 3. Rebuild các service đã thêm dependency
docker-compose up -d --build booking-service notification-service forum-service
# ... các service khác ...
```

---

## Bước 8 — Truy cập và cấu hình Grafana

### Đăng nhập Grafana
- URL: `http://localhost:3001`
- Username: `admin`
- Password: `admin123`

### Kiểm tra Prometheus nhận data
- URL: `http://localhost:9090`
- Vào **Status → Targets** — phải thấy 8 target với trạng thái **UP** (xanh)
- Nếu target **DOWN**: kiểm tra service đã expose `/actuator/prometheus` chưa

### Import Dashboard có sẵn (nhanh nhất)

Grafana có dashboard cộng đồng cho Spring Boot. Import bằng ID:

| Dashboard | ID Grafana | Nội dung |
|---|---|---|
| JVM Micrometer | `4701` | Heap, GC, threads, CPU của từng service |
| Spring Boot Statistics | `6756` | HTTP requests, latency, error rate |
| Spring Boot 3.x APM | `19004` | Full APM cho Spring Boot 3 |

**Cách import:**
1. Grafana → **Dashboards** → **Import**
2. Nhập ID (ví dụ `4701`) → **Load**
3. Chọn datasource **Prometheus** → **Import**

---

## Bước 9 — Các metric quan trọng cần theo dõi

Sau khi import dashboard, chú ý các panel sau:

### HTTP Performance
```promql
# Số request mỗi giây (RPS) theo service
rate(http_server_requests_seconds_count{application="booking-service"}[1m])

# P99 latency (99% request dưới bao nhiêu ms)
histogram_quantile(0.99,
  rate(http_server_requests_seconds_bucket{application="booking-service"}[5m])
)

# Tỉ lệ lỗi 5xx
rate(http_server_requests_seconds_count{status=~"5..",application="booking-service"}[1m])
  /
rate(http_server_requests_seconds_count{application="booking-service"}[1m])
```

### JVM Health
```promql
# Heap đang dùng (MB)
jvm_memory_used_bytes{area="heap", application="booking-service"} / 1024 / 1024

# GC pause time
rate(jvm_gc_pause_seconds_sum[1m])
```

### RabbitMQ (forum notifications)
```promql
# Số message trong queue forum.notification.queue
# Cần thêm rabbitmq_prometheus plugin — xem Bonus bên dưới
rabbitmq_queue_messages{queue="forum.notification.queue"}
```

---

## Bonus — Theo dõi RabbitMQ trong Grafana

RabbitMQ image `rabbitmq:3-management-alpine` đã có management plugin.
Thêm vào `docker-compose.yml` phần rabbitmq:

```yaml
  rabbitmq:
    image: rabbitmq:3-management-alpine
    container_name: tourism-rabbitmq
    environment:
      - RABBITMQ_DEFAULT_USER=guest
      - RABBITMQ_DEFAULT_PASS=guest
    ports:
      - "5672:5672"
      - "15672:15672"    # RabbitMQ Management UI
      - "15692:15692"    # Prometheus metrics endpoint
    command: >
      sh -c "rabbitmq-plugins enable rabbitmq_prometheus && rabbitmq-server"
```

Thêm vào `prometheus.yml`:

```yaml
  - job_name: 'rabbitmq'
    static_configs:
      - targets: ['tourism-rabbitmq:15692']
```

Import dashboard RabbitMQ Overview: ID `10991`

---

## Troubleshooting thường gặp

| Vấn đề | Nguyên nhân | Cách fix |
|---|---|---|
| Target DOWN trong Prometheus | Service chưa expose `/actuator/prometheus` | Kiểm tra `application.yml`, rebuild service |
| `404 /actuator/prometheus` | Thiếu dependency `micrometer-registry-prometheus` | Thêm vào `pom.xml`, rebuild |
| Grafana không thấy datasource | Sai URL Prometheus | Phải là `http://tourism-prometheus:9090` (tên container) |
| Metric không có label `application` | Thiếu `metrics.tags.application` | Thêm vào `application.yml` |
| Port 3000 conflict | React dev server dùng 3000 | Grafana map sang `3001:3000` như trên |
| Prometheus không scrape được | Spring Security block `/actuator` | Thêm permit `/actuator/**` trong Security config |

### Fix Spring Security block actuator (nếu có)

Nếu service có Spring Security, thêm vào Security config:

```java
// Trong SecurityFilterChain hoặc SecurityConfig
http.authorizeHttpRequests(auth -> auth
    .requestMatchers("/actuator/prometheus", "/actuator/health").permitAll()
    // ... các rule khác
);
```

---

## Thứ tự thực hiện tóm tắt

```
[1] Thêm micrometer dependency vào pom.xml của 8 service
[2] Thêm management config vào application.yml của 8 service
[3] Tạo monitoring/prometheus.yml
[4] Tạo monitoring/grafana/provisioning/datasources/prometheus.yml
[5] Tạo monitoring/grafana/provisioning/dashboards/dashboard.yml
[6] Thêm prometheus + grafana service vào docker-compose.yml
[7] Thêm prometheus_data + grafana_data vào volumes
[8] mvn package -DskipTests cho tất cả service
[9] docker-compose up -d --build
[10] Mở http://localhost:9090 → Status → Targets → kiểm tra UP
[11] Mở http://localhost:3001 → Import dashboard 4701 + 19004
```
