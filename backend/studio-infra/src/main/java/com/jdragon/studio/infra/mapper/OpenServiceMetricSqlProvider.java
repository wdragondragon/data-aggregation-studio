package com.jdragon.studio.infra.mapper;

public class OpenServiceMetricSqlProvider {

    public String selectDataServiceDashboardSummary() {
        return summarySql(dataServiceSpec());
    }

    public String selectDataServiceDashboardBuckets() {
        return bucketsSql(dataServiceSpec());
    }

    public String selectDataServiceDashboardErrorDistribution() {
        return errorDistributionSql(dataServiceSpec());
    }

    public String selectDataServiceDashboardApiStats() {
        return apiStatsSql(dataServiceSpec());
    }

    public String selectDataServiceDashboardSubscriptionRank() {
        return subscriptionRankSql(dataServiceSpec());
    }

    public String selectDataIngestionDashboardSummary() {
        return summarySql(dataIngestionSpec());
    }

    public String selectDataIngestionDashboardBuckets() {
        return bucketsSql(dataIngestionSpec());
    }

    public String selectDataIngestionDashboardErrorDistribution() {
        return errorDistributionSql(dataIngestionSpec());
    }

    public String selectDataIngestionDashboardApiStats() {
        return apiStatsSql(dataIngestionSpec());
    }

    public String selectDataIngestionDashboardSubscriptionRank() {
        return subscriptionRankSql(dataIngestionSpec());
    }

    private String summarySql(SqlSpec spec) {
        return String.join("\n",
                "<script>",
                "with filtered as (",
                filteredSelect(spec),
                "),",
                "grouped as (",
                "  select",
                "    count(1) as access_count,",
                "    coalesce(sum(case when success = 1 then 1 else 0 end), 0) as success_count,",
                "    coalesce(sum(case when success = 1 then 0 else 1 end), 0) as failure_count,",
                spec.summaryColumns,
                "    coalesce(min(duration_ms), 0) as min_response_time_ms,",
                "    coalesce(max(duration_ms), 0) as max_response_time_ms,",
                "    coalesce(round(avg(duration_ms)), 0) as avg_response_time_ms,",
                "    max(occurred_at) as last_access_at",
                "  from filtered",
                "),",
                "ranked as (",
                "  select duration_ms, row_number() over (order by duration_ms asc, id asc) as rn, count(1) over () as cnt",
                "  from filtered",
                "),",
                "percentiles as (",
                "  select",
                "    coalesce(max(case when rn = ceiling(cnt * 0.95) then duration_ms end), 0) as p95_response_time_ms,",
                "    coalesce(max(case when rn = ceiling(cnt * 0.99) then duration_ms end), 0) as p99_response_time_ms",
                "  from ranked",
                ")",
                "select",
                "  g.access_count as access_count,",
                "  g.success_count as success_count,",
                "  g.failure_count as failure_count,",
                spec.summarySelectColumns,
                "  g.min_response_time_ms as min_response_time_ms,",
                "  g.max_response_time_ms as max_response_time_ms,",
                "  g.avg_response_time_ms as avg_response_time_ms,",
                "  coalesce(p.p95_response_time_ms, 0) as p95_response_time_ms,",
                "  coalesce(p.p99_response_time_ms, 0) as p99_response_time_ms,",
                "  g.last_access_at as last_access_at",
                "from grouped g",
                "left join percentiles p on 1 = 1",
                "</script>");
    }

