package com.tafj.reverse.translator;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.tafj.reverse.model.TranslationResult;
import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;

import java.io.*;
import java.util.*;

public class TafjTranslator {

    private PatternMatcher patternMatcher;
    private JavaParser javaParser;

    public TafjTranslator() {
        this.patternMatcher = new PatternMatcher();
        this.javaParser = new JavaParser();
    }

    /**
     * Decompile a .class file to Java source
     */
    public String decompileClass(File classFile) throws IOException {
        StringBuilder output = new StringBuilder();

        OutputSinkFactory sinkFactory = new OutputSinkFactory() {
            @Override
            public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
                return Collections.singletonList(SinkClass.STRING);
            }

            @Override
            public Sink getSink(SinkType sinkType, SinkClass sinkClass) {
                return s -> {
                    String line = String.valueOf(s);
                    // Filter out CFR progress messages
                    if (!line.startsWith("Analysing") && !line.startsWith("Loading")
                            && !line.startsWith("Decompiling")) {
                        output.append(line).append("\n");
                    }
                };
            }
        };

        CfrDriver driver = new CfrDriver.Builder().withOutputSink(sinkFactory).build();

        driver.analyse(Collections.singletonList(classFile.getAbsolutePath()));

        return output.toString();
    }

    /**
     * Main translation method - Java to JBC
     */
    public TranslationResult translateToJBC(String javaSource) {
        long startTime = System.currentTimeMillis();
        TranslationResult result;

        try {
            // Step 1: Extract TAFJ metadata from comments
            TafjMetadata metadata = extractTafjMetadata(javaSource);

            // Step 2: Parse Java to AST
            ParseResult<CompilationUnit> parseResult = javaParser.parse(javaSource);

            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                result = new TranslationResult(
                        "* ERROR: Failed to parse Java source\n" + parseResult.getProblems().toString());
                result.addError("Parse failed: " + parseResult.getProblems().toString());
                return result;
            }

            CompilationUnit cu = parseResult.getResult().get();

            // Remove non-essential methods (keep only lbl_* and main)
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                cls.getMethods().stream().filter(
                        m -> (!m.getNameAsString().startsWith("lbl_") && !m.getNameAsString().startsWith("main")))
                        .forEach(MethodDeclaration::remove);
            });

            // Remove _l() line number marker statements from all methods
            cu.findAll(MethodDeclaration.class).forEach(method -> {
                method.getBody().ifPresent(body -> {
                    // Collect statements to remove
                    List<Statement> toRemove = new ArrayList<>();
                    for (Statement stmt : body.getStatements()) {
                        if (stmt instanceof ExpressionStmt) {
                            ExpressionStmt exprStmt = (ExpressionStmt) stmt;
                            if (exprStmt.getExpression() instanceof MethodCallExpr) {
                                MethodCallExpr methodCall = (MethodCallExpr) exprStmt.getExpression();
                                if (methodCall.getNameAsString().equals("_l")) {
                                    toRemove.add(stmt);
                                }
                            }
                        }
                    }
                    // Remove the collected statements
                    body.getStatements().removeAll(toRemove);
                });
            });

            // Step 3: Visit AST and generate JBC
            JbcVisitor visitor = new JbcVisitor(patternMatcher);
            cu.accept(visitor, null);

            String jbcCode = visitor.getJbcCode();

            // Step 4: Post-process JBC (formatting, cleanup)
            jbcCode = postProcessJBC(jbcCode, visitor.componentList, metadata);

            long endTime = System.currentTimeMillis();

            result = new TranslationResult(jbcCode);
            result.setTranslationTimeMs(endTime - startTime);

            // Add warnings for unhandled constructs
            if (jbcCode.contains("TODO") || jbcCode.contains("FIXME")) {
                result.addWarning("Generated code contains TODO markers that need manual review");
            }

            System.out.println("Translation completed in " + (endTime - startTime) + "ms");

            return result;

        } catch (Exception e) {
            e.printStackTrace();
            result = new TranslationResult("* ERROR: Translation failed\n" + "* " + e.getMessage() + "\n"
                    + "* Falling back to pattern-based translation\n\n" + patternMatcher.fallbackTranslate(javaSource));
            result.addError("Translation exception: " + e.getMessage());
            return result;
        }
    }

    /**
     * Extract TAFJ metadata from Java source comments
     */
    private TafjMetadata extractTafjMetadata(String javaSource) {
        TafjMetadata metadata = new TafjMetadata();

        // Extract <TAFJ-BP> - Base Pointer
        int bpStart = javaSource.indexOf("<TAFJ-BP>");
        if (bpStart > 0) {
            int bpEnd = javaSource.indexOf("<\\TAFJ-BP>", bpStart);
            if (bpEnd > 0) {
                metadata.basePointer = javaSource.substring(bpStart + 11, bpEnd).trim();
            }
        }

        // Extract <TAFJ-BPA> - Application/Mapping
        int bpaStart = javaSource.indexOf("<TAFJ-BPA>");
        if (bpaStart > 0) {
            int bpaEnd = javaSource.indexOf("<\\TAFJ-BPA>", bpaStart);
            if (bpaEnd > 0) {
                metadata.application = javaSource.substring(bpaStart + 12, bpaEnd).trim();
            }
        }

        // Extract <TAFJ-BN> - Base Name (program name)
        int bnStart = javaSource.indexOf("<TAFJ-BN>");
        if (bnStart > 0) {
            int bnEnd = javaSource.indexOf("<\\TAFJ-BN>", bnStart);
            if (bnEnd > 0) {
                metadata.baseName = javaSource.substring(bnStart + 11, bnEnd).trim();
            }
        }

        return metadata;
    }

    /**
     * Simple metadata holder class
     */
    private static class TafjMetadata {
        String basePointer = "";
        String application = "";
        String baseName = "";
    }


    /**
     * Post-process generated JBC for formatting
     */
    private String postProcessJBC(String jbcCode, ArrayList<String> componentList, TafjMetadata metadata) {
        String formatted = jbcCode;

        // Ensure proper line endings
        formatted = formatted.replaceAll("\\r\\n?", "\n");

        // Remove any leading/trailing digits and digit sequences at start of lines
        // This removes leftover numbers from _l() calls and line number comments
        formatted = formatted.replaceAll("^[0-9]+", "");
        formatted = formatted.replaceAll("\\n[0-9]+", "\n");
        formatted = formatted.replaceAll("[0-9]+SUBROUTINE", "SUBROUTINE");

        // Ensure SUBROUTINE has parameters
        formatted = formatted.replaceAll("SUBROUTINE ([A-Z.]+)\\(\\)", "SUBROUTINE $1(...)");

        // Remove dummy component methods
        for (String component : componentList) {
            formatted = formatted.replaceAll("\s*"+ component + "\\(\\)\s*", "");
        }

        // Remove multiple consecutive blank lines
        formatted = formatted.replaceAll("\\n\\n\\n+", "\n\n");

        // Build header with TAFJ metadata
        StringBuilder header = new StringBuilder();
        header.append("*-----------------------------------------------------------------------------\n");
        header.append("* REVERSE TRANSLATED FROM TAFJ JAVA\n");
        header.append("* VERIFY BEFORE USE - APPROXIMATE RECONSTRUCTION\n");
        header.append("*-----------------------------------------------------------------------------\n");

        return header.toString() + formatted;
    }

}