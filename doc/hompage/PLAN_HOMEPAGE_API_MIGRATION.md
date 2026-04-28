# Plan: Migrate Homepage APIs - Monolith to Microservices (tour-catalog-service)

## TL;DR

Frontend goi **5 endpoint trang chu** vao `http://localhost:8080/api` (API Gateway).
Gateway da co route san tat ca `/api/tours/**` va `/api/locations/**` -> `tour-catalog-service:8082`.
Nhiem vu: **trien khai day du layer controller/service/repository/dto** trong `tour-catalog-service`
de 5 endpoint hoat dong dung y het response shape frontend expect, **khong sua frontend**.

---

## 1. Frontend -> Backend Contract (5 endpoints)

| # | HTTP | URL | Frontend Hook | Response Type |
|---|------|-----|---------------|--------------|
| 1 | GET | `/api/tours/display` | `useFeaturedTours` | `TourDisplayResponse[]` |
| 2 | GET | `/api/tours/deepest-discount` | `useSpecialTours` | `TourSpecialResponse[]` |
| 3 | GET | `/api/locations/start-location` | `useLocations` | `LocationResponse[]` |
| 4 | GET | `/api/locations/end-location` | `useLocations` | `LocationResponse[]` |
| 5 | POST | `/api/locations/destinations-by-region` | `useFavoriteDestinations` | `DestinationResponse[]` |

### Exact DTO Shape (match 100% TypeScript DTOs trong frontend)

```
TourDisplayResponse:
  tourID, tourCode, tourName
  endPointName       <- tour.endLocation.name
  transportation, duration
  departureDate[]    <- ISO dates cua upcoming departures (List<String>)
  money (Long)       <- gia ADULT thap nhat
  image (String)     <- URL anh dau tien

TourSpecialResponse:
  departureID, tourID, tourName, tourCode
  startLocationName  <- tour.startLocation.name
  duration
  departureDate      <- LocalDate.toString() "yyyy-MM-dd"
  availableSlots
  salePrice          <- DeparturePricing.price (ADULT)
  originalPrice      <- DeparturePricing.originalPrice (ADULT)
  discountPercentage <- (originalPrice - price) / originalPrice * 100
  image

LocationResponse:
  locationID, name
  imageUrl           <- location.image  (khac ten field!)
  description

DestinationResponse:
  locationID
  endPoint           <- location.name
  listImage          <- location.image
  region             <- region.name()
```

---

## 2. Hien Trang Codebase

### San co
- `tour-catalog-service`: **13 entities** day du (Tour, Location, TourDeparture, DeparturePricing, TourImage, Review, FavoriteTour, ItineraryDay, ...)
- API Gateway da route `/api/tours/**` va `/api/locations/**` -> `tour-catalog-service`
- ModelMapper `3.2.0` da co trong `pom.xml`
- Database da seeded: 9 tours, 12 locations, 15 departures, 45 pricing rows
- Folder structure co san: `controller/`, `service/`, `repository/`, `dto/`, `config/`, `convert/`

### Chua co (can tao)
- `AppConfig.java` (ModelMapper @Bean)
- Toan bo: Repository, Service, ServiceImpl, Controller, DTO, Converter

### ENTITY BI LECH VOI DB THUC TE - PHAI SUA TRUOC

**`DeparturePricing.java`** - DB co cot `price`, khong co `sale_price` / `age_description`:
- Xoa field: `ageDescription` (NOT NULL se crash insert)
- Doi ten field: `salePrice` -> `price`, them `@Column(name = "price")`

**`TourDeparture.java`** - DB co `departure_code`, `return_date`, `total_slots`; khong co `tour_guide_info`, `coupon_id`:
- Xoa fields: `tourGuideInfo` (NOT NULL), `couponId`
- Them fields: `departureCode (String)`, `returnDate (LocalDate)`, `totalSlots (Integer)`
- Doi type `departureDate`: `LocalDateTime` -> `LocalDate`

---

## 3. Kien Truc Clean Code

