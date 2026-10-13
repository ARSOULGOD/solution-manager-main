package com.solace.sm.dp.meshbridge.ingest.archfixture;

import com.solacesystems.jcsmp.JCSMPSession;

/** Planted violation: a JCSMP type held by a class of the ingest package that is not the SDK-facing subscriber. */
public final class BadIngestJcsmpLeakClass {
    JCSMPSession session;
}
