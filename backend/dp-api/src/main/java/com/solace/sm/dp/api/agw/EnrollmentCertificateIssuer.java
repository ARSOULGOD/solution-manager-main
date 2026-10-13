package com.solace.sm.dp.api.agw;

public interface EnrollmentCertificateIssuer {
    EnrollmentIssuance issue(EnrollmentRequest request);
}