```
tour-catalog-service/src/main/java/com/tourism/tourcatalog/
|
+-- config/
|   +-- AppConfig.java                        @Bean ModelMapper + TypeMaps + Converters
|
+-- convert/
|   +-- TourToDisplayResponseConverter.java   Converter<Tour, TourDisplayResponse>
|   +-- TourDepartureToSpecialConverter.java  Converter<TourDeparture, TourSpecialResponse>
|
+-- dto/
|   +-- request/
|   |   +-- RegionRequest.java
|   +-- response/
|       +-- TourDisplayResponse.java
|       +-- TourSpecialResponse.java
|       +-- LocationResponse.java
|       +-- DestinationResponse.java
|
+-- repository/
|   +-- TourRepository.java
|   +-- TourDepartureRepository.java
|   +-- LocationRepository.java
|
+-- service/
|   +-- TourService.java          (interface)
|   +-- LocationService.java      (interface)
|   +-- impl/
|       +-- TourServiceImpl.java
|       +-- LocationServiceImpl.java
|
+-- controller/
    +-- TourController.java       /api/tours/display, /api/tours/deepest-discount
    +-- LocationController.java   /api/locations/...
```

**Nguyen tac clean code ap dung:**
- Controller chi inject Service, tra `ResponseEntity<T>` - khong chua business logic
- Service interface + Impl pattern - de test, de mock
- ModelMapper voi **typed Converter** cho mapping phuc tap (traversal qua relations)
- Repository dung JPQL `@Query` + `JOIN FETCH` de tranh N+1 - khong lazy load trong service
- DTO la POJO thuan (`@Data @NoArgsConstructor @AllArgsConstructor`) - khong chua logic
- Converter la `@Component` bean, inject vao `AppConfig` qua constructor - Spring DI chuan

---

## 4. Implementation Steps

> Thu tu phu thuoc: Phase 1 -> (2 song song 3) -> 4 -> 5 -> 6 -> 7

### Phase 1 - Entity Fixes (prerequisite, KHONG the skip)

**Step 1.1** - Sua `entity/DeparturePricing.java`:
- Xoa: `private String ageDescription`
- Doi: `private BigDecimal salePrice` -> `@Column(name="price") private BigDecimal price`

**Step 1.2** - Sua `entity/TourDeparture.java`:
- Xoa: `tourGuideInfo`, `couponId`
- Them:
  ```java
  @Column(name = "departure_code") private String departureCode;
  @Column(name = "return_date")    private LocalDate returnDate;
  @Column(name = "total_slots")    private Integer totalSlots;
  ```
- Doi: `LocalDateTime departureDate` -> `LocalDate departureDate`

---

### Phase 2 - Config & ModelMapper (song song voi Phase 3)

**Step 2.1** - Tao `config/AppConfig.java`:

```java
@Configuration
public class AppConfig {
    @Bean
    public ModelMapper modelMapper(
            TourToDisplayResponseConverter displayConverter,
            TourDepartureToSpecialConverter specialConverter) {
        ModelMapper mm = new ModelMapper();
        mm.addConverter(displayConverter);
        mm.addConverter(specialConverter);
        // Location -> LocationResponse: image -> imageUrl
        mm.typeMap(Location.class, LocationResponse.class)
          .addMappings(m -> m.map(Location::getImage, LocationResponse::setImageUrl));
        // Location -> DestinationResponse
        mm.typeMap(Location.class, DestinationResponse.class)
          .addMappings(m -> {
              m.map(Location::getName,  DestinationResponse::setEndPoint);
              m.map(Location::getImage, DestinationResponse::setListImage);
              m.<String>map(src -> src.getRegion().name(), DestinationResponse::setRegion);
          });
        return mm;
    }
}
```

---

### Phase 3 - DTOs (song song voi Phase 2)

**Step 3.1** - `dto/request/RegionRequest.java`:
```java
@Data @NoArgsConstructor @AllArgsConstructor
public class RegionRequest {
    @NotBlank private String region; // "NORTH" | "CENTRAL" | "SOUTH"
}
```

