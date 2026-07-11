package org.commrogue.indexanalyzer.analysis.iindex;

public enum TermStructureAnalysisMode {
    FULL_INSTRUMENTED_IO("fullInstrumentation"),
    PARTIAL_INSTRUMENTED_IO("partialInstrumentation"),
    BLOCK_SKIPPING("blockSkipping");

    public final String param;

    TermStructureAnalysisMode(String param) {
        this.param = param;
    }
}
