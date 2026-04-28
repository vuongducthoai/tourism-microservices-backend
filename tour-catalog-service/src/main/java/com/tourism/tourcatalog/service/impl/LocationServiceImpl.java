package com.tourism.tourcatalog.service.impl;

import com.tourism.tourcatalog.dto.request.RegionRequest;
import com.tourism.tourcatalog.dto.response.DestinationResponse;
import com.tourism.tourcatalog.dto.response.LocationResponse;
import com.tourism.tourcatalog.entity.Location;
import com.tourism.tourcatalog.entity.Region;
import com.tourism.tourcatalog.repository.LocationRepository;
import com.tourism.tourcatalog.service.LocationService;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LocationServiceImpl implements LocationService {

    private final LocationRepository locationRepository;
    private final ModelMapper        modelMapper;

    /**
     * GET /api/locations/start-location
     * Location -> LocationResponse: imageUrl <- location.image (TypeMap trong AppConfig)
     */
    @Override
    @Transactional(readOnly = true)
    public List<LocationResponse> getStartLocations() {
        return locationRepository.findDistinctStartLocations().stream()
                .map(l -> modelMapper.map(l, LocationResponse.class))
                .collect(Collectors.toList());
    }

    /**
     * GET /api/locations/end-location
     */
    @Override
    @Transactional(readOnly = true)
    public List<LocationResponse> getEndLocations() {
        return locationRepository.findDistinctEndLocations().stream()
                .map(l -> modelMapper.map(l, LocationResponse.class))
                .collect(Collectors.toList());
    }

    /**
     * POST /api/locations/destinations-by-region
     *
     * Chuyển region string -> Region enum.
     * IllegalArgumentException nếu value không hợp lệ -> Spring tự trả 400.
     *
     * Location -> DestinationResponse:
     *   endPoint  <- location.name
     *   listImage <- location.image
     *   region    <- location.region.name()
     * (TypeMap được cấu hình trong AppConfig)
     */
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
}