**Step 3.2** - `dto/response/TourDisplayResponse.java`:
```java
@Data @NoArgsConstructor @AllArgsConstructor
public class TourDisplayResponse {
    private Integer      tourID;
    private String       tourCode;
    private String       tourName;
    private String       endPointName;
    private String       transportation;
    private String       duration;
    private List<String> departureDate;
    private Long         money;
    private String       image;
}
```

**Step 3.3** - `dto/response/TourSpecialResponse.java`:
```java
@Data @NoArgsConstructor @AllArgsConstructor
public class TourSpecialResponse {
    private Integer    departureID;
    private Integer    tourID;
    private String     tourName;
    private String     tourCode;
    private String     startLocationName;
    private String     duration;
    private String     departureDate;
    private Integer    availableSlots;
    private BigDecimal salePrice;
    private BigDecimal originalPrice;
    private Integer    discountPercentage;
    private String     image;
}
```

**Step 3.4** - `dto/response/LocationResponse.java`:
```java
@Data @NoArgsConstructor @AllArgsConstructor
public class LocationResponse {
    private Integer locationID;
    private String  name;
    private String  imageUrl;     // <- location.image
    private String  description;
}
```

**Step 3.5** - `dto/response/DestinationResponse.java`:
```java
@Data @NoArgsConstructor @AllArgsConstructor
public class DestinationResponse {
    private Integer locationID;
    private String  endPoint;   // <- location.name
    private String  listImage;  // <- location.image
    private String  region;     // <- region.name()
}
```

---

### Phase 4 - Converters (depends on Phase 1 + 3)

**Step 4.1** - `convert/TourToDisplayResponseConverter.java`
implements `Converter<Tour, TourDisplayResponse>`, danh dau `@Component`:

```
Mapping:
  endPointName    <- tour.endLocation.name
  departureDate[] <- tour.departures.stream()
                       .filter(d -> TRUE.equals(d.getStatus()))
                       .map(d -> d.getDepartureDate().toString())
                       .collect(toList())
  money           <- tour.departures.stream()
                       .flatMap(d -> d.getPricings().stream())
                       .filter(p -> "ADULT".equals(p.getPassengerType()))
                       .map(p -> p.getPrice().longValue())
                       .min(Long::compare).orElse(0L)
  image           <- tour.images empty? null : tour.images.get(0).getImageUrl()
```

**Step 4.2** - `convert/TourDepartureToSpecialConverter.java`
implements `Converter<TourDeparture, TourSpecialResponse>`, danh dau `@Component`:

```
Mapping:
  startLocationName <- departure.getTour().getStartLocation().getName()
  adultPricing      = pricings.stream().filter("ADULT").findFirst().orElseThrow()
  salePrice         <- adultPricing.getPrice()
  originalPrice     <- adultPricing.getOriginalPrice()
  discountPct       <- (originalPrice - salePrice).divide(originalPrice, 4, HALF_UP)
                          .multiply(BigDecimal.valueOf(100)).intValue()
  image             <- tour.images empty? null : tour.images.get(0).getImageUrl()
```

---

### Phase 5 - Repositories (depends on Phase 1)

**Step 5.1** - `repository/TourRepository.java`:
```java
@Query("""
    SELECT DISTINCT t FROM Tour t
    LEFT JOIN FETCH t.images
    LEFT JOIN FETCH t.departures d
    LEFT JOIN FETCH d.pricings
    WHERE t.status = true
    ORDER BY t.tourID
    """)
List<Tour> findAllActiveWithDetails();
```

**Step 5.2** - `repository/TourDepartureRepository.java`:
```java
@Query("""
    SELECT DISTINCT d FROM TourDeparture d
    JOIN FETCH d.tour t
    LEFT JOIN FETCH t.images
    LEFT JOIN FETCH t.startLocation
    LEFT JOIN FETCH d.pricings p
    WHERE d.status = true
      AND d.departureDate >= :today
      AND p.passengerType = 'ADULT'
      AND p.originalPrice > p.price
    """)
List<TourDeparture> findActiveDiscountedDepartures(@Param("today") LocalDate today);
```

