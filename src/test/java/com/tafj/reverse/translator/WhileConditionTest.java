package com.tafj.reverse.translator;

import com.tafj.reverse.model.TranslationResult;

public class WhileConditionTest {

    public static void main(String[] args) {
        TafjTranslator translator = new TafjTranslator();
        
        String javaCode = """
package com.temenos.t24.component_ST_CompanyCreation_22_cl;

public class ST_CompanyCreation_22_cl {
    public void main() {
        do {
            this._isContinue_ = false;

            if (this._isBreak_ || !this.boolVal(this.op_and(this.op_le(this._CompCnt, this._CompDCnt), this.NOT(this._T24AccountNo)))) break;
            
            this._Sys_PostGlobus = this.lbl_READ_ACCOUNT_DETAILS();
            
            this.set(this._CompCnt, this.op_add(this._CompCnt, 1L));
            
        } while (!this._isBreak_ && this._loop_);
        this._loop_ = true;
        this._isBreak_ = false;
        this._isContinue_ = false;
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
        
        TranslationResult result = translator.translateToJBC(javaCode);
        String output = result.getJbcCode();
        System.out.println(output);
        
        System.out.println("\n=== Validation ===");
        if (output.contains("WHILE CompCnt LE CompDCnt AND NOT(T24AccountNo)")) {
            System.out.println("✓ PASSED: WHILE condition is correct!");
        } else if (output.contains("WHILE !(")) {
            System.out.println("✗ FAILED: WHILE condition still has negation wrapper");
        } else {
            System.out.println("? UNKNOWN: Could not determine result");
        }
    }
}
