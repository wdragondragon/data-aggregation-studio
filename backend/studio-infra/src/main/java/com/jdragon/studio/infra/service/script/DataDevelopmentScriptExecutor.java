package com.jdragon.studio.infra.service.script;

import com.jdragon.studio.dto.enums.ScriptType;
import com.jdragon.studio.dto.model.DataScriptExecutionResultView;

public interface DataDevelopmentScriptExecutor {
    ScriptType getScriptType();

    DataScriptExecutionResultView execute(DataDevelopmentExecutionContext context);
}
