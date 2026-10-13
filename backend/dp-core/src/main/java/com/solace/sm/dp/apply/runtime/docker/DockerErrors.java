package com.solace.sm.dp.apply.runtime.docker;

import com.solace.sm.dp.apply.model.ErrorClass;

/**
 * Status code → classifier, in one place.
 *
 * <p>Takes {@code operation} so a future refinement ("a 409 on restart means a conflicting
 * operation; a 409 on stop means something else") has a parameter to use rather than a signature
 * change. {@code DockerErrorsTest} pins the current mapping so a refinement is a visible edit.
 */
public final class DockerErrors {

    private DockerErrors() {
    }

    public static ErrorClass classify(int status, String operation) {
        return new ErrorClass(switch (status) {
            case 304 -> "docker.api.notmodified";
            case 400 -> "docker.api.rejected";
            case 401, 403 -> "docker.api.forbidden";
            case 404 -> "docker.container.absent";
            case 409 -> "docker.api.conflict";
            case 500, 502, 503, 504 -> "docker.api.failed";
            default -> "docker.api.unexpected";
        });
    }
}
