package com.tafj.reverse.translator;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;

public class LocateSwitchTest {

    public static void main(String[] args) {
        TafjTranslator translator = new TafjTranslator();
        
        String javaCode = """
// <TAFJ-BP>EB.SystemTables</TAFJ-BP>
// <TAFJ-BPA>ST.CompanyCreation</TAFJ-BPA>
// <TAFJ-BN>AT.ISO.FMT.BAL.RTN</TAFJ-BN>

package com.temenos.t24.component_ST_CompanyCreation_22_cl;

public class ST_CompanyCreation_22_cl {
    public void main() {
        switch (this.LOCATE(this._CurrentCompany, this._CompanyList, 1, 1, 0, null, null, this._CurCompPos, 1)) {
            case 0: {
                this._l(180);
                this.DEL(this._CompanyList, 1, this._CurCompPos, 0);
                this._l(181);
                this.DEL(this._BranchCodeList, 1, this._CurCompPos, 0);
            }
        }
    }
    
    protected int lbl_end() {
        this._Sys_PostGlobus = lbl_end();
        return this._Sys_ReturnTo = LABEL_NULL;
    }
}
""";
        
        System.out.println("=== Input Java Code ===");
        System.out.println(javaCode);
        System.out.println("\n=== Translated JBC Output ===");
        
        var result = translator.translateToJBC(javaCode);
        System.out.println(result.getJbcCode());
        
        // Debug: parse and check switch
        JavaParser parser = new JavaParser();
        var parseResult = parser.parse(javaCode);
        if (parseResult.isSuccessful()) {
            var switchStmts = parseResult.getResult().get().findAll(com.github.javaparser.ast.stmt.SwitchStmt.class);
            System.out.println("\n=== DEBUG: Found " + switchStmts.size() + " switch statements ===");
            for (var sw : switchStmts) {
                System.out.println("Selector type: " + sw.getSelector().getClass().getSimpleName());
                System.out.println("Selector: " + sw.getSelector());
                System.out.println("Is MethodCallExpr: " + (sw.getSelector() instanceof com.github.javaparser.ast.expr.MethodCallExpr));
                
                if (sw.getSelector() instanceof com.github.javaparser.ast.expr.MethodCallExpr) {
                    var methodCall = (com.github.javaparser.ast.expr.MethodCallExpr) sw.getSelector();
                    System.out.println("Method name: " + methodCall.getNameAsString());
                    
                    PatternMatcher pm = new PatternMatcher();
                    String converted = pm.convertMethodCall(methodCall);
                    System.out.println("Converted LOCATE: " + converted);
                    
                    // Check the selector string
                    String selectorStr = sw.getSelector().toString();
                    System.out.println("selectorStr: " + selectorStr);
                    System.out.println("Contains LOCATE: " + selectorStr.contains("LOCATE"));
                }
            }
        }
    }
}
