package com.solace.sm.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * The pom allow-list for {@code agent-gateway} (AGW-D2; plan 015 T1.2, P15-15). The module holds no datasource and
 * imports only the named artifacts; {@code javax.annotation-api} is refused outright (OR-127 L1). The parser takes a
 * string, so the planted-pom proofs exercise the same code as the real check.
 */
class AgentGatewayDependencyAllowListTest {

    private static final Set<String> ALLOWED = Set.of(
            "com.solace.sm:dp-api",
            "io.grpc:grpc-netty-shaded",
            "io.grpc:grpc-protobuf",
            "io.grpc:grpc-stub",
            "com.google.protobuf:protobuf-java-util",
            "org.springframework.boot:spring-boot-starter",
            "org.springframework.boot:spring-boot-actuator",
            "io.micrometer:micrometer-core");

    private static Element child(Element parent, String name) {
        for (org.w3c.dom.Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(name)) {
                return e;
            }
        }
        return null;
    }

    private static Document parse(String pomXml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(pomXml.getBytes(StandardCharsets.UTF_8)));
    }

    /** The {@code <dependency>} elements directly under {@code project/dependencies} (not management, not plugin dependencies). */
    private static List<Element> directDependencies(Document doc) {
        List<Element> out = new ArrayList<>();
        Element deps = child(doc.getDocumentElement(), "dependencies");
        if (deps != null) {
            for (org.w3c.dom.Node n = deps.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element e && e.getTagName().equals("dependency")) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    static int topLevelDependencyCount(String pomXml) throws Exception {
        return directDependencies(parse(pomXml)).size();
    }

    static List<String> violations(String pomXml) throws Exception {
        List<String> out = new ArrayList<>();
        for (Element d : directDependencies(parse(pomXml))) {
            String ga = text(d, "groupId") + ":" + text(d, "artifactId");
            String scope = text(d, "scope");
            if (ga.equals("javax.annotation:javax.annotation-api")) {
                out.add(ga + " is refused (OR-127 L1: gRPC Java is generated with @generated=omit)");
            } else if (ga.toLowerCase(java.util.Locale.ROOT).matches(
                    ".*(jdbc|datasource|hikari|flyway|liquibase|postgresql|persistence|spring-data|spring-orm|hibernate|jooq|mybatis|r2dbc).*")) {
                out.add(ga + " is a datasource/JDBC/ORM/migration artifact; the gateway holds no datasource (AGW-D2)");
            } else if (!"test".equals(scope) && !ALLOWED.contains(ga)) {
                out.add(ga + " is outside the allow-list");
            }
        }
        return out;
    }

    /** Text of a DIRECT child only: an {@code <exclusions>} block's own groupId must not be mistaken for the dependency's. */
    private static String text(Element d, String tag) {
        Element e = child(d, tag);
        return e == null ? "" : e.getTextContent().trim();
    }

    private static String pomWith(String extraDependency) {
        return "<project><dependencies><dependency><groupId>io.grpc</groupId><artifactId>grpc-stub</artifactId></dependency>"
                + extraDependency + "</dependencies></project>";
    }

    @Test
    void theRealAgentGatewayPomIsWithinTheAllowList() throws IOException, Exception {
        Path pom = Path.of("..", "agent-gateway", "pom.xml");
        assertTrue(Files.exists(pom), "agent-gateway/pom.xml must exist: " + pom.toAbsolutePath());
        assertEquals(List.of(), violations(Files.readString(pom)));
    }

    @Test
    void plantedSpringJdbcIsReported() throws Exception {
        List<String> v = violations(pomWith("<dependency><groupId>org.springframework</groupId><artifactId>spring-jdbc</artifactId></dependency>"));
        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).contains("spring-jdbc"));
    }

    @Test
    void plantedJavaxAnnotationApiIsReported() throws Exception {
        List<String> v = violations(pomWith("<dependency><groupId>javax.annotation</groupId><artifactId>javax.annotation-api</artifactId><version>1.3.2</version></dependency>"));
        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).contains("OR-127"));
    }

    @Test
    void plantedUnlistedArtifactIsReportedButTestScopeIsNot() throws Exception {
        assertFalse(violations(pomWith("<dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId></dependency>")).isEmpty());
        assertTrue(violations(pomWith("<dependency><groupId>org.mockito</groupId><artifactId>mockito-core</artifactId><scope>test</scope></dependency>")).isEmpty());
    }

    @Test
    void plantedMixedCaseOrmAndQueryBuilderArtifactsAreReportedEvenAtTestScope() throws Exception {
        for (String ga : new String[] {"com.zaxxer:HikariCP", "org.hibernate.orm:hibernate-core", "org.jooq:jooq",
                "org.mybatis:mybatis", "io.r2dbc:r2dbc-spi", "org.liquibase:liquibase-core"}) {
            String[] p = ga.split(":");
            List<String> v = violations(pomWith("<dependency><groupId>" + p[0] + "</groupId><artifactId>" + p[1]
                    + "</artifactId><scope>test</scope></dependency>"));
            assertEquals(1, v.size(), ga + " -> " + v);
        }
    }

    @Test
    void plantedDependencyWhoseExclusionsComeFirstIsNotMisread() throws Exception {
        List<String> v = violations(pomWith("<dependency><exclusions><exclusion><groupId>io.grpc</groupId>"
                + "<artifactId>grpc-stub</artifactId></exclusion></exclusions><groupId>org.springframework</groupId>"
                + "<artifactId>spring-jdbc</artifactId></dependency>"));
        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).contains("spring-jdbc"), v.toString());
    }

    @Test
    void theReactorParentDeclaresNoTopLevelDependencies() throws Exception {
        Path pom = Path.of("..", "pom.xml");
        assertEquals(0, topLevelDependencyCount(Files.readString(pom)),
                "backend/pom.xml must not declare <dependencies> (they would be inherited by agent-gateway unchecked)");
        assertEquals(1, topLevelDependencyCount("<project><dependencies><dependency><groupId>a</groupId>"
                + "<artifactId>b</artifactId></dependency></dependencies></project>"));
    }
}
