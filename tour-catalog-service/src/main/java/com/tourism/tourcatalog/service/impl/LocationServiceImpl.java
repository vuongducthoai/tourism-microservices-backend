package com.tourism.tourcatalog.service.impl;

import com.tourism.tourcatalog.dto.request.RegionRequest;
import com.tourism.tourcatalog.dto.response.DestinationResponse;
import com.tourism.tourcatalog.dto.response.LocationChatbotSyncResponse;
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

    /**
     * GET /api/locations/chatbot-sync
     * Lấy tất cả location active với đầy đủ thông tin (region, airport) để sync Pinecone.
     */
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
}

