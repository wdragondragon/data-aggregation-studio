package com.jdragon.studio.nacos.compat.http;

public record NacosHttpResponse(int statusCode, String body) {

    public boolean is2xxSuccessful() {
        return this.statusCode >= 200 && this.statusCode < 300;
    }

}
