package com.jdragon.studio.worker.runtime.log;

import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.jdragon.studio.commons.logging.StudioSensitiveLogSanitizer;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Writes sanitized text so task log files are safe before any archive upload occurs. */
public class SanitizingPatternLayoutEncoder extends PatternLayoutEncoder {

    @Override
    public byte[] encode(ILoggingEvent event) {
        byte[] encoded = super.encode(event);
        Charset charset = getCharset() == null ? StandardCharsets.UTF_8 : getCharset();
        return StudioSensitiveLogSanitizer.sanitize(new String(encoded, charset)).getBytes(charset);
    }
}
