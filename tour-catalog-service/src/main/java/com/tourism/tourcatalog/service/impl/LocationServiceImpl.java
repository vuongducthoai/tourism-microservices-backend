package com.tourism.tourcatalog.service.impl;

import com.tourism.tourcatalog.dto.request.RegionRequest;
import com.tourism.tourcatalog.dto.request.admin.LocationRequest;
import com.tourism.tourcatalog.dto.response.DestinationResponse;
import com.tourism.tourcatalog.dto.response.LocationChatbotSyncResponse;
import com.tourism.tourcatalog.dto.response.LocationResponse;
import com.tourism.tourcatalog.dto.response.admin.AdminLocationResponse;
import com.tourism.tourcatalog.entity.Location;
import com.tourism.tourcatalog.entity.Region;
import com.tourism.tourcatalog.exception.DuplicateResourceException;
import com.tourism.tourcatalog.exception.ResourceInUseException;
import com.tourism.tourcatalog.exception.ResourceNotFoundException;
import com.tourism.tourcatalog.repository.LocationRepository;
import com.tourism.tourcatalog.service.ChatbotSyncEventPublisher;
import com.tourism.tourcatalog.service.FileStorageService;
import com.tourism.tourcatalog.service.LocationService;
import com.tourism.tourcatalog.util.VietnamAirportUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LocationServiceImpl implements LocationService {

    private final LocationRepository locationRepository;
    private final ModelMapper modelMapper;
    private final FileStorageService fileStorageService;
    private final ChatbotSyncEventPublisher chatbotSyncEventPublisher;

    @Override
    @Transactional(readOnly = true)
    public List<LocationResponse> getStartLocations() {
        return locationRepository.findDistinctStartLocations().stream()
                .map(l -> modelMapper.map(l, LocationResponse.class))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocationResponse> getEndLocations() {
        return locationRepository.findDistinctEndLocations().stream()
                .map(l -> modelMapper.map(l, LocationResponse.class))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DestinationResponse> getDestinationsByRegion(RegionRequest request) {
        Region region = Region.valueOf(request.getRegion().toUpperCase());
        return locationRepository.findByRegionActive(region).stream()
                .map(l -> {
                    DestinationResponse dr = modelMapper.map(l, DestinationResponse.class);
                    dr.setRegion(l.getRegion() != null ? l.getRegion().name() : null);
                    return dr;
                })
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<LocationChatbotSyncResponse> getAllLocationsForChatbotSync() {
        return locationRepository.findAll().stream()
                .filter(Location::isStatus)
                .map(l -> LocationChatbotSyncResponse.builder()
                        .locationID(l.getLocationID())
                        .name(l.getName())
                        .imageUrl(l.getImage())
                        .description(l.getDescription())
                        .region(l.getRegion() != null ? l.getRegion().name() : null)
                        .airportCode(l.getAirportCode())
                        .airportName(l.getAirportName())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LocationResponse> getNationalLocations(int page, int size) {
        var locations = locationRepository.findDistinctStartLocations().stream()
                .map(l -> {
                    LocationResponse lr = new LocationResponse();
                    lr.setLocationID(l.getLocationID());
                    lr.setName(l.getName());
                    lr.setImageUrl(l.getImage());
                    lr.setDescription(l.getDescription());
                    return lr;
                })
                .skip((long) page * size)
                .limit(size)
                .collect(Collectors.toList());

        long total = (long) locationRepository.findDistinctStartLocations().size();
        return new PageImpl<>(locations, PageRequest.of(page, size), total);
    }

    // ===================== Admin CRUD =====================

    @Override
    @Transactional(readOnly = true)
    public Page<AdminLocationResponse> getAllLocations(Pageable pageable, String search, String regionStr) {
        Region region = null;
        if (regionStr != null && !regionStr.isBlank()) {
            try {
                region = Region.valueOf(regionStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid region: {}", regionStr);
            }
        }
        return locationRepository.searchLocations(search, region, pageable)
                .map(this::mapToAdminResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminLocationResponse> getAirportNational(Pageable pageable) {
        return locationRepository.getAllAirportNational(pageable)
                .map(this::mapToAdminResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminLocationResponse getLocationById(Integer id) {
        return mapToAdminResponse(findById(id));
    }

    @Override
    @Transactional
    public AdminLocationResponse createLocation(LocationRequest request) {
        if (locationRepository.existsByName(request.getName())) {
            throw new DuplicateResourceException("Địa điểm '" + request.getName() + "' đã tồn tại");
        }
        if (locationRepository.existsBySlug(request.getSlug())) {
            throw new DuplicateResourceException("Slug '" + request.getSlug() + "' đã được sử dụng");
        }

        if (request.getAirportCode() == null || request.getAirportName() == null) {
            VietnamAirportUtils.getAirportInfo(request.getName()).ifPresent(info -> {
                if (request.getAirportCode() == null) request.setAirportCode(info.getCode());
                if (request.getAirportName() == null) request.setAirportName(info.getName());
            });
        }

        Location location = new Location();
        location.setName(request.getName());
        location.setSlug(request.getSlug());
        location.setRegion(request.getRegion());
        location.setDescription(request.getDescription());
        location.setAirportCode(request.getAirportCode());
        location.setAirportName(request.getAirportName());
        location.setImage(request.getImage());
        Location saved = locationRepository.save(location);
        chatbotSyncEventPublisher.publish("location", saved.getLocationID(), null, "CREATE");
        return mapToAdminResponse(saved);
    }

    @Override
    @Transactional
    public AdminLocationResponse updateLocation(Integer id, LocationRequest request) {
        Location location = findById(id);

        if (!location.getName().equals(request.getName()) &&
                locationRepository.existsByNameAndNotId(request.getName(), id)) {
            throw new DuplicateResourceException("Địa điểm '" + request.getName() + "' đã tồn tại");
        }
        if (!location.getSlug().equals(request.getSlug()) &&
                locationRepository.existsBySlugAndNotId(request.getSlug(), id)) {
            throw new DuplicateResourceException("Slug '" + request.getSlug() + "' đã được sử dụng");
        }

        if (!location.getName().equals(request.getName()) &&
                (request.getAirportCode() == null || request.getAirportName() == null)) {
            VietnamAirportUtils.getAirportInfo(request.getName()).ifPresent(info -> {
                if (request.getAirportCode() == null) request.setAirportCode(info.getCode());
                if (request.getAirportName() == null) request.setAirportName(info.getName());
            });
        }

        location.setName(request.getName());
        location.setSlug(request.getSlug());
        location.setRegion(request.getRegion());
        location.setDescription(request.getDescription());
        location.setAirportCode(request.getAirportCode());
        location.setAirportName(request.getAirportName());
        if (request.getImage() != null) location.setImage(request.getImage());

        Location saved = locationRepository.save(location);
        chatbotSyncEventPublisher.publish("location", saved.getLocationID(), null, "UPDATE");
        return mapToAdminResponse(saved);
    }

    @Override
    @Transactional
    public void deleteLocation(Integer id) {
        Location location = findById(id);

        Long startCount = locationRepository.countToursAsStartPoint(id);
        Long endCount = locationRepository.countToursAsEndPoint(id);

        if (startCount > 0 || endCount > 0) {
            throw new ResourceInUseException(
                    String.format("Không thể xóa địa điểm '%s'. Đang có %d tour sử dụng làm điểm xuất phát và %d tour sử dụng làm điểm đến",
                            location.getName(), startCount, endCount));
        }
        location.setStatus(false);
        locationRepository.save(location);
        chatbotSyncEventPublisher.publish("location", location.getLocationID(), null, "DELETE");
    }

    @Override
    @Transactional
    public String uploadImage(Integer id, MultipartFile file) throws IOException {
        Location location = findById(id);
        String imageUrl = fileStorageService.saveFile(file);
        location.setImage(imageUrl);
        locationRepository.save(location);
        chatbotSyncEventPublisher.publish("location", location.getLocationID(), null, "UPDATE");
        return imageUrl;
    }

    private Location findById(Integer id) {
        return locationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy địa điểm với ID: " + id));
    }

    private AdminLocationResponse mapToAdminResponse(Location l) {
        Long startCount = locationRepository.countToursAsStartPoint(l.getLocationID());
        Long endCount = locationRepository.countToursAsEndPoint(l.getLocationID());
        return AdminLocationResponse.builder()
                .locationID(l.getLocationID())
                .name(l.getName())
                .slug(l.getSlug())
                .image(l.getImage())
                .region(l.getRegion())
                .description(l.getDescription())
                .airportCode(l.getAirportCode())
                .airportName(l.getAirportName())
                .createdAt(l.getCreatedAt())
                .updatedAt(l.getUpdatedAt())
                .toursAsStartPoint(startCount)
                .toursAsEndPoint(endCount)
                .status(l.isStatus())
                .build();
    }
}

