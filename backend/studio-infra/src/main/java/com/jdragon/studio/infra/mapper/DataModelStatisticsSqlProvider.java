package com.jdragon.studio.infra.mapper;

public class DataModelStatisticsSqlProvider {

    public String selectStatisticsSummary() {
        return String.join("\n",
                "<script>",
                "select",
                "  count(distinct model_id) as matched_model_count,",
                "  count(1) as matched_item_count,",
                "  count(distinct nullif(" + valueExpression() + ", '')) as distinct_count,",
                "  count(number_value) as numeric_count,",
                "  min(number_value) as min_value,",
                "  max(number_value) as max_value,",
                "  coalesce(sum(number_value), 0) as sum_value,",
                "  avg(number_value) as avg_value",
                "from data_model_attr_index",
                targetWhere(),
                "</script>");
    }

    public String selectValueBuckets() {
        return String.join("\n",
                "<script>",
                "select",
                "  bucket_key as bucket_key,",
                "  count(1) as count",
                "from (",
                "  select " + valueExpression() + " as bucket_key",
                "  from data_model_attr_index",
                targetWhere(),
                ") indexed_values",
                "where bucket_key &lt;&gt; ''",
                "group by bucket_key",
                "order by count desc, bucket_key asc",
                "<if test='limit != null and limit &gt; 0'>limit #{limit}</if>",
                "</script>");
    }

    public String selectNumericBucketCounts() {
        return String.join("\n",
                "<script>",
                "<choose>",
                "  <when test='buckets != null and buckets.size() &gt; 0'>",
                "    <foreach collection='buckets' item='bucket' separator=' union all '>",
                "      select",
                "        #{bucket.bucketKey} as bucket_key,",
                "        #{bucket.bucketIndex} as bucket_index,",
                "        count(1) as count",
                "      from data_model_attr_index",
                targetWhere(),
                "        and number_value is not null",
                "        and number_value &gt;= #{bucket.lowerBound}",
                "        <choose>",
                "          <when test='bucket.lastBucket'>and number_value &lt;= #{bucket.upperBound}</when>",
                "          <otherwise>and number_value &lt; #{bucket.upperBound}</otherwise>",
                "        </choose>",
                "    </foreach>",
                "  </when>",
                "  <otherwise>",
                "    select '' as bucket_key, -1 as bucket_index, 0 as count where 1 = 0",
                "  </otherwise>",
                "</choose>",
                "</script>");
    }

    public String selectTrendBuckets() {
        return String.join("\n",
                "<script>",
                "with target_models as (",
                "  select distinct model_id",
                "  from data_model_attr_index",
                targetWhere(),
                ")",
                "select",
                "  substr(m.created_at, 1, 10) as bucket_key,",
                "  count(distinct m.id) as count",
                "from data_model m",
                "inner join target_models tm on tm.model_id = m.id",
                "where m.deleted = 0",
                "  and m.tenant_id = #{tenantId}",
                "  and m.created_at &gt;= #{startTime}",
                "group by substr(m.created_at, 1, 10)",
                "order by bucket_key asc",
                "</script>");
    }

    private String targetWhere() {
        return String.join("\n",
                "where tenant_id = #{tenantId}",
                "  and meta_schema_code = #{targetMetaSchemaCode}",
                "  and scope = #{targetScope}",
                "  and field_key = #{targetFieldKey}",
                "  <choose>",
                "    <when test='modelIds != null and modelIds.size() &gt; 0'>",
                "      and model_id in",
                "      <foreach collection='modelIds' item='modelId' open='(' separator=',' close=')'>#{modelId}</foreach>",
                "    </when>",
                "    <otherwise>and 1 = 0</otherwise>",
                "  </choose>",
                "  <if test='matchUnits != null'>",
                "    <choose>",
                "      <when test='matchUnits.size() &gt; 0'>",
                "        and (",
                "          <foreach collection='matchUnits' item='unit' separator=' or '>",
                "            (model_id = #{unit.modelId} and coalesce(nullif(trim(item_key), ''), '__single__') = #{unit.itemKey})",
                "          </foreach>",
                "        )",
                "      </when>",
                "      <otherwise>and 1 = 0</otherwise>",
                "    </choose>",
                "  </if>");
    }

    private String valueExpression() {
        return String.join(" ",
                "case",
                "when number_value is not null then trim(cast(number_value as char))",
                "when bool_value is not null then case when bool_value = 0 then 'false' else 'true' end",
                "when keyword_value is not null and trim(keyword_value) &lt;&gt; '' then trim(keyword_value)",
                "when raw_value is not null and trim(raw_value) &lt;&gt; '' then trim(raw_value)",
                "else ''",
                "end");
    }
}
