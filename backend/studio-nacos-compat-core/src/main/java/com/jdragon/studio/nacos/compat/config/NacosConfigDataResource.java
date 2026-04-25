package com.jdragon.studio.nacos.compat.config;

import com.jdragon.studio.nacos.compat.model.NacosCompatMode;
import org.springframework.boot.context.config.ConfigDataResource;

import java.time.Duration;
import java.util.Objects;

public final class NacosConfigDataResource extends ConfigDataResource {

    private final boolean optional;

    private final String serverAddr;

    private final String namespace;

    private final String username;

    private final String password;

    private final String accessKey;

    private final String secretKey;

    private final String encode;

    private final String dataId;

    private final String group;

    private final String fileExtension;

    private final boolean refreshEnabled;

    private final NacosCompatMode compatMode;

    private final Duration probeTimeout;

    private final boolean logProbeDetail;

    private final Duration configReadTimeout;

    private final Duration legacyLongPollingTimeout;

    public NacosConfigDataResource(boolean optional, String serverAddr, String namespace, String username,
            String password, String accessKey, String secretKey, String encode, String dataId, String group,
            String fileExtension, boolean refreshEnabled, NacosCompatMode compatMode, Duration probeTimeout,
            boolean logProbeDetail, Duration configReadTimeout, Duration legacyLongPollingTimeout) {
        super(optional);
        this.optional = optional;
        this.serverAddr = serverAddr;
        this.namespace = namespace;
        this.username = username;
        this.password = password;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.encode = encode;
        this.dataId = dataId;
        this.group = group;
        this.fileExtension = fileExtension;
        this.refreshEnabled = refreshEnabled;
        this.compatMode = compatMode;
        this.probeTimeout = probeTimeout;
        this.logProbeDetail = logProbeDetail;
        this.configReadTimeout = configReadTimeout;
        this.legacyLongPollingTimeout = legacyLongPollingTimeout;
    }

    public String getServerAddr() {
        return serverAddr;
    }

    public boolean isOptionalResource() {
        return optional;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public String getEncode() {
        return encode;
    }

    public String getDataId() {
        return dataId;
    }

    public String getGroup() {
        return group;
    }

    public String getFileExtension() {
        return fileExtension;
    }

    public boolean isRefreshEnabled() {
        return refreshEnabled;
    }

    public NacosCompatMode getCompatMode() {
        return compatMode;
    }

    public Duration getProbeTimeout() {
        return probeTimeout;
    }

    public boolean isLogProbeDetail() {
        return logProbeDetail;
    }

    public Duration getConfigReadTimeout() {
        return configReadTimeout;
    }

    public Duration getLegacyLongPollingTimeout() {
        return legacyLongPollingTimeout;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        NacosConfigDataResource that = (NacosConfigDataResource) o;
        return optional == that.optional && refreshEnabled == that.refreshEnabled
                && logProbeDetail == that.logProbeDetail && Objects.equals(serverAddr, that.serverAddr)
                && Objects.equals(namespace, that.namespace) && Objects.equals(username, that.username)
                && Objects.equals(password, that.password) && Objects.equals(accessKey, that.accessKey)
                && Objects.equals(secretKey, that.secretKey) && Objects.equals(encode, that.encode)
                && Objects.equals(dataId, that.dataId) && Objects.equals(group, that.group)
                && Objects.equals(fileExtension, that.fileExtension) && compatMode == that.compatMode
                && Objects.equals(probeTimeout, that.probeTimeout)
                && Objects.equals(configReadTimeout, that.configReadTimeout)
                && Objects.equals(legacyLongPollingTimeout, that.legacyLongPollingTimeout);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serverAddr, namespace, username, password, accessKey, secretKey, encode, dataId, group,
                fileExtension, refreshEnabled, compatMode, probeTimeout, logProbeDetail, configReadTimeout,
                legacyLongPollingTimeout, optional);
    }

    @Override
    public String toString() {
        return "NacosConfigDataResource{" +
                "serverAddr='" + serverAddr + '\'' +
                ", namespace='" + namespace + '\'' +
                ", dataId='" + dataId + '\'' +
                ", group='" + group + '\'' +
                ", fileExtension='" + fileExtension + '\'' +
                ", refreshEnabled=" + refreshEnabled +
                ", mode=" + compatMode +
                '}';
    }

}
