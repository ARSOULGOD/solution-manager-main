package com.solace.sm.dp.meshbridge.archfixture;

import com.fasterxml.jackson.databind.json.JsonMapper;

/** Planted violation: the builder path to a second mapper. */
public final class BadMeshbridgeJsonMapperClass {
    Object mapper = JsonMapper.builder().build();
}
