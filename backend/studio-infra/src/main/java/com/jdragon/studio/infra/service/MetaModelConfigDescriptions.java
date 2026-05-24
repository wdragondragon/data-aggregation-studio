package com.jdragon.studio.infra.service;

import com.alibaba.fastjson.JSONObject;

final class MetaModelConfigDescriptions {

    private static final String PREFIX = "META_MODEL_CONFIG:";

    private MetaModelConfigDescriptions() {
    }

    static String encode(JSONObject config, String plainDescription) {
        String description = plainDescription == null ? "" : plainDescription;
        return PREFIX + config.toJSONString() + "\n" + description;
    }

    static JSONObject decode(String description) {
        JSONObject result = null;
        if (description != null) {
            String[] lines = description.split("\\r?\\n", 2);
            if (lines.length > 0 && lines[0].startsWith(PREFIX)) {
                try {
                    result = JSONObject.parseObject(lines[0].substring(PREFIX.length()).trim());
                } catch (Exception parseFailure) {
                    // Legacy descriptions may contain plain text or damaged JSON; callers treat that as no embedded config.
                    result = null;
                }
            }
        }
        return result;
    }
}
