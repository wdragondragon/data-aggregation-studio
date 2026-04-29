package com.jdragon.studio.nacos.compat.discovery;

import com.jdragon.studio.nacos.compat.props.NacosDiscoveryProperties;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.cloud.client.serviceregistry.ServiceRegistry;
import org.springframework.cloud.commons.util.InetUtils;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import jakarta.annotation.PreDestroy;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class NacosRegistrationLifecycle implements ApplicationListener<WebServerInitializedEvent> {

    private static final String IPV4 = "IPv4";

    private static final String IPV6 = "IPv6";

    private final ServiceRegistry<NacosRegistration> serviceRegistry;

    private final NacosDiscoveryProperties discoveryProperties;

    private final Environment environment;

    private final InetUtils inetUtils;

    private volatile NacosRegistration registration;

    private volatile boolean registered;

    public NacosRegistrationLifecycle(ServiceRegistry<NacosRegistration> serviceRegistry,
            NacosDiscoveryProperties discoveryProperties, Environment environment, InetUtils inetUtils) {
        this.serviceRegistry = serviceRegistry;
        this.discoveryProperties = discoveryProperties;
        this.environment = environment;
        this.inetUtils = inetUtils;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        if (!this.discoveryProperties.isEnabled() || !this.discoveryProperties.isRegisterEnabled() || this.registered) {
            return;
        }
        int port = this.discoveryProperties.getPort() != null ? this.discoveryProperties.getPort()
                : event.getWebServer().getPort();
        String serviceId = StringUtils.hasText(this.discoveryProperties.getService())
                ? this.discoveryProperties.getService()
                : this.environment.getProperty("spring.application.name");
        String host = StringUtils.hasText(this.discoveryProperties.getIp()) ? this.discoveryProperties.getIp() : resolveHost();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.putAll(this.discoveryProperties.getMetadata());
        metadata.putIfAbsent("preserved.register.source", "SPRING_CLOUD");
        this.registration = new NacosRegistration(UUID.randomUUID().toString(), serviceId, host, port,
                this.discoveryProperties.isSecure(), metadata, this.discoveryProperties.getNamespace(),
                this.discoveryProperties.getGroup(), this.discoveryProperties.getClusterName(),
                this.discoveryProperties.getWeight(), this.discoveryProperties.isEphemeral());
        this.serviceRegistry.register(this.registration);
        this.registered = true;
    }

    public NacosRegistration getRegistration() {
        return this.registration;
    }

    @PreDestroy
    public void stop() {
        if (this.registered && this.registration != null) {
            this.serviceRegistry.deregister(this.registration);
            this.registered = false;
        }
    }

    private String resolveHost() {
        if (StringUtils.hasText(this.discoveryProperties.getNetworkInterface())) {
            return resolveNetworkInterfaceHost(this.discoveryProperties.getNetworkInterface());
        }
        String ipType = this.discoveryProperties.getIpType();
        if (!StringUtils.hasText(ipType) || IPV4.equalsIgnoreCase(ipType)) {
            return this.inetUtils.findFirstNonLoopbackHostInfo().getIpAddress();
        }
        if (IPV6.equalsIgnoreCase(ipType)) {
            String ipv6Address = findIpv6Address();
            if (StringUtils.hasText(ipv6Address)) {
                return ipv6Address;
            }
            return this.inetUtils.findFirstNonLoopbackHostInfo().getIpAddress();
        }
        throw new IllegalArgumentException("please checking the type of IP " + ipType);
    }

    protected String resolveNetworkInterfaceHost(String networkInterfaceName) {
        try {
            NetworkInterface networkInterface = NetworkInterface.getByName(networkInterfaceName);
            if (networkInterface == null) {
                throw new IllegalArgumentException("no such interface " + networkInterfaceName);
            }
            Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
            while (inetAddresses.hasMoreElements()) {
                InetAddress currentAddress = inetAddresses.nextElement();
                if ((currentAddress instanceof Inet4Address || currentAddress instanceof Inet6Address)
                        && !currentAddress.isLoopbackAddress()) {
                    return currentAddress.getHostAddress();
                }
            }
            throw new IllegalStateException("cannot find available ip from network interface " + networkInterfaceName);
        }
        catch (IllegalArgumentException | IllegalStateException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new IllegalStateException("cannot resolve network interface " + networkInterfaceName, ex);
        }
    }

    protected String findIpv6Address() {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces != null && networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                if (!networkInterface.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress currentAddress = inetAddresses.nextElement();
                    if (currentAddress instanceof Inet6Address && !currentAddress.isLoopbackAddress()
                            && !currentAddress.isLinkLocalAddress()) {
                        return currentAddress.getHostAddress();
                    }
                }
            }
            return null;
        }
        catch (Exception ex) {
            return null;
        }
    }

}
