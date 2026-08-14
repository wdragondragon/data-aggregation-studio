package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.FileTransferEventOutboxEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FileTransferEventOutboxMapper extends BaseMapper<FileTransferEventOutboxEntity> {
}
