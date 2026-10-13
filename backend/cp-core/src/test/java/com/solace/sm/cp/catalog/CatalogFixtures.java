package com.solace.sm.cp.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locates {@code contracts/} read-only and derives package variants from the production-loadable fixture. */
final class CatalogFixtures {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private CatalogFixtures() {
    }

    static Path contracts() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve("contracts/validation/fixtures"))) {
                return dir.resolve("contracts");
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate contracts/ from " + Path.of("").toAbsolutePath());
    }

    static ObjectNode fixture(String name) {
        try {
            return (ObjectNode) MAPPER.readTree(Files.readString(contracts().resolve("validation/fixtures/" + name)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The one fixture production gate 2 admits ({@code author: solace} with a signature): cache, {@code [1]}. */
    static ObjectNode cache() {
        return fixture("cache-2.4.package.json");
    }

    /** cache, re-versioned and re-ranged so it can sit beside {@link #cache()} without tripping gate 3. */
    static ObjectNode cacheVersion(int packageVersion, String appliesTo) {
        ObjectNode doc = cache();
        ArrayNode versions = MAPPER.createArrayNode().add(packageVersion);
        doc.set("packageVersion", versions);
        doc.put("appliesTo", appliesTo);
        return doc;
    }

    /** The same document under another solution name (the schema only constrains its shape). */
    static ObjectNode asSolution(ObjectNode doc, String solution) {
        ObjectNode copy = doc.deepCopy();
        copy.put("solution", solution);
        return copy;
    }

    static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String json(JsonNode node) {
        return node.toString();
    }
}
