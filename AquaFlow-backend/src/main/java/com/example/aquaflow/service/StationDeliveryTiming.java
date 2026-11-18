package com.example.aquaflow.service;

import com.example.aquaflow.constant.StationOperatingStatus;
import com.example.aquaflow.entity.Orders;
import com.example.aquaflow.entity.Station;
import com.example.aquaflow.mapper.OrderMapper;
import com.example.aquaflow.mapper.StationMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Service
public class StationDeliveryTiming {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private final Clock clock;
    private final StationMapper stations;
    private final OrderMapper orders;
    private final InventoryReservationService reservations;

    public StationDeliveryTiming(Clock clock, StationMapper stations, OrderMapper orders,
                                 InventoryReservationService reservations) {
        this.clock = clock;
        this.stations = stations;
        this.orders = orders;
        this.reservations = reservations;
    }

    public record Hours(LocalTime opens, LocalTime closes, int openMinutes, int closedMinutes) {
        private boolean overnight() {
            return closes.isBefore(opens);
        }

        boolean isOpen(LocalTime time) {
            boolean afterOpening = !time.isBefore(opens);
            boolean beforeClosing = time.isBefore(closes);
            return overnight() ? afterOpening || beforeClosing : afterOpening && beforeClosing;
        }

        LocalDateTime deadline(LocalDateTime acceptedAt) {
            if (isOpen(acceptedAt.toLocalTime())) {
                return acceptedAt.plusMinutes(openMinutes);
            }
            return nextOccurrence(acceptedAt, opens).plusMinutes(closedMinutes);
        }

        private LocalDateTime nextOccurrence(LocalDateTime now, LocalTime time) {
            LocalDateTime occurrence = now.toLocalDate().atTime(time);
            return occurrence.isAfter(now) ? occurrence : occurrence.plusDays(1);
        }
    }

    private static Hours hoursOf(Station station) {
        return new Hours(
                station.getBusinessOpensAt() == null ? LocalTime.of(8, 0) : station.getBusinessOpensAt(),
                station.getBusinessClosesAt() == null ? LocalTime.of(17, 0) : station.getBusinessClosesAt(),
                station.getOpenDeliveryMinutes() == null ? 60 : station.getOpenDeliveryMinutes(),
                station.getClosedDeliveryMinutes() != null ? station.getClosedDeliveryMinutes()
                        : (station.getClosedDeliveryHours() == null ? 2 : station.getClosedDeliveryHours()) * 60);
    }

    public static boolean offersTiming(Station station) {
        return station != null && Integer.valueOf(1).equals(station.getStatus())
                && (station.getOperatingStatus() == null || station.getOperatingStatus() == StationOperatingStatus.NORMAL);
    }

    public Map<String, Object> describe(Station station) {
        Hours hours = hoursOf(station);
        LocalDateTime now = LocalDateTime.now(clock);
        boolean open = hours.isOpen(now.toLocalTime());
        long nextChange = Duration.between(now,
                hours.nextOccurrence(now, open ? hours.closes() : hours.opens())).toMillis();
        return Map.of("opensAt", TIME.format(hours.opens()), "closesAt", TIME.format(hours.closes()),
                "overnight", hours.overnight(), "openMinutes", hours.openMinutes(), "closedMinutes", hours.closedMinutes(),
                "closedHours", (hours.closedMinutes() + 59) / 60, // 兼容旧端的整小时展示
                "openNow", open, "statusText", open ? "营业中" : "已打烊", "nextChangeMillis", nextChange);
    }

    public String displayStatus(Station station, Map<String, Object> hours) {
        if (Integer.valueOf(2).equals(station.getStatus())) return "已停业";
        return offersTiming(station) ? (String) hours.get("statusText") : station.getOperatingStatusText();
    }

    public void onCreated(Orders order, Station station, boolean readyToDispatch, boolean stockAvailable) {
        if (readyToDispatch) {
            if (stockAvailable && offersTiming(station)) {
                orders.setDeliveryDeadline(order.getId(), hoursOf(station).deadline(LocalDateTime.now(clock)));
            }
        } else {
            orders.awaitDeliveryTiming(order.getId());
        }
    }

    public void onPrepaymentAccepted(Orders order) {
        Station station = stations.getById(order.getStationId());
        LocalDateTime deadline = offersTiming(station) && reservations.shortageOfOrder(order.getId()) == 0
                ? hoursOf(station).deadline(LocalDateTime.now(clock)) : null;
        orders.setDeliveryDeadline(order.getId(), deadline);
    }
}
