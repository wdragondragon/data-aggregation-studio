package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.test.support.StudioApiRegressionTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DataModelStatisticsRegressionTest extends StudioApiRegressionTestSupport {

    private static final AtomicLong INDEX_ID_SEQUENCE = new AtomicLong(900000L);

    @Test
    void shouldStatisticStringFieldWithinCurrentProjectAndSharedModelsOnly() throws Exception {
        String authorization = adminAuthorizationHeader();
        Long tableSchemaVersionId = syncMysqlTechnicalTableSchema(authorization);
        Long ownerSchemaVersionId = createBusinessSchema(authorization,
                "business:stats:owner",
                "责任人信息",
                "table",
                "stats",
                "统计目录",
                "SINGLE",
                Arrays.asList(businessField("owner", "责任人", "STRING", true, false)));

        Long sourceProjectId = createProject(authorization, "source_stats", "Source Stats");
        Long targetProjectId = createProject(authorization, "target_stats", "Target Stats");

        Long sourceDatasourceId = createDatasource(authorization, sourceProjectId, "source-ds");
        Long targetDatasourceId = createDatasource(authorization, targetProjectId, "target-ds");

        Long sharedModelId = createModel(authorization, sourceProjectId, sourceDatasourceId, tableSchemaVersionId,
                "orders_shared", singleBusinessMetadata(ownerSchemaVersionId, "owner", "alice"));
        createModel(authorization, sourceProjectId, sourceDatasourceId, tableSchemaVersionId,
                "orders_private", singleBusinessMetadata(ownerSchemaVersionId, "owner", "bob"));
        Long selfModelId = createModel(authorization, targetProjectId, targetDatasourceId, tableSchemaVersionId,
                "orders_target", singleBusinessMetadata(ownerSchemaVersionId, "owner", "carol"));

        shareModel(authorization, sourceProjectId, targetProjectId, sharedModelId);
        insertForeignTenantLeakRow(selfModelId, targetDatasourceId, ownerSchemaVersionId,
                "business:stats:owner", "owner", "LEAK", targetProjectId);

        JsonNode body = statistics(authorization, targetProjectId, statisticsRequest("business:stats:owner", "owner", "COUNT_BY_VALUE"));
        assertThat(body.path("success").asBoolean()).isTrue();
        JsonNode data = body.path("data");
        assertThat(data.path("matchedModelCount").asLong()).isEqualTo(2L);
        assertThat(data.path("matchedItemCount").asLong()).isEqualTo(2L);

        Map<String, Long> buckets = bucketCountMap(data.path("buckets"));
        assertThat(buckets).containsEntry("alice", 1L);
        assertThat(buckets).containsEntry("carol", 1L);
        assertThat(buckets).doesNotContainKey("bob");
        assertThat(buckets).doesNotContainKey("LEAK");
    }

    @Test
    void shouldStatisticNumericSummaryAndBuckets() throws Exception {
        String authorization = adminAuthorizationHeader();
        Long tableSchemaVersionId = syncMysqlTechnicalTableSchema(authorization);
        Long scoreSchemaVersionId = createBusinessSchema(authorization,
                "business:risk:score",
                "风险评分",
                "table",
                "risk",
                "风险目录",
                "SINGLE",
                Arrays.asList(businessField("score", "评分", "INTEGER", true, false)));

        Long projectId = createProject(authorization, "numeric_stats", "Numeric Stats");
        Long datasourceId = createDatasource(authorization, projectId, "numeric-ds");

        createModel(authorization, projectId, datasourceId, tableSchemaVersionId,
                "risk_table_1", singleBusinessMetadata(scoreSchemaVersionId, "score", 10));
        createModel(authorization, projectId, datasourceId, tableSchemaVersionId,
                "risk_table_2", singleBusinessMetadata(scoreSchemaVersionId, "score", 20));
        createModel(authorization, projectId, datasourceId, tableSchemaVersionId,
                "risk_table_3", singleBusinessMetadata(scoreSchemaVersionId, "score", 20));

        JsonNode summaryBody = statistics(authorization, projectId, statisticsRequest("business:risk:score", "score", "SUMMARY"));
        JsonNode summaryData = summaryBody.path("data");
        assertThat(summaryData.path("matchedModelCount").asLong()).isEqualTo(3L);
        assertThat(summaryData.path("matchedItemCount").asLong()).isEqualTo(3L);
        assertThat(summaryData.path("summaryMetrics").path("count").asLong()).isEqualTo(3L);
        assertThat(summaryData.path("summaryMetrics").path("distinctCount").asLong()).isEqualTo(2L);
        assertThat(summaryData.path("summaryMetrics").path("min").decimalValue()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(summaryData.path("summaryMetrics").path("max").decimalValue()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(summaryData.path("summaryMetrics").path("sum").decimalValue()).isEqualByComparingTo(new BigDecimal("50"));
        assertThat(summaryData.path("summaryMetrics").path("avg").decimalValue()).isEqualByComparingTo(new BigDecimal("16.6666666667"));

        Map<String, Object> bucketRequest = statisticsRequest("business:risk:score", "score", "COUNT_BY_BUCKET");
        Map<String, Object> bucketConfig = new LinkedHashMap<String, Object>();
        bucketConfig.put("lowerBound", 10);
        bucketConfig.put("upperBound", 30);
        bucketConfig.put("step", 10);
        bucketRequest.put("bucketConfig", bucketConfig);

        JsonNode bucketBody = statistics(authorization, projectId, bucketRequest);
        JsonNode buckets = bucketBody.path("data").path("buckets");
        assertThat(buckets).hasSize(2);
        assertThat(buckets.get(0).path("count").asLong()).isEqualTo(1L);
        assertThat(buckets.get(1).path("count").asLong()).isEqualTo(2L);
    }

    @Test
    void shouldDifferentiateAnyItemAndSameItemForMultipleBusinessSchema() throws Exception {
        String authorization = adminAuthorizationHeader();
        Long tableSchemaVersionId = syncMysqlTechnicalTableSchema(authorization);
        Long qualitySchemaVersionId = createBusinessSchema(authorization,
                "business:quality:check",
                "质量检查",
                "field",
                "quality",
                "质量目录",
                "MULTIPLE",
                Arrays.asList(
                        businessField("label", "标签", "STRING", true, false),
                        businessField("category", "分类", "STRING", true, false),
                        businessField("amount", "数量", "INTEGER", true, false)
                ));

        Long projectId = createProject(authorization, "multiple_stats", "Multiple Stats");
        Long datasourceId = createDatasource(authorization, projectId, "multiple-ds");

        createModel(authorization, projectId, datasourceId, tableSchemaVersionId,
                "quality_table_1",
                multipleBusinessMetadata(qualitySchemaVersionId, Arrays.asList(
                        row("label", "apple", "category", "fruit", "amount", 5),
                        row("label", "carrot", "category", "vegetable", "amount", 7)
                )));
        createModel(authorization, projectId, datasourceId, tableSchemaVersionId,
                "quality_table_2",
                multipleBusinessMetadata(qualitySchemaVersionId, Arrays.asList(
                        row("label", "apple", "category", "vegetable", "amount", 9)
                )));

        Map<String, Object> anyItemRequest = statisticsRequest("business:quality:check", "amount", "SUMMARY");
        anyItemRequest.put("groups", Arrays.asList(queryGroup("business:quality:check", "ANY_ITEM",
                condition("label", "EQ", "apple"),
                condition("category", "EQ", "vegetable"))));
        JsonNode anyItemBody = statistics(authorization, projectId, anyItemRequest);
        JsonNode anyItemData = anyItemBody.path("data");
        assertThat(anyItemData.path("matchedModelCount").asLong()).isEqualTo(2L);
        assertThat(anyItemData.path("matchedItemCount").asLong()).isEqualTo(3L);
        assertThat(anyItemData.path("summaryMetrics").path("sum").decimalValue()).isEqualByComparingTo(new BigDecimal("21"));

        Map<String, Object> sameItemRequest = statisticsRequest("business:quality:check", "amount", "SUMMARY");
        sameItemRequest.put("groups", Arrays.asList(queryGroup("business:quality:check", "SAME_ITEM",
                condition("label", "EQ", "apple"),
                condition("category", "EQ", "vegetable"))));
        JsonNode sameItemBody = statistics(authorization, projectId, sameItemRequest);
        JsonNode sameItemData = sameItemBody.path("data");
        assertThat(sameItemData.path("matchedModelCount").asLong()).isEqualTo(1L);
        assertThat(sameItemData.path("matchedItemCount").asLong()).isEqualTo(1L);
        assertThat(sameItemData.path("summaryMetrics").path("sum").decimalValue()).isEqualByComparingTo(new BigDecimal("9"));
    }

    @Test
    void shouldAutoRebuildScopedIndexAfterBusinessSchemaSearchableChange() throws Exception {
        String authorization = adminAuthorizationHeader();
        Long tableSchemaVersionId = syncMysqlTechnicalTableSchema(authorization);
        Long ownerSchemaVersionId = createBusinessSchema(authorization,
                "business:auto:owner",
                "自动索引责任人",
                "table",
                "auto",
                "自动目录",
                "SINGLE",
                Arrays.asList(businessField("owner", "责任人", "STRING", false, false)));

        JsonNode schema = findSchemaByCode(authorization, "business:auto:owner");
        Long projectId = createProject(authorization, "auto_rebuild_stats", "Auto Rebuild Stats");
        Long datasourceId = createDatasource(authorization, projectId, "auto-rebuild-ds");

        createModel(authorization, projectId, datasourceId, tableSchemaVersionId,
                "auto_rebuild_table", singleBusinessMetadata(ownerSchemaVersionId, "owner", "alice"));

        mockMvc.perform(post("/api/v1/models/statistics")
                        .header("Authorization", authorization)
                        .header("X-Project-Id", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(statisticsRequest("business:auto:owner", "owner", "COUNT_BY_VALUE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        saveBusinessSchemaDraft(authorization,
                schema.path("id").asLong(),
                "business:auto:owner",
                "自动索引责任人",
                "table",
                "auto",
                "自动目录",
                "SINGLE",
                Arrays.asList(businessField("owner", "责任人", "STRING", true, false)));

        JsonNode body = statistics(authorization, projectId, statisticsRequest("business:auto:owner", "owner", "COUNT_BY_VALUE"));
        JsonNode data = body.path("data");
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(data.path("matchedModelCount").asLong()).isEqualTo(1L);
        assertThat(data.path("matchedItemCount").asLong()).isEqualTo(1L);
        assertThat(bucketCountMap(data.path("buckets"))).containsEntry("alice", 1L);
    }

    @Test
    void shouldRejectUnsupportedStatisticFields() throws Exception {
        String authorization = adminAuthorizationHeader();
        createBusinessSchema(authorization,
                "business:invalid:plain",
                "无索引字段",
                "table",
                "invalid",
                "异常目录",
                "SINGLE",
                Arrays.asList(businessField("plain", "普通字段", "STRING", false, false)));
        createBusinessSchema(authorization,
                "business:invalid:json",
                "JSON 字段",
                "table",
                "invalid",
                "异常目录",
                "SINGLE",
                Arrays.asList(businessField("payload", "负载", "JSON", true, false)));

        mockMvc.perform(post("/api/v1/models/statistics")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(statisticsRequest("business:invalid:plain", "plain", "COUNT_BY_VALUE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        mockMvc.perform(post("/api/v1/models/statistics")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(statisticsRequest("business:invalid:json", "payload", "COUNT_BY_VALUE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void shouldListStatisticsOptionsByScopeAndFieldEligibility() throws Exception {
        String authorization = adminAuthorizationHeader();
        syncMysqlTechnicalTableSchema(authorization);
        createBusinessSchema(authorization,
                "business:stats:eligible",
                "可统计业务信息",
                "table",
                "stats",
                "统计目录",
                "SINGLE",
                Arrays.asList(
                        businessField("owner", "责任人", "STRING", true, false),
                        businessField("secret", "敏感字段", "STRING", true, true),
                        businessField("payload", "载荷", "JSON", true, false),
                        businessField("hidden", "隐藏字段", "STRING", false, false)
                ));
        createBusinessSchema(authorization,
                "business:stats:topic_only",
                "消息业务信息",
                "topic",
                "stats",
                "统计目录",
                "SINGLE",
                Arrays.asList(businessField("tag", "标签", "STRING", true, false)));

        Map<String, Object> businessPayload = new LinkedHashMap<String, Object>();
        businessPayload.put("targetScope", "BUSINESS");
        MvcResult businessResult = mockMvc.perform(post("/api/v1/statistics/options")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(businessPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        JsonNode businessData = readBody(businessResult).path("data");
        assertThat(schemaCodes(businessData.path("targetSchemas")))
                .contains("business:stats:eligible")
                .contains("business:stats:topic_only")
                .doesNotContain("technical:mysql8:table");
        assertThat(schemaCodes(businessData.path("querySchemas")))
                .contains("business:stats:eligible")
                .contains("business:stats:topic_only")
                .doesNotContain("technical:mysql8:table");

        JsonNode businessSchema = findSchemaOptionByCode(businessData.path("targetSchemas"), "business:stats:eligible");
        assertThat(fieldKeys(businessSchema.path("fields"))).containsExactly("owner");

        Map<String, Object> narrowedBusinessPayload = new LinkedHashMap<String, Object>();
        narrowedBusinessPayload.put("targetScope", "BUSINESS");
        narrowedBusinessPayload.put("datasourceType", "mysql8");
        MvcResult narrowedBusinessResult = mockMvc.perform(post("/api/v1/statistics/options")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(narrowedBusinessPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        JsonNode narrowedBusinessData = readBody(narrowedBusinessResult).path("data");
        assertThat(schemaCodes(narrowedBusinessData.path("targetSchemas")))
                .contains("business:stats:eligible")
                .doesNotContain("technical:mysql8:table")
                .doesNotContain("business:stats:topic_only");

        Map<String, Object> technicalWithoutTypePayload = new LinkedHashMap<String, Object>();
        technicalWithoutTypePayload.put("targetScope", "TECHNICAL");
        MvcResult technicalWithoutTypeResult = mockMvc.perform(post("/api/v1/statistics/options")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(technicalWithoutTypePayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        JsonNode technicalWithoutTypeData = readBody(technicalWithoutTypeResult).path("data");
        assertThat(technicalWithoutTypeData.path("targetSchemas").size()).isEqualTo(0);
        assertThat(technicalWithoutTypeData.path("querySchemas").size()).isEqualTo(0);

        Map<String, Object> technicalPayload = new LinkedHashMap<String, Object>();
        technicalPayload.put("targetScope", "TECHNICAL");
        technicalPayload.put("datasourceType", "mysql8");
        MvcResult technicalResult = mockMvc.perform(post("/api/v1/statistics/options")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(technicalPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        JsonNode technicalData = readBody(technicalResult).path("data");
        assertThat(schemaCodes(technicalData.path("targetSchemas")))
                .contains("technical:mysql8:table")
                .doesNotContain("business:stats:eligible");
        assertThat(schemaCodes(technicalData.path("querySchemas")))
                .contains("technical:mysql8:table")
                .contains("business:stats:eligible")
                .doesNotContain("business:stats:topic_only");
    }

    @Test
    void shouldQueryChartViewsForSingleBusinessField() throws Exception {
        String authorization = adminAuthorizationHeader();
        Long tableSchemaVersionId = syncMysqlTechnicalTableSchema(authorization);
        Long ownerSchemaVersionId = createBusinessSchema(authorization,
                "business:chart:owner",
                "图表责任人",
                "table",
                "chart",
                "图表目录",
                "SINGLE",
                Arrays.asList(businessField("owner", "责任人", "STRING", true, false)));

        Long projectId = createProject(authorization, "chart_stats", "Chart Stats");
        Long datasourceId = createDatasource(authorization, projectId, "chart-ds");

        createModel(authorization, projectId, datasourceId, tableSchemaVersionId,
                "chart_table_1", singleBusinessMetadata(ownerSchemaVersionId, "owner", "alice"));
        createModel(authorization, projectId, datasourceId, tableSchemaVersionId,
                "chart_table_2", singleBusinessMetadata(ownerSchemaVersionId, "owner", "bob"));
        createModel(authorization, projectId, datasourceId, tableSchemaVersionId,
                "chart_table_3", singleBusinessMetadata(ownerSchemaVersionId, "owner", "bob"));

        JsonNode bar = chartQuery(authorization, projectId, chartRequest("business:chart:owner", "owner", "BUSINESS", "BAR"));
        assertThat(bar.path("data").path("xAxis")).hasSize(2);
        assertThat(bar.path("data").path("tableRows").get(0).path("count").asLong()).isEqualTo(2L);

        JsonNode pie = chartQuery(authorization, projectId, chartRequest("business:chart:owner", "owner", "BUSINESS", "PIE"));
        assertThat(pie.path("data").path("series").get(0).path("type").asText()).isEqualTo("pie");
        assertThat(pie.path("data").path("tableRows")).hasSize(2);

        JsonNode topN = chartQuery(authorization, projectId, chartRequest("business:chart:owner", "owner", "BUSINESS", "TOPN"));
        assertThat(topN.path("data").path("tableRows").get(0).path("label").asText()).isEqualTo("bob");
        assertThat(topN.path("data").path("tableRows").get(0).path("count").asLong()).isEqualTo(2L);

        Map<String, Object> trendRequest = chartRequest("business:chart:owner", "owner", "BUSINESS", "TREND");
        trendRequest.put("days", 7);
        JsonNode trend = chartQuery(authorization, projectId, trendRequest);
        assertThat(trend.path("data").path("xAxis")).hasSize(7);
        assertThat(trend.path("data").path("summaryMetrics").path("matchedModelCount").asLong()).isEqualTo(3L);
        long trendCount = 0L;
        for (JsonNode row : trend.path("data").path("tableRows")) {
            trendCount += row.path("count").asLong();
        }
        assertThat(trendCount).isEqualTo(3L);
    }

    @Test
    void shouldApplyTopNOnlyToRankingChart() throws Exception {
        String authorization = adminAuthorizationHeader();
        Long tableSchemaVersionId = syncMysqlTechnicalTableSchema(authorization);
        Long ownerSchemaVersionId = createBusinessSchema(authorization,
                "business:chart:topn_only",
                "TopN 仅排行",
                "table",
                "chart",
                "图表目录",
                "SINGLE",
                Arrays.asList(businessField("owner", "责任人", "STRING", true, false)));

        Long projectId = createProject(authorization, "chart_topn_only", "Chart TopN Only");
        Long datasourceId = createDatasource(authorization, projectId, "chart-topn-only-ds");

        createModel(authorization, projectId, datasourceId, tableSchemaVersionId,
                "chart_topn_only_1", singleBusinessMetadata(ownerSchemaVersionId, "owner", "alice"));
        createModel(authorization, projectId, datasourceId, tableSchemaVersionId,
                "chart_topn_only_2", singleBusinessMetadata(ownerSchemaVersionId, "owner", "bob"));
        createModel(authorization, projectId, datasourceId, tableSchemaVersionId,
                "chart_topn_only_3", singleBusinessMetadata(ownerSchemaVersionId, "owner", "carol"));
        createModel(authorization, projectId, datasourceId, tableSchemaVersionId,
                "chart_topn_only_4", singleBusinessMetadata(ownerSchemaVersionId, "owner", "carol"));

        Map<String, Object> barRequest = chartRequest("business:chart:topn_only", "owner", "BUSINESS", "BAR");
        barRequest.put("topN", 1);
        JsonNode bar = chartQuery(authorization, projectId, barRequest);
        assertThat(bar.path("data").path("tableRows")).hasSize(3);

        Map<String, Object> pieRequest = chartRequest("business:chart:topn_only", "owner", "BUSINESS", "PIE");
        pieRequest.put("topN", 1);
        JsonNode pie = chartQuery(authorization, projectId, pieRequest);
        assertThat(pie.path("data").path("tableRows")).hasSize(3);

        Map<String, Object> topNRequest = chartRequest("business:chart:topn_only", "owner", "BUSINESS", "TOPN");
        topNRequest.put("topN", 1);
        JsonNode topN = chartQuery(authorization, projectId, topNRequest);
        assertThat(topN.path("data").path("tableRows")).hasSize(1);
        assertThat(topN.path("data").path("tableRows").get(0).path("label").asText()).isEqualTo("carol");
        assertThat(topN.path("data").path("tableRows").get(0).path("count").asLong()).isEqualTo(2L);
    }

    @Test
    void shouldAutoBucketNumericBarChartAndIgnoreIncomingBucketConfig() throws Exception {
        String authorization = adminAuthorizationHeader();
        Long tableSchemaVersionId = syncMysqlTechnicalTableSchema(authorization);
        Long scoreSchemaVersionId = createBusinessSchema(authorization,
                "business:chart:auto_bucket",
                "自动分桶评分",
                "table",
                "chart",
                "图表目录",
                "SINGLE",
                Arrays.asList(businessField("score", "评分", "INTEGER", true, false)));

        Long projectId = createProject(authorization, "chart_auto_bucket", "Chart Auto Bucket");
        Long datasourceId = createDatasource(authorization, projectId, "chart-auto-bucket-ds");

        createModel(authorization, projectId, datasourceId, tableSchemaVersionId,
                "chart_auto_bucket_1", singleBusinessMetadata(scoreSchemaVersionId, "score", 3));
        createModel(authorization, projectId, datasourceId, tableSchemaVersionId,
                "chart_auto_bucket_2", singleBusinessMetadata(scoreSchemaVersionId, "score", 17));
        createModel(authorization, projectId, datasourceId, tableSchemaVersionId,
                "chart_auto_bucket_3", singleBusinessMetadata(scoreSchemaVersionId, "score", 24));
        createModel(authorization, projectId, datasourceId, tableSchemaVersionId,
                "chart_auto_bucket_4", singleBusinessMetadata(scoreSchemaVersionId, "score", 39));
        createModel(authorization, projectId, datasourceId, tableSchemaVersionId,
                "chart_auto_bucket_5", singleBusinessMetadata(scoreSchemaVersionId, "score", 42));

        Map<String, Object> request = chartRequest("business:chart:auto_bucket", "score", "BUSINESS", "BAR");
        Map<String, Object> bucketConfig = new LinkedHashMap<String, Object>();
        bucketConfig.put("lowerBound", 10);
        bucketConfig.put("upperBound", 30);
        bucketConfig.put("step", 1);
        request.put("bucketConfig", bucketConfig);

        JsonNode bar = chartQuery(authorization, projectId, request);
        JsonNode data = bar.path("data");
        assertThat(data.path("summaryMetrics").path("effectiveLowerBound").decimalValue()).isEqualByComparingTo(new BigDecimal("0"));
        assertThat(data.path("summaryMetrics").path("effectiveUpperBound").decimalValue()).isEqualByComparingTo(new BigDecimal("50"));
        assertThat(data.path("summaryMetrics").path("effectiveStep").decimalValue()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(data.path("summaryMetrics").path("effectiveBucketCount").asLong()).isEqualTo(5L);
        assertThat(data.path("tableRows")).hasSize(5);

        long total = 0L;
        for (JsonNode row : data.path("tableRows")) {
            total += row.path("count").asLong();
        }
        assertThat(total).isEqualTo(5L);
    }

    @Test
    void shouldCollapseIdenticalNumericValuesIntoSingleAutomaticBucket() throws Exception {
        String authorization = adminAuthorizationHeader();
        Long tableSchemaVersionId = syncMysqlTechnicalTableSchema(authorization);
        Long scoreSchemaVersionId = createBusinessSchema(authorization,
                "business:chart:single_bucket",
                "单桶评分",
                "table",
                "chart",
                "图表目录",
                "SINGLE",
                Arrays.asList(businessField("score", "评分", "INTEGER", true, false)));

        Long projectId = createProject(authorization, "chart_single_bucket", "Chart Single Bucket");
        Long datasourceId = createDatasource(authorization, projectId, "chart-single-bucket-ds");

        createModel(authorization, projectId, datasourceId, tableSchemaVersionId,
                "chart_single_bucket_1", singleBusinessMetadata(scoreSchemaVersionId, "score", 7));
        createModel(authorization, projectId, datasourceId, tableSchemaVersionId,
                "chart_single_bucket_2", singleBusinessMetadata(scoreSchemaVersionId, "score", 7));
        createModel(authorization, projectId, datasourceId, tableSchemaVersionId,
                "chart_single_bucket_3", singleBusinessMetadata(scoreSchemaVersionId, "score", 7));

        JsonNode bar = chartQuery(authorization, projectId, chartRequest("business:chart:single_bucket", "score", "BUSINESS", "BAR"));
        JsonNode data = bar.path("data");
        assertThat(data.path("summaryMetrics").path("effectiveLowerBound").decimalValue()).isEqualByComparingTo(new BigDecimal("7"));
        assertThat(data.path("summaryMetrics").path("effectiveUpperBound").decimalValue()).isEqualByComparingTo(new BigDecimal("7"));
        assertThat(data.path("summaryMetrics").path("effectiveBucketCount").asLong()).isEqualTo(1L);
        assertThat(data.path("tableRows")).hasSize(1);
        assertThat(data.path("tableRows").get(0).path("count").asLong()).isEqualTo(3L);
    }

    @Test
    void shouldDisableTrendChartForMultipleMetaModel() throws Exception {
        String authorization = adminAuthorizationHeader();
        Long tableSchemaVersionId = syncMysqlTechnicalTableSchema(authorization);
        Long qualitySchemaVersionId = createBusinessSchema(authorization,
                "business:chart:multiple",
                "图表明细",
                "field",
                "chart",
                "图表目录",
                "MULTIPLE",
                Arrays.asList(
                        businessField("label", "标签", "STRING", true, false),
                        businessField("score", "评分", "INTEGER", true, false)
                ));

        Long projectId = createProject(authorization, "chart_multiple_stats", "Chart Multiple Stats");
        Long datasourceId = createDatasource(authorization, projectId, "chart-multi-ds");

        createModel(authorization, projectId, datasourceId, tableSchemaVersionId,
                "chart_multi_table",
                multipleBusinessMetadata(qualitySchemaVersionId, Arrays.asList(
                        row("label", "apple", "score", 5),
                        row("label", "banana", "score", 7)
                )));

        JsonNode trend = chartQuery(authorization, projectId, chartRequest("business:chart:multiple", "label", "BUSINESS", "TREND"));
        assertThat(trend.path("data").path("disabledReason").asText()).isNotBlank();
    }

    private Long syncMysqlTechnicalTableSchema(String authorization) throws Exception {
        mockMvc.perform(post("/api/v1/meta-schemas/technical/sync/mysql8")
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        MvcResult result = mockMvc.perform(get("/api/v1/meta-schemas")
                        .header("Authorization", authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        for (JsonNode schema : readBody(result).path("data")) {
            if ("technical:mysql8:table".equals(schema.path("schemaCode").asText())) {
                return schema.path("currentVersionId").asLong();
            }
        }
        throw new IllegalStateException("Unable to resolve technical:mysql8:table currentVersionId");
    }

    private Long createBusinessSchema(String authorization,
                                      String schemaCode,
                                      String schemaName,
                                      String metaModelCode,
                                      String directoryCode,
                                      String directoryName,
                                      String displayMode,
                                      List<Map<String, Object>> fields) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("schemaCode", schemaCode);
        payload.put("schemaName", schemaName);
        payload.put("objectType", "business");
        payload.put("typeCode", directoryCode + "." + metaModelCode);
        payload.put("description", "META_MODEL_CONFIG:{\"domain\":\"BUSINESS\",\"directoryCode\":\"" + directoryCode
                + "\",\"directoryName\":\"" + directoryName + "\",\"metaModelCode\":\"" + metaModelCode
                + "\",\"metaModelName\":\"" + schemaName + "\",\"displayMode\":\"" + displayMode
                + "\",\"required\":false}");
        payload.put("fields", fields);

        MvcResult draftResult = mockMvc.perform(post("/api/v1/meta-schemas/draft")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        Long schemaId = readBody(draftResult).path("data").path("id").asLong();
        mockMvc.perform(post("/api/v1/meta-schemas/{schemaId}/publish", schemaId)
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        MvcResult listResult = mockMvc.perform(get("/api/v1/meta-schemas")
                        .header("Authorization", authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode schema : readBody(listResult).path("data")) {
            if (schemaCode.equals(schema.path("schemaCode").asText())) {
                return schema.path("currentVersionId").asLong();
            }
        }
        throw new IllegalStateException("Unable to resolve currentVersionId for schema " + schemaCode);
    }

    private void saveBusinessSchemaDraft(String authorization,
                                         Long schemaId,
                                         String schemaCode,
                                         String schemaName,
                                         String metaModelCode,
                                         String directoryCode,
                                         String directoryName,
                                         String displayMode,
                                         List<Map<String, Object>> fields) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("schemaId", schemaId);
        payload.put("schemaCode", schemaCode);
        payload.put("schemaName", schemaName);
        payload.put("objectType", "business");
        payload.put("typeCode", directoryCode + "." + metaModelCode);
        payload.put("description", "META_MODEL_CONFIG:{\"domain\":\"BUSINESS\",\"directoryCode\":\"" + directoryCode
                + "\",\"directoryName\":\"" + directoryName + "\",\"metaModelCode\":\"" + metaModelCode
                + "\",\"metaModelName\":\"" + schemaName + "\",\"displayMode\":\"" + displayMode
                + "\",\"required\":false}");
        payload.put("fields", fields);

        mockMvc.perform(post("/api/v1/meta-schemas/draft")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        awaitIndexQueueIdle();
    }

    private JsonNode findSchemaByCode(String authorization,
                                      String schemaCode) throws Exception {
        MvcResult listResult = mockMvc.perform(get("/api/v1/meta-schemas")
                        .header("Authorization", authorization)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode schema : readBody(listResult).path("data")) {
            if (schemaCode.equals(schema.path("schemaCode").asText())) {
                return schema;
            }
        }
        throw new IllegalStateException("Unable to resolve schema by code " + schemaCode);
    }

    private Map<String, Object> businessField(String fieldKey,
                                              String fieldName,
                                              String valueType,
                                              boolean searchable,
                                              boolean sensitive) {
        Map<String, Object> field = new LinkedHashMap<String, Object>();
        field.put("fieldKey", fieldKey);
        field.put("fieldName", fieldName);
        field.put("scope", "BUSINESS");
        field.put("valueType", valueType);
        field.put("componentType", componentType(valueType));
        field.put("required", false);
        field.put("sensitive", sensitive);
        field.put("searchable", searchable);
        field.put("sortable", searchable);
        field.put("queryOperators", queryOperators(valueType));
        field.put("queryDefaultOperator", defaultOperator(valueType));
        return field;
    }

    private String componentType(String valueType) {
        if ("INTEGER".equalsIgnoreCase(valueType) || "LONG".equalsIgnoreCase(valueType) || "DECIMAL".equalsIgnoreCase(valueType)) {
            return "NUMBER";
        }
        if ("BOOLEAN".equalsIgnoreCase(valueType)) {
            return "SWITCH";
        }
        if ("JSON".equalsIgnoreCase(valueType)) {
            return "JSON_EDITOR";
        }
        return "INPUT";
    }

    private List<String> queryOperators(String valueType) {
        if ("INTEGER".equalsIgnoreCase(valueType) || "LONG".equalsIgnoreCase(valueType) || "DECIMAL".equalsIgnoreCase(valueType)) {
            return Arrays.asList("EQ", "GT", "GE", "LT", "LE", "BETWEEN", "IN");
        }
        if ("BOOLEAN".equalsIgnoreCase(valueType)) {
            return Arrays.asList("EQ");
        }
        if ("STRING".equalsIgnoreCase(valueType)) {
            return Arrays.asList("EQ", "LIKE", "IN");
        }
        return new ArrayList<String>();
    }

    private String defaultOperator(String valueType) {
        return "STRING".equalsIgnoreCase(valueType) ? "LIKE" : "EQ";
    }

    private Long createProject(String authorization, String projectCode, String projectName) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("projectCode", projectCode);
        payload.put("projectName", projectName);
        payload.put("enabled", 1);
        payload.put("defaultProject", 0);
        MvcResult result = mockMvc.perform(post("/api/v1/system/projects")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return readBody(result).path("data").path("id").asLong();
    }

    private Long createDatasource(String authorization, Long projectId, String name) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("name", name);
        payload.put("typeCode", "mysql8");
        payload.put("enabled", true);
        payload.put("executable", false);
        payload.put("technicalMetadata", minimalSqlMetadata());
        payload.put("businessMetadata", new LinkedHashMap<String, Object>());

        MvcResult result = mockMvc.perform(post("/api/v1/datasources")
                        .header("Authorization", authorization)
                        .header("X-Project-Id", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return readBody(result).path("data").path("id").asLong();
    }

    private Long createModel(String authorization,
                             Long projectId,
                             Long datasourceId,
                             Long schemaVersionId,
                             String name,
                             Map<String, Object> businessMetadata) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("datasourceId", datasourceId);
        payload.put("name", name);
        payload.put("physicalLocator", name);
        payload.put("modelKind", "TABLE");
        payload.put("schemaVersionId", schemaVersionId);
        payload.put("technicalMetadata", new LinkedHashMap<String, Object>());
        payload.put("businessMetadata", businessMetadata);

        MvcResult result = mockMvc.perform(post("/api/v1/models")
                        .header("Authorization", authorization)
                        .header("X-Project-Id", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        awaitIndexQueueIdle();
        return readBody(result).path("data").path("id").asLong();
    }

    private void shareModel(String authorization,
                            Long sourceProjectId,
                            Long targetProjectId,
                            Long modelId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("sourceProjectId", sourceProjectId);
        payload.put("targetProjectId", targetProjectId);
        payload.put("resourceType", StudioConstants.RESOURCE_TYPE_DATA_MODEL);
        payload.put("resourceId", modelId);
        payload.put("enabled", 1);

        mockMvc.perform(post("/api/v1/system/resource-shares")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private JsonNode statistics(String authorization,
                                Long projectId,
                                Map<String, Object> payload) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/models/statistics")
                        .header("Authorization", authorization)
                        .header("X-Project-Id", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return readBody(result);
    }

    private JsonNode chartQuery(String authorization,
                                Long projectId,
                                Map<String, Object> payload) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/statistics/charts/query")
                        .header("Authorization", authorization)
                        .header("X-Project-Id", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return readBody(result);
    }

    private Map<String, Object> statisticsRequest(String schemaCode, String fieldKey, String statType) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("targetMetaSchemaCode", schemaCode);
        payload.put("targetFieldKey", fieldKey);
        payload.put("targetScope", "BUSINESS");
        payload.put("statType", statType);
        return payload;
    }

    private Map<String, Object> chartRequest(String schemaCode,
                                             String fieldKey,
                                             String targetScope,
                                             String chartType) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("targetMetaSchemaCode", schemaCode);
        payload.put("targetFieldKey", fieldKey);
        payload.put("targetScope", targetScope);
        payload.put("chartType", chartType);
        payload.put("topN", 10);
        payload.put("timeMode", "CREATED_AT");
        return payload;
    }

    private Map<String, Object> singleBusinessMetadata(Long schemaVersionId, String fieldKey, Object value) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("schemaVersionId", schemaVersionId);
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(fieldKey, value);
        entry.put("values", values);
        entries.add(entry);
        metadata.put("__metaModels", entries);
        return metadata;
    }

    private Map<String, Object> multipleBusinessMetadata(Long schemaVersionId, List<Map<String, Object>> rows) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("schemaVersionId", schemaVersionId);
        entry.put("rows", rows);
        entries.add(entry);
        metadata.put("__metaModels", entries);
        return metadata;
    }

    private Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            row.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return row;
    }

    private Map<String, Object> queryGroup(String schemaCode, String rowMatchMode, Map<String, Object>... conditions) {
        Map<String, Object> group = new LinkedHashMap<String, Object>();
        group.put("scope", "BUSINESS");
        group.put("metaSchemaCode", schemaCode);
        group.put("rowMatchMode", rowMatchMode);
        group.put("conditions", Arrays.asList(conditions));
        return group;
    }

    private Map<String, Object> condition(String fieldKey, String operator, Object value) {
        Map<String, Object> condition = new LinkedHashMap<String, Object>();
        condition.put("fieldKey", fieldKey);
        condition.put("operator", operator);
        condition.put("value", value);
        return condition;
    }

    private Map<String, Long> bucketCountMap(JsonNode buckets) {
        Map<String, Long> result = new LinkedHashMap<String, Long>();
        for (JsonNode bucket : buckets) {
            result.put(bucket.path("value").asText(), bucket.path("count").asLong());
        }
        return result;
    }

    private List<String> schemaCodes(JsonNode schemas) {
        List<String> result = new ArrayList<String>();
        for (JsonNode schema : schemas) {
            result.add(schema.path("schemaCode").asText());
        }
        return result;
    }

    private List<String> fieldKeys(JsonNode fields) {
        List<String> result = new ArrayList<String>();
        for (JsonNode field : fields) {
            result.add(field.path("fieldKey").asText());
        }
        return result;
    }

    private JsonNode findSchemaOptionByCode(JsonNode schemas, String schemaCode) {
        for (JsonNode schema : schemas) {
            if (schemaCode.equals(schema.path("schemaCode").asText())) {
                return schema;
            }
        }
        throw new IllegalStateException("Unable to resolve schema option by code " + schemaCode);
    }

    private Map<String, Object> mapOf(String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put(key, value);
        return result;
    }

    private void insertForeignTenantLeakRow(Long modelId,
                                            Long datasourceId,
                                            Long schemaVersionId,
                                            String schemaCode,
                                            String fieldKey,
                                            String value,
                                            Long projectId) {
        jdbcTemplate.update("insert into data_model_attr_index (" +
                        "id, tenant_id, project_id, model_id, datasource_id, meta_schema_version_id, " +
                        "meta_schema_code, scope, meta_model_code, item_key, field_key, value_type, keyword_value, text_value, raw_value" +
                        ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                INDEX_ID_SEQUENCE.incrementAndGet(),
                "other-tenant",
                projectId,
                modelId,
                datasourceId,
                schemaVersionId,
                schemaCode,
                "BUSINESS",
                "owner",
                "__single__",
                fieldKey,
                "STRING",
                value,
                value.toLowerCase(),
                value);
    }

    private Map<String, Object> minimalSqlMetadata() {
        Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();
        technicalMetadata.put("host", "127.0.0.1");
        technicalMetadata.put("port", "3306");
        technicalMetadata.put("database", "demo");
        technicalMetadata.put("userName", "root");
        technicalMetadata.put("password", "root");
        return technicalMetadata;
    }
}
