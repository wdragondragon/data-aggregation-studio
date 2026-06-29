package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.DataModelAttrIndexEntity;
import com.jdragon.studio.infra.model.DataModelStatisticsBucketAggregate;
import com.jdragon.studio.infra.model.DataModelStatisticsBucketRange;
import com.jdragon.studio.infra.model.DataModelStatisticsSummaryAggregate;
import com.jdragon.studio.infra.model.DataModelStatisticsTrendAggregate;
import com.jdragon.studio.infra.service.DataModelMatchUnit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DataModelAttrIndexMapper extends BaseMapper<DataModelAttrIndexEntity> {

    @SelectProvider(type = DataModelStatisticsSqlProvider.class, method = "selectStatisticsSummary")
    DataModelStatisticsSummaryAggregate selectStatisticsSummary(@Param("tenantId") String tenantId,
                                                                @Param("targetMetaSchemaCode") String targetMetaSchemaCode,
                                                                @Param("targetScope") String targetScope,
                                                                @Param("targetFieldKey") String targetFieldKey,
                                                                @Param("modelIds") List<Long> modelIds,
                                                                @Param("matchUnits") List<DataModelMatchUnit> matchUnits);

    @SelectProvider(type = DataModelStatisticsSqlProvider.class, method = "selectValueBuckets")
    List<DataModelStatisticsBucketAggregate> selectStatisticsValueBuckets(@Param("tenantId") String tenantId,
                                                                          @Param("targetMetaSchemaCode") String targetMetaSchemaCode,
                                                                          @Param("targetScope") String targetScope,
                                                                          @Param("targetFieldKey") String targetFieldKey,
                                                                          @Param("modelIds") List<Long> modelIds,
                                                                          @Param("matchUnits") List<DataModelMatchUnit> matchUnits,
                                                                          @Param("limit") Integer limit);

    @SelectProvider(type = DataModelStatisticsSqlProvider.class, method = "selectNumericBucketCounts")
    List<DataModelStatisticsBucketAggregate> selectStatisticsNumericBucketCounts(@Param("tenantId") String tenantId,
                                                                                 @Param("targetMetaSchemaCode") String targetMetaSchemaCode,
                                                                                 @Param("targetScope") String targetScope,
                                                                                 @Param("targetFieldKey") String targetFieldKey,
                                                                                 @Param("modelIds") List<Long> modelIds,
                                                                                 @Param("matchUnits") List<DataModelMatchUnit> matchUnits,
                                                                                 @Param("buckets") List<DataModelStatisticsBucketRange> buckets);

    @SelectProvider(type = DataModelStatisticsSqlProvider.class, method = "selectTrendBuckets")
    List<DataModelStatisticsTrendAggregate> selectStatisticsTrendBuckets(@Param("tenantId") String tenantId,
                                                                         @Param("targetMetaSchemaCode") String targetMetaSchemaCode,
                                                                         @Param("targetScope") String targetScope,
                                                                         @Param("targetFieldKey") String targetFieldKey,
                                                                         @Param("modelIds") List<Long> modelIds,
                                                                         @Param("matchUnits") List<DataModelMatchUnit> matchUnits,
                                                                         @Param("startTime") LocalDateTime startTime);
}
