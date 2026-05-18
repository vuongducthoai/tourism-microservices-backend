package com.tourism.booking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tourism.booking.dto.response.QueueHealthResponse;
import com.tourism.booking.service.DeadEventAdminService;
import com.tourism.booking.service.QueueHealthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Standalone MockMvc tests for DeadEventAdminController.
 * No Spring context -- purely Mockito + MockMvc standaloneSetup.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeadEventAdminController")
class DeadEventAdminControllerTest {

    MockMvc mockMvc;
    ObjectMapper objectMapper;

    @Mock DeadEventAdminService deadEventAdminService;
    @Mock QueueHealthService    queueHealthService;
    @InjectMocks DeadEventAdminController controller;

    private static final String BASE = "/api/bookings/admin/outbox";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .findAndRegisterModules();
        MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter(objectMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setMessageConverters(converter)
                .build();
    }

    @Test
    @DisplayName("GET /dead returns 200 with empty page when no dead events")
    void listDead_empty() throws Exception {
        when(deadEventAdminService.listDead(0, 20))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        mockMvc.perform(get(BASE + "/dead").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("GET /dead/count returns correct split map")
    void countDead_returnsMap() throws Exception {
        when(deadEventAdminService.countDead())
                .thenReturn(Map.of("coinRefund", 2L, "notification", 1L, "total", 3L));
        mockMvc.perform(get(BASE + "/dead/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coinRefund").value(2))
                .andExpect(jsonPath("$.notification").value(1))
                .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    @DisplayName("POST /retry/1 returns 200 and calls service")
    void retryOne_ok() throws Exception {
        doNothing().when(deadEventAdminService).retryOne(1L);
        mockMvc.perform(post(BASE + "/retry/1"))
                .andExpect(status().isOk());
        verify(deadEventAdminService).retryOne(1L);
    }

    @Test
    @DisplayName("POST /retry-all returns { retried: 5 }")
    void retryAll_returnsCount() throws Exception {
        when(deadEventAdminService.retryAll(null)).thenReturn(5);
        mockMvc.perform(post(BASE + "/retry-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retried").value(5));
    }

    @Test
    @DisplayName("POST /retry-all?routingKey=booking.coin.refund filters by routing key")
    void retryAll_withRoutingKey() throws Exception {
        when(deadEventAdminService.retryAll("booking.coin.refund")).thenReturn(2);
        mockMvc.perform(post(BASE + "/retry-all").param("routingKey", "booking.coin.refund"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retried").value(2));
        verify(deadEventAdminService).retryAll("booking.coin.refund");
    }

    @Test
    @DisplayName("GET /rabbitmq-health returns 200 HEALTHY with Vietnamese message")
    void queueHealth_healthy() throws Exception {
        QueueHealthResponse healthy = QueueHealthResponse.builder()
                .queue("booking.notification.queue")
                .ready(0).unacked(0).consumers(1).dlqReady(0)
                .status("HEALTHY")
                .message("He thong gui thong bao dang hoat dong binh thuong.")
                .checkedAt("2026-05-15T10:00:00")
                .build();
        when(queueHealthService.checkNotificationQueue()).thenReturn(healthy);
        mockMvc.perform(get(BASE + "/rabbitmq-health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HEALTHY"))
                .andExpect(jsonPath("$.queue").value("booking.notification.queue"))
                .andExpect(jsonPath("$.consumers").value(1))
                .andExpect(jsonPath("$.dlqReady").value(0))
                .andExpect(jsonPath("$.checkedAt").isNotEmpty());
    }

    @Test
    @DisplayName("GET /rabbitmq-health returns DLQ_ATTENTION when DLQ has messages")
    void queueHealth_dlqAttention() throws Exception {
        QueueHealthResponse dlqAlert = QueueHealthResponse.builder()
                .queue("booking.notification.queue")
                .ready(0).unacked(0).consumers(1).dlqReady(3)
                .status("DLQ_ATTENTION")
                .message("Co thong bao gui that bai nhieu lan.")
                .checkedAt("2026-05-15T10:05:00")
                .build();
        when(queueHealthService.checkNotificationQueue()).thenReturn(dlqAlert);
        mockMvc.perform(get(BASE + "/rabbitmq-health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DLQ_ATTENTION"))
                .andExpect(jsonPath("$.dlqReady").value(3));
    }

    @Test
    @DisplayName("GET /rabbitmq-health returns BROKER_DOWN when management API unreachable")
    void queueHealth_brokerDown() throws Exception {
        QueueHealthResponse brokerDown = QueueHealthResponse.builder()
                .queue("booking.notification.queue")
                .status("BROKER_DOWN")
                .message("Khong kiem tra duoc hang doi thong bao.")
                .checkedAt("2026-05-15T10:10:00")
                .build();
        when(queueHealthService.checkNotificationQueue()).thenReturn(brokerDown);
        mockMvc.perform(get(BASE + "/rabbitmq-health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BROKER_DOWN"));
    }
}