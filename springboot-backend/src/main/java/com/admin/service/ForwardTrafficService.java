package com.admin.service;

import com.admin.common.dto.FlowDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ForwardTrafficService {

    private static final long HOUR_MILLIS = 60L * 60 * 1000;
    private static final int DISPLAY_HOURS = 24;
    private static final int RETENTION_HOURS = 48;

    private final JdbcTemplate jdbc;

    public record HourlyTraffic(long hourStart, long inFlow, long outFlow) {}

    @Transactional
    public void record(Long forwardId, String userId, String userTunnelId, FlowDto traffic) {
        long inFlow = traffic.getD();
        long outFlow = traffic.getU();
        if (inFlow == 0 && outFlow == 0) {
            return;
        }

        if (forwardId != null) {
            recordForward(forwardId, inFlow, outFlow);
        }
        jdbc.update("UPDATE user SET in_flow = in_flow + ?, out_flow = out_flow + ? WHERE id = ?",
                inFlow, outFlow, userId);
        if (!"0".equals(userTunnelId)) {
            jdbc.update("UPDATE user_tunnel SET in_flow = in_flow + ?, out_flow = out_flow + ? WHERE id = ?",
                    inFlow, outFlow, userTunnelId);
        }
    }

    private void recordForward(Long forwardId, long inFlow, long outFlow) {
        long hour = currentHour();
        int updated = jdbc.update(
                "UPDATE forward SET in_flow = in_flow + ?, out_flow = out_flow + ? WHERE id = ?",
                inFlow, outFlow, forwardId);
        if (updated == 0) {
            return;
        }

        jdbc.update("""
                INSERT INTO forward_hourly_traffic (forward_id, hour_start, in_flow, out_flow)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    in_flow = in_flow + VALUES(in_flow),
                    out_flow = out_flow + VALUES(out_flow)
                """, forwardId, hour, inFlow, outFlow);
    }

    public List<HourlyTraffic> hourly(Long forwardId) {
        long lastHour = currentHour();
        long firstHour = lastHour - (DISPLAY_HOURS - 1) * HOUR_MILLIS;

        Map<Long, HourlyTraffic> recorded = new HashMap<>();
        jdbc.query("""
                SELECT hour_start, in_flow, out_flow
                FROM forward_hourly_traffic
                WHERE forward_id = ? AND hour_start BETWEEN ? AND ?
                """, (rs, rowNum) -> new HourlyTraffic(rs.getLong("hour_start"),
                rs.getLong("in_flow"), rs.getLong("out_flow")), forwardId, firstHour, lastHour)
                .forEach(hour -> recorded.put(hour.hourStart(), hour));

        List<HourlyTraffic> result = new ArrayList<>(DISPLAY_HOURS);
        for (long hour = firstHour; hour <= lastHour; hour += HOUR_MILLIS) {
            result.add(recorded.getOrDefault(hour, new HourlyTraffic(hour, 0, 0)));
        }
        return result;
    }

    public void reset(Long forwardId) {
        jdbc.update("UPDATE forward SET in_flow = 0, out_flow = 0 WHERE id = ?", forwardId);
    }

    @Scheduled(fixedDelay = HOUR_MILLIS)
    public void pruneHourlyTraffic() {
        long cutoff = currentHour() - (RETENTION_HOURS - 1) * HOUR_MILLIS;
        jdbc.update("DELETE FROM forward_hourly_traffic WHERE hour_start < ?", cutoff);
    }

    private long currentHour() {
        return Math.floorDiv(System.currentTimeMillis(), HOUR_MILLIS) * HOUR_MILLIS;
    }
}