    private String bucketsSql(SqlSpec spec) {
        return String.join("\n",
                "<script>",
                "with filtered as (",
                filteredSelect(spec),
                "),",
                "grouped as (",
                "  select",
                "    bucket_key,",
                "    count(1) as access_count,",
                "    coalesce(sum(case when success = 1 then 1 else 0 end), 0) as success_count,",
                "    coalesce(sum(case when success = 1 then 0 else 1 end), 0) as failure_count,",
                spec.bucketColumns,
                "    coalesce(min(duration_ms), 0) as min_response_time_ms,",
                "    coalesce(max(duration_ms), 0) as max_response_time_ms,",
                "    coalesce(round(avg(duration_ms)), 0) as avg_response_time_ms",
                "  from filtered",
                "  group by bucket_key",
                "),",
                "ranked as (",
                "  select",
                "    bucket_key,",
                "    duration_ms,",
                "    row_number() over (partition by bucket_key order by duration_ms asc, id asc) as rn,",
                "    count(1) over (partition by bucket_key) as cnt",
                "  from filtered",
                "),",
                "percentiles as (",
                "  select",
                "    bucket_key,",
                "    coalesce(max(case when rn = ceiling(cnt * 0.95) then duration_ms end), 0) as p95_response_time_ms,",
                "    coalesce(max(case when rn = ceiling(cnt * 0.99) then duration_ms end), 0) as p99_response_time_ms",
                "  from ranked",
                "  group by bucket_key",
                ")",
                "select",
                "  g.bucket_key as bucket_key,",
                "  g.access_count as access_count,",
                "  g.success_count as success_count,",
                "  g.failure_count as failure_count,",
                spec.bucketSelectColumns,
                "  g.min_response_time_ms as min_response_time_ms,",
                "  g.max_response_time_ms as max_response_time_ms,",
                "  g.avg_response_time_ms as avg_response_time_ms,",
                "  coalesce(p.p95_response_time_ms, 0) as p95_response_time_ms,",
                "  coalesce(p.p99_response_time_ms, 0) as p99_response_time_ms",
                "from grouped g",
                "left join percentiles p on p.bucket_key = g.bucket_key",
                "order by g.bucket_key asc",
                "</script>");
    }

    private String errorDistributionSql(SqlSpec spec) {
        return String.join("\n",
                "<script>",
                "with filtered as (",
                filteredSelect(spec),
                ")",
                "select",
                "  error_key as `key`,",
                "  error_key as label,",
                "  count(1) as count",
                "from (",
                "  select",
                "    case",
                "      when error_code is not null and trim(error_code) &lt;&gt; '' then error_code",
                "      when http_status is null then 'UNKNOWN'",
                "      else cast(http_status as char)",
                "    end as error_key",
                "  from filtered",
                "  where success &lt;&gt; 1",
                ") errors",
                "group by error_key",
                "order by count desc, error_key asc",
                "limit #{limit}",
                "</script>");
    }

    private String apiStatsSql(SqlSpec spec) {
        return String.join("\n",
                "<script>",
                "with filtered as (",
                filteredSelect(spec),
                "),",
                "grouped as (",
                "  select",
                "    service_id,",
                "    count(1) as access_count,",
                "    coalesce(sum(case when success = 1 then 1 else 0 end), 0) as success_count,",
                "    coalesce(sum(case when success = 1 then 0 else 1 end), 0) as failure_count,",
                spec.apiColumns,
                "    coalesce(min(duration_ms), 0) as min_response_time_ms,",
                "    coalesce(max(duration_ms), 0) as max_response_time_ms,",
                "    coalesce(round(avg(duration_ms)), 0) as avg_response_time_ms,",
                "    max(occurred_at) as last_access_at",
                "  from filtered",
                "  where service_id is not null",
                "  group by service_id",
                "),",
                "ranked as (",
                "  select",
                "    service_id,",
                "    duration_ms,",
                "    row_number() over (partition by service_id order by duration_ms asc, id asc) as rn,",
                "    count(1) over (partition by service_id) as cnt",
                "  from filtered",
                "  where service_id is not null",
                "),",
                "percentiles as (",
                "  select",
                "    service_id,",
                "    coalesce(max(case when rn = ceiling(cnt * 0.95) then duration_ms end), 0) as p95_response_time_ms,",
                "    coalesce(max(case when rn = ceiling(cnt * 0.99) then duration_ms end), 0) as p99_response_time_ms",
                "  from ranked",
                "  group by service_id",
                ")",
                "select",
                "  g.service_id as service_id,",
                "  g.access_count as access_count,",
                "  g.success_count as success_count,",
                "  g.failure_count as failure_count,",
                spec.apiSelectColumns,
                "  g.min_response_time_ms as min_response_time_ms,",
                "  g.max_response_time_ms as max_response_time_ms,",
                "  g.avg_response_time_ms as avg_response_time_ms,",
                "  coalesce(p.p95_response_time_ms, 0) as p95_response_time_ms,",
                "  coalesce(p.p99_response_time_ms, 0) as p99_response_time_ms,",
                "  g.last_access_at as last_access_at",
                "from grouped g",
                "left join percentiles p on p.service_id = g.service_id",
                "<choose>",
                "  <when test='orderMode == \"SLOW\"'>order by coalesce(p.p95_response_time_ms, 0) desc, g.max_response_time_ms desc, g.access_count desc</when>",
                "  <when test='orderMode == \"FAILED\"'>order by g.failure_count desc, case when g.access_count = 0 then 0 else (g.success_count * 1.0 / g.access_count) end asc, g.access_count desc</when>",
                "  <otherwise>order by g.access_count desc, g.last_access_at desc</otherwise>",
                "</choose>",
                "limit #{limit}",
                "</script>");
    }

