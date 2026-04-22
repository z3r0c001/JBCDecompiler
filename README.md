# JBC Decompiler

A reverse engineering tool that converts TAFJ (Temenos Application Framework for Java) compiled Java code back to native JBC (JBase Compiler) source code for Temenos T24/Transact applications.

## Overview

This tool addresses the need to recover original JBC source code from TAFJ-compiled Java classes. When Temenos T24 applications are compiled using the TAFJ compiler, the original JBC source is transformed into Java bytecode. This tool performs the reverse translation, reconstructing readable and maintainable JBC source from the generated Java code.



## Design Features

### 1. **Java AST Parsing & Analysis**
   - Parses TAFJ-generated Java source files using JavaParser/Eclipse JDT
   - Builds Abstract Syntax Tree (AST) representation of Java code
   - Identifies TAFJ-specific patterns and metadata annotations
   - Extracts program structure including sections, labels, and control flow

### 2. **TAFJ Metadata Extraction**
   - Parses TAFJ comment blocks to recover original program information:
     - `<TAFJ-BP>` - Base Pointer (file path)
     - `<TAFJ-BPA>` - Application/Mapping reference
     - `<TAFJ-BN>` - Base Name (program name)
     - `<TAFJ-FF>` - File References
   - Uses metadata to reconstruct original program naming and structure

### 3. **Control Flow Reconstruction**
   - **Label Detection**: Identifies `lbl_*` methods and converts to JBC section labels
   - **GOSUB/RETURN Patterns**: Recognizes `_Sys_PostGlobus` and `_Sys_ReturnTo` patterns for subroutine calls
   - **Line Number Tracking**: Extracts `_l(lineNumber)` calls to preserve original source line references
   - **Conditional Logic**: Converts Java `if-else` blocks back to JBC `IF...THEN...ELSE...END` structures
   - **Loop Constructs**: Transforms `do-while(false)` patterns with `break` statements into `BEGIN CASE...END CASE` blocks

### 4. **Variable Mapping & Recovery**
   - **Naming Convention Restoration**: Converts Java camelCase (`_Y_ACCT_NO`) back to JBC dot notation (`Y.ACCT.NO`)
   - **Variable Type Inference**: Deduces variable types from usage patterns and TAFJ type hints
   - **Common Variables**: Identifies and preserves T24 common variables (`_R_ACCT`, `_R.CCY`, etc.)
   - **System Variables**: Maps TAFJ system variables (`_Sys_PostGlobus`, `_Sys_ReturnTo`) to JBC equivalents

### 5. **Component/Component Call Translation**
   - **Component Detection**: Identifies component instances (e.g., `ATMFRM_Foundation`, `AC_AccountOpening`)
   - **$USING Directive Recovery**: Reconstructs `$USING` statements from component declarations
   - **Method Call Translation**: Converts Java method calls to JBC subroutine/function syntax:
     - `getATMFRM_Foundation().getAtAtmParameterRec()` → `ATMFRM.Foundation.getAtAtmParameterRec()`
     - `get(_R_ACCT, getAC_AccountOpening()._Account_Currency, 0, 0)` → `R.ACCT<AC.AccountOpening.Account.Currency>`

### 6. **Expression & Function Translation**
   - **String Operations**:
     - `op_cat(a, b)` → `a:b` (concatenation)
     - `op_sub(a, b)` → `a-b` (subtraction)
     - `op_add(a, b)` → `a+b` (addition)
     - `op_equal(a, b)` → `a EQ b`
     - `op_ne(a, b)` → `a NE b`
     - `op_gt(a, b)` → `a GT b`
     - `op_le(a, b)` → `a LE b`
   - **JBC Functions**:
     - `FMT(value, pattern)` → `FMT(value, pattern)` (preserved)
     - `ABS(value)` → `ABS(value)` (preserved)
     - `DCOUNT(str, delimiter)` → `DCOUNT(str, @VM)`
     - `FIELD(str, delimiter, occurrence)` → `FIELD(str, delimiter, occurrence)`
     - `jAtVariable.VM` → `@VM` (value mark)
     - `set(var, value)` → `var = value` (assignment)

### 7. **Section & Paragraph Structure Recovery**
   - Identifies main entry point (`main()` method) and converts to primary program flow
   - Detects helper methods (`lbl_*`) and converts to labeled sections
   - Reconstructs `SUBROUTINE` declaration with original parameter list
   - Preserves `$PACKAGE` directive from TAFJ-BPA metadata

### 8. **Comment & Documentation Preservation**
   - Extracts Java comments and places them as JBC comments (`*`)
   - Preserves TAFJ metadata comments in JBC format
   - Maintains section separator comments (`*-------------`)
   - Recovers inline documentation from original Java source

### 9. **Special Pattern Recognition**
   - **DEBUG Statements**: Preserves `DEBUG()` calls as JBC `DEBUG`
   - **END Program**: Converts `LABEL_STOP` return to `END` statement
   - **Case Statements**: Detects `do-while(false)` with conditional breaks as `BEGIN CASE...END CASE`
   - **Multi-value Operations**: Recognizes `get(array, field, MV, index)` patterns for multi-value access

### 10. **Output Formatting**
   - Generates properly indented JBC source code
   - Maintains consistent coding style (uppercase keywords, proper spacing)
   - Preserves original line number references in comments
   - Outputs valid JBC syntax compatible with T24 compilation

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Input: TAFJ Java File                    │
│              (e.g., TEST_BAL_RTN_cl.java)             │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                   Java Source Parser                        │
│              (AST Generation & Analysis)                    │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                  TAFJ Pattern Recognizer                    │
│  • Metadata Extraction  • Control Flow Analysis             │
│  • Variable Mapping     • Component Detection               │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                  JBC Code Generator                         │
│  • Section Reconstruction  • Expression Translation         │
│  • Statement Conversion    • Comment Preservation           │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                   Output: JBC Source File                   │
│            (e.g., TEST.BAL.RTN.b)                     │
└─────────────────────────────────────────────────────────────┘
```

## Usage

### GUI Mode (Recommended)

```bash
# Launch the GUI
mvn javafx:run

# Or run the JAR
java -jar target/tafj-reverse-tool-1.0.0-jar-with-dependencies.jar
```

**GUI Features:**
- **Open JAR** (`Ctrl+J`): Browse JAR files with package tree structure
- **Tree View**: Navigate package hierarchy in JAR files
- **Click to Translate**: Click any class in the tree to decompile and translate
- **Export JBC** (`Ctrl+E`): Save translated JBC code to file
- **Syntax Highlighting**: JBC keywords are highlighted in the output pane

### Command Line Mode

```bash
# Run the reverse tool
java -jar tafj-reverse-tool-1.0.0-jar-with-dependencies.jar <input-java-file> [output-directory]

# Example
java -jar target/tafj-reverse-tool-1.0.0-jar-with-dependencies.jar samples/TEST_BAL_RTN_cl.java samples/
