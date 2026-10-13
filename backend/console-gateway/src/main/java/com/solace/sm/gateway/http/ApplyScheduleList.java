package com.solace.sm.gateway.http;

import java.util.List;

/** Wire shape of console.yaml's {@code ApplyScheduleList} (plan 012 T7): an instance's schedules, newest first. */
public record ApplyScheduleList(List<ApplySchedule> items) {
}
