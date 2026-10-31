package com.pricepilot.alerts;

import com.pricepilot.config.AppProperties;
import com.pricepilot.domain.AlertLog;
import com.pricepilot.repo.AlertLogRepository;
import com.pricepilot.repo.ProductRepository;
import com.pricepilot.repo.TrackerRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AlertWriterTest {
    @Test void failureIsPersistedWithoutErasingTheNotification() {
        var repository = mock(AlertLogRepository.class);
        var alert = new AlertLog();
        alert.setMessage("Verified source price reached your target.");
        when(repository.findById(1L)).thenReturn(Optional.of(alert));
        var writer = new AlertWriter(mock(TrackerRepository.class), repository, mock(ProductRepository.class), new AppProperties());
        writer.markFailed(1L, "SMTP authentication failed.");
        assertThat(alert.isDelivered()).isFalse();
        assertThat(alert.getDeliveryError()).isEqualTo("SMTP authentication failed.");
        assertThat(alert.getMessage()).isEqualTo("Verified source price reached your target.");
        verify(repository, never()).delete(any());
    }

    @Test void laterFailureNeverOverwritesAnAcceptedDelivery() {
        var repository = mock(AlertLogRepository.class);
        var alert = new AlertLog();
        when(repository.findById(1L)).thenReturn(Optional.of(alert));
        var writer = new AlertWriter(mock(TrackerRepository.class), repository, mock(ProductRepository.class), new AppProperties());
        writer.markDelivered(1L);
        writer.markFailed(1L, "Late failure from another attempt.");
        assertThat(alert.isDelivered()).isTrue();
        assertThat(alert.getDeliveryError()).isNull();
    }
}
