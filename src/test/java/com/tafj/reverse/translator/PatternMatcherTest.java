package com.tafj.reverse.translator;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.expr.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PatternMatcher class
 */
public class PatternMatcherTest {

    private PatternMatcher patternMatcher;
    private JavaParser javaParser;

    @BeforeEach
    public void setUp() {
        patternMatcher = new PatternMatcher();
        javaParser = new JavaParser();
    }

    @Test
    @DisplayName("Variable name conversion: _R_ACCT -> R.ACCT")
    public void testConvertVariableName() {
        assertEquals("R.ACCT", patternMatcher.convertVariableName("_R_ACCT"));
        assertEquals("Y.ACCT.NO", patternMatcher.convertVariableName("_Y_ACCT_NO"));
        assertEquals("LED.BAL", patternMatcher.convertVariableName("_LED_BAL"));
        assertEquals("PHX.PROCESS", patternMatcher.convertVariableName("_PHX_PROCESS"));
    }

    @Test
    @DisplayName("Variable name conversion with this. prefix")
    public void testConvertVariableNameWithThisPrefix() {
        assertEquals("R.ACCT", patternMatcher.convertVariableName("this._R_ACCT"));
        assertEquals("BALANCE.FORMATTED", patternMatcher.convertVariableName("this._BALANCE_FORMATTED"));
    }

    @Test
    @DisplayName("Component name extraction from import")
    public void testExtractComponentName() {
        assertEquals("ATMFRM.Foundation",
                patternMatcher.extractComponentName("com.temenos.t24.component_ATMFRM_Foundation_18_cl"));
        assertEquals("AC.AccountOpening",
                patternMatcher.extractComponentName("com.temenos.t24.component_AC_AccountOpening_21_cl"));
        assertEquals("ST.CurrencyConfig",
                patternMatcher.extractComponentName("com.temenos.t24.component_ST_CurrencyConfig_21_cl"));
    }

    @Test
    @DisplayName("Component name extraction returns null for non-component imports")
    public void testExtractComponentNameNonComponent() {
        assertNull(patternMatcher.extractComponentName("com.temenos.tafj.common.jVar"));
        assertNull(patternMatcher.extractComponentName("java.util.ArrayList"));
    }

    @Test
    @DisplayName("op_cat conversion to JBC concatenation")
    public void testOpCatConversion() {
        String javaCode = "op_cat(\"+-N\", jAtVariable.VM)";
        ParseResult<Expression> result = javaParser.parseExpression(javaCode);
        assertTrue(result.isSuccessful());

        MethodCallExpr methodCall = (MethodCallExpr) result.getResult().get();
        String jbcEquivalent = patternMatcher.convertMethodCall(methodCall);

        assertNotNull(jbcEquivalent);
        assertTrue(jbcEquivalent.contains("':'"));  // Should contain colon for concatenation
        assertTrue(jbcEquivalent.contains("@VM"));  // Should convert jAtVariable.VM to @VM
    }

    @Test
    @DisplayName("jAtVariable.VM converts to @VM")
    public void testJAtVariableVMConversion() {
        String javaCode = "jAtVariable.VM";
        ParseResult<Expression> result = javaParser.parseExpression(javaCode);
        assertTrue(result.isSuccessful());

        String jbcEquivalent = patternMatcher.convertExpression(result.getResult().get());
        assertEquals("@VM", jbcEquivalent);
    }

    @Test
    @DisplayName("op_equal conversion to EQ")
    public void testOpEqualConversion() {
        String javaCode = "op_equal(_AMT_FMT_TYPE, \"PHOENIX\")";
        ParseResult<Expression> result = javaParser.parseExpression(javaCode);
        assertTrue(result.isSuccessful());

        MethodCallExpr methodCall = (MethodCallExpr) result.getResult().get();
        String jbcEquivalent = patternMatcher.convertMethodCall(methodCall);

        assertNotNull(jbcEquivalent);
        assertTrue(jbcEquivalent.contains("EQ"));
    }

