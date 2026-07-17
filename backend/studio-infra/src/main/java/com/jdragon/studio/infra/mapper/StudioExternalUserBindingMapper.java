package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.StudioExternalUserBindingEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StudioExternalUserBindingMapper extends BaseMapper<StudioExternalUserBindingEntity> {

    @Delete("delete from studio_external_user_binding where provider_code = #{providerCode} and studio_user_id = #{studioUserId}")
    int hardDeleteByProviderAndStudioUserId(@Param("providerCode") String providerCode,
                                            @Param("studioUserId") Long studioUserId);
}
