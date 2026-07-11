package org.commrogue.indexanalyzer.analysis.exceptions;

public class NonBlockTreeException extends Exception {
    public NonBlockTreeException(String field, String segmentName) {
        super("Field " + field + " in segment " + segmentName
                + " does not map to a start BlockTermState, and allowNonBlockTermState is not enabled.");
    }
}