    @Test
    @DisplayName("op_match conversion to MATCHES")
    public void testOpMatchConversion() {
        String javaCode = "op_match(_AMT_FMT_TYPE, op_cat(\"+-N\", jAtVariable.VM).concat(\"N+-\"))";
        ParseResult<Expression> result = javaParser.parseExpression(javaCode);
        assertTrue(result.isSuccessful());

        MethodCallExpr methodCall = (MethodCallExpr) result.getResult().get();
        String jbcEquivalent = patternMatcher.convertMethodCall(methodCall);

        assertNotNull(jbcEquivalent);
        assertTrue(jbcEquivalent.contains("MATCHES"));
    }

    @Test
    @DisplayName("boolVal wrapper removal")
    public void testBoolValWrapperRemoval() {
        String javaCode = "boolVal(op_equal(_PHX_PROCESS, \"1\"))";
        ParseResult<Expression> result = javaParser.parseExpression(javaCode);
        assertTrue(result.isSuccessful());

        MethodCallExpr methodCall = (MethodCallExpr) result.getResult().get();
        String jbcEquivalent = patternMatcher.convertMethodCall(methodCall);

        assertNotNull(jbcEquivalent);
        assertTrue(jbcEquivalent.contains("EQ"));
        assertFalse(jbcEquivalent.contains("boolVal"));
    }

    @Test
    @DisplayName("String literal uses single quotes")
    public void testStringLiteralConversion() {
        String javaCode = "\"PHOENIX\"";
        ParseResult<Expression> result = javaParser.parseExpression(javaCode);
        assertTrue(result.isSuccessful());

        String jbcEquivalent = patternMatcher.convertExpression(result.getResult().get());
        assertTrue(jbcEquivalent.startsWith("'"));
        assertTrue(jbcEquivalent.endsWith("'"));
        assertEquals("'PHOENIX'", jbcEquivalent);
    }

    @Test
    @DisplayName("ABS function preservation")
    public void testAbsFunctionPreservation() {
        String javaCode = "ABS(_LED_BAL)";
        ParseResult<Expression> result = javaParser.parseExpression(javaCode);
        assertTrue(result.isSuccessful());

        MethodCallExpr methodCall = (MethodCallExpr) result.getResult().get();
        String jbcEquivalent = patternMatcher.convertMethodCall(methodCall);

        assertNotNull(jbcEquivalent);
        assertTrue(jbcEquivalent.startsWith("ABS("));
    }

    @Test
    @DisplayName("FMT function preservation")
    public void testFmtFunctionPreservation() {
        String javaCode = "FMT(_NUM_CCY, \"R%3\")";
        ParseResult<Expression> result = javaParser.parseExpression(javaCode);
        assertTrue(result.isSuccessful());

        MethodCallExpr methodCall = (MethodCallExpr) result.getResult().get();
        String jbcEquivalent = patternMatcher.convertMethodCall(methodCall);

        assertNotNull(jbcEquivalent);
        assertTrue(jbcEquivalent.startsWith("FMT("));
    }

    @Test
    @DisplayName("DCOUNT with @VM delimiter")
    public void testDcountConversion() {
        String javaCode = "DCOUNT(_CR_LCK_AMT, jAtVariable.VM)";
        ParseResult<Expression> result = javaParser.parseExpression(javaCode);
        assertTrue(result.isSuccessful());

        MethodCallExpr methodCall = (MethodCallExpr) result.getResult().get();
        String jbcEquivalent = patternMatcher.convertMethodCall(methodCall);

        assertNotNull(jbcEquivalent);
        assertTrue(jbcEquivalent.contains("@VM"));
    }

