package com.solace.sm.cp.agent;

import java.util.regex.Pattern;

/**
 * The grammars an enrolment envelope and a target name are checked against at token issue. They are the SAME patterns as
 * {@code V021}'s {@code agent_registration_config_root} and {@code agent_registration_container_prefix} CHECKs (which are
 * in the grammars of {@code sm_data.target_connection}, V007, and {@code registerTarget}): the service refuses first with a
 * message, the database refuses last by constraint name.
 */
final class AgentEnvelopeGrammar {

    static final int MAX_TARGET_NAME = 128;
    private static final Pattern CONFIG_ROOT = Pattern.compile("^/([A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+$");
    private static final Pattern CONTAINER_PREFIX = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");

    private AgentEnvelopeGrammar() {
    }

    static boolean configRoot(String value) {
        return value != null && CONFIG_ROOT.matcher(value).matches() && !value.contains("..");
    }

    static boolean containerPrefix(String value) {
        return value != null && CONTAINER_PREFIX.matcher(value).matches();
    }
}
