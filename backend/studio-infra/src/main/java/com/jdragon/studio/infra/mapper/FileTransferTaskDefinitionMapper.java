package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.FileTransferTaskDefinitionEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FileTransferTaskDefinitionMapper extends BaseMapper<FileTransferTaskDefinitionEntity> {
}
