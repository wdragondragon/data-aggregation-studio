package com.jdragon.studio.server.web.scheduler;

import org.quartz.CronExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.TimeZone;

@Component
public class CronScheduleDueEvaluator {

    private static final Logger log = LoggerFactory.getLogger(CronScheduleDueEvaluator.class);
    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    public boolean isDue(String cronExpression,
                         String timezone,
                         LocalDateTime lastTriggeredAt,
                         LocalDateTime now) {
        String normalizedCron = normalizeCron(cronExpression);
        if (normalizedCron == null) {
            return false;
        }

        ZoneId zoneId = resolveZoneId(timezone);
        CronExpression expression;
        try {
            expression = new CronExpression(normalizedCron);
            expression.setTimeZone(TimeZone.getTimeZone(zoneId));
        } catch (ParseException ex) {
            log.warn("Ignore invalid cron expression: {}", normalizedCron, ex);
            return false;
        }

        ZonedDateTime nowAtZone = now.atZone(zoneId);
        LocalDateTime referenceTime = lastTriggeredAt == null ? now.minusMinutes(1) : lastTriggeredAt;
        Date nextValidTime = expression.getNextValidTimeAfter(Date.from(referenceTime.atZone(zoneId).toInstant()));
        return nextValidTime != null && !nextValidTime.toInstant().isAfter(nowAtZone.toInstant());
    }

    private String normalizeCron(String cronExpression) {
        if (cronExpression == null) {
            return null;
        }
        String normalized = cronExpression.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private ZoneId resolveZoneId(String timezone) {
        if (timezone == null || timezone.trim().isEmpty()) {
            return ZoneId.of(DEFAULT_TIMEZONE);
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (Exception ex) {
            log.warn("Fallback to default timezone {} for invalid value {}", DEFAULT_TIMEZONE, timezone);
            return ZoneId.of(DEFAULT_TIMEZONE);
        }
    }
}
