package com.tafj.reverse.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the result of a TAFJ Java to JBC translation.
 * Contains the generated JBC code and any warnings/errors encountered.
 */
public class TranslationResult {
    private String jbcCode;
    private boolean successful;
    private List<String> warnings;
    private List<String> errors;
    private String sourceFileName;
    private long translationTimeMs;

    public TranslationResult(String jbcCode) {
        this.jbcCode = jbcCode;
        this.successful = jbcCode != null && !jbcCode.startsWith("* ERROR:");
        this.warnings = new ArrayList<>();
        this.errors = new ArrayList<>();
    }

    public TranslationResult(String jbcCode, List<String> warnings) {
        this(jbcCode);
        this.warnings = warnings != null ? warnings : new ArrayList<>();
    }

    public String getJbcCode() {
        return jbcCode;
    }

    public boolean isSuccessful() {
        return successful;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void addWarning(String warning) {
        this.warnings.add(warning);
    }

    public List<String> getErrors() {
        return errors;
    }

    public void addError(String error) {
        this.errors.add(error);
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public void setSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
    }

    public long getTranslationTimeMs() {
        return translationTimeMs;
    }

    public void setTranslationTimeMs(long translationTimeMs) {
        this.translationTimeMs = translationTimeMs;
    }

    /**
     * Get a summary of issues found during translation.
     */
    public String getIssuesSummary() {
        StringBuilder sb = new StringBuilder();
        if (!errors.isEmpty()) {
            sb.append("Errors (").append(errors.size()).append("):\n");
            for (String error : errors) {
                sb.append("  - ").append(error).append("\n");
            }
        }
        if (!warnings.isEmpty()) {
            sb.append("Warnings (").append(warnings.size()).append("):\n");
            for (String warning : warnings) {
                sb.append("  - ").append(warning).append("\n");
            }
        }
        return sb.length() > 0 ? sb.toString() : "No issues found.";
    }
}