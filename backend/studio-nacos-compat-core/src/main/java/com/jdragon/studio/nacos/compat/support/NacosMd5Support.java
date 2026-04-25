package com.jdragon.studio.nacos.compat.support;

import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

public final class NacosMd5Support {

    private NacosMd5Support() {
    }

    public static String md5(String value) {
        return DigestUtils.md5DigestAsHex((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

}
