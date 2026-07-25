package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline, transaction-scoped rotation of Studio-managed encrypted values. */
public final class StudioEncryptionRotationService {

    private static final String ENCRYPTED_MARKER_PREFIX = "ENC(";
    private static final Pattern ENCRYPTED_MARKER = Pattern.compile("ENC\\(([^)]*)\\)");
    private static final int DEFAULT_BATCH_SIZE = 500;
    private static final int LARGE_VALUE_BATCH_SIZE = 10;
    private static final List<ColumnTarget> RAW_TARGETS = Collections.unmodifiableList(Arrays.asList(
            new ColumnTarget("studio_runtime_endpoint", "endpoint_ciphertext"),
            new ColumnTarget("studio_runtime_endpoint", "headers_ciphertext"),
            new ColumnTarget("studio_runtime_endpoint", "token_ciphertext"),
            new ColumnTarget("studio_alert_channel", "endpoint_ciphertext"),
            new ColumnTarget("studio_alert_channel", "headers_ciphertext"),
            new ColumnTarget("studio_alert_channel", "signing_secret_ciphertext"),
            new ColumnTarget("dispatch_task", "protected_payload_ciphertext", true),
            new ColumnTarget("studio_runtime_idempotency", "response_body_ciphertext", true)
    ));
    private static final List<ColumnTarget> JSON_TARGETS = Collections.unmodifiableList(Arrays.asList(
            new ColumnTarget("datasource_definition", "technical_metadata"),
            new ColumnTarget("data_model", "technical_metadata"),
            new ColumnTarget("collection_task_definition", "source_bindings_json")
    ));

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public StudioEncryptionRotationService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        if (jdbcTemplate == null || jdbcTemplate.getDataSource() == null) {
            throw new IllegalArgumentException("JdbcTemplate with a DataSource is required");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public RotationReport rotate(String oldSecret, String newSecret, boolean apply) {
        validateSecrets(oldSecret, newSecret);
        EncryptionService oldEncryption = EncryptionService.forSecret(oldSecret);
        EncryptionService newEncryption = EncryptionService.forSecret(newSecret);
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbcTemplate.getDataSource()));
        transaction.setReadOnly(!apply);
        RotationReport report = transaction.execute(status -> rotateInTransaction(
                oldEncryption, newEncryption, apply));
        if (report == null) {
            throw new IllegalStateException("Encryption rotation transaction returned no result");
        }
        return report;
    }

    private RotationReport rotateInTransaction(EncryptionService oldEncryption,
                                               EncryptionService newEncryption,
                                               boolean apply) {
        Set<String> availableColumns = loadAvailableColumns();
        MutableReport report = new MutableReport(apply);
        for (ColumnTarget target : RAW_TARGETS) {
            rotateColumn(target, false, availableColumns, oldEncryption, newEncryption, apply, report);
        }
        for (ColumnTarget target : JSON_TARGETS) {
            rotateColumn(target, true, availableColumns, oldEncryption, newEncryption, apply, report);
        }
        quarantineUnreadableRuntimeEndpoints(availableColumns, apply, report);
        return report.toReport();
    }

    private void rotateColumn(ColumnTarget target,
                              boolean json,
                              Set<String> availableColumns,
                              EncryptionService oldEncryption,
                              EncryptionService newEncryption,
                              boolean apply,
                              MutableReport report) {
        if (!availableColumns.contains(target.key())) {
            report.skippedTargets.add(target.toString());
            return;
        }
        Object lastId = null;
        int batchSize = target.largeValue ? LARGE_VALUE_BATCH_SIZE : DEFAULT_BATCH_SIZE;
        while (true) {
            List<RowValue> rows = loadBatch(target, lastId, batchSize);
            if (rows.isEmpty()) {
                return;
            }
            for (RowValue row : rows) {
                report.scannedRows++;
                RotatedValue rotated = json
                        ? rotateJson(row.value, target, row.id, oldEncryption, newEncryption, report)
                        : rotateCiphertext(row.value, target, row.id, oldEncryption, newEncryption, report);
                if (!rotated.changed) {
                    continue;
                }
                report.candidateRows++;
                if (!apply) {
                    continue;
                }
                String originalValuePredicate = json
                        ? "cast(`" + target.column + "` as char) = ?"
                        : "`" + target.column + "` = ?";
                String updateSql = "update `" + target.table + "` set `" + target.column
                        + "` = ? where `id` = ? and " + originalValuePredicate;
                int updated = jdbcTemplate.update(updateSql, rotated.value, row.id, row.value);
                if (updated != 1) {
                    throw new IllegalStateException("Concurrent change detected while rotating "
                            + target + " at id=" + row.id);
                }
                report.updatedRows++;
            }
            lastId = rows.get(rows.size() - 1).id;
        }
    }

    private List<RowValue> loadBatch(ColumnTarget target, Object lastId, int batchSize) {
        StringBuilder sql = new StringBuilder("select `id`, `")
                .append(target.column).append("` from `").append(target.table)
                .append("` where `").append(target.column).append("` is not null and `")
                .append(target.column).append("` <> ''");
        List<Object> parameters = new ArrayList<Object>();
        if (lastId != null) {
            sql.append(" and `id` > ?");
            parameters.add(lastId);
        }
        sql.append(" order by `id` limit ").append(batchSize);
        return jdbcTemplate.query(sql.toString(), (resultSet, rowNum) ->
                new RowValue(resultSet.getObject(1), resultSet.getString(2)), parameters.toArray());
    }

    private RotatedValue rotateJson(String json,
                                    ColumnTarget target,
                                    Object rowId,
                                    EncryptionService oldEncryption,
                                    EncryptionService newEncryption,
                                    MutableReport report) {
        try {
            objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid JSON while rotating " + target + " at id=" + rowId, ex);
        }
        Matcher matcher = ENCRYPTED_MARKER.matcher(json);
        StringBuffer output = new StringBuffer(json.length());
        boolean changed = false;
        int matchedMarkers = 0;
        while (matcher.find()) {
            matchedMarkers++;
            RotatedValue cipher = rotateCiphertext(matcher.group(1), target, rowId,
                    oldEncryption, newEncryption, report);
            String replacement = "ENC(" + cipher.value + ")";
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
            changed = changed || cipher.changed;
        }
        matcher.appendTail(output);
        if (matchedMarkers != countOccurrences(json, ENCRYPTED_MARKER_PREFIX)) {
            throw new IllegalStateException("Malformed encrypted marker while rotating "
                    + target + " at id=" + rowId);
        }
        return new RotatedValue(output.toString(), changed);
    }

    private int countOccurrences(String value, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private RotatedValue rotateCiphertext(String ciphertext,
                                          ColumnTarget target,
                                          Object rowId,
                                          EncryptionService oldEncryption,
                                          EncryptionService newEncryption,
                                          MutableReport report) {
        report.scannedValues++;
        DecryptionAttempt oldAttempt = decrypt(oldEncryption, ciphertext);
        DecryptionAttempt newAttempt = decrypt(newEncryption, ciphertext);
        if (oldAttempt.success && newAttempt.success) {
            throw new IllegalStateException("Stored value is ambiguous under both keys at "
                    + target + " id=" + rowId + "; rotation stopped without committing changes");
        }
        if (newAttempt.success) {
            report.alreadyCurrentValues++;
            return new RotatedValue(ciphertext, false);
        }
        if (!oldAttempt.success) {
            if ("studio_runtime_endpoint".equals(target.table)) {
                report.unreadableRuntimeEndpointIds.add(rowId);
                return new RotatedValue(ciphertext, false);
            }
            throw new IllegalStateException("Stored value cannot be decrypted with either key at "
                    + target + " id=" + rowId);
        }
        report.rotatedValues++;
        return new RotatedValue(newEncryption.encrypt(oldAttempt.plainText), true);
    }

    private DecryptionAttempt decrypt(EncryptionService encryptionService, String ciphertext) {
        try {
            return new DecryptionAttempt(true, encryptionService.decrypt(ciphertext));
        } catch (RuntimeException ignored) {
        return new DecryptionAttempt(false, null);
        }
    }

    /**
     * An unreadable Worker endpoint cannot be routed safely. It is quarantined instead of
     * preventing a control-plane key rotation; administrators must configure it again.
     */
    private void quarantineUnreadableRuntimeEndpoints(Set<String> availableColumns,
                                                       boolean apply,
                                                       MutableReport report) {
        if (report.unreadableRuntimeEndpointIds.isEmpty()) {
            return;
        }
        if (!availableColumns.contains(key("studio_runtime_endpoint", "enabled"))) {
            throw new IllegalStateException("Cannot quarantine unreadable runtime endpoint without enabled column");
        }
        for (Object endpointId : report.unreadableRuntimeEndpointIds) {
            report.candidateRows++;
            report.quarantinedRuntimeEndpoints++;
            if (!apply) {
                continue;
            }
            StringBuilder sql = new StringBuilder("update `studio_runtime_endpoint` set `enabled` = 0");
            for (String column : Arrays.asList("endpoint_ciphertext", "headers_ciphertext", "token_ciphertext")) {
                if (availableColumns.contains(key("studio_runtime_endpoint", column))) {
                    sql.append(", `").append(column).append("` = null");
                }
            }
            if (availableColumns.contains(key("studio_runtime_endpoint", "last_test_status"))) {
                sql.append(", `last_test_status` = 'CONFIGURATION_INVALID'");
            }
            if (availableColumns.contains(key("studio_runtime_endpoint", "last_test_message"))) {
                sql.append(", `last_test_message` = 'Encrypted endpoint configuration cannot be decrypted; reconfigure endpoint'");
            }
            sql.append(" where `id` = ?");
            int updated = jdbcTemplate.update(sql.toString(), endpointId);
            if (updated != 1) {
                throw new IllegalStateException("Concurrent change detected while quarantining runtime endpoint id=" + endpointId);
            }
            report.updatedRows++;
        }
    }

    private Set<String> loadAvailableColumns() {
        Set<String> expectedTables = new LinkedHashSet<String>();
        for (ColumnTarget target : RAW_TARGETS) {
            expectedTables.add(target.table);
        }
        for (ColumnTarget target : JSON_TARGETS) {
            expectedTables.add(target.table);
        }
        return jdbcTemplate.execute((ConnectionCallback<Set<String>>) connection -> {
            DatabaseMetaData metadata = connection.getMetaData();
            String catalog = connection.getCatalog();
            Set<String> available = new LinkedHashSet<String>();
            for (String table : expectedTables) {
                String actualTable = findTable(metadata, catalog, table);
                if (actualTable == null) {
                    continue;
                }
                try (ResultSet columns = metadata.getColumns(catalog, null, actualTable, "%")) {
                    while (columns.next()) {
                        String column = columns.getString("COLUMN_NAME");
                        if (StringUtils.hasText(column)) {
                            available.add(key(table, column));
                        }
                    }
                }
            }
            return available;
        });
    }

    private String findTable(DatabaseMetaData metadata, String catalog, String expected) throws SQLException {
        try (ResultSet tables = metadata.getTables(catalog, null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String table = tables.getString("TABLE_NAME");
                if (expected.equalsIgnoreCase(table)) {
                    return table;
                }
            }
        }
        return null;
    }

    private void validateSecrets(String oldSecret, String newSecret) {
        if (!StringUtils.hasText(oldSecret) || !StringUtils.hasText(newSecret)) {
            throw new IllegalArgumentException("Old and new encryption secrets are required");
        }
        if (oldSecret.equals(newSecret)) {
            throw new IllegalArgumentException("Old and new encryption secrets must differ");
        }
    }

    private static String key(String table, String column) {
        return (table + "." + column).toLowerCase(Locale.ENGLISH);
    }

    private static final class ColumnTarget {
        private final String table;
        private final String column;
        private final boolean largeValue;

        private ColumnTarget(String table, String column) {
            this(table, column, false);
        }

        private ColumnTarget(String table, String column, boolean largeValue) {
            this.table = table;
            this.column = column;
            this.largeValue = largeValue;
        }

        private String key() {
            return StudioEncryptionRotationService.key(table, column);
        }

        @Override
        public String toString() {
            return table + "." + column;
        }
    }

    private static final class RowValue {
        private final Object id;
        private final String value;

        private RowValue(Object id, String value) {
            this.id = id;
            this.value = value;
        }
    }

    private static final class RotatedValue {
        private final String value;
        private final boolean changed;

        private RotatedValue(String value, boolean changed) {
            this.value = value;
            this.changed = changed;
        }
    }

    private static final class DecryptionAttempt {
        private final boolean success;
        private final String plainText;

        private DecryptionAttempt(boolean success, String plainText) {
            this.success = success;
            this.plainText = plainText;
        }
    }

    private static final class MutableReport {
        private final boolean applied;
        private int scannedValues;
        private int rotatedValues;
        private int alreadyCurrentValues;
        private int scannedRows;
        private int candidateRows;
        private int updatedRows;
        private int quarantinedRuntimeEndpoints;
        private final Set<String> skippedTargets = new LinkedHashSet<String>();
        private final Set<Object> unreadableRuntimeEndpointIds = new LinkedHashSet<Object>();

        private MutableReport(boolean applied) {
            this.applied = applied;
        }

        private RotationReport toReport() {
            return new RotationReport(applied, scannedRows, scannedValues, rotatedValues,
                    alreadyCurrentValues, candidateRows, updatedRows,
                    quarantinedRuntimeEndpoints, new ArrayList<String>(skippedTargets));
        }
    }

    public static final class RotationReport {
        private final boolean applied;
        private final int scannedRows;
        private final int scannedValues;
        private final int rotatedValues;
        private final int alreadyCurrentValues;
        private final int candidateRows;
        private final int updatedRows;
        private final int quarantinedRuntimeEndpoints;
        private final List<String> skippedTargets;

        private RotationReport(boolean applied, int scannedRows, int scannedValues,
                               int rotatedValues, int alreadyCurrentValues, int candidateRows,
                               int updatedRows, int quarantinedRuntimeEndpoints, List<String> skippedTargets) {
            this.applied = applied;
            this.scannedRows = scannedRows;
            this.scannedValues = scannedValues;
            this.rotatedValues = rotatedValues;
            this.alreadyCurrentValues = alreadyCurrentValues;
            this.candidateRows = candidateRows;
            this.updatedRows = updatedRows;
            this.quarantinedRuntimeEndpoints = quarantinedRuntimeEndpoints;
            this.skippedTargets = Collections.unmodifiableList(skippedTargets);
        }

        public boolean isApplied() {
            return applied;
        }

        public int getScannedRows() {
            return scannedRows;
        }

        public int getScannedValues() {
            return scannedValues;
        }

        public int getRotatedValues() {
            return rotatedValues;
        }

        public int getAlreadyCurrentValues() {
            return alreadyCurrentValues;
        }

        public int getCandidateRows() {
            return candidateRows;
        }

        public int getUpdatedRows() {
            return updatedRows;
        }

        public int getQuarantinedRuntimeEndpoints() {
            return quarantinedRuntimeEndpoints;
        }

        public List<String> getSkippedTargets() {
            return skippedTargets;
        }
    }
}