    private String subscriptionRankSql(SqlSpec spec) {
        return String.join("\n",
                "<script>",
                "with filtered as (",
                filteredSelect(spec),
                "),",
                "grouped as (",
                "  select",
                "    service_id,",
                "    subscription_id,",
                "    max(subscription_name_snapshot) as subscription_name,",
                "    count(1) as access_count,",
                "    coalesce(sum(case when success = 1 then 1 else 0 end), 0) as success_count,",
                "    coalesce(sum(case when success = 1 then 0 else 1 end), 0) as failure_count,",
                spec.apiColumns,
                "    coalesce(min(duration_ms), 0) as min_response_time_ms,",
                "    coalesce(max(duration_ms), 0) as max_response_time_ms,",
                "    coalesce(round(avg(duration_ms)), 0) as avg_response_time_ms,",
                "    max(occurred_at) as last_access_at",
                "  from filtered",
                "  where subscription_id is not null",
                "  group by service_id, subscription_id",
                "),",
                "ranked as (",
                "  select",
                "    service_id,",
                "    subscription_id,",
                "    duration_ms,",
                "    row_number() over (partition by subscription_id order by duration_ms asc, id asc) as rn,",
                "    count(1) over (partition by subscription_id) as cnt",
                "  from filtered",
                "  where subscription_id is not null",
                "),",
                "percentiles as (",
                "  select",
                "    subscription_id,",
                "    coalesce(max(case when rn = ceiling(cnt * 0.95) then duration_ms end), 0) as p95_response_time_ms,",
                "    coalesce(max(case when rn = ceiling(cnt * 0.99) then duration_ms end), 0) as p99_response_time_ms",
                "  from ranked",
                "  group by subscription_id",
                ")",
                "select",
                "  g.service_id as service_id,",
                "  g.subscription_id as subscription_id,",
                "  g.subscription_name as subscription_name,",
                "  g.access_count as access_count,",
                "  g.success_count as success_count,",
                "  g.failure_count as failure_count,",
                spec.apiSelectColumns,
                "  g.min_response_time_ms as min_response_time_ms,",
                "  g.max_response_time_ms as max_response_time_ms,",
                "  g.avg_response_time_ms as avg_response_time_ms,",
                "  coalesce(p.p95_response_time_ms, 0) as p95_response_time_ms,",
                "  coalesce(p.p99_response_time_ms, 0) as p99_response_time_ms,",
                "  g.last_access_at as last_access_at",
                "from grouped g",
                "left join percentiles p on p.subscription_id = g.subscription_id",
                "order by g.access_count desc, g.failure_count desc, g.last_access_at desc",
                "limit #{limit}",
                "</script>");
    }

    private String filteredSelect(SqlSpec spec) {
        return String.join("\n",
                "  select",
                "    id,",
                "    service_id,",
                "    subscription_id,",
                "    subscription_name_snapshot,",
                "    occurred_at,",
                "    <choose>",
                "      <when test='hourGranularity'>substr(occurred_at, 1, 13)</when>",
                "      <otherwise>substr(occurred_at, 1, 10)</otherwise>",
                "    </choose> as bucket_key,",
                "    coalesce(duration_ms, 0) as duration_ms,",
                "    success,",
                "    http_status,",
                "    error_code,",
                spec.filteredColumns,
                "  from " + spec.tableName,
                "  where deleted = 0",
                "    and tenant_id = #{tenantId}",
                "    and project_id = #{projectId}",
                "    <if test='requestedClusterId != null'>and requested_cluster_id = #{requestedClusterId}</if>",
                "    <if test='actualClusterId != null'>and actual_cluster_id = #{actualClusterId}</if>",
                "    and service_id is not null",
                "    and occurred_at &gt;= #{startTime}",
                "    and occurred_at &lt;= #{endTime}",
                "    <choose>",
                "      <when test='serviceIds != null and serviceIds.size() &gt; 0'>",
                "        and service_id in",
                "        <foreach collection='serviceIds' item='serviceId' open='(' separator=',' close=')'>#{serviceId}</foreach>",
                "      </when>",
                "      <otherwise>and 1 = 0</otherwise>",
                "    </choose>",
                "    <if test='subscriptionId != null'>and subscription_id = #{subscriptionId}</if>",
                "    <if test='noTokenSubscription'>and subscription_id is null</if>",
                "    <if test='success != null'>and success = #{success}</if>",
                spec.cacheFilter,
                "    <if test='logFocus == \"ERROR\"'>and success &lt;&gt; 1</if>",
                "    <if test='logFocus == \"SLOW\"'>and duration_ms &gt;= #{minDurationMs}</if>",
                "    <if test='logFocus == \"ERROR_OR_SLOW\"'>and (success &lt;&gt; 1 or duration_ms &gt;= #{minDurationMs})</if>");
    }

