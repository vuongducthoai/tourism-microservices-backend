package com.tourism.tourcatalog.service.impl;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.tourism.tourcatalog.dto.request.admin.CreateTourRequest;
import com.tourism.tourcatalog.dto.request.admin.ItineraryDayRequest;
import com.tourism.tourcatalog.dto.request.admin.TourGeneralInfoRequest;
import com.tourism.tourcatalog.dto.response.ItineraryDayResponse;
import com.tourism.tourcatalog.dto.response.LocationResponse;
import com.tourism.tourcatalog.dto.response.admin.*;
import com.tourism.tourcatalog.entity.*;
import com.tourism.tourcatalog.repository.LocationRepository;
import com.tourism.tourcatalog.repository.TourRepository;
import com.tourism.tourcatalog.service.AdminTourService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminTourServiceImpl implements AdminTourService {

    private final TourRepository tourRepository;
    private final LocationRepository locationRepository;
    private final Cloudinary cloudinary;

    // ─── List / Search ──────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedAdminResponse<AdminTourListItem> getAllTours(int page, int size, String sortBy, String sortDir) {
        Sort sort = sortDir.equalsIgnoreCase("ASC")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Page<Tour> tourPage = tourRepository.findAll(PageRequest.of(page, size, sort));
        return buildPagedResponse(tourPage);
    }

    @Override
    @Transactional(readOnly = true)
    public PagedAdminResponse<AdminTourListItem> searchTours(String keyword, int page, int size) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        Page<Tour> all = tourRepository.findAll(PageRequest.of(page, size, Sort.by("tourID").descending()));
        List<Tour> filtered = all.getContent().stream()
                .filter(t -> (t.getIsDeleted() == null || !t.getIsDeleted())
                        && (t.getTourName().toLowerCase().contains(kw)
                        || t.getTourCode().toLowerCase().contains(kw)))
                .collect(Collectors.toList());
        // Build paged response from filtered (simple approach - search on current page)
        List<AdminTourListItem> items = filtered.stream().map(this::toListItem).collect(Collectors.toList());
        return PagedAdminResponse.<AdminTourListItem>builder()
                .content(items)
                .page(page)
                .size(size)
                .totalPages(all.getTotalPages())
                .totalItems(all.getTotalElements())
                .build();
    }

    // ─── Get Detail ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public AdminTourDetailResponse getTourById(Integer tourId) {
        Tour tour = findTourById(tourId);
        return toDetailResponse(tour);
    }

    // ─── Create ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public AdminTourDetailResponse createTour(CreateTourRequest request) {
        TourGeneralInfoRequest info = request.getGeneralInfo();
        Tour tour = new Tour();
        applyGeneralInfo(tour, info);

        if (request.getItineraryDays() != null) {
            List<ItineraryDay> days = new ArrayList<>();
            for (int i = 0; i < request.getItineraryDays().size(); i++) {
                ItineraryDayRequest dr = request.getItineraryDays().get(i);
                ItineraryDay day = new ItineraryDay();
                day.setDayNumber(dr.getDayNumber() != null ? dr.getDayNumber() : i + 1);
                day.setTitle(dr.getTitle());
                day.setDetails(dr.getDetails());
                day.setMeals(dr.getMeals());
                day.setTour(tour);
                days.add(day);
            }
            tour.setItineraryDays(days);
        }

        Tour saved = tourRepository.save(tour);
        return toDetailResponse(saved);
    }

    // ─── Update General Info ─────────────────────────────────────────────────────

    @Override
    @Transactional
    public AdminTourDetailResponse updateGeneralInfo(Integer tourId, TourGeneralInfoRequest request) {
        Tour tour = findTourById(tourId);
        applyGeneralInfo(tour, request);
        Tour saved = tourRepository.save(tour);
        return toDetailResponse(saved);
    }

    // ─── Update Itinerary ────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void updateItinerary(Integer tourId, List<ItineraryDayRequest> days) {
        Tour tour = findTourById(tourId);
        if (tour.getItineraryDays() == null) {
            tour.setItineraryDays(new ArrayList<>());
        }
        tour.getItineraryDays().clear();
        tourRepository.saveAndFlush(tour);

        for (int i = 0; i < days.size(); i++) {
            ItineraryDayRequest dr = days.get(i);
            ItineraryDay day = new ItineraryDay();
            day.setDayNumber(dr.getDayNumber() != null ? dr.getDayNumber() : i + 1);
            day.setTitle(dr.getTitle());
            day.setDetails(dr.getDetails());
            day.setMeals(dr.getMeals());
            day.setTour(tour);
            tour.getItineraryDays().add(day);
        }
        tourRepository.save(tour);
    }

    // ─── Upload Image ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public AdminImageResponse uploadImage(Integer tourId, MultipartFile file, boolean isMain) {
        Tour tour = findTourById(tourId);
        String url = uploadToCloudinary(file, "tourism_app_tours/tour_images");

        if (isMain && tour.getImages() != null) {
            tour.getImages().forEach(img -> img.setIsMainImage(false));
        }

        TourImage image = new TourImage();
        image.setImageUrl(url);
        image.setIsMainImage(isMain);
        image.setTour(tour);

        if (tour.getImages() == null) tour.setImages(new ArrayList<>());
        tour.getImages().add(image);
        tourRepository.save(tour);

        return AdminImageResponse.builder()
                .imageUrl(url)
                .isMainImage(isMain)
                .build();
    }

    // ─── Upload Media ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public AdminMediaResponse uploadMedia(Integer tourId, MultipartFile file, String mediaType) {
        Tour tour = findTourById(tourId);
        String url = uploadToCloudinary(file, "tourism_app_tours/tour_media");

        TourMedia media = new TourMedia();
        media.setMediaUrl(url);
        media.setTour(tour);

        boolean isVideo = "video".equalsIgnoreCase(mediaType);
        if (isVideo) {
            boolean hasPrimaryVideo = tour.getMediaList() != null && tour.getMediaList().stream()
            .anyMatch(m -> Boolean.TRUE.equals(m.getIsPrimary()));
            media.setIsPrimary(!hasPrimaryVideo);
        }

        if (tour.getMediaList() == null) tour.setMediaList(new ArrayList<>());
        tour.getMediaList().add(media);
        tourRepository.save(tour);

        return AdminMediaResponse.builder()
                .mediaUrl(url)
                .build();
    }

    // ─── Delete ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void deleteTour(Integer tourId) {
        Tour tour = findTourById(tourId);
        tour.setIsDeleted(true);
        tourRepository.save(tour);
    }

    // ─── Utilities ───────────────────────────────────────────────────────────────

    @Override
    public boolean checkTourCodeExists(String tourCode) {
        return tourRepository.findAll().stream()
                .anyMatch(t -> t.getTourCode().equalsIgnoreCase(tourCode)
                        && (t.getIsDeleted() == null || !t.getIsDeleted()));
    }

    @Override
    public List<LocationResponse> getAllLocations() {
        return locationRepository.findAll().stream()
                .filter(Location::isStatus)
                .map(l -> new LocationResponse(l.getLocationID(), l.getName(), l.getImage(), l.getDescription()))
                .collect(Collectors.toList());
    }

    // ─── Private helpers ─────────────────────────────────────────────────────────

    private Tour findTourById(Integer tourId) {
        return tourRepository.findById(tourId)
                .filter(t -> t.getIsDeleted() == null || !t.getIsDeleted())
                .orElseThrow(() -> new RuntimeException("Tour not found: " + tourId));
    }

    private void applyGeneralInfo(Tour tour, TourGeneralInfoRequest info) {
        tour.setTourName(info.getTourName());
        tour.setTourCode(info.getTourCode());
        tour.setDuration(info.getDuration());
        tour.setTransportation(info.getTransportation());
        tour.setAttractions(info.getAttractions());
        tour.setMeals(info.getMeals());
        tour.setIdealTime(info.getIdealTime());
        tour.setTripTransportation(info.getTripTransportation());
        tour.setSuitableCustomer(info.getSuitableCustomer());
        tour.setHotel(info.getHotel());
        tour.setStatus(info.getStatus() != null ? info.getStatus() : true);

        Location start = locationRepository.findById(info.getStartLocationId())
                .orElseThrow(() -> new RuntimeException("Start location not found: " + info.getStartLocationId()));
        Location end = locationRepository.findById(info.getEndLocationId())
                .orElseThrow(() -> new RuntimeException("End location not found: " + info.getEndLocationId()));
        tour.setStartLocation(start);
        tour.setEndLocation(end);
    }

    private AdminTourListItem toListItem(Tour tour) {
        String mainImg = null;
        if (tour.getImages() != null && !tour.getImages().isEmpty()) {
            mainImg = tour.getImages().stream()
                    .filter(img -> Boolean.TRUE.equals(img.getIsMainImage()))
                    .map(TourImage::getImageUrl)
                    .findFirst()
                    .orElse(tour.getImages().get(0).getImageUrl());
        }
        return AdminTourListItem.builder()
                .tourID(tour.getTourID())
                .tourCode(tour.getTourCode())
                .tourName(tour.getTourName())
                .duration(tour.getDuration())
                .transportation(tour.getTransportation())
                .startLocationName(tour.getStartLocation() != null ? tour.getStartLocation().getName() : null)
                .endLocationName(tour.getEndLocation() != null ? tour.getEndLocation().getName() : null)
                .mainImageUrl(mainImg)
                .status(tour.getStatus())
                .createdAt(tour.getCreatedAt())
                .departureCount(tour.getDepartures() != null ? tour.getDepartures().size() : 0)
                .build();
    }

    private AdminTourDetailResponse toDetailResponse(Tour tour) {
        List<AdminImageResponse> images = tour.getImages() == null ? new ArrayList<>() :
                tour.getImages().stream()
                        .map(img -> AdminImageResponse.builder()
                                .tourImageID(img.getTourImageID())
                                .imageUrl(img.getImageUrl())
                                .isMainImage(img.getIsMainImage())
                                .build())
                        .collect(Collectors.toList());

        List<AdminMediaResponse> mediaList = tour.getMediaList() == null ? new ArrayList<>() :
                tour.getMediaList().stream()
                        .map(m -> AdminMediaResponse.builder()
                                .tourMediaID(m.getMediaId())
                                .mediaUrl(m.getMediaUrl())
                                .build())
                        .collect(Collectors.toList());

        List<ItineraryDayResponse> itinerary = tour.getItineraryDays() == null ? new ArrayList<>() :
                tour.getItineraryDays().stream()
                        .sorted((a, b) -> Integer.compare(
                                a.getDayNumber() != null ? a.getDayNumber() : 0,
                                b.getDayNumber() != null ? b.getDayNumber() : 0))
                        .map(d -> ItineraryDayResponse.builder()
                                .dayNumber(d.getDayNumber())
                                .title(d.getTitle())
                                .details(d.getDetails())
                                .meals(d.getMeals())
                                .build())
                        .collect(Collectors.toList());

        return AdminTourDetailResponse.builder()
                .tourID(tour.getTourID())
                .tourCode(tour.getTourCode())
                .tourName(tour.getTourName())
                .duration(tour.getDuration())
                .transportation(tour.getTransportation())
                .startLocationId(tour.getStartLocation() != null ? tour.getStartLocation().getLocationID() : null)
                .startLocationName(tour.getStartLocation() != null ? tour.getStartLocation().getName() : null)
                .endLocationId(tour.getEndLocation() != null ? tour.getEndLocation().getLocationID() : null)
                .endLocationName(tour.getEndLocation() != null ? tour.getEndLocation().getName() : null)
                .attractions(tour.getAttractions())
                .meals(tour.getMeals())
                .idealTime(tour.getIdealTime())
                .tripTransportation(tour.getTripTransportation())
                .suitableCustomer(tour.getSuitableCustomer())
                .hotel(tour.getHotel())
                .status(tour.getStatus())
                .images(images)
                .mediaList(mediaList)
                .itineraryDays(itinerary)
                .build();
    }

    private PagedAdminResponse<AdminTourListItem> buildPagedResponse(Page<Tour> tourPage) {
        List<AdminTourListItem> items = tourPage.getContent().stream()
                .filter(t -> t.getIsDeleted() == null || !t.getIsDeleted())
                .map(this::toListItem)
                .collect(Collectors.toList());
        return PagedAdminResponse.<AdminTourListItem>builder()
                .content(items)
                .page(tourPage.getNumber())
                .size(tourPage.getSize())
                .totalPages(tourPage.getTotalPages())
                .totalItems(tourPage.getTotalElements())
                .build();
    }

    @SuppressWarnings("unchecked")
    private String uploadToCloudinary(MultipartFile file, String folder) {
        try {
            Map<String, Object> result = cloudinary.uploader().upload(
                    file.getBytes(),
                    ObjectUtils.asMap("folder", folder, "resource_type", "auto")
            );
            return (String) result.get("secure_url");
        } catch (IOException e) {
            throw new RuntimeException("Upload failed: " + e.getMessage(), e);
        }
    }
}
