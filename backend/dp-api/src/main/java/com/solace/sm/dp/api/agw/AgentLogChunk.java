package com.solace.sm.dp.api.agw;

import com.solace.sm.dp.api.logs.LogLineView;
import java.util.List;
import java.util.Objects;

/**
 * One piece of an export. {@code resumeSeq} is the resume point: the {@code fromSeq} that continues right after this chunk
 * (not the sequence number of its first line). An interrupted export resumes with the last received chunk's value.
 */
public record AgentLogChunk(long resumeSeq, List<LogLineView> lines, List<AgentLogGap> gaps, boolean last) {
    public AgentLogChunk {
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        gaps = List.copyOf(Objects.requireNonNull(gaps, "gaps"));
    }
}
