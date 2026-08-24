package com.gcll.docagent.llm;

public record StepOutcome<T>(T value, boolean llmUsed, long costMs) {

    public static <T> StepOutcome<T> ruleBased(T value) {
        return new StepOutcome<>(value, false, 0L);
    }

    public static <T> StepOutcome<T> llm(T value, long costMs) {
        return new StepOutcome<>(value, true, costMs);
    }
}
