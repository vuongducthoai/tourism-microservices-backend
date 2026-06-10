package com.tourism.tourcatalog.service.impl;

import com.tourism.tourcatalog.dto.request.admin.CreateDepartureRequest;
import com.tourism.tourcatalog.dto.request.admin.DeparturePricingRequest;
import com.tourism.tourcatalog.dto.request.admin.DepartureTransportRequest;
import com.tourism.tourcatalog.dto.response.admin.AdminDepartureDetailResponse;
import com.tourism.tourcatalog.dto.response.admin.AdminDeparturePricingItem;
import com.tourism.tourcatalog.dto.response.admin.AdminDepartureListItem;
import com.tourism.tourcatalog.dto.response.admin.AdminDepartureTransportItem;
import com.tourism.tourcatalog.entity.*;
import com.tourism.tourcatalog.repository.PolicyTemplateRepository;
import com.tourism.tourcatalog.repository.TourDepartureRepository;
import com.tourism.tourcatalog.repository.TourRepository;
import com.tourism.tourcatalog.service.AdminDepartureService;
import com.tourism.tourcatalog.service.ChatbotSyncEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminDepartureServiceImpl implements AdminDepartureService {

    private final TourDepartureRepository departureRepository;
    private final TourRepository tourRepository;
    private final PolicyTemplateRepository policyTemplateRepository;
    private final ChatbotSyncEventPublisher chatbotSyncEventPublisher;
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_DATE_TIME;
    private static final DateTimeFormatter DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getAllDepartures(int page, int size, Boolean status, String startDate, String endDate) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("departureDate").ascending());

        Page<TourDeparture> departurePage;

        if (startDate != null && endDate != null) {
            LocalDateTime start = LocalDateTime.parse(startDate, ISO_FORMATTER);
            LocalDateTime end = LocalDateTime.parse(endDate, ISO_FORMATTER);

            if (status != null) {
                departurePage = departureRepository.findAllByStatusAndIsDeletedFalseAndDepartureDateBetween(
                        status, start, end, pageable);
            } else {
                departurePage = departureRepository.findAllByIsDeletedFalseAndDepartureDateBetween(
                        start, end, pageable);
            }
        } else {
            if (status != null) {
                departurePage = departureRepository.findAllByStatusAndIsDeletedFalse(status, pageable);
            } else {
                departurePage = departureRepository.findAllByIsDeletedFalse(pageable);
            }
        }

        List<AdminDepartureListItem> items = departurePage.getContent().stream()
                .map(this::toListItem)
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", items);
        result.put("totalPages", departurePage.getTotalPages());
        result.put("totalItems", departurePage.getTotalElements());
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public AdminDepartureDetailResponse getDepartureById(Integer id) {
        TourDeparture departure = departureRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Departure not found: " + id));
        if (departure.getIsDeleted()) {
            throw new RuntimeException("Departure not found: " + id);
        }
        return toDetailResponse(departure);
    }

    @Override
    @Transactional
    public AdminDepartureDetailResponse createDeparture(CreateDepartureRequest request) {
        Tour tour = tourRepository.findById(request.getTourId())
                .orElseThrow(() -> new RuntimeException("Tour not found: " + request.getTourId()));

        TourDeparture departure = new TourDeparture();
        departure.setTour(tour);
        departure.setDepartureDate(LocalDateTime.parse(request.getDepartureDate(), ISO_FORMATTER));
        departure.setAvailableSlots(request.getAvailableSlots());
        departure.setStatus(request.getStatus() != null ? request.getStatus() : true);
        departure.setTourGuideInfo(request.getTourGuideInfo());
        departure.setCouponId(null);

        if (request.getPolicyTemplateId() != null) {
            PolicyTemplate template = policyTemplateRepository.findById(request.getPolicyTemplateId())
                    .orElseThrow(() -> new RuntimeException("Policy template not found: " + request.getPolicyTemplateId()));
            departure.setPolicyTemplate(template);
        }

        if (request.getPricings() != null) {
            List<DeparturePricing> pricings = new ArrayList<>();
            for (DeparturePricingRequest pr : request.getPricings()) {
                DeparturePricing pricing = new DeparturePricing();
                pricing.setPassengerType(pr.getPassengerType());
                pricing.setOriginalPrice(pr.getOriginalPrice());
                pricing.setSalePrice(pr.getSalePrice());
                pricing.setAgeDescription(getAgeDescription(pr.getPassengerType()));
                pricing.setTourDeparture(departure);
                pricings.add(pricing);
            }
            departure.setPricings(pricings);
        }

        if (request.getOutboundTransport() != null || request.getInboundTransport() != null) {
            List<DepartureTransport> transports = new ArrayList<>();
            if (request.getOutboundTransport() != null) {
                transports.add(buildTransport(request.getOutboundTransport(), departure, TransportType.OUTBOUND));
            }
            if (request.getInboundTransport() != null) {
                transports.add(buildTransport(request.getInboundTransport(), departure, TransportType.INBOUND));
            }
            departure.setTransports(transports);
        }

        TourDeparture saved = departureRepository.save(departure);
        chatbotSyncEventPublisher.publish("departure", saved.getDepartureID(), saved.getTour().getTourID(), "CREATE");
        return toDetailResponse(saved);
    }

    @Override
    @Transactional
    public AdminDepartureDetailResponse updateDeparture(Integer id, CreateDepartureRequest request) {
        TourDeparture departure = departureRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Departure not found: " + id));
        if (departure.getIsDeleted()) {
            throw new RuntimeException("Departure not found: " + id);
        }

        departure.setDepartureDate(LocalDateTime.parse(request.getDepartureDate(), ISO_FORMATTER));
        departure.setAvailableSlots(request.getAvailableSlots());
        departure.setStatus(request.getStatus() != null ? request.getStatus() : true);
        departure.setTourGuideInfo(request.getTourGuideInfo());
        departure.setCouponId(null);

        if (request.getPolicyTemplateId() != null) {
            PolicyTemplate template = policyTemplateRepository.findById(request.getPolicyTemplateId())
                    .orElseThrow(() -> new RuntimeException("Policy template not found: " + request.getPolicyTemplateId()));
            departure.setPolicyTemplate(template);
        } else {
            departure.setPolicyTemplate(null);
        }

        if (request.getPricings() != null) {
            departure.getPricings().clear();
            for (DeparturePricingRequest pr : request.getPricings()) {
                DeparturePricing pricing = new DeparturePricing();
                pricing.setPassengerType(pr.getPassengerType());
                pricing.setOriginalPrice(pr.getOriginalPrice());
                pricing.setSalePrice(pr.getSalePrice());
                pricing.setAgeDescription(getAgeDescription(pr.getPassengerType()));
                pricing.setTourDeparture(departure);
                departure.getPricings().add(pricing);
            }
        }

        if (request.getOutboundTransport() != null || request.getInboundTransport() != null) {
            departure.getTransports().clear();
            if (request.getOutboundTransport() != null) {
                departure.getTransports().add(buildTransport(request.getOutboundTransport(), departure, TransportType.OUTBOUND));
            }
            if (request.getInboundTransport() != null) {
                departure.getTransports().add(buildTransport(request.getInboundTransport(), departure, TransportType.INBOUND));
            }
        }

        TourDeparture updated = departureRepository.save(departure);
        chatbotSyncEventPublisher.publish("departure", updated.getDepartureID(), updated.getTour().getTourID(), "UPDATE");
        return toDetailResponse(updated);
    }

    @Override
    @Transactional
    public void deleteDeparture(Integer id) {
        TourDeparture departure = departureRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Departure not found: " + id));
        departure.setIsDeleted(true);
        departureRepository.save(departure);
        chatbotSyncEventPublisher.publish("departure", departure.getDepartureID(), departure.getTour().getTourID(), "DELETE");
    }

    @Override
    @Transactional
    public AdminDepartureDetailResponse cloneDeparture(Integer id, String newDepartureDate) {
        TourDeparture original = departureRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Departure not found: " + id));
        if (original.getIsDeleted()) {
            throw new RuntimeException("Departure not found: " + id);
        }

        TourDeparture cloned = new TourDeparture();
        cloned.setTour(original.getTour());
        cloned.setDepartureDate(LocalDateTime.parse(newDepartureDate, ISO_FORMATTER));
        cloned.setAvailableSlots(original.getAvailableSlots());
        cloned.setStatus(original.getStatus());
        cloned.setTourGuideInfo(original.getTourGuideInfo());
        cloned.setCouponId(original.getCouponId());
        cloned.setPolicyTemplate(original.getPolicyTemplate());

        if (original.getPricings() != null && !original.getPricings().isEmpty()) {
            List<DeparturePricing> newPricings = new ArrayList<>();
            for (DeparturePricing p : original.getPricings()) {
                DeparturePricing newPricing = new DeparturePricing();
                newPricing.setPassengerType(p.getPassengerType());
                newPricing.setAgeDescription(p.getAgeDescription());
                newPricing.setOriginalPrice(p.getOriginalPrice());
                newPricing.setSalePrice(p.getSalePrice());
                newPricing.setTourDeparture(cloned);
                newPricings.add(newPricing);
            }
            cloned.setPricings(newPricings);
        }

        if (original.getTransports() != null && !original.getTransports().isEmpty()) {
            List<DepartureTransport> newTransports = new ArrayList<>();
            for (DepartureTransport t : original.getTransports()) {
                DepartureTransport newTransport = new DepartureTransport();
                newTransport.setTransportType(t.getTransportType());
                newTransport.setVehicleType(t.getVehicleType());
                newTransport.setVehicleName(t.getVehicleName());
                newTransport.setStartPoint(t.getStartPoint());
                newTransport.setEndPoint(t.getEndPoint());
                newTransport.setDepartTime(t.getDepartTime());
                newTransport.setArrivalTime(t.getArrivalTime());
                newTransport.setTourDeparture(cloned);
                newTransports.add(newTransport);
            }
            cloned.setTransports(newTransports);
        }

        TourDeparture saved = departureRepository.save(cloned);
        chatbotSyncEventPublisher.publish("departure", saved.getDepartureID(), saved.getTour().getTourID(), "CREATE");
        return toDetailResponse(saved);
    }

    private DepartureTransport buildTransport(DepartureTransportRequest req, TourDeparture departure, TransportType type) {
        DepartureTransport transport = new DepartureTransport();
        transport.setTransportType(type);
        transport.setVehicleType(VehicleType.valueOf(req.getVehicleType()));
        transport.setVehicleName(req.getTransportCode());
        transport.setStartPoint(req.getStartPoint());
        transport.setEndPoint(req.getEndPoint());
        transport.setDepartTime(LocalDateTime.parse(req.getDepartTime(), ISO_FORMATTER));
        transport.setArrivalTime(LocalDateTime.parse(req.getArrivalTime(), ISO_FORMATTER));
        transport.setTourDeparture(departure);
        return transport;
    }

    private String getAgeDescription(String passengerType) {
        return switch (passengerType) {
            case "ADULT" -> "Người lớn";
            case "CHILD" -> "Trẻ em (2-11 tuổi)";
            case "INFANT" -> "Em bé (dưới 2 tuổi)";
            case "SINGLE_SUPPLEMENT" -> "Phụ cấp phòng đơn";
            default -> passengerType;
        };
    }

    private AdminDepartureListItem toListItem(TourDeparture departure) {
        AdminDepartureListItem item = new AdminDepartureListItem();
        item.setDepartureID(departure.getDepartureID());
        item.setTourName(departure.getTour().getTourName());
        item.setTourCode(departure.getTour().getTourCode());
        item.setTourDuration(departure.getTour().getDuration());
        item.setDepartureDate(departure.getDepartureDate().format(DISPLAY_FORMATTER));
        item.setAvailableSlots(departure.getAvailableSlots());
        item.setTotalBookings(0);

        if (departure.getPricings() != null && !departure.getPricings().isEmpty()) {
            BigDecimal minPrice = departure.getPricings().stream()
                    .map(DeparturePricing::getSalePrice)
                    .min(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
            item.setLowestPrice(minPrice);
        } else {
            item.setLowestPrice(BigDecimal.ZERO);
        }

        item.setHasOutboundTransport(hasTransport(departure, TransportType.OUTBOUND));
        item.setHasInboundTransport(hasTransport(departure, TransportType.INBOUND));
        item.setStatus(departure.getStatus());
        item.setCreatedAt(departure.getCreatedAt());
        return item;
    }

    private AdminDepartureDetailResponse toDetailResponse(TourDeparture departure) {
        AdminDepartureDetailResponse response = new AdminDepartureDetailResponse();
        response.setDepartureID(departure.getDepartureID());
        response.setTourName(departure.getTour().getTourName());
        response.setTourCode(departure.getTour().getTourCode());
        response.setTourDuration(departure.getTour().getDuration());
        response.setDepartureDate(departure.getDepartureDate().format(DISPLAY_FORMATTER));
        response.setAvailableSlots(departure.getAvailableSlots());
        response.setBookedSlots(0);
        response.setTourGuideInfo(departure.getTourGuideInfo());
        response.setCouponCode(null);
        response.setCreatedAt(departure.getCreatedAt());
        response.setUpdatedAt(departure.getUpdatedAt());
        response.setStatus(departure.getStatus());

        if (departure.getPolicyTemplate() != null) {
            response.setPolicyTemplateId(departure.getPolicyTemplate().getPolicyTemplateID());
            response.setPolicyTemplateName(departure.getPolicyTemplate().getTemplateName());
        }

        if (departure.getPricings() != null) {
            response.setPricings(departure.getPricings().stream()
                    .map(p -> new AdminDeparturePricingItem(
                            p.getPricingID(),
                            p.getPassengerType(),
                            p.getAgeDescription(),
                            p.getOriginalPrice(),
                            p.getSalePrice()
                    ))
                    .collect(Collectors.toList()));
        }

        if (departure.getTransports() != null) {
            for (DepartureTransport t : departure.getTransports()) {
                AdminDepartureTransportItem item = new AdminDepartureTransportItem(
                        t.getTransportType() != null ? t.getTransportType().name() : null,
                        t.getVehicleType() != null ? t.getVehicleType().name() : null,
                        t.getVehicleName(),
                        t.getVehicleName(),
                        t.getStartPoint(),
                        t.getEndPoint(),
                        t.getDepartTime() != null ? t.getDepartTime().format(DISPLAY_FORMATTER) : null,
                        t.getArrivalTime() != null ? t.getArrivalTime().format(DISPLAY_FORMATTER) : null
                );
                if (t.getTransportType() == TransportType.OUTBOUND) {
                    response.setOutboundTransport(item);
                } else if (t.getTransportType() == TransportType.INBOUND) {
                    response.setInboundTransport(item);
                }
            }
        }

        return response;
    }

    private boolean hasTransport(TourDeparture departure, TransportType type) {
        if (departure.getTransports() == null) return false;
        return departure.getTransports().stream()
                .anyMatch(t -> t.getTransportType() == type);
    }
}
