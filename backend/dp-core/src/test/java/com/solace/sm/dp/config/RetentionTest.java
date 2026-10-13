package com.solace.sm.dp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RetentionTest {

    @Test
    void theDefaultIsTenAndTheMinimumIsOne() {
        assertEquals(10, Retention.DEFAULT);
        assertEquals(1, new Retention(1).retainedGenerations());
        assertThrows(IllegalArgumentException.class, () -> new Retention(0));
        assertThrows(IllegalArgumentException.class, () -> new Retention(-5));
    }
}