    @Test
    @DisplayName("concat method conversion")
    public void testConcatMethodConversion() {
        String javaCode = "op_cat(\"+-N\", jAtVariable.VM).concat(\"N+-\")";
        ParseResult<Expression> result = javaParser.parseExpression(javaCode);
        assertTrue(result.isSuccessful());

        // The outer method is concat
        MethodCallExpr concatCall = (MethodCallExpr) result.getResult().get();
        String jbcEquivalent = patternMatcher.convertMethodCall(concatCall);

        assertNotNull(jbcEquivalent);
        assertTrue(jbcEquivalent.contains(":"));  // Should use colon for concatenation
    }

    @Test
    @DisplayName("Nested expression: op_cat with Character.valueOf")
    public void testNestedOpCatWithCharacterValueOf() {
        String javaCode = "op_cat(\"+-N\", Character.valueOf(jAtVariable.VM))";
        ParseResult<Expression> result = javaParser.parseExpression(javaCode);
        assertTrue(result.isSuccessful());

        MethodCallExpr methodCall = (MethodCallExpr) result.getResult().get();
        String jbcEquivalent = patternMatcher.convertMethodCall(methodCall);

        assertNotNull(jbcEquivalent);
        // Should contain both the string and @VM
        assertTrue(jbcEquivalent.contains("'"));  // String literal
        assertTrue(jbcEquivalent.contains("@VM") || jbcEquivalent.contains("VM"));  // VM reference
    }

    @Test
    @DisplayName("fGet method conversion")
    public void testFGetConversion() {
        String javaCode = "fGet(_PROC_CODE, 3, 2)";
        ParseResult<Expression> result = javaParser.parseExpression(javaCode);
        assertTrue(result.isSuccessful());

        MethodCallExpr methodCall = (MethodCallExpr) result.getResult().get();
        String jbcEquivalent = patternMatcher.convertMethodCall(methodCall);

        assertNotNull(jbcEquivalent);
        assertTrue(jbcEquivalent.contains("["));  // JBC substring syntax
    }

    @Test
    @DisplayName("op_add conversion to +")
    public void testOpAddConversion() {
        String javaCode = "op_add(_AVAIL_BAL, _CR_LCK_AMT)";
        ParseResult<Expression> result = javaParser.parseExpression(javaCode);
        assertTrue(result.isSuccessful());

        MethodCallExpr methodCall = (MethodCallExpr) result.getResult().get();
        String jbcEquivalent = patternMatcher.convertMethodCall(methodCall);

        assertNotNull(jbcEquivalent);
        assertTrue(jbcEquivalent.contains("+"));
    }

    @Test
    @DisplayName("op_sub conversion to -")
    public void testOpSubConversion() {
        String javaCode = "op_sub(_AMT_FMT_LENGTH, 1)";
        ParseResult<Expression> result = javaParser.parseExpression(javaCode);
        assertTrue(result.isSuccessful());

        MethodCallExpr methodCall = (MethodCallExpr) result.getResult().get();
        String jbcEquivalent = patternMatcher.convertMethodCall(methodCall);

        assertNotNull(jbcEquivalent);
        assertTrue(jbcEquivalent.contains("-"));
    }

    @Test
    @DisplayName("Complex condition: boolVal with op_match and nested op_cat.concat")
    public void testComplexConditionWithOpMatchAndOpCat() {
        // This is the actual pattern from AT_ISO_FMT_BAL_RTN_cl.java
        String javaCode = "boolVal(op_match(_AMT_FMT_TYPE, op_cat(\"+-N\", Character.valueOf(jAtVariable.VM)).concat(\"N+-\")))";
        ParseResult<Expression> result = javaParser.parseExpression(javaCode);
        assertTrue(result.isSuccessful());

        MethodCallExpr methodCall = (MethodCallExpr) result.getResult().get();
        String jbcEquivalent = patternMatcher.convertMethodCall(methodCall);

        assertNotNull(jbcEquivalent);
        assertTrue(jbcEquivalent.contains("MATCHES"));
        // The exact output may vary, but it should contain the key elements
    }
}
