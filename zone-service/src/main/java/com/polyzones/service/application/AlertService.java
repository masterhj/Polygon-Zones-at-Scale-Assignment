package com.polyzones.service.application;

import com.polyzones.service.security.TenantContext;
import com.polyzones.service.web.dto.AlertResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AlertService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public AlertService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(transactionManager);
    }

    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public List<AlertResponse> list(UUID tripId, Instant from, Instant to, int page, int size) {
        if (page < 0 || size < 1 || size > 200 || (from != null && to != null && from.isAfter(to)))
            throw new ApplicationException("VALIDATION_FAILED", "Alert filters or pagination are invalid.");
        UUID tenant = TenantContext.requireTenantId();
        String sql = "select alert_id,trip_id,zone_id,kind,severity,event_time,acknowledged from alerts where tenant_id=? "
                + "and (?::uuid is null or trip_id=?) and (?::timestamptz is null or event_time>=?) "
                + "and (?::timestamptz is null or event_time<=?) order by event_time desc limit ? offset ?";
        return jdbc.query(sql, (rs, row) -> new AlertResponse(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant(), rs.getBoolean(7)),
                tenant, tripId, tripId, from == null ? null : java.sql.Timestamp.from(from), from == null ? null : java.sql.Timestamp.from(from),
                to == null ? null : java.sql.Timestamp.from(to), to == null ? null : java.sql.Timestamp.from(to), size, (long) page * size);
    }

    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public AlertResponse acknowledge(UUID alertId) {
        UUID tenant = TenantContext.requireTenantId();
        AlertResponse row = transactions.execute(status -> {
            jdbc.update("update alerts set acknowledged=true,row_version=row_version+1 where tenant_id=? and alert_id=? and acknowledged=false", tenant, alertId);
            return jdbc.query("select alert_id,trip_id,zone_id,kind,severity,event_time,acknowledged from alerts where tenant_id=? and alert_id=?",
                    rs -> rs.next() ? new AlertResponse(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                            rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant(), rs.getBoolean(7)) : null, tenant, alertId);
        });
        if (row == null) throw new ApplicationException("NOT_FOUND", "The requested alert was not found.");
        return row;
    }
}
