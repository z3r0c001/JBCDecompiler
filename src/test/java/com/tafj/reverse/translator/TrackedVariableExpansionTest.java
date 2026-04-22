package com.tafj.reverse.translator;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for tracked variable expansion in get() calls
 */
public class TrackedVariableExpansionTest {

    private PatternMatcher patternMatcher;
    private JavaParser javaParser;

    @BeforeEach
    public void setUp() {
        patternMatcher = new PatternMatcher();
        javaParser = new JavaParser();
    }

    @Test
    @DisplayName("Tracked variable expansion in op_and with FIELD function")
    public void testTrackedVariableExpansionInOpAndWithField() {
        // Simulate the Java code pattern:
        // jVar l2 = this.op_ne(this._ChildActivityRec, component_AA_Framework_17_cl._ArrangementActivity_ArrActActivityClass);
        // jVar jVar2 = this.get(this._ChildActivityRec, component_AA_Framework_17_cl._ArrangementActivity_ArrActActivityClass, 0, 0);
        // if (this.boolVal(this.op_and(l2, this.op_equal(this.FIELD(jVar2, component_AA_Framework_17_cl._Sep, 2, 2), "SETTLE-PARTICIPANT"))))
        
        String javaCode = 
            "package com.temenos.t24;\n" +
            "import com.temenos.tafj.common.jVar;\n" +
            "public class Test_cl {\n" +
            "    public void main() {\n" +
            "        jVar l2 = this.op_ne(this._ChildActivityRec, component_AA_Framework_17_cl._ArrangementActivity_ArrActActivityClass);\n" +
            "        jVar jVar2 = this.get(this._ChildActivityRec, component_AA_Framework_17_cl._ArrangementActivity_ArrActActivityClass, 0, 0);\n" +
            "        if (this.boolVal(this.op_and(l2, this.op_equal(this.FIELD(jVar2, component_AA_Framework_17_cl._Sep, 2, 2), \"SETTLE-PARTICIPANT\")))) {\n" +
            "            this.set(_Result, \"FOUND\");\n" +
            "        }\n" +
            "    }\n" +
            "    public jVar op_ne(jVar a, jVar b) { return null; }\n" +
            "    public jVar op_equal(jVar a, jVar b) { return null; }\n" +
            "    public jVar get(jVar arr, jVar field, int mv, int sv) { return null; }\n" +
            "}";

        try {
            CompilationUnit cu = javaParser.parse(javaCode).getResult().get();
            
            JbcVisitor visitor = new JbcVisitor(patternMatcher);
            cu.accept(visitor, new StringBuilder());
            
            String jbcCode = visitor.getJbcCode();
            
            System.out.println("Generated JBC Code:");
            System.out.println(jbcCode);
            
            // The expected output should expand l2 and jVar2
            // l2 should become: ChildActivityId NE ArrActivityId
            // jVar2 should become: ChildActivityRec<AA.Framework.ArrangementActivity.ArrActActivityClass>
            assertTrue(jbcCode.contains("ChildActivityId NE ArrActivityId") || 
                      jbcCode.contains("ChildActivityRec"), 
                      "Output should contain expanded l2 or ChildActivityRec");
            
            // Should contain FIELD with expanded jVar2
            assertTrue(jbcCode.contains("FIELD("), 
                      "Output should contain FIELD function");
            
            // Should NOT contain raw l2 or jVar2 in the condition
            assertFalse(jbcCode.contains("IF l2 AND"), 
                       "Output should not contain raw l2 variable in IF condition");
            assertFalse(jbcCode.contains("FIELD(jVar2,"), 
                       "Output should not contain raw jVar2 in FIELD call");
            
        } catch (Exception e) {
            fail("Test failed with exception: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Test
    @DisplayName("get() call in op_and condition should convert to JBC array access syntax")
    public void testGetCallConversionInOpAndCondition() {
        // Simulate the Java code pattern:
        // if (this.boolVal(this.op_and(this.get(this._AgingStatuses, 1, this._AgeCnt, 0), 
        //        this.op_ne(this.get(this._AgingStatuses, 1, this._AgeCnt, 0), "SETTLED"))))
        // Expected: IF AgingStatuses<1,AgeCnt> AND AgingStatuses<1,AgeCnt> NE 'SETTLED' THEN
        
        String javaCode = 
            "package com.temenos.t24;\n" +
            "import com.temenos.tafj.common.jVar;\n" +
            "public class TestGet_cl {\n" +
            "    public void main() {\n" +
            "        if (this.boolVal(this.op_and(this.get(this._AgingStatuses, 1, this._AgeCnt, 0), \n" +
            "                this.op_ne(this.get(this._AgingStatuses, 1, this._AgeCnt, 0), \"SETTLED\")))) {\n" +
            "            this.set(_Result, \"FOUND\");\n" +
            "        }\n" +
            "    }\n" +
            "    public jVar get(jVar arr, int mv, jVar sv, int def) { return null; }\n" +
            "    public jVar op_ne(jVar a, jVar b) { return null; }\n" +
            "}";

        try {
            CompilationUnit cu = javaParser.parse(javaCode).getResult().get();
            
            JbcVisitor visitor = new JbcVisitor(patternMatcher);
            cu.accept(visitor, new StringBuilder());
            
            String jbcCode = visitor.getJbcCode();
            
            System.out.println("Generated JBC Code:");
            System.out.println(jbcCode);
            
            // The expected output should convert get() to array<field> syntax
            assertTrue(jbcCode.contains("AgingStatuses<1,AgeCnt>") || 
                      jbcCode.contains("AgingStatuses<1, AgeCnt>"), 
                      "Output should contain AgingStatuses<1,AgeCnt> array access syntax");
            
            // Should NOT contain raw get() function call
            assertFalse(jbcCode.contains("get(AgingStatuses") || jbcCode.contains("get( AgingStatuses"), 
                       "Output should not contain raw get() function call");
            
        } catch (Exception e) {
            fail("Test failed with exception: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Test
    @DisplayName("FIELD with tracked variable in set() should expand to get() result")
    public void testFieldWithTrackedVariableInSet() {
        // Simulate the Java code pattern:
        // jVar jVar3 = this.get(this._ActivityBalancesRec, component_AA_Framework_17_cl._ActivityBalances_ActBalActivity, this._ActivityIndex, 0);
        // this.set(this._ActBalProperty, this.FIELD(jVar3, component_AA_Framework_17_cl._Sep, 3));
        // Expected: ActBalProperty = FIELD(ActivityBalancesRec<AA.Framework.ActivityBalances.ActBalActivity,ActivityIndex>,AA.Framework.Sep,3)
        
        String javaCode = 
            "package com.temenos.t24;\n" +
            "import com.temenos.tafj.common.jVar;\n" +
            "public class TestFieldSet_cl {\n" +
            "    public void main() {\n" +
            "        jVar jVar3 = this.get(this._ActivityBalancesRec, component_AA_Framework_17_cl._ActivityBalances_ActBalActivity, this._ActivityIndex, 0);\n" +
            "        this.set(this._ActBalProperty, this.FIELD(jVar3, component_AA_Framework_17_cl._Sep, 3));\n" +
            "    }\n" +
            "    public jVar get(jVar arr, jVar field, jVar mv, int def) { return null; }\n" +
            "}";

        try {
            CompilationUnit cu = javaParser.parse(javaCode).getResult().get();
            
            JbcVisitor visitor = new JbcVisitor(patternMatcher);
            cu.accept(visitor, new StringBuilder());
            
            String jbcCode = visitor.getJbcCode();
            
            System.out.println("Generated JBC Code:");
            System.out.println(jbcCode);
            
            // The expected output should expand jVar3 in FIELD to the get() result
            assertTrue(jbcCode.contains("FIELD(") && 
                      (jbcCode.contains("ActivityBalancesRec") || jbcCode.contains("ActivityIndex")), 
                      "Output should contain expanded FIELD with ActivityBalancesRec/ActivityIndex");
            
            // Should NOT contain raw jVar3 in FIELD
            assertFalse(jbcCode.contains("FIELD(jVar3,"), 
                       "Output should not contain raw jVar3 in FIELD call");
            
        } catch (Exception e) {
            fail("Test failed with exception: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
