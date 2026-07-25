package com.jdragon.studio.server.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.infra.service.StudioEncryptionRotationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StringUtils;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Set;

public final class StudioEncryptionRotationApplication {

    private static final Set<String> DISALLOWED_NEW_SECRETS = Set.of(
            "studio-secret-key", "secret-key");

    private StudioEncryptionRotationApplication() {
    }

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            boolean apply = Arrays.asList(args).contains("--apply");
            String oldSecret = requiredEnvironment("STUDIO_ENCRYPTION_OLD_SECRET");
            String newSecret = requiredEnvironment("STUDIO_ENCRYPTION_NEW_SECRET");
            if (DISALLOWED_NEW_SECRETS.contains(newSecret.trim())) {
                throw new IllegalArgumentException("The new encryption secret must not use a known default value");
            }
            if (apply && !"ROTATE".equals(System.getenv("STUDIO_ENCRYPTION_ROTATION_CONFIRM"))) {
                throw new IllegalArgumentException(
                        "Apply mode requires STUDIO_ENCRYPTION_ROTATION_CONFIRM=ROTATE");
            }

            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setUrl(requiredEnvironment("SPRING_DATASOURCE_URL"));
            dataSource.setUsername(environment("SPRING_DATASOURCE_USERNAME", ""));
            dataSource.setPassword(environment("SPRING_DATASOURCE_PASSWORD", ""));
            String driverClassName = environment("SPRING_DATASOURCE_DRIVER_CLASS_NAME", null);
            if (StringUtils.hasText(driverClassName)) {
                dataSource.setDriverClassName(driverClassName.trim());
            }

            StudioEncryptionRotationService service = new StudioEncryptionRotationService(
                    new JdbcTemplate(dataSource), new ObjectMapper());
            StudioEncryptionRotationService.RotationReport report = service.rotate(
                    oldSecret, newSecret, apply);
            System.out.println("Studio encryption rotation " + (apply ? "apply" : "dry-run") + " completed.");
            System.out.println("Scanned row/column records: " + report.getScannedRows());
            System.out.println("Scanned encrypted values: " + report.getScannedValues());
            System.out.println("Values requiring rotation: " + report.getRotatedValues());
            System.out.println("Values already using the new key: " + report.getAlreadyCurrentValues());
            System.out.println("Candidate row/column updates: " + report.getCandidateRows());
            System.out.println("Updated row/column values: " + report.getUpdatedRows());
            System.out.println("Quarantined unreadable runtime endpoints: "
                    + report.getQuarantinedRuntimeEndpoints());
            System.out.println("Schema targets not present: " + report.getSkippedTargets().size());
        } catch (Exception ex) {
            exitCode = 1;
            ex.printStackTrace();
            System.err.println("Studio encryption rotation failed: " + safeMessage(ex));
        } finally {
            System.exit(exitCode);
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " must be configured");
        }
        return value;
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    private static String safeMessage(Exception exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException) {
                SQLException sqlException = (SQLException) current;
                String sqlState = StringUtils.hasText(sqlException.getSQLState())
                        ? sqlException.getSQLState() : "unknown";
                return "Database operation failed (SQLState=" + sqlState
                        + ", errorCode=" + sqlException.getErrorCode() + ")";
            }
            current = current.getCause();
        }
        String message = exception.getMessage();
        return StringUtils.hasText(message) ? message : exception.getClass().getSimpleName();
    }
}