**Step 5.3** - `repository/LocationRepository.java`:
```java
@Query("SELECT DISTINCT l FROM Location l JOIN l.startPoint t WHERE t.status = true AND l.status = true")
List<Location> findDistinctStartLocations();

@Query("SELECT DISTINCT l FROM Location l JOIN l.endPoint t WHERE t.status = true AND l.status = true")
List<Location> findDistinctEndLocations();

List<Location> findByRegionAndStatusTrue(Region region); // Spring Data method
```

---

### Phase 6 - Services (depends on Phase 4 + 5)

**Step 6.1** - `service/TourService.java` (interface):
```java
public interface TourService {
    List<TourDisplayResponse> getAllToursForDisplay();
    List<TourSpecialResponse> getTop10DeepestDiscountTours();
}
```

**Step 6.2** - `service/impl/TourServiceImpl.java`:
```java
@Service @RequiredArgsConstructor
public class TourServiceImpl implements TourService {
    private final TourRepository          tourRepository;
    private final TourDepartureRepository departureRepository;
    private final ModelMapper             modelMapper;

    @Override
    public List<TourDisplayResponse> getAllToursForDisplay() {
        return tourRepository.findAllActiveWithDetails().stream()
            .map(t -> modelMapper.map(t, TourDisplayResponse.class))
            .collect(Collectors.toList());
    }

    @Override
    public List<TourSpecialResponse> getTop10DeepestDiscountTours() {
        return departureRepository.findActiveDiscountedDepartures(LocalDate.now()).stream()
            .map(d -> modelMapper.map(d, TourSpecialResponse.class))
            .sorted(Comparator.comparingInt(TourSpecialResponse::getDiscountPercentage).reversed())
            .limit(10)
            .collect(Collectors.toList());
    }
}
```

> NOTE: Sort trong **Java stream** - khong ORDER BY JPQL voi BigDecimal division

**Step 6.3** - `service/LocationService.java` (interface):
```java
public interface LocationService {
    List<LocationResponse>    getStartLocations();
    List<LocationResponse>    getEndLocations();
    List<DestinationResponse> getDestinationsByRegion(String regionStr);
}
```

**Step 6.4** - `service/impl/LocationServiceImpl.java`:
```java
@Override
public List<DestinationResponse> getDestinationsByRegion(String regionStr) {
    Region region = Region.valueOf(regionStr.toUpperCase()); // throws -> 400 Bad Request
    return locationRepository.findByRegionAndStatusTrue(region).stream()
        .map(l -> modelMapper.map(l, DestinationResponse.class))
        .collect(Collectors.toList());
}
```

---

### Phase 7 - Controllers (depends on Phase 6)

**Step 7.1** - `controller/TourController.java`:
```java
@RestController @RequestMapping("/api/tours") @RequiredArgsConstructor
public class TourController {
    private final TourService tourService;

    @GetMapping("/display")
    public ResponseEntity<List<TourDisplayResponse>> getToursForDisplay() {
        return ResponseEntity.ok(tourService.getAllToursForDisplay());
    }

    @GetMapping("/deepest-discount")
    public ResponseEntity<List<TourSpecialResponse>> getDeepestDiscountTours() {
        return ResponseEntity.ok(tourService.getTop10DeepestDiscountTours());
    }
}
```

**Step 7.2** - `controller/LocationController.java`:
```java
@RestController @RequestMapping("/api/locations") @RequiredArgsConstructor
public class LocationController {
    private final LocationService locationService;

    @GetMapping("/start-location")
    public ResponseEntity<List<LocationResponse>> getStartLocations() {
        return ResponseEntity.ok(locationService.getStartLocations());
    }

    @GetMapping("/end-location")
    public ResponseEntity<List<LocationResponse>> getEndLocations() {
        return ResponseEntity.ok(locationService.getEndLocations());
    }

    @PostMapping("/destinations-by-region")
    public ResponseEntity<List<DestinationResponse>> getDestinationsByRegion(
            @Valid @RequestBody RegionRequest request) {
        return ResponseEntity.ok(locationService.getDestinationsByRegion(request.getRegion()));
    }
}
```