    private SqlSpec dataServiceSpec() {
        SqlSpec spec = new SqlSpec();
        spec.tableName = "data_service_access_log";
        spec.filteredColumns = String.join("\n",
                "    cache_enabled,",
                "    cache_hit,",
                "    row_count");
        spec.cacheFilter = "    <if test='cacheHit != null'>and cache_enabled = 1 and cache_hit = #{cacheHit}</if>";
        spec.summaryColumns = String.join("\n",
                "    coalesce(sum(case when cache_enabled = 1 then 1 else 0 end), 0) as cache_enabled_count,",
                "    coalesce(sum(case when cache_enabled = 1 and cache_hit = 1 then 1 else 0 end), 0) as cache_hit_count,",
                "    coalesce(sum(case when cache_enabled = 1 and cache_hit &lt;&gt; 1 then 1 else 0 end), 0) as cache_miss_count,",
                "    coalesce(sum(case when cache_enabled &lt;&gt; 1 or cache_enabled is null then 1 else 0 end), 0) as cache_disabled_count,");
        spec.summarySelectColumns = String.join("\n",
                "  g.cache_enabled_count as cache_enabled_count,",
                "  g.cache_hit_count as cache_hit_count,",
                "  g.cache_miss_count as cache_miss_count,",
                "  g.cache_disabled_count as cache_disabled_count,");
        spec.bucketColumns = String.join("\n",
                "    coalesce(sum(row_count), 0) as row_count,",
                "    coalesce(sum(case when cache_enabled = 1 then 1 else 0 end), 0) as cache_enabled_count,",
                "    coalesce(sum(case when cache_enabled = 1 and cache_hit = 1 then 1 else 0 end), 0) as cache_hit_count,",
                "    coalesce(sum(case when cache_enabled = 1 and cache_hit &lt;&gt; 1 then 1 else 0 end), 0) as cache_miss_count,",
                "    coalesce(sum(case when cache_enabled &lt;&gt; 1 or cache_enabled is null then 1 else 0 end), 0) as cache_disabled_count,");
        spec.bucketSelectColumns = String.join("\n",
                "  g.row_count as row_count,",
                "  g.cache_enabled_count as cache_enabled_count,",
                "  g.cache_hit_count as cache_hit_count,",
                "  g.cache_miss_count as cache_miss_count,",
                "  g.cache_disabled_count as cache_disabled_count,");
        spec.apiColumns = spec.summaryColumns;
        spec.apiSelectColumns = spec.summarySelectColumns;
        return spec;
    }

    private SqlSpec dataIngestionSpec() {
        SqlSpec spec = new SqlSpec();
        spec.tableName = "data_ingestion_access_log";
        spec.filteredColumns = String.join("\n",
                "    received_count,",
                "    success_count,",
                "    failed_count");
        spec.cacheFilter = "";
        spec.summaryColumns = String.join("\n",
                "    coalesce(sum(received_count), 0) as received_count,",
                "    coalesce(sum(success_count), 0) as written_count,",
                "    coalesce(sum(failed_count), 0) as failed_count,");
        spec.summarySelectColumns = String.join("\n",
                "  g.received_count as received_count,",
                "  g.written_count as written_count,",
                "  g.failed_count as failed_count,");
        spec.bucketColumns = spec.summaryColumns;
        spec.bucketSelectColumns = spec.summarySelectColumns;
        spec.apiColumns = spec.summaryColumns;
        spec.apiSelectColumns = spec.summarySelectColumns;
        return spec;
    }

    private static class SqlSpec {
        private String tableName;
        private String filteredColumns;
        private String cacheFilter;
        private String summaryColumns;
        private String summarySelectColumns;
        private String bucketColumns;
        private String bucketSelectColumns;
        private String apiColumns;
        private String apiSelectColumns;
    }
}
