package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagedFilePolicyRegistryTest {

    @TempDir
    Path tempDir;

    private final ManagedFilePolicyRegistry registry = new ManagedFilePolicyRegistry();

    @Test
    void validatesKerberosAndHadoopFilesByContent() throws Exception {
        Path keytab = tempDir.resolve("service.keytab");
        Files.write(keytab, new byte[]{0x05, 0x02, 0x01});
        assertDoesNotThrow(() -> registry.validate("KERBEROS_KEYTAB", keytab.getFileName().toString(), keytab));

        Path krb5 = tempDir.resolve("krb5.conf");
        Files.writeString(krb5, "[realms]\nEXAMPLE.COM = { kdc = kdc.example.com }\n",
                StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> registry.validate("KERBEROS_KRB5_CONF", krb5.getFileName().toString(), krb5));

        Path hadoop = tempDir.resolve("core-site.xml");
        Files.writeString(hadoop, "<configuration><property><name>a</name><value>b</value></property></configuration>",
                StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> registry.validate("HADOOP_SITE_XML", hadoop.getFileName().toString(), hadoop));
    }

    @Test
    void rejectsInvalidKeytabKrb5IncludeAndXmlDoctype() throws Exception {
        Path keytab = tempDir.resolve("invalid.keytab");
        Files.write(keytab, new byte[]{0x01, 0x02});
        assertThrows(StudioException.class,
                () -> registry.validate("KERBEROS_KEYTAB", keytab.getFileName().toString(), keytab));

        Path krb5 = tempDir.resolve("included.conf");
        Files.writeString(krb5, "include /etc/krb5.conf\n[realms]\nEXAMPLE.COM = { kdc = kdc }\n",
                StandardCharsets.UTF_8);
        assertThrows(StudioException.class,
                () -> registry.validate("KERBEROS_KRB5_CONF", krb5.getFileName().toString(), krb5));

        Path hadoop = tempDir.resolve("hdfs-site.xml");
        Files.writeString(hadoop,
                "<!DOCTYPE configuration [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><configuration>&xxe;</configuration>",
                StandardCharsets.UTF_8);
        assertThrows(StudioException.class,
                () -> registry.validate("HADOOP_SITE_XML", hadoop.getFileName().toString(), hadoop));
    }
}
