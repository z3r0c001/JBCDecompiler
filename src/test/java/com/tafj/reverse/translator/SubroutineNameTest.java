package com.tafj.reverse.translator;

import com.tafj.reverse.model.TranslationResult;

public class SubroutineNameTest {

    public static void main(String[] args) {
        TafjTranslator translator = new TafjTranslator();
        
        String javaCode = """
package com.temenos.t24.component_ST_CompanyCreation_22_cl;

public class ST_CompanyCreation_22_cl {
    public void main() {
        this.set(this._BranchCodeList, "test");
    }
}
""";
        
        TranslationResult result = translator.translateToJBC(javaCode);
        System.out.println(result.getJbcCode());
        
        if (result.getJbcCode().contains("SUBROUTINE ST.CompanyCreation.22.cl(...)")) {
            System.out.println("✓ PASSED: Subroutine name from class name");
        } else {
            System.out.println("✗ FAILED");
        }
    }
}
