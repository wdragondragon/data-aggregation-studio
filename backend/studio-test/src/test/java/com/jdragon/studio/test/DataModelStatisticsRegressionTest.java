package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DataModelStatisticsRegressionTest extends DataModelStatisticsTestSupport {

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

}