---

## 5. Danh Sach Files

### Sua - 2 files
| File | Thay doi |
|------|---------|
| `entity/DeparturePricing.java` | Xoa `ageDescription`; doi `salePrice` -> `price` voi `@Column(name="price")` |
| `entity/TourDeparture.java` | Xoa `tourGuideInfo`, `couponId`; them `departureCode`, `returnDate`, `totalSlots`; doi type `departureDate` -> `LocalDate` |

### Tao moi - 17 files
| File | Phase | Phu thuoc |
|------|-------|-----------|
| `config/AppConfig.java` | 2 | Phase 3 DTOs |
| `dto/request/RegionRequest.java` | 3 | - |
| `dto/response/TourDisplayResponse.java` | 3 | - |
| `dto/response/TourSpecialResponse.java` | 3 | - |
| `dto/response/LocationResponse.java` | 3 | - |
| `dto/response/DestinationResponse.java` | 3 | - |
| `convert/TourToDisplayResponseConverter.java` | 4 | Phase 1 + 3 |
| `convert/TourDepartureToSpecialConverter.java` | 4 | Phase 1 + 3 |
| `repository/TourRepository.java` | 5 | Phase 1 |
| `repository/TourDepartureRepository.java` | 5 | Phase 1 |
| `repository/LocationRepository.java` | 5 | Phase 1 |
| `service/TourService.java` | 6 | Phase 3 |
| `service/impl/TourServiceImpl.java` | 6 | Phase 4 + 5 |
| `service/LocationService.java` | 6 | Phase 3 |
| `service/impl/LocationServiceImpl.java` | 6 | Phase 5 |
| `controller/TourController.java` | 7 | Phase 6 |
| `controller/LocationController.java` | 7 | Phase 6 |

**Tong: 2 file sua + 17 file moi - tap trung hoan toan trong `tour-catalog-service`**

---

## 6. Verification Checklist

```bash
# 1. Build
mvn clean package -pl tour-catalog-service -am

# 2. Start theo thu tu
#    service-discovery (8761) -> config-server (8888) -> api-gateway (8080) -> tour-catalog-service (8082)

# 3. Curl test 5 endpoints qua gateway (port 8080)
curl http://localhost:8080/api/tours/display
curl http://localhost:8080/api/tours/deepest-discount
curl http://localhost:8080/api/locations/start-location
curl http://localhost:8080/api/locations/end-location
curl -X POST http://localhost:8080/api/locations/destinations-by-region \
     -H "Content-Type: application/json" -d "{\"region\":\"NORTH\"}"

# 4. Check N+1: spring.jpa.show-sql: true -> moi endpoint chi 1-2 SQL query

# 5. Start frontend: npm start -> localhost:3000 -> trang chu render du 5 section
```

---

## 7. Decisions & Scope

**In scope:**
- 5 homepage endpoints
- Entity alignment voi actual DB (tien quyet)
- Clean ModelMapper Converter pattern

**Out of scope (lan nay):**
- Auth / JWT filter (chua implement trong microservices)
- Tour search `/api/tours/search` (search page, khong phai homepage)
- Tour detail, booking, payment, notification, forum, analytics endpoints

**Key decisions:**
- Sort `deepest-discount` trong **Java stream**, khong ORDER BY JPQL voi BigDecimal division -> portable, de doc
- `DestinationResponse` dung `TypeMap.addMappings()` trong `AppConfig` - mapping don gian, khong can full Converter
- `JOIN FETCH` thay vi `@EntityGraph` - JPQL co the doc ngay trong Repository
- Converter la `@Component`, inject vao `AppConfig` constructor - khong dung `new` trong @Bean

---

*Generated: 2026-04-28 | Target: `tour-catalog-service` | Frontend: giu nguyen 100%*
