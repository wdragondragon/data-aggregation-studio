package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.DatasourceConnectionTestRecordEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Mapper
public interface DatasourceConnectionTestRecordMapper extends BaseMapper<DatasourceConnectionTestRecordEntity> {

    @Select({
            "<script>",
            "select r.*",
            "from datasource_connection_test_record r",
            "where r.tenant_id = #{tenantId}",
            "  and r.deleted = 0",
            "  and r.connection_fingerprint in",
            "  <foreach collection='fingerprints' item='fingerprint' open='(' separator=',' close=')'>",
            "    #{fingerprint}",
            "  </foreach>",
            "  and (",
            "    select count(1)",
            "    from datasource_connection_test_record newer",
            "    where newer.tenant_id = r.tenant_id",
            "      and newer.deleted = 0",
            "      and newer.connection_fingerprint = r.connection_fingerprint",
            "      and (newer.ended_at &gt; r.ended_at or (newer.ended_at = r.ended_at and newer.id &gt; r.id))",
            "  ) &lt; #{limit}",
            "order by r.connection_fingerprint asc, r.ended_at asc, r.id asc",
            "</script>"
    })
    List<DatasourceConnectionTestRecordEntity> selectRecentByFingerprints(@Param("tenantId") String tenantId,
                                                                          @Param("fingerprints") Collection<String> fingerprints,
                                                                          @Param("limit") int limit);

    @Delete("delete from datasource_connection_test_record where ended_at < #{cutoff}")
    int deleteExpiredPhysically(@Param("cutoff") LocalDateTime cutoff);
}
