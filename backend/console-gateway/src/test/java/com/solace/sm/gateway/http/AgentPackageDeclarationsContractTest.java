package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.dp.api.inventory.PackageDeclarationSource;
import com.solace.sm.dp.api.inventory.kit.AbstractPackageDeclarationSourceContractTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link AgentPackageDeclarations} against the shared {@link PackageDeclarationSource} kit (plan 015 W4 integration, lane A review MINOR
 * 11). The catalog is the declare tests' fake behind an organization check, so the kit's tenant case proves the source passes the
 * caller's organization through rather than answering any organization's package.
 */
class AgentPackageDeclarationsContractTest extends AbstractPackageDeclarationSourceContractTest {

    private static String reference() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("contracts/validation/fixtures/cache-2.4.package.json");
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new IllegalStateException("fixture not found");
    }

    @Override
    protected World newWorld() {
        InstanceControllerTest.FakeCatalog fake = new InstanceControllerTest.FakeCatalog();
        Set<String> loaded = new HashSet<>();                 // organization|entry|version
        SolutionCatalog scoped = (SolutionCatalog) Proxy.newProxyInstance(SolutionCatalog.class.getClassLoader(),
                new Class<?>[] {SolutionCatalog.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "find", "documentOf" -> {
                            if (!loaded.contains(args[0] + "|" + args[1] + "|" + args[2])) {
                                return Optional.empty();
                            }
                            return method.getName().equals("find")
                                    ? fake.find((UUID) args[0], (UUID) args[1], (String) args[2])
                                    : fake.documentOf((UUID) args[0], (UUID) args[1], (String) args[2]);
                        }
                        case "toString" -> {
                            return "organization-scoped fake catalog";
                        }
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        case "equals" -> {
                            return proxy == args[0];
                        }
                        default -> throw new AssertionError("the package source only reads: " + method.getName());
                    }
                });
        AgentPackageDeclarations source = new AgentPackageDeclarations(scoped, new MetricsFakes.JacksonPackageCodec(),
                new MetricsFakes.RecordingApplicability());
        return new World() {
            @Override
            public PackageDeclarationSource source() {
                return source;
            }

            @Override
            public void loadReference(UUID organizationId, UUID catalogSolutionId, String packageVersion) {
                fake.serve(catalogSolutionId, packageVersion, reference());
                loaded.add(organizationId + "|" + catalogSolutionId + "|" + packageVersion);
            }
        };
    }
}
