package com.jdragon.studio.infra.mapper;

public class RunMetricSqlProvider {

    public String selectDashboardBuckets() {
        return String.join("\n",
                "<script>",
                "select",
                "  substr(ended_at, 1, 10) as bucket_key,",
                "  coalesce(sum(collected_records), 0) as collected_records,",
                "  coalesce(sum(" + successExpression() + "), 0) as success_records,",
                "  coalesce(sum(failed_records), 0) as failed_records,",
                "  coalesce(sum(transformer_total_records), 0) as transformer_total_records,",
                "  coalesce(sum(transformer_success_records), 0) as transformer_success_records,",
                "  coalesce(sum(transformer_failed_records), 0) as transformer_failed_records,",
                "  coalesce(sum(transformer_filter_records), 0) as transformer_filter_records",
                "from run_record",
                baseWhere(),
                "  and " + preciseMetricCondition(),
                "group by substr(ended_at, 1, 10)",
                "order by bucket_key asc",
                "</script>");
    }

    public String selectDashboardTaskAggregates() {
        return String.join("\n",
                "<script>",
                "select",
                "  collection_task_id as collection_task_id,",
                "  coalesce(sum(case when " + preciseMetricCondition() + " then read_succeed_records else 0 end), 0) as read_succeed_records,",
                "  coalesce(sum(case when " + preciseMetricCondition() + " then " + successExpression() + " else 0 end), 0) as success_records,",
                "  coalesce(sum(case when " + preciseMetricCondition() + " then 1 else 0 end), 0) as precise_run_count,",
                "  coalesce(sum(case when " + preciseMetricCondition() + " then 0 else 1 end), 0) as legacy_run_count",
                "from run_record",
                baseWhere(),
                "group by collection_task_id",
                "order by collection_task_id asc",
                "</script>");
    }

    private String baseWhere() {
        return String.join("\n",
                "where deleted = 0",
                "  and tenant_id = #{tenantId}",
                "  <if test='projectId != null'>and project_id = #{projectId}</if>",
                "  and collection_task_id is not null",
                "  and status in ('SUCCESS', 'FAILED')",
                "  and ended_at is not null",
                "  and ended_at &gt;= #{startTime}",
                "  and ended_at &lt;= #{endTime}",
                "  <choose>",
                "    <when test='taskIds != null and taskIds.size() &gt; 0'>",
                "      and collection_task_id in",
                "      <foreach collection='taskIds' item='taskId' open='(' separator=',' close=')'>#{taskId}</foreach>",
                "    </when>",
                "    <otherwise>and 1 = 0</otherwise>",
                "  </choose>");
    }

    private String preciseMetricCondition() {
        return String.join(" and ",
                "collected_records is not null",
                "read_succeed_records is not null",
                "read_failed_records is not null",
                "write_succeed_records is not null",
                "write_failed_records is not null",
                "failed_records is not null",
                "transformer_total_records is not null",
                "transformer_success_records is not null",
                "transformer_failed_records is not null",
                "transformer_filter_records is not null");
    }

    private String successExpression() {
        return "coalesce(success_records, case when read_succeed_records - write_failed_records &gt; 0 then read_succeed_records - write_failed_records else 0 end)";
    }
}
