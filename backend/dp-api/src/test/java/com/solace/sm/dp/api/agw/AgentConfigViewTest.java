package com.solace.sm.dp.api.agw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.dp.api.agw.AgentConfigView.Entry;
import com.solace.sm.dp.api.agw.AgentConfigView.Reason;
import com.solace.sm.dp.api.agw.AgentConfigView.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Plan 017 task 4: the view's closed vocabulary, its shape rules, and that no key or value reaches a {@code toString}. */
class AgentConfigViewTest {

    @Test
    void theReasonVocabularyIsClosedAndAnythingElseIsUnknown() {
        assertThat(Reason.values()).extracting(Enum::name).containsExactly("PLACEMENT_NOT_IN_LEASE", "FILE_MISSING",
                "FILE_UNREADABLE", "FILE_TOO_LARGE", "INVALID_YAML", "NOT_PERMITTED", "TOO_MANY_KEYS", "UNKNOWN");
        assertThat(Reason.of("FILE_MISSING")).isEqualTo(Reason.FILE_MISSING);
        assertThat(Reason.of("BUSY")).isEqualTo(Reason.UNKNOWN);
        assertThat(Reason.of("file_missing")).isEqualTo(Reason.UNKNOWN);
        assertThat(new AgentConfigView(Status.REFUSED, List.of(), 0, false, "<script>").reason()).isEqualTo("UNKNOWN");
        assertThat(AgentConfigView.refused(Reason.INVALID_YAML).reasonCode()).isEqualTo(Optional.of(Reason.INVALID_YAML));
    }

    @Test
    void theStatusVocabularyIsClosed() {
        assertThat(Status.values()).extracting(Enum::name).containsExactly("OK", "REFUSED", "BUSY", "UNAVAILABLE");
    }

    @Test
    void onlyAnOkViewCarriesEntriesAndOnlyARefusalOrAMalformedReplyCarriesAReason() {
        assertThatThrownBy(() -> new AgentConfigView(Status.OK, List.of(), 0, false, "FILE_MISSING"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentConfigView(Status.REFUSED, List.of(), 0, false, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentConfigView(Status.REFUSED, List.of(new Entry("a", "b")), 0, false, "FILE_MISSING"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentConfigView(Status.BUSY, List.of(), 0, false, "UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentConfigView(Status.UNAVAILABLE, List.of(), 0, false, "FILE_MISSING"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentConfigView(Status.UNAVAILABLE, List.of(), 1, false, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentConfigView.ok(List.of(), -1, false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(AgentConfigView.malformed().reason()).isEqualTo("UNKNOWN");
        assertThat(AgentConfigView.malformed().status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    void theEntriesAreCopied() {
        List<Entry> source = new ArrayList<>(List.of(new Entry("a", "1")));
        AgentConfigView view = AgentConfigView.ok(source, 0, false);
        source.add(new Entry("b", "2"));
        assertThat(view.entries()).hasSize(1);
        assertThatThrownBy(() -> view.entries().add(new Entry("c", "3"))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void noKeyNameOrValueReachesAToString() {
        Entry entry = new Entry("datasource.password-SECRETKEY", "hunter2-SECRETVALUE");
        AgentConfigView view = AgentConfigView.ok(List.of(entry), 2, true);
        for (String rendered : List.of(entry.toString(), view.toString())) {
            assertThat(rendered).doesNotContain("SECRETKEY", "SECRETVALUE", "datasource", "hunter2");
        }
        assertThat(view.toString()).contains("entries=1", "hiddenCount=2");
    }
}
