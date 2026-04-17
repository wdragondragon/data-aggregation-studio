package com.jdragon.studio.test;

import com.jdragon.studio.test.support.StudioApiRegressionTestSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StudioInitializationServiceRegressionTest extends StudioApiRegressionTestSupport {

    @Test
    void initializeResetShouldBootstrapBuiltinFieldMappingAndQualityRules() {
        studioInitializationService.initialize(true);

        Integer fieldMappingRuleCount = jdbcTemplate.queryForObject(
                "select count(1) from field_mapping_rule where deleted = 0", Integer.class);
        Integer fieldMappingParamCount = jdbcTemplate.queryForObject(
                "select count(1) from field_mapping_rule_param where deleted = 0", Integer.class);
        Integer qualityRuleCount = jdbcTemplate.queryForObject(
                "select count(1) from quality_rule where deleted = 0 and tenant_id = 'default' and scope_type = 'SYSTEM'", Integer.class);
        Integer qualityInputParamCount = jdbcTemplate.queryForObject(
                "select count(1) from quality_rule_input_param where deleted = 0", Integer.class);
        Integer qualityOutputParamCount = jdbcTemplate.queryForObject(
                "select count(1) from quality_rule_output_param where deleted = 0", Integer.class);
        Integer hasRangeFilterRule = jdbcTemplate.queryForObject(
                "select count(1) from field_mapping_rule where deleted = 0 and mapping_code = 'range_number_filter'", Integer.class);
        Integer hasTableRowQualityRule = jdbcTemplate.queryForObject(
                "select count(1) from quality_rule where deleted = 0 and rule_code = 'DQ_TABLE_ROW_COUNT'", Integer.class);

        assertThat(fieldMappingRuleCount).isEqualTo(26);
        assertThat(fieldMappingParamCount).isGreaterThan(0);
        assertThat(qualityRuleCount).isEqualTo(17);
        assertThat(qualityInputParamCount).isGreaterThan(0);
        assertThat(qualityOutputParamCount).isEqualTo(17);
        assertThat(hasRangeFilterRule).isEqualTo(1);
        assertThat(hasTableRowQualityRule).isEqualTo(1);
    }

    @Test
    void initializeResetShouldClearDynamicModelAttributeIndexTable() {
        jdbcTemplate.update("insert into data_model_attr_index (" +
                        "id, tenant_id, deleted, created_at, updated_at, model_id, datasource_id, meta_schema_version_id, " +
                        "meta_schema_code, scope, meta_model_code, item_key, field_key, value_type, keyword_value, text_value, number_value, bool_value, raw_value" +
                        ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L,
                "default",
                0,
                "2026-04-03T00:00:00",
                "2026-04-03T00:00:00",
                100L,
                200L,
                300L,
                "technical:mysql8:table",
                "TECHNICAL",
                "table",
                "__single__",
                "physicalName",
                "STRING",
                "orders",
                "orders",
                null,
                null,
                "\"orders\"");

        Integer before = jdbcTemplate.queryForObject("select count(1) from data_model_attr_index", Integer.class);
        assertThat(before).isEqualTo(1);

        studioInitializationService.initialize(true);

        Integer after = jdbcTemplate.queryForObject("select count(1) from data_model_attr_index", Integer.class);
        assertThat(after).isZero();
    }
}
