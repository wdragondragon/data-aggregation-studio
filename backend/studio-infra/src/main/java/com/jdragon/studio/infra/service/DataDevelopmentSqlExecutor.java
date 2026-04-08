package com.jdragon.studio.infra.service;

import com.jdragon.aggregation.datasource.AbstractDataSourcePlugin;
import com.jdragon.aggregation.datasource.BaseDataSourceDTO;
import com.jdragon.aggregation.datasource.SourcePluginType;
import com.jdragon.aggregation.pluginloader.PluginClassLoaderCloseable;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.ScriptType;
import com.jdragon.studio.dto.model.DataScriptExecutionResultView;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.SqlExecutionResultView;
import com.jdragon.studio.dto.model.SqlStatementExecutionResultView;
import com.jdragon.studio.infra.service.script.DataDevelopmentExecutionContext;
import com.jdragon.studio.infra.service.script.DataDevelopmentScriptExecutor;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class DataDevelopmentSqlExecutor implements DataDevelopmentScriptExecutor {

    private static final Set<String> SQL_DATASOURCE_TYPES = Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(
            "mysql5",
            "mysql8",
            "dm",
            "oracle",
            "postgres",
            "tbds-hive2",
            "tbds-hive3",
            "odps"
    )));

    private static final Set<String> RESERVED_KEYS = Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(
            "name",
            "type",
            "host",
            "port",
            "database",
            "userName",
            "password",
            "other",
            "usePool",
            "bucket",
            "principal",
            "keytabPath",
            "krb5File",
            "jdbcUrl",
            "driverClassName",
            "extraParams"
    )));

    private final EncryptionService encryptionService;

    public DataDevelopmentSqlExecutor(EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    public boolean supports(DataSourceDefinition datasource) {
        if (datasource == null || datasource.getTypeCode() == null) {
            return false;
        }
        return SQL_DATASOURCE_TYPES.contains(datasource.getTypeCode().trim().toLowerCase(Locale.ENGLISH));
    }

    @Override
    public ScriptType getScriptType() {
        return ScriptType.SQL;
    }

    @Override
    public DataScriptExecutionResultView execute(DataDevelopmentExecutionContext context) {
        SqlExecutionResultView sqlResult = executeSql(context.getDatasource(), context.getContent(), context.getMaxRows());
        DataScriptExecutionResultView result = new DataScriptExecutionResultView();
        result.setScriptType(ScriptType.SQL);
        result.setSuccess(Boolean.TRUE);
        result.setStatus("SUCCESS");
        result.setMessage(sqlResult.getMessage());
        result.setExecutionMs(sqlResult.getExecutionMs());
        result.setDatasourceName(sqlResult.getDatasourceName());
        result.setLogs(sqlResult.getMessage());
        result.setResultJson(new LinkedHashMap<String, Object>(sqlResult.getSummary()));
        result.setSqlResult(sqlResult);
        return result;
    }

    public SqlExecutionResultView executeSql(DataSourceDefinition datasource, String scriptContent, Integer maxRows) {
        if (!supports(datasource)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Only database datasources can execute SQL scripts");
        }
        List<String> statements = splitStatements(scriptContent);
        if (statements.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "SQL script content is empty");
        }

        BaseDataSourceDTO dataSourceDTO = toBaseDataSourceDTO(datasource);
        long startedAt = System.currentTimeMillis();
        try (PluginClassLoaderCloseable loader =
                     PluginClassLoaderCloseable.newCurrentThreadClassLoaderSwapper(SourcePluginType.SOURCE, datasource.getTypeCode())) {
            AbstractDataSourcePlugin plugin = loader.loadPlugin();
            try (Connection connection = plugin.getConnection(dataSourceDTO);
                 Statement statement = connection.createStatement()) {
                if (maxRows != null && maxRows.intValue() > 0) {
                    statement.setMaxRows(maxRows.intValue());
                }
                SqlExecutionResultView result = new SqlExecutionResultView();
                result.setDatasourceName(datasource.getName());
                result.setStatementCount(statements.size());
                int totalAffectedRows = 0;
                int queryCount = 0;
                for (int index = 0; index < statements.size(); index++) {
                    String sql = statements.get(index);
                    SqlStatementExecutionResultView statementResult = new SqlStatementExecutionResultView();
                    statementResult.setStatementIndex(index + 1);
                    statementResult.setSql(sql);
                    long statementStartedAt = System.currentTimeMillis();
                    boolean hasResultSet = statement.execute(sql);
                    if (hasResultSet) {
                        queryCount++;
                        try (ResultSet resultSet = statement.getResultSet()) {
                            populateQueryResult(statementResult, resultSet);
                        }
                        statementResult.setQuery(Boolean.TRUE);
                        statementResult.setMessage(String.format("Query returned %d row(s)", statementResult.getRows().size()));
                        statementResult.getSummary().put("rowCount", statementResult.getRows().size());
                        result.setColumns(new ArrayList<String>(statementResult.getColumns()));
                        result.setRows(new ArrayList<Map<String, Object>>(statementResult.getRows()));
                    } else {
                        int affectedRows = statement.getUpdateCount();
                        int normalizedAffectedRows = affectedRows < 0 ? 0 : affectedRows;
                        totalAffectedRows += normalizedAffectedRows;
                        statementResult.setQuery(Boolean.FALSE);
                        statementResult.setAffectedRows(normalizedAffectedRows);
                        statementResult.setMessage(String.format("Statement affected %d row(s)", normalizedAffectedRows));
                        statementResult.getSummary().put("affectedRows", normalizedAffectedRows);
                    }
                    statementResult.setExecutionMs(System.currentTimeMillis() - statementStartedAt);
                    result.getResults().add(statementResult);
                }
                long endedAt = System.currentTimeMillis();
                result.setExecutionMs(endedAt - startedAt);
                result.setAffectedRows(totalAffectedRows);
                result.getSummary().put("statementCount", statements.size());
                result.getSummary().put("queryCount", queryCount);
                result.getSummary().put("affectedRows", totalAffectedRows);
                result.getSummary().put("datasourceType", datasource.getTypeCode());
                if (!result.getRows().isEmpty() || !result.getColumns().isEmpty()) {
                    result.setQuery(Boolean.TRUE);
                    result.setMessage(queryCount > 1
                            ? String.format("Executed %d query statements successfully", queryCount)
                            : "Query executed successfully");
                    result.getSummary().put("rowCount", result.getRows().size());
                } else {
                    result.setQuery(Boolean.FALSE);
                    result.setMessage(String.format("Executed %d statement(s), affected rows: %d", statements.size(), totalAffectedRows));
                }
                return result;
            }
        } catch (StudioException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "SQL execution failed: " + ex.getMessage());
        }
    }

    private void populateQueryResult(SqlStatementExecutionResultView result, ResultSet resultSet) throws Exception {
        ResultSetMetaData metadata = resultSet.getMetaData();
        int columnCount = metadata.getColumnCount();
        List<String> columns = new ArrayList<String>();
        for (int columnIndex = 1; columnIndex <= columnCount; columnIndex++) {
            columns.add(metadata.getColumnLabel(columnIndex));
        }
        result.setColumns(columns);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            for (int columnIndex = 1; columnIndex <= columnCount; columnIndex++) {
                Object value = resultSet.getObject(columnIndex);
                row.put(columns.get(columnIndex - 1), normalizeJdbcValue(value));
            }
            rows.add(row);
        }
        result.setRows(rows);
    }

    private Object normalizeJdbcValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Timestamp || value instanceof java.sql.Date || value instanceof java.sql.Time) {
            return value.toString();
        }
        if (value instanceof byte[]) {
            return "(BLOB) " + ((byte[]) value).length + " bytes";
        }
        return value;
    }

    private BaseDataSourceDTO toBaseDataSourceDTO(DataSourceDefinition datasource) {
        BaseDataSourceDTO dto = new BaseDataSourceDTO();
        dto.setName(datasource.getName());
        dto.setType(datasource.getTypeCode());
        Map<String, Object> metadata = datasource.getTechnicalMetadata() == null
                ? new LinkedHashMap<String, Object>()
                : datasource.getTechnicalMetadata();
        dto.setHost(asString(metadata.get("host")));
        dto.setPort(asString(metadata.get("port")));
        dto.setDatabase(asString(metadata.get("database")));
        dto.setUserName(asString(metadata.get("userName")));
        dto.setPassword(decryptIfNecessary("password", asString(metadata.get("password"))));
        dto.setOther(asString(metadata.get("other")));
        dto.setUsePool(Boolean.parseBoolean(String.valueOf(metadata.getOrDefault("usePool", Boolean.TRUE))));
        dto.setBucket(asString(metadata.get("bucket")));
        dto.setPrincipal(asString(metadata.get("principal")));
        dto.setKeytabPath(asString(metadata.get("keytabPath")));
        dto.setKrb5File(asString(metadata.get("krb5File")));
        dto.setJdbcUrl(asString(metadata.get("jdbcUrl")));
        dto.setDriverClassName(asString(metadata.get("driverClassName")));

        Map<String, String> extraParams = new LinkedHashMap<String, String>();
        Object configuredExtraParams = metadata.get("extraParams");
        if (configuredExtraParams instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) configuredExtraParams;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    extraParams.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (RESERVED_KEYS.contains(entry.getKey())) {
                continue;
            }
            if (entry.getValue() == null) {
                continue;
            }
            extraParams.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        dto.setExtraParams(extraParams);
        return dto;
    }

    private String decryptIfNecessary(String key, String value) {
        if (value == null) {
            return null;
        }
        if (isSensitive(key) && value.startsWith("ENC(") && value.endsWith(")")) {
            return encryptionService.decrypt(value.substring(4, value.length() - 1));
        }
        return value;
    }

    private boolean isSensitive(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ENGLISH);
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("accesskey");
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.trim().isEmpty() ? null : text;
    }

    private List<String> splitStatements(String content) {
        List<String> result = new ArrayList<String>();
        if (content == null) {
            return result;
        }
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int index = 0; index < content.length(); index++) {
            char ch = content.charAt(index);
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            }
            if (ch == ';' && !inSingleQuote && !inDoubleQuote) {
                appendStatement(result, current.toString());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        appendStatement(result, current.toString());
        return result;
    }

    private void appendStatement(List<String> target, String candidate) {
        String trimmed = candidate == null ? "" : candidate.trim();
        if (!trimmed.isEmpty()) {
            target.add(trimmed);
        }
    }
}
