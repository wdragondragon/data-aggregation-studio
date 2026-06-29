package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.enums.MetadataScope;
import com.jdragon.studio.dto.model.DataModelStatisticsBucketView;
import com.jdragon.studio.dto.model.DataModelStatisticsView;
import com.jdragon.studio.dto.model.MetadataFieldDefinition;
import com.jdragon.studio.dto.model.MetadataSchemaDefinition;
import com.jdragon.studio.dto.model.request.DataModelStatisticsRequest;
import com.jdragon.studio.infra.entity.DataModelAttrIndexEntity;
import com.jdragon.studio.infra.mapper.DataModelAttrIndexMapper;
import com.jdragon.studio.infra.model.DataModelStatisticsBucketAggregate;
import com.jdragon.studio.infra.model.DataModelStatisticsSummaryAggregate;
import com.jdragon.studio.infra.service.DataModelAccessScopeService;
import com.jdragon.studio.infra.service.DataModelSearchIndexService;
import com.jdragon.studio.infra.service.DataModelStatisticsService;
import com.jdragon.studio.infra.service.MetadataSchemaService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataModelStatisticsSourceSlimmingRegressionTest {

    @Test
    void statisticsShouldAggregateBucketsAtSourceWithoutLoadingTargetRows() {
        DataModelAttrIndexMapper indexMapper = mock(DataModelAttrIndexMapper.class);
        MetadataSchemaService metadataSchemaService = mock(MetadataSchemaService.class);
        DataModelSearchIndexService searchIndexService = mock(DataModelSearchIndexService.class);
        DataModelAccessScopeService accessScopeService = mock(DataModelAccessScopeService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        DataModelStatisticsService service = new DataModelStatisticsService(indexMapper,
                metadataSchemaService,
                searchIndexService,
                accessScopeService,
                securityService);
        MetadataSchemaDefinition schema = customerSegmentSchema();
        when(metadataSchemaService.getSchemaByCode("business:客户画像:分层")).thenReturn(schema);
        when(metadataSchemaService.getSchemaDomain(schema)).thenReturn("BUSINESS");
        when(accessScopeService.listAccessibleModelIds(null, null))
                .thenReturn(new LinkedHashSet<Long>(Arrays.asList(101L, 102L, 103L)));
        when(securityService.currentTenantId()).thenReturn("default");

        DataModelStatisticsSummaryAggregate summary = new DataModelStatisticsSummaryAggregate();
        summary.setMatchedModelCount(3L);
        summary.setMatchedItemCount(3L);
        summary.setDistinctCount(2L);
        summary.setNumericCount(0L);
        when(indexMapper.selectStatisticsSummary(eq("default"),
                eq("business:客户画像:分层"),
                eq("BUSINESS"),
                eq("customerSegment"),
                anyList(),
                isNull())).thenReturn(summary);

        DataModelStatisticsBucketAggregate vip = bucket("高价值客户", 2L);
        DataModelStatisticsBucketAggregate normal = bucket("成长客户", 1L);
        when(indexMapper.selectStatisticsValueBuckets(eq("default"),
                eq("business:客户画像:分层"),
                eq("BUSINESS"),
                eq("customerSegment"),
                anyList(),
                isNull(),
                isNull())).thenReturn(Arrays.asList(vip, normal));

        DataModelStatisticsRequest request = new DataModelStatisticsRequest();
        request.setTargetMetaSchemaCode("business:客户画像:分层");
        request.setTargetFieldKey("customerSegment");
        request.setTargetScope("BUSINESS");
        request.setStatType("COUNT_BY_VALUE");

        DataModelStatisticsView view = service.statistics(request);

        assertThat(view.getMatchedModelCount()).isEqualTo(3L);
        assertThat(view.getMatchedItemCount()).isEqualTo(3L);
        assertThat(view.getBuckets())
                .extracting(DataModelStatisticsBucketView::getLabel)
                .containsExactly("高价值客户", "成长客户");
        verify(indexMapper).selectStatisticsSummary(eq("default"),
                eq("business:客户画像:分层"),
                eq("BUSINESS"),
                eq("customerSegment"),
                anyList(),
                isNull());
        verify(indexMapper).selectStatisticsValueBuckets(eq("default"),
                eq("business:客户画像:分层"),
                eq("BUSINESS"),
                eq("customerSegment"),
                anyList(),
                isNull(),
                isNull());
        verify(indexMapper, never()).selectList(any(Wrapper.class));
    }

    private MetadataSchemaDefinition customerSegmentSchema() {
        MetadataFieldDefinition field = new MetadataFieldDefinition();
        field.setFieldKey("customerSegment");
        field.setFieldName("客户分层");
        field.setScope(MetadataScope.BUSINESS);
        field.setValueType(FieldValueType.STRING);
        field.setSearchable(true);
        field.setSensitive(false);

        MetadataSchemaDefinition schema = new MetadataSchemaDefinition();
        schema.setSchemaCode("business:客户画像:分层");
        schema.setSchemaName("客户画像分层");
        schema.setFields(Arrays.asList(field));
        return schema;
    }

    private DataModelStatisticsBucketAggregate bucket(String label, Long count) {
        DataModelStatisticsBucketAggregate bucket = new DataModelStatisticsBucketAggregate();
        bucket.setBucketKey(label);
        bucket.setCount(count);
        return bucket;
    }
}
