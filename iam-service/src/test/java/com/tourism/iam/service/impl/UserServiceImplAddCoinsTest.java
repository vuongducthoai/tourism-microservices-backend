package com.tourism.iam.service.impl;

import com.tourism.iam.entity.User;
import com.tourism.iam.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UserServiceImpl.addCoins.
 * Verifies the IAM coin-balance update logic used by booking-service
 * after a coin-refund cancellation.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplAddCoinsTest {

    @Mock UserRepository userRepository;

    // Cloudinary is required by constructor — mock it
    @Mock com.cloudinary.Cloudinary cloudinary;

    @InjectMocks UserServiceImpl service;

    private User makeUser(int id, BigDecimal coinBalance) {
        User u = new User();
        u.setUserID(id);
        u.setFullName("Test User");
        u.setEmail("test@example.com");
        u.setCoinBalance(coinBalance);
        return u;
    }

    @Nested
    @DisplayName("addCoins")
    class AddCoinsTests {

        @Test
        @DisplayName("HAPPY PATH: user has 0 coins → adding 900 → balance becomes 900")
        void addCoins_zeroBalance_addsCorrectly() {
            User user = makeUser(1, BigDecimal.ZERO);
            when(userRepository.findById(1)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.addCoins(1, new BigDecimal("900"));

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getCoinBalance()).isEqualByComparingTo("900");
        }

        @Test
        @DisplayName("HAPPY PATH: user already has 500 coins → adding 300 → balance becomes 800")
        void addCoins_existingBalance_accumulates() {
            User user = makeUser(2, new BigDecimal("500"));
            when(userRepository.findById(2)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.addCoins(2, new BigDecimal("300"));

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getCoinBalance()).isEqualByComparingTo("800");
        }

        @Test
        @DisplayName("EDGE CASE: user coinBalance is null → treated as 0, then adds correctly")
        void addCoins_nullBalance_treatedAsZero() {
            User user = makeUser(3, null);
            when(userRepository.findById(3)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.addCoins(3, new BigDecimal("100"));

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getCoinBalance()).isEqualByComparingTo("100");
        }

        @Test
        @DisplayName("EDGE CASE: adding 0 coins → balance unchanged")
        void addCoins_zeroAmount_balanceUnchanged() {
            User user = makeUser(4, new BigDecimal("250"));
            when(userRepository.findById(4)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.addCoins(4, BigDecimal.ZERO);

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getCoinBalance()).isEqualByComparingTo("250");
        }

        @Test
        @DisplayName("EDGE CASE: large coin amount — precision maintained")
        void addCoins_largeAmount_precisionMaintained() {
            User user = makeUser(5, new BigDecimal("9999"));
            when(userRepository.findById(5)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.addCoins(5, new BigDecimal("1000000"));

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getCoinBalance()).isEqualByComparingTo("1009999");
        }

        @Test
        @DisplayName("ERROR CASE: user not found → RuntimeException, user NOT saved")
        void addCoins_userNotFound_throwsException() {
            when(userRepository.findById(999)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addCoins(999, new BigDecimal("100")))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("User not found: 999");

            verify(userRepository, never()).save(any());
        }
    }
}
