package com.jdragon.studio.infra.service;

import com.jdragon.aggregation.commons.pagination.Table;
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

import java.sql.Connection;
import java.sql.PreparedStatement;
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

public class DataDevelopmentSqlExecutor implements DataDevelopmentScriptExecutor {

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
    private static final Set<String> SOURCE_PLUGIN_QUERY_KEYWORDS = Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(
            "select",
            "show",
            "desc",
            "describe",
            "with"
    )));

    private final EncryptionService encryptionService;
    private final DatasourceTypeCapabilityService datasourceTypeCapabilityService;

    public DataDevelopmentSqlExecutor(EncryptionService encryptionService,
                                      DatasourceTypeCapabilityService datasourceTypeCapabilityService) {
        this.encryptionService = encryptionService;
        this.datasourceTypeCapabilityService = datasourceTypeCapabilityService;
    }

    public boolean supports(DataSourceDefinition datasource) {
        if (datasource == null || datasource.getTypeCode() == null) {
            return false;
        }
        return datasourceTypeCapabilityService.isSqlExecutable(datasource.getTypeCode());
    }

    public Set<String> supportedDatasourceTypes() {
        return new LinkedHashSet<String>(datasourceTypeCapabilityService.sqlExecutableTypes());
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
            Connection connection = resolveJdbcConnection(plugin, dataSourceDTO);
            if (connection == null) {
                return executeWithSourcePlugin(datasource, plugin, dataSourceDTO, statements, maxRows, startedAt);
            }
            try (connection;
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

    public SqlExecutionResultView executePreparedQuery(DataSourceDefinition datasource,
                                                       String sql,
                                                       List<Object> parameters,
                                                       Integer maxRows) {
        if (!supports(datasource)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Only database datasources can execute SQL scripts");
        }
        if (sql == null || sql.trim().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "SQL script content is empty");
        }
        BaseDataSourceDTO dataSourceDTO = toBaseDataSourceDTO(datasource);
        long startedAt = System.currentTimeMillis();
        try (PluginClassLoaderCloseable loader =
                     PluginClassLoaderCloseable.newCurrentThreadClassLoaderSwapper(SourcePluginType.SOURCE, datasource.getTypeCode())) {
            AbstractDataSourcePlugin plugin = loader.loadPlugin();
            Connection connection = resolveJdbcConnection(plugin, dataSourceDTO);
            if (connection == null) {
                if (parameters != null && !parameters.isEmpty()) {
                    throw new StudioException(StudioErrorCode.BAD_REQUEST,
                            "Datasource type " + datasource.getTypeCode() + " does not support parameterized SQL execution");
                }
                if (!isSourcePluginQuerySql(sql)) {
                    throw new StudioException(StudioErrorCode.BAD_REQUEST,
                            "Prepared query requires a query SQL statement for datasource type " + datasource.getTypeCode());
                }
                return executeWithSourcePlugin(datasource, plugin, dataSourceDTO, Collections.singletonList(sql), maxRows, startedAt);
            }
            try (connection;
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                if (maxRows != null && maxRows.intValue() > 0) {
                    statement.setMaxRows(maxRows.intValue());
                }
                List<Object> safeParameters = parameters == null ? Collections.emptyList() : parameters;
                for (int index = 0; index < safeParameters.size(); index++) {
                    statement.setObject(index + 1, safeParameters.get(index));
                }
                SqlStatementExecutionResultView statementResult = new SqlStatementExecutionResultView();
                statementResult.setStatementIndex(1);
                statementResult.setSql(sql);
                statementResult.setQuery(Boolean.TRUE);
                long statementStartedAt = System.currentTimeMillis();
                try (ResultSet resultSet = statement.executeQuery()) {
                    populateQueryResult(statementResult, resultSet);
                }
                statementResult.setExecutionMs(System.currentTimeMillis() - statementStartedAt);
                statementResult.setMessage(String.format("Query returned %d row(s)", statementResult.getRows().size()));
                statementResult.getSummary().put("rowCount", statementResult.getRows().size());

                SqlExecutionResultView result = new SqlExecutionResultView();
                result.setDatasourceName(datasource.getName());
                result.setStatementCount(1);
                result.setQuery(Boolean.TRUE);
                result.setAffectedRows(0);
                result.setExecutionMs(System.currentTimeMillis() - startedAt);
                result.setMessage("Query executed successfully");
                result.setColumns(new ArrayList<String>(statementResult.getColumns()));
                result.setRows(new ArrayList<Map<String, Object>>(statementResult.getRows()));
                result.getSummary().put("statementCount", 1);
                result.getSummary().put("queryCount", 1);
                result.getSummary().put("rowCount", result.getRows().size());
                result.getSummary().put("datasourceType", datasource.getTypeCode());
                result.getResults().add(statementResult);
                return result;
            }
        } catch (StudioException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "SQL execution failed: " + ex.getMessage());
        }
    }

    private Connection resolveJdbcConnection(AbstractDataSourcePlugin plugin, BaseDataSourceDTO dataSourceDTO) {
        try {
            return plugin.getConnection(dataSourceDTO);
        } catch (UnsupportedOperationException ex) {
            return null;
        }
    }

    private SqlExecutionResultView executeWithSourcePlugin(DataSourceDefinition datasource,
                                                           AbstractDataSourcePlugin plugin,
                                                           BaseDataSourceDTO dataSourceDTO,
                                                           List<String> statements,
                                                           Integer maxRows,
                                                           long startedAt) {
        SqlExecutionResultView result = new SqlExecutionResultView();
        result.setDatasourceName(datasource.getName());
        result.setStatementCount(statements.size());
        int totalAffectedRows = 0;
        int queryCount = 0;
        for (int index = 0; index < statements.size(); index++) {
            String sql = statements.get(index);
            SqlStatementExecutionResultView statementResult = executeStatementWithSourcePlugin(
                    plugin, dataSourceDTO, sql, maxRows, index + 1);
            result.getResults().add(statementResult);
            if (Boolean.TRUE.equals(statementResult.getQuery())) {
                queryCount++;
                result.setColumns(new ArrayList<String>(statementResult.getColumns()));
                result.setRows(new ArrayList<Map<String, Object>>(statementResult.getRows()));
            } else {
                totalAffectedRows += statementResult.getAffectedRows() == null ? 0 : statementResult.getAffectedRows();
            }
        }
        result.setExecutionMs(System.currentTimeMillis() - startedAt);
        result.setAffectedRows(totalAffectedRows);
        result.getSummary().put("statementCount", statements.size());
        result.getSummary().put("queryCount", queryCount);
        result.getSummary().put("affectedRows", totalAffectedRows);
        result.getSummary().put("datasourceType", datasource.getTypeCode());
        result.getSummary().put("executionMode", "sourcePlugin");
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

    private SqlStatementExecutionResultView executeStatementWithSourcePlugin(AbstractDataSourcePlugin plugin,
                                                                             BaseDataSourceDTO dataSourceDTO,
                                                                             String sql,
                                                                             Integer maxRows,
                                                                             int statementIndex) {
        SqlStatementExecutionResultView statementResult = new SqlStatementExecutionResultView();
        statementResult.setStatementIndex(statementIndex);
        statementResult.setSql(sql);
        long statementStartedAt = System.currentTimeMillis();
        try {
            if (isSourcePluginQuerySql(sql)) {
                Table<Map<String, Object>> table = plugin.executeQuerySql(dataSourceDTO, sql, true);
                populatePluginQueryResult(statementResult, table, maxRows);
                statementResult.setQuery(Boolean.TRUE);
                statementResult.setMessage(String.format("Query returned %d row(s)", statementResult.getRows().size()));
                statementResult.getSummary().put("rowCount", statementResult.getRows().size());
                return statementResult;
            } else {
                plugin.executeUpdate(dataSourceDTO, sql);
                statementResult.setQuery(Boolean.FALSE);
                statementResult.setAffectedRows(0);
                statementResult.setMessage("Statement executed successfully");
                statementResult.getSummary().put("affectedRows", 0);
                return statementResult;
            }
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "SQL execution failed: " + safeExceptionMessage(ex));
        } finally {
            statementResult.setExecutionMs(System.currentTimeMillis() - statementStartedAt);
        }
    }

    static boolean isSourcePluginQuerySql(String sql) {
        String keyword = firstSqlKeyword(sql);
        return keyword != null && SOURCE_PLUGIN_QUERY_KEYWORDS.contains(keyword);
    }

    private static String firstSqlKeyword(String sql) {
        if (sql == null) {
            return null;
        }
        int index = 0;
        int length = sql.length();
        while (index < length) {
            while (index < length && Character.isWhitespace(sql.charAt(index))) {
                index++;
            }
            if (index + 1 < length && sql.charAt(index) == '-' && sql.charAt(index + 1) == '-') {
                index += 2;
                while (index < length && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') {
                    index++;
                }
                continue;
            }
            if (index + 1 < length && sql.charAt(index) == '/' && sql.charAt(index + 1) == '*') {
                index += 2;
                while (index + 1 < length && !(sql.charAt(index) == '*' && sql.charAt(index + 1) == '/')) {
                    index++;
                }
                index = Math.min(length, index + 2);
                continue;
            }
            break;
        }
        if (index >= length) {
            return null;
        }
        int start = index;
        while (index < length) {
            char ch = sql.charAt(index);
            if (!Character.isLetter(ch) && ch != '_') {
                break;
            }
            index++;
        }
        if (index <= start) {
            return null;
        }
        return sql.substring(start, index).toLowerCase(Locale.ENGLISH);
    }

    private String safeExceptionMessage(Exception ex) {
        if (ex == null) {
            return "unknown error";
        }
        if (ex.getMessage() == null || ex.getMessage().trim().isEmpty()) {
            return ex.getClass().getSimpleName();
        }
        return ex.getMessage();
    }

    private void populatePluginQueryResult(SqlStatementExecutionResultView result,
                                           Table<Map<String, Object>> table,
                                           Integer maxRows) {
        List<String> columns = new ArrayList<String>();
        if (table != null && table.getHeaders() != null) {
            for (Table.Header header : table.getHeaders()) {
                if (header != null && header.getName() != null && !columns.contains(header.getName())) {
                    columns.add(header.getName());
                }
            }
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        int rowLimit = maxRows == null || maxRows.intValue() <= 0 ? Integer.MAX_VALUE : maxRows.intValue();
        if (table != null && table.getBodies() != null) {
            for (Map<String, Object> body : table.getBodies()) {
                if (body == null) {
                    continue;
                }
                if (rows.size() >= rowLimit) {
                    break;
                }
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                for (Map.Entry<String, Object> entry : body.entrySet()) {
                    if (!columns.contains(entry.getKey())) {
                        columns.add(entry.getKey());
                    }
                    row.put(entry.getKey(), normalizeJdbcValue(entry.getValue()));
                }
                rows.add(row);
            }
        }
        result.setColumns(columns);
        result.setRows(rows);
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
