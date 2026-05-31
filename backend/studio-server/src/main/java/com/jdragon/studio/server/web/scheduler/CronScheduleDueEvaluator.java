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
        return nextDueTime(cronExpression, timezone, lastTriggeredAt, now) != null;
    }

    public LocalDateTime nextDueTime(String cronExpression,
                                     String timezone,
                                     LocalDateTime lastTriggeredAt,
                                     LocalDateTime now) {
        String normalizedCron = normalizeCron(cronExpression);
        if (normalizedCron == null) {
            return null;
        }

        ZoneId zoneId = resolveZoneId(timezone);
        CronExpression expression;
        try {
            expression = new CronExpression(normalizedCron);
            expression.setTimeZone(TimeZone.getTimeZone(zoneId));
        } catch (ParseException ex) {
            log.warn("Ignore invalid cron expression: {}", normalizedCron, ex);
            return null;
        }

        ZonedDateTime nowAtZone = now.atZone(zoneId);
        LocalDateTime referenceTime = lastTriggeredAt == null ? now.minusMinutes(1) : lastTriggeredAt;
        Date nextValidTime = expression.getNextValidTimeAfter(Date.from(referenceTime.atZone(zoneId).toInstant()));
        if (nextValidTime == null || nextValidTime.toInstant().isAfter(nowAtZone.toInstant())) {
            return null;
        }
        return LocalDateTime.ofInstant(nextValidTime.toInstant(), zoneId);
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
