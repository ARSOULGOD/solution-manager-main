package com.solace.sm.dp.api.agw;

/** The host-side limits the issuing principal granted this agent (T3.5): where it may write, which containers it may touch. */
public record EnrollmentEnvelope(String allowedConfigRoot, String allowedContainerPrefix) {
    public EnrollmentEnvelope {
        if (allowedConfigRoot == null || allowedConfigRoot.isBlank()
                || allowedContainerPrefix == null || allowedContainerPrefix.isBlank()) {
            throw new IllegalArgumentException("the enrolment envelope is incomplete");
        }
    }
}
