package com.solace.sm.dp.meshbridge.archfixture;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Planted violation: a second ObjectMapper in the publisher package (plan 001 invariant). */
public final class BadMeshbridgeMapperClass {
    ObjectMapper mapper = new ObjectMapper();
}
