package com.tafj.reverse.translator;

import java.util.*;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

public class JbcVisitor extends VoidVisitorAdapter<StringBuilder> {

    private PatternMatcher patternMatcher;
    private StringBuilder jbcCode;
    private int indentLevel;
    ArrayList<String> componentList;
    private String subroutineName;

    // Track array element assignments for expansion in get() calls
    private Map<String, List<String>> arrayContents = new HashMap<>();

    // Track loop counter variable initial values (for TAFJ _TestFor_ pattern)
    private Map<String, String> loopCounterValues = new HashMap<>();

    public JbcVisitor(PatternMatcher patternMatcher) {
        this.patternMatcher = patternMatcher;
        this.jbcCode = new StringBuilder();
        this.indentLevel = 0;
        this.componentList = new ArrayList<>();
    }

    public String getJbcCode() {
        return jbcCode.toString();
    }

    @Override
    public void visit(CompilationUnit n, StringBuilder arg) {
        // Process imports for $USING statements
        n.getImports().forEach(importDecl -> {
            String importName = importDecl.getNameAsString();
            if (importName.startsWith("com.temenos.t24.component_")) {
                String component = patternMatcher.extractComponentName(importName);
                if (component != null) {
                    appendIndented("$USING " + component + "\n");
                    componentList.add(component);
                }
            }
        });

        // Visit only types (classes/interfaces), skip comments and other nodes
        for (var type : n.getTypes()) {
            type.accept(this, arg);
        }
    }

    @Override
    public void visit(AssignExpr n, StringBuilder arg) {
        // This is CRITICAL for tracking intermediate variables like 'charArray'
        if (n.getOperator() == AssignExpr.Operator.ASSIGN) {
            Expression target = n.getTarget();
            Expression value = n.getValue();

            // Only track assignments to local variables or fields that are simple enough
            String targetStr = convertExpression(target);
            String valueStr = convertExpression(value);

            // Skip TAFJ infrastructure assignments
            if (targetStr.contains("Sys.PostGlobus") ||
                    targetStr.equals("this._Sys_PostGlobus") ||
                    targetStr.contains("Sys.ReturnTo") ||
                    targetStr.equals("this._Sys_ReturnTo")) {
                super.visit(n, arg);
                return;
            }

            // Track the variable if the target is a simple name (local var)
            if (target instanceof NameExpr) {
                String varName = ((NameExpr) target).getNameAsString();
                // Only track if the value is a method call or complex expression, not a literal
                if (!(value instanceof StringLiteralExpr) && !(value instanceof IntegerLiteralExpr)) {
                    patternMatcher.variableMethodCalls.put(varName, valueStr);
                }
            }

            // For the purpose of the final output, we might also want to emit the
            // assignment if it's a field/setter
            // But the bug report focuses on the final assignment to _SEL_CMD
            super.visit(n, arg);
        } else {
            super.visit(n, arg);
        }
    }

    @Override
    public void visit(ClassOrInterfaceDeclaration n, StringBuilder arg) {
        // Capture class name as subroutine name (convert underscores to dots)
        String className = n.getNameAsString();
        this.subroutineName = className.replaceAll("_\\d+_cl", "").replaceAll("_cl", "").replace("_", ".");

        // Only visit methods
        for (var method : n.getMethods()) {
            method.accept(this, arg);
        }
    }

    @Override
    public void visit(MethodDeclaration n, StringBuilder arg) {
        String methodName = n.getNameAsString();
        if (methodName.startsWith("lbl_")) {
            String labelName = methodName.substring(4).replace("_", ".");
            appendIndented(labelName + ":\n");
            indentLevel++;
        } else if (methodName.equals("main") && n.getParameters().isEmpty()) {
            String subName = (subroutineName != null && !subroutineName.isEmpty()) ? subroutineName : "TEST.RTN";
            System.out.println("subName :" + subName);
            appendIndented("SUBROUTINE " + subName + "(...)\n");
            indentLevel++;
        } else {
            return;
        }

        // Process method body - only statements, skip comments
        n.getBody().ifPresent(body -> {
            for (var stmt : body.getStatements()) {
                // Skip comment statements
                if (stmt instanceof ExpressionStmt) {
                    stmt.accept(this, arg);
                } else if (stmt instanceof IfStmt || stmt instanceof ForStmt ||
                        stmt instanceof DoStmt || stmt instanceof WhileStmt ||
                        stmt instanceof ReturnStmt || stmt instanceof BlockStmt ||
                        stmt instanceof SwitchStmt) {
                    stmt.accept(this, arg);
                }
                // Skip comments
            }
        });

        if (methodName.startsWith("lbl_") || methodName.equals("main")) {
            indentLevel--;
        }
    }

    @Override
    public void visit(IfStmt n, StringBuilder arg) {
        // System.out.println("IfStmt :" + n.toString());

        // Skip TAFJ infrastructure code: _Sys_PostGlobus checks
        String conditionStr = n.getCondition().toString();
        if (conditionStr.contains("_Sys_PostGlobus") ||
                conditionStr.contains("Sys.PostGlobus")) {
            // Skip this TAFJ boilerplate code
            return;
        }

        // Convert condition (with array contents available for expansion)
        patternMatcher.setArrayContents(arrayContents);
        String condition = convertCondition(n.getCondition());
        patternMatcher.setArrayContents(null);

        if (!condition.isEmpty()) {
            if (condition.contains("session.isUnitTest()")) {
                if (n.getElseStmt().isPresent()) {
                    n.getElseStmt().get().accept(this, arg);
                }
            } else if (condition.contains("session.getStateSubroutineAfterCall()")) {
            } else {
                appendIndented("\nIF ");
                jbcCode.append(condition);
                jbcCode.append(" THEN\n");

                indentLevel++;
                n.getThenStmt().accept(this, arg);
                indentLevel--;

                if (n.getElseStmt().isPresent()) {
                    appendIndented("END ELSE\n");
                    indentLevel++;
                    n.getElseStmt().get().accept(this, arg);
                    indentLevel--;
                }

                appendIndented("END\n");
            }
        }
        // super.visit(n, arg);
    }

    @Override
    public void visit(SwitchStmt n, StringBuilder arg) {
        // Check if this is a LOCATE switch statement
        String selectorStr = n.getSelector().toString();
        if (selectorStr.contains("LOCATE")) {
            // Convert LOCATE switch to IF/THEN/END structure
            // The selector already contains "LOCATE ... THEN" from PatternMatcher
            String locateStmt = patternMatcher.convertMethodCall(
                    (MethodCallExpr) ((SwitchStmt) n).getSelector());

            if (locateStmt != null && locateStmt.startsWith("LOCATE ")) {
                appendIndented(locateStmt + "\n");
                indentLevel++;

                // Process each case
                for (SwitchEntry entry : n.getEntries()) {
                    if (entry.getLabels().isEmpty()) {
                        // default case (NOT FOUND) - skip for now
                        continue;
                    }

                    // For LOCATE, case 0 means found, other cases mean not found
                    // We only process case 0 (found case)
                    boolean isFoundCase = false;
                    for (var label : entry.getLabels()) {
                        if (label.toString().equals("0")) {
                            isFoundCase = true;
                            break;
                        }
                    }

                    if (isFoundCase) {
                        // Process statements in this case
                        for (Statement stmt : entry.getStatements()) {
                            stmt.accept(this, arg);
                        }
                    }
                }

                indentLevel--;
                appendIndented("END\n");
                return;
            }
        }
        // For other switch statements, visit all entries to track array assignments
        for (SwitchEntry entry : n.getEntries()) {
            for (Statement stmt : entry.getStatements()) {
                stmt.accept(this, arg);
            }
        }
    }

    @Override
    public void visit(ForStmt n, StringBuilder arg) {
        // System.out.println("ForStmt :" + n.toString());
        // Detect TAFJ FOR loops
        Expression init = n.getInitialization().isEmpty() ? null : n.getInitialization().get(0);
        Expression compare = n.getCompare().orElse(null);
        Expression update = n.getUpdate().isEmpty() ? null : n.getUpdate().get(0);

        String forLine = patternMatcher.convertForLoop(init, compare, update);
        appendIndented(forLine + "\n");

        indentLevel++;
        // Process body, skipping TAFJ boilerplate at end (counter = this._Var)
        if (n.getBody() instanceof BlockStmt) {
            BlockStmt block = (BlockStmt) n.getBody();
            for (Statement stmt : block.getStatements()) {
                if (isForLoopEndBoilerplate(stmt)) {
                    continue;
                }
                stmt.accept(this, arg);
            }
        } else {
            n.getBody().accept(this, arg);
        }
        indentLevel--;

        appendIndented("NEXT\n");
    }

    @Override
    public void visit(DoStmt n, StringBuilder arg) {
        // System.out.println("DoStmt :" + n.toString());
        // Detect TAFJ BEGIN CASE pattern (do-while-false with breaks)
        String body = n.getBody().toString();
        String conditionStr = n.getCondition().toString();

        if (body.contains("break") && conditionStr.contains("false")) {
            // This is likely a BEGIN CASE statement
            appendIndented("BEGIN CASE\n");
            indentLevel++;
            n.getBody().accept(this, arg);
            indentLevel--;
            appendIndented("END CASE\n");
        } else if (body.contains("break") && (conditionStr.contains("_isBreak_") || conditionStr.contains("_loop_"))) {
            // TAFJ WHILE loop pattern: do { if (breakCondition) break; ... } while
            // (!_isBreak_ && _loop_)
            // Find the break IF statement and extract the WHILE condition
            String whileCondition = extractWhileCondition(n);

            if (whileCondition != null && !whileCondition.isEmpty()) {
                appendIndented("LOOP\n");
                indentLevel++;
                appendIndented("WHILE " + whileCondition + "\n");
                indentLevel++;

                // Process body statements, skipping break IF and TAFJ boilerplate
                if (n.getBody() instanceof BlockStmt) {
                    BlockStmt block = (BlockStmt) n.getBody();
                    for (Statement stmt : block.getStatements()) {
                        if (isBreakIfStatement(stmt)) {
                            // Skip the break IF statement - it's the loop condition check
                            continue;
                        }
                        if (isTafjLoopBoilerplate(stmt)) {
                            // Skip TAFJ loop control assignments
                            continue;
                        }
                        stmt.accept(this, arg);
                    }
                } else {
                    n.getBody().accept(this, arg);
                }

                indentLevel--;
                indentLevel--;
                appendIndented("REPEAT\n");
            } else {
                // Fallback to regular DO loop
                appendIndented("LOOP\n");
                indentLevel++;
                n.getBody().accept(this, arg);
                indentLevel--;
                appendIndented("REPEAT\n");
            }
        } else {
            // Regular DO loop
            appendIndented("LOOP\n");
            indentLevel++;
            n.getBody().accept(this, arg);
            indentLevel--;
            appendIndented("REPEAT\n");
        }
    }

    @Override
    public void visit(com.github.javaparser.ast.stmt.WhileStmt n, StringBuilder arg) {
        Expression condition = n.getCondition();

        // Detect TAFJ FOR loop pattern: while (this._TestFor_(counter, limit, step))
        if (condition instanceof MethodCallExpr) {
            MethodCallExpr condCall = (MethodCallExpr) condition;
            if (condCall.getNameAsString().equals("_TestFor_") && condCall.getArguments().size() >= 3) {
                Expression counterExpr = condCall.getArgument(0);
                Expression limitExpr = condCall.getArgument(1);

                String counterName = counterExpr.toString().trim();
                String limitStr = patternMatcher.convertExpression(limitExpr);

                // Look for the loop variable in the body: this._TargetVar = counter;
                String loopVarName = null;
                String startValue = null;

                if (n.getBody() instanceof BlockStmt) {
                    BlockStmt body = (BlockStmt) n.getBody();
                    for (Statement stmt : body.getStatements()) {
                        if (stmt instanceof ExpressionStmt) {
                            ExpressionStmt exprStmt = (ExpressionStmt) stmt;
                            if (exprStmt.getExpression() instanceof AssignExpr) {
                                AssignExpr assign = (AssignExpr) exprStmt.getExpression();
                                String assignStr = assign.getValue().toString().trim();
                                // Check if RHS is the counter variable
                                if (assignStr.equals(counterName)) {
                                    // Found: targetVar = counter
                                    loopVarName = patternMatcher.convertExpression(assign.getTarget());
                                    // Look up the counter's initial value
                                    if (loopCounterValues.containsKey(counterName)) {
                                        startValue = loopCounterValues.get(counterName);
                                    }
                                    break;
                                }
                            }
                        }
                    }
                }

                if (loopVarName != null && startValue != null) {
                    // Build FOR line
                    String forLine = "FOR " + loopVarName + " = " + startValue + " TO " + limitStr;
                    appendIndented(forLine + "\n");

                    indentLevel++;
                    // Process body, skipping TAFJ boilerplate
                    if (n.getBody() instanceof BlockStmt) {
                        BlockStmt body = (BlockStmt) n.getBody();
                        for (Statement stmt : body.getStatements()) {
                            if (isTafjLoopBoilerplate(stmt))
                                continue;
                            if (isForLoopIncrement(stmt, counterName))
                                continue;
                            if (isForLoopEndBoilerplate(stmt))
                                continue;
                            // Skip the assignment: loopVar = counter
                            if (stmt instanceof ExpressionStmt) {
                                ExpressionStmt exprStmt = (ExpressionStmt) stmt;
                                if (exprStmt.getExpression() instanceof AssignExpr) {
                                    AssignExpr assign = (AssignExpr) exprStmt.getExpression();
                                    if (assign.getValue().toString().trim().equals(counterName))
                                        continue;
                                }
                            }
                            stmt.accept(this, arg);
                        }
                    } else {
                        n.getBody().accept(this, arg);
                    }
                    indentLevel--;

                    // Build NEXT line
                    String nextLine = "NEXT " + loopVarName;
                    appendIndented(nextLine + "\n");
                    return;
                }
            }
        }

        // Fallback: treat as regular WHILE loop
        String conditionStr = convertCondition(condition);
        appendIndented("LOOP\n");
        indentLevel++;
        appendIndented("WHILE " + conditionStr + "\n");
        indentLevel++;
        n.getBody().accept(this, arg);
        indentLevel--;
        indentLevel--;
        appendIndented("REPEAT\n");
    }

    /**
     * Check if statement is the TAFJ for-loop increment (counter = _inc2(var,
     * step))
     */
    private boolean isForLoopIncrement(Statement stmt, String counterName) {
        if (stmt instanceof ExpressionStmt) {
            ExpressionStmt exprStmt = (ExpressionStmt) stmt;
            if (exprStmt.getExpression() instanceof AssignExpr) {
                AssignExpr assign = (AssignExpr) exprStmt.getExpression();
                String targetStr = assign.getTarget().toString().trim();
                if (targetStr.equals(counterName)) {
                    Expression value = assign.getValue();
                    if (value instanceof MethodCallExpr) {
                        MethodCallExpr methodCall = (MethodCallExpr) value;
                        return methodCall.getNameAsString().equals("_inc2") ||
                                methodCall.getNameAsString().equals("_inc");
                    }
                }
            }
        }
        return false;
    }

    /**
     * Check if statement is a TAFJ FOR loop end boilerplate (counter = this._Var)
     * e.g., l2 = this._PTyp; l = this._Prt; l3 = this.tvar;
     */
    private boolean isForLoopEndBoilerplate(Statement stmt) {
        if (stmt instanceof ExpressionStmt) {
            ExpressionStmt exprStmt = (ExpressionStmt) stmt;
            if (exprStmt.getExpression() instanceof AssignExpr) {
                AssignExpr assign = (AssignExpr) exprStmt.getExpression();
                String targetStr = assign.getTarget().toString().trim();
                String valueStr = assign.getValue().toString().trim();
                // Pattern: simpleVar = this._Something or simpleVar = this.something
                // where simpleVar is a simple name (likely a loop counter like l, l2, jVar2,
                // counter1)
                if (targetStr.matches("[a-zA-Z]+\\d*") && valueStr.startsWith("this.")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Extract the WHILE condition from the break IF statement in a TAFJ loop
     * TAFJ pattern: if (_isBreak_ || !boolVal(actualCondition)) break;
     * The WHILE condition is just the actualCondition part
     */
    private String extractWhileCondition(DoStmt doStmt) {
        if (doStmt.getBody() instanceof BlockStmt) {
            BlockStmt block = (BlockStmt) doStmt.getBody();
            for (Statement stmt : block.getStatements()) {
                if (stmt instanceof IfStmt) {
                    IfStmt ifStmt = (IfStmt) stmt;
                    // Check if this IF contains a break
                    if (containsBreak(ifStmt.getThenStmt())) {
                        Expression condition = ifStmt.getCondition();

                        // TAFJ pattern: if (_isBreak_ || !boolVal(actualCondition)) break;
                        // Detect OR expression: left || right
                        if (condition instanceof BinaryExpr) {
                            BinaryExpr orExpr = (BinaryExpr) condition;
                            if (orExpr.getOperator() == BinaryExpr.Operator.OR) {
                                Expression right = orExpr.getRight();
                                // Right side should be: !boolVal(actualCondition)
                                if (right instanceof UnaryExpr) {
                                    UnaryExpr notExpr = (UnaryExpr) right;
                                    if (notExpr.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
                                        Expression inner = notExpr.getExpression();
                                        // inner could be boolVal(actualCondition) or just actualCondition
                                        if (inner instanceof MethodCallExpr) {
                                            MethodCallExpr innerCall = (MethodCallExpr) inner;
                                            if (innerCall.getNameAsString().equals("boolVal")
                                                    && innerCall.getArguments().size() > 0) {
                                                // Extract from boolVal
                                                return convertCondition(innerCall.getArgument(0));
                                            }
                                        }
                                        // If not boolVal, just convert the inner expression
                                        return convertCondition(inner);
                                    }
                                }
                            }
                        }

                        // Fallback: negate the whole break condition
                        String breakCondition = convertCondition(condition);
                        return "!(" + breakCondition + ")";
                    }
                }
            }
        }
        return null;
    }

    /**
     * Check if a statement is the break IF in a TAFJ loop
     */
    private boolean isBreakIfStatement(Statement stmt) {
        if (stmt instanceof IfStmt) {
            IfStmt ifStmt = (IfStmt) stmt;
            return containsBreak(ifStmt.getThenStmt());
        }
        return false;
    }

    /**
     * Check if statement or its children contain a break
     */
    private boolean containsBreak(Statement stmt) {
        if (stmt instanceof BreakStmt)
            return true;
        if (stmt instanceof BlockStmt) {
            for (Statement s : ((BlockStmt) stmt).getStatements()) {
                if (containsBreak(s))
                    return true;
            }
        }
        return false;
    }

    /**
     * Check if statement is TAFJ loop control boilerplate (_loop_, _isBreak_,
     * _isContinue_ assignments)
     */
    private boolean isTafjLoopBoilerplate(Statement stmt) {
        if (stmt instanceof ExpressionStmt) {
            ExpressionStmt exprStmt = (ExpressionStmt) stmt;
            String str = exprStmt.getExpression().toString();
            return str.contains("_loop_") || str.contains("_isBreak_") || str.contains("_isContinue_");
        }
        return false;
    }

    @Override
    public void visit(BreakStmt n, StringBuilder arg) {
        // In CASE context, break is implicit
        // Don't output anything for break in CASE
    }

    @Override
    public void visit(BlockStmt n, StringBuilder arg) {
        // Visit all statements in the block
        for (Statement stmt : n.getStatements()) {
            stmt.accept(this, arg);
        }
    }

    @Override
    public void visit(ReturnStmt n, StringBuilder arg) {
        // Skip TAFJ infrastructure returns
        if (n.getExpression().isPresent()) {
            String exprStr = n.getExpression().get().toString();
            if (exprStr.contains("_Sys_PostGlobus") ||
                    exprStr.contains("_Sys_ReturnTo")) {
                return;
            }
        }
        jbcCode.append("RETURN\n");
    }

    @Override
    public void visit(ExpressionStmt n, StringBuilder arg) {
        Expression expr = n.getExpression();

        /*
         * // Skip TAFJ loop boilerplate assignments after WHILE loop conversion
         * // These are: _loop_ = true, _isBreak_ = false, _isContinue_ = false
         * if (expr instanceof AssignExpr) {
         * AssignExpr assign = (AssignExpr) expr;
         * String targetStr = assign.getTarget().toString();
         * if (targetStr.contains("_loop_") || targetStr.contains("_isBreak_") ||
         * targetStr.contains("_isContinue_")) {
         * // Skip these boilerplate assignments
         * return;
         * }
         * }
         */

        // Track variable assignments from method calls: Type var =
        // this.getComponent().method();
        if (expr instanceof VariableDeclarationExpr) {
            VariableDeclarationExpr varDecl = (VariableDeclarationExpr) expr;
            if (varDecl.getVariables().size() == 1) {
                com.github.javaparser.ast.body.VariableDeclarator varDeclarator = varDecl.getVariables().get(0);
                if (varDeclarator.getInitializer().isPresent()) {
                    Expression init = varDeclarator.getInitializer().get();
                    String varName = varDeclarator.getNameAsString();

                    // Check if initializer is a method call
                    if (init instanceof MethodCallExpr) {
                        MethodCallExpr methodCall = (MethodCallExpr) init;
                        // Check for jVarFactory.get((int)N) pattern → track N as loop counter value
                        if (methodCall.getNameAsString().equals("get") && methodCall.getArguments().size() >= 1) {
                            Expression initArg = methodCall.getArgument(0);
                            if (initArg instanceof CastExpr) {
                                initArg = ((CastExpr) initArg).getExpression();
                            }
                            if (initArg instanceof IntegerLiteralExpr || initArg instanceof LongLiteralExpr) {
                                loopCounterValues.put(varName, patternMatcher.convertExpression(initArg));
                                // Don't output variable declarations
                                return;
                            }
                        }
                        // General method call tracking
                        String convertedMethod = patternMatcher.convertMethodCall(methodCall);
                        if (convertedMethod == null) {
                            // Fallback: try converting the method call chain directly
                            convertedMethod = patternMatcher.convertVariableOrMethodCall(methodCall);
                        }
                        if (convertedMethod != null) {
                            patternMatcher.variableMethodCalls.put(varName, convertedMethod);
                            // Don't output variable declarations
                            return;
                        }
                    } else if (init instanceof IntegerLiteralExpr || init instanceof LongLiteralExpr) {
                        // Track literal initializers for loop counter detection (e.g., long l2 = 1L)
                        loopCounterValues.put(varName, patternMatcher.convertExpression(init));
                    }
                }
            }
        }

        // Track array element assignments: array[i] = value
        if (expr instanceof AssignExpr) {
            AssignExpr assign = (AssignExpr) expr;
            Expression target = assign.getTarget();
            String targetStr = assign.getTarget().toString();
            if (targetStr.contains("_loop_") || targetStr.contains("_isBreak_") || targetStr.contains("_isContinue_")) {
                // Skip TAFJ loop boilerplate assignments after WHILE loop conversion
                return;
            }

            // Check if target is an array access: array[index] or this.array[index]
            Expression arrayNameExpr = null;
            Expression indexExpr = null;

            if (target instanceof ArrayAccessExpr) {
                ArrayAccessExpr arrayAccess = (ArrayAccessExpr) target;
                arrayNameExpr = arrayAccess.getName();
                indexExpr = arrayAccess.getIndex();
            }

            if (arrayNameExpr != null && indexExpr != null) {
                String arrayName = arrayNameExpr.toString().trim();
                String indexStr = indexExpr.toString().trim();

                // Try to parse index as integer
                try {
                    int index = Integer.parseInt(indexStr);
                    // Use convertFieldExpression for proper component_ handling
                    String valueStr = patternMatcher.convertFieldExpression(assign.getValue());

                    // Store in array contents map
                    arrayContents.computeIfAbsent(arrayName, k -> new ArrayList<>());
                    List<String> contents = arrayContents.get(arrayName);

                    // Ensure list is large enough
                    while (contents.size() <= index) {
                        contents.add("");
                    }
                    contents.set(index, valueStr);

                    // Don't output array element assignments
                    return;
                } catch (NumberFormatException e) {
                    // Not a numeric index, fall through to normal handling
                }
            }
        }

        if (expr instanceof MethodCallExpr) {
            MethodCallExpr methodCall = (MethodCallExpr) expr;
            String methodName = methodCall.getNameAsString();

            // Skip TAFJ line number markers: _l(...)
            if (methodName.equals("_l")) {
                return;
            }

            // Handle GOSUB pattern
            if (methodName.startsWith("lbl_")) {
                String labelName = methodName.replace("lbl_", "").replace("_", ".");
                appendIndented("GOSUB " + labelName + "\n");
                return;
            }

            // Handle set() as assignment
            if (methodName.equals("set")) {
                // Check if set() contains fGet() call: set(target, fGet(array, fields...))
                if (methodCall.getArguments().size() >= 2) {
                    Expression valueArg = methodCall.getArgument(1);
                    if (valueArg instanceof MethodCallExpr) {
                        MethodCallExpr fGetCall = (MethodCallExpr) valueArg;
                        if (fGetCall.getNameAsString().equals("fGet") && fGetCall.getArguments().size() >= 2) {
                            // fGet(array, field1, field2, ...)
                            Expression arrayExpr = fGetCall.getArgument(0);

                            // Check if array argument is a tracked variable from method call assignments
                            String arrayStr = null;
                            if (arrayExpr instanceof NameExpr) {
                                String varName = ((NameExpr) arrayExpr).getNameAsString();
                                if (patternMatcher.variableMethodCalls.containsKey(varName)) {
                                    arrayStr = patternMatcher.variableMethodCalls.get(varName);
                                    patternMatcher.variableMethodCalls.remove(varName);
                                }
                            }

                            if (arrayStr == null) {
                                // Fallback to normal conversion
                                arrayStr = patternMatcher.convertExpression(arrayExpr);
                            }

                            // Build field list from remaining fGet() arguments (starting from index 1)
                            StringBuilder fieldList = new StringBuilder();
                            for (int i = 1; i < fGetCall.getArguments().size(); i++) {
                                String fieldStr = patternMatcher.convertFieldExpression(fGetCall.getArgument(i));
                                // Skip 0 values
                                if (!fieldStr.equals("0")) {
                                    if (fieldList.length() > 0)
                                        fieldList.append(",");
                                    fieldList.append(fieldStr);
                                }
                            }

                            if (fieldList.length() > 0) {
                                arrayStr = arrayStr + "[" + fieldList.toString() + "]";
                            }

                            String targetStr = patternMatcher.convertExpression(methodCall.getArgument(0));
                            String assignment = targetStr + " = " + arrayStr;
                            appendIndented(assignment + "\n");
                            return;
                        }
                    }
                }

                // Check if set() contains FIELD() with tracked variables
                int argCount = methodCall.getArguments().size();
                if (argCount >= 2) {
                    Expression valueArg = methodCall.getArgument(argCount - 1);
                    if (valueArg instanceof MethodCallExpr) {
                        MethodCallExpr fieldCall = (MethodCallExpr) valueArg;
                        if (fieldCall.getNameAsString().equals("FIELD") && fieldCall.getArguments().size() >= 2) {
                            // FIELD(string, delimiter, fieldNum, ...)
                            // Check if first argument is a get() call with array contents tracking
                            Expression firstArgExpr = fieldCall.getArgument(0);
                            String firstArg;

                            if (firstArgExpr instanceof MethodCallExpr) {
                                MethodCallExpr innerGetCall = (MethodCallExpr) firstArgExpr;
                                if ((innerGetCall.getNameAsString().equals("get")
                                        || innerGetCall.getNameAsString().equals("fGet"))
                                        && innerGetCall.getArguments().size() >= 2) {
                                    Expression innerArrayExpr = innerGetCall.getArgument(0);
                                    Expression innerFieldExpr = innerGetCall.getArgument(1);

                                    // Check if second argument is a tracked array from indexed assignments
                                    if (innerFieldExpr instanceof NameExpr && arrayContents != null) {
                                        String varName = ((NameExpr) innerFieldExpr).getNameAsString();
                                        if (arrayContents.containsKey(varName)) {
                                            List<String> fields = arrayContents.get(varName);
                                            StringBuilder fieldList = new StringBuilder();
                                            for (int i = 0; i < fields.size(); i++) {
                                                String field = fields.get(i);
                                                if (!field.equals("0") && !field.isEmpty()) {
                                                    if (fieldList.length() > 0)
                                                        fieldList.append(",");
                                                    fieldList.append(field);
                                                }
                                            }

                                            String innerArrayConverted = patternMatcher
                                                    .convertExpression(innerArrayExpr);
                                            if (fieldList.length() > 0) {
                                                firstArg = innerArrayConverted + "<" + fieldList.toString() + ">";
                                            } else {
                                                firstArg = innerArrayConverted;
                                            }
                                        } else {
                                            firstArg = expandTrackedVariable(firstArgExpr);
                                        }
                                    } else {
                                        firstArg = expandTrackedVariable(firstArgExpr);
                                    }
                                } else {
                                    firstArg = expandTrackedVariable(firstArgExpr);
                                }
                            } else {
                                firstArg = expandTrackedVariable(firstArgExpr);
                            }

                            // Build the FIELD call with expanded first argument
                            StringBuilder argsBuilder = new StringBuilder();
                            argsBuilder.append(firstArg);
                            for (int i = 1; i < fieldCall.getArguments().size(); i++) {
                                argsBuilder.append(", ");
                                argsBuilder.append(patternMatcher.convertExpression(fieldCall.getArgument(i)));
                            }

                            String targetStr = patternMatcher.convertExpression(methodCall.getArgument(0));
                            String assignment = targetStr + " = FIELD(" + argsBuilder.toString() + ")";
                            appendIndented(assignment + "\n");
                            return;
                        }
                    }
                }

                // Check if set() contains get() with an array variable or tracked variable
                // Use PatternMatcher with arrayContents to handle nested get() calls (e.g.,
                // inside FIELD)
                if (argCount >= 2) {
                    Expression valueArg = methodCall.getArgument(argCount - 1);
                    if (valueArg instanceof MethodCallExpr) {
                        MethodCallExpr getCall = (MethodCallExpr) valueArg;
                        String getMethodName = getCall.getNameAsString();
                        // Check if value is a get() call with an array variable that can be expanded
                        if ((getMethodName.equals("get") || getMethodName.equals("fGet"))
                                && getCall.getArguments().size() >= 2) {
                            Expression arrayExpr = getCall.getArgument(0);
                            Expression fieldExpr = getCall.getArgument(1);

                            // Check if first argument (array) is a tracked variable from method call
                            String arrayStr = null;
                            if (arrayExpr instanceof NameExpr) {
                                String varName = ((NameExpr) arrayExpr).getNameAsString();
                                if (patternMatcher.variableMethodCalls.containsKey(varName)) {
                                    arrayStr = patternMatcher.variableMethodCalls.get(varName);
                                    patternMatcher.variableMethodCalls.remove(varName);
                                }
                            }

                            if (arrayStr != null) {
                                // Build field list from remaining get() arguments
                                StringBuilder fieldList = new StringBuilder();
                                for (int i = 1; i < getCall.getArguments().size(); i++) {
                                    String fieldStr = patternMatcher.convertFieldExpression(getCall.getArgument(i));
                                    // Skip 0 values
                                    if (!fieldStr.equals("0")) {
                                        if (fieldList.length() > 0)
                                            fieldList.append(",");
                                        fieldList.append(fieldStr);
                                    }
                                }

                                if (fieldList.length() > 0) {
                                    arrayStr = arrayStr + "<" + fieldList.toString() + ">";
                                }

                                String targetStr = patternMatcher.convertExpression(methodCall.getArgument(0));
                                String assignment = targetStr + " = " + arrayStr;
                                appendIndented(assignment + "\n");
                                return;
                            }

                            // Check if second argument is a tracked array from indexed assignments
                            // (objectArray2 pattern)
                            if (fieldExpr instanceof NameExpr && arrayContents != null) {
                                String varName = ((NameExpr) fieldExpr).getNameAsString();
                                if (arrayContents.containsKey(varName)) {
                                    // get(array, trackedArray) → expand
                                    List<String> fields = arrayContents.get(varName);
                                    StringBuilder fieldList = new StringBuilder();
                                    for (int i = 0; i < fields.size(); i++) {
                                        String field = fields.get(i);
                                        if (!field.equals("0") && !field.isEmpty()) {
                                            if (fieldList.length() > 0)
                                                fieldList.append(",");
                                            fieldList.append(field);
                                        }
                                    }

                                    // Convert array expression
                                    String arrayConverted = patternMatcher.convertExpression(arrayExpr);
                                    if (fieldList.length() > 0) {
                                        arrayStr = arrayConverted + "<" + fieldList.toString() + ">";
                                    } else {
                                        arrayStr = arrayConverted;
                                    }

                                    String targetStr = patternMatcher.convertExpression(methodCall.getArgument(0));
                                    String assignment = targetStr + " = " + arrayStr;
                                    appendIndented(assignment + "\n");
                                    return;
                                }
                            }
                        }
                    }
                }

                // Standard multi-field or simple set() conversion
                String assignment = patternMatcher.convertSetCall(methodCall);
                if (assignment != null) {
                    appendIndented(assignment + "\n");
                    return;
                }
            }

            // --- NEW LOGIC TO HANDLE OPF/SERVICE CALLS ---
            if (methodName.equals("invoke")) {
                // Check for the pattern: service.invoke(new Object[]{arg1, arg2})

                if (methodCall.getArguments().size() >= 1) {
                    Expression firstArg = methodCall.getArgument(0);
                    if (firstArg instanceof Expression || firstArg instanceof AssignExpr) {
                        // This might be the component selector string, e.g., new String[]{"OPF"} or
                        // similar setup.

                        String scope = methodCall.getScope().toString();
                        scope = scope.startsWith("Optional[") ? scope.substring(9) : scope;

                        String serviceName = scope.substring(0, scope.indexOf("_cl.INSTANCE"))
                                .replaceAll("_\\d+_cl", "").replaceAll("_cl", "").replace("_", ".");
                        ;

                        // Convert arguments to JBC format: FN.SEAT.RESULTS, F.SEAT.RESULTS
                        StringBuilder argList = new StringBuilder();
                        for (int i = 0; i < methodCall.getArguments().size(); i++) {
                            String convertedArg = convertExpression(methodCall.getArgument(i));
                            if (convertedArg != null && !convertedArg.isEmpty()) {
                                if (argList.length() > 0)
                                    argList.append(", ");
                                argList.append(convertedArg);
                            }
                        }

                        // Emit the CALL statement
                        String assignment = "CALL " + serviceName + "(" + argList.toString() + ")";
                        appendIndented(assignment + "\n");
                        return;

                    }
                }
            }

            // Handle DEBUG
            if (methodName.equals("DEBUG")) {
                appendIndented("DEBUG\n");
                return;
            }

            // Handle other method calls (e.g., component method calls)
            String jbcEquivalent = patternMatcher.convertMethodCall(methodCall);
            if (jbcEquivalent != null && !jbcEquivalent.isEmpty()) {
                // Remove any trailing newlines from the converted method call
                jbcEquivalent = jbcEquivalent.trim();
                appendIndented(jbcEquivalent + "\n");
                return;
            }
        }

        if (expr instanceof AssignExpr) {
            String assignment = convertAssignment((AssignExpr) expr);
            if (assignment != null) {
                appendIndented(assignment + "\n");
            }
        }
        super.visit(n, arg);
    }

    @Override
    public void visit(MethodCallExpr n, StringBuilder arg) {
        String methodName = n.getNameAsString();

        // Skip TAFJ line number markers: _l(...)
        if (methodName.equals("_l")) {
            return;
        }

        // Convert TAFJ runtime calls to JBC
        String jbcEquivalent = patternMatcher.convertMethodCall(n);

        if (jbcEquivalent != null) {
            jbcCode.append(jbcEquivalent);
        }
        // For unknown methods, skip - don't output anything
    }

    @Override
    public void visit(VariableDeclarationExpr n, StringBuilder arg) {
        // JBC doesn't need explicit variable declarations
        // Add as comment for reference
        // String varName = n.getVariables().get(0).getNameAsString();
        // String jbcVarName = patternMatcher.convertVariableName(varName);
        // appendIndented("* VAR: " + jbcVarName + "\n");
        // super.visit(n, arg);
    }

    @Override
    public void visit(StringLiteralExpr n, StringBuilder arg) {
        // Preserve string literals
        jbcCode.append("'").append(n.getValue()).append("'");
    }

    @Override
    public void visit(IntegerLiteralExpr n, StringBuilder arg) {
        // Skip integer literals - they're usually from _l() calls or other TAFJ
        // boilerplate
        // Don't output raw integers
    }

    @Override
    public void visit(DoubleLiteralExpr n, StringBuilder arg) {
        jbcCode.append(n.getValue());
    }

    @Override
    public void visit(BooleanLiteralExpr n, StringBuilder arg) {
        jbcCode.append(n.getValue() ? "1" : "0");
    }

    @Override
    public void visit(BlockComment n, StringBuilder arg) {
        // Skip all block comments - they may contain line numbers
    }

    @Override
    public void visit(LineComment n, StringBuilder arg) {
        // Skip all line comments
    }

    private String convertCondition(Expression condition) {
        // System.out.println("convertCondition :" + condition.toString());
        if (condition instanceof MethodCallExpr) {
            MethodCallExpr methodCall = (MethodCallExpr) condition;
            String methodName = methodCall.getNameAsString();

            // Convert boolVal(op_equal(...)) to simple comparison
            if (methodName.equals("boolVal")) {
                Expression inner = methodCall.getArgument(0);
                if (inner instanceof MethodCallExpr) {
                    MethodCallExpr opMethod = (MethodCallExpr) inner;
                    String opName = opMethod.getNameAsString();

                    switch (opName) {
                        case "op_equal":
                            return patternMatcher.convertBinaryOp(opMethod, "EQ");
                        case "op_ne":
                            return patternMatcher.convertBinaryOp(opMethod, "NE");
                        case "op_gt":
                            return patternMatcher.convertBinaryOp(opMethod, "GT");
                        case "op_lt":
                            return patternMatcher.convertBinaryOp(opMethod, "LT");
                        case "op_ge":
                            return patternMatcher.convertBinaryOp(opMethod, "GE");
                        case "op_le":
                            return patternMatcher.convertBinaryOp(opMethod, "LE");
                        case "op_match":
                            return patternMatcher.convertBinaryOp(opMethod, "MATCHES");
                        case "op_and":
                            // op_and(A, B) → A AND B
                            return convertBinaryOpWithAnd(opMethod);
                        case "op_or":
                            // op_or(A, B) → A OR B
                            return convertBinaryOpWithOr(opMethod);
                        case "NOT":
                            // NOT(expr) → NOT(expr)
                            return "NOT(" + convertCondition(opMethod.getArgument(0)) + ")";
                        default:
                            // For other boolVal wrapped expressions, convert the inner expression
                            return patternMatcher.convertExpression(inner);
                    }
                }
            } else if (methodName.equals("op_and")) {
                // Direct op_and without boolVal wrapper
                return convertBinaryOpWithAnd(methodCall);
            } else if (methodName.equals("op_or")) {
                return convertBinaryOpWithOr(methodCall);
            } else if (methodName.equals("NOT")) {
                return "NOT(" + convertCondition(methodCall.getArgument(0)) + ")";
            }
            // For direct method calls (like op_equal without boolVal)
            return patternMatcher.convertMethodCall(methodCall);
        } else if (condition instanceof BinaryExpr) {
            // Handle Java binary operators like || and &&
            BinaryExpr binary = (BinaryExpr) condition;
            switch (binary.getOperator()) {
                case OR:
                    return convertCondition(binary.getLeft()) + " OR " + convertCondition(binary.getRight());
                case AND:
                    return convertCondition(binary.getLeft()) + " AND " + convertCondition(binary.getRight());
                default:
                    break;
            }
        } else if (condition instanceof UnaryExpr) {
            UnaryExpr unary = (UnaryExpr) condition;
            if (unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
                return "!(" + convertCondition(unary.getExpression()) + ")";
            }
        }

        // Fallback: try to convert the expression directly
        return patternMatcher.convertExpression(condition);
    }

    private String convertBinaryOpWithAnd(MethodCallExpr opMethod) {
        if (opMethod.getArguments().size() < 2)
            return "";
        String left = convertConditionWithTrackedVariables(opMethod.getArgument(0));
        String right = convertConditionWithTrackedVariables(opMethod.getArgument(1));
        return left + " AND " + right;
    }

    private String convertBinaryOpWithOr(MethodCallExpr opMethod) {
        if (opMethod.getArguments().size() < 2)
            return "";
        String left = convertConditionWithTrackedVariables(opMethod.getArgument(0));
        String right = convertConditionWithTrackedVariables(opMethod.getArgument(1));
        return left + " OR " + right;
    }

    /**
     * Convert a condition expression, expanding tracked variables if applicable.
     */
    private String convertConditionWithTrackedVariables(Expression expr) {
        // Check if this is a simple variable reference that's tracked
        if (expr instanceof NameExpr) {
            String varName = ((NameExpr) expr).getNameAsString();
            if (patternMatcher.variableMethodCalls.containsKey(varName)) {
                // Expand the tracked variable
                String expanded = patternMatcher.variableMethodCalls.get(varName);
                patternMatcher.variableMethodCalls.remove(varName);
                return expanded;
            }
        }

        // Handle method calls that may contain tracked variables as arguments
        if (expr instanceof MethodCallExpr) {
            MethodCallExpr methodCall = (MethodCallExpr) expr;
            String methodName = methodCall.getNameAsString();

            // For FIELD - expand tracked variables in arguments and rebuild the call
            if (methodName.equals("FIELD")) {
                StringBuilder argsBuilder = new StringBuilder();
                for (int i = 0; i < methodCall.getArguments().size(); i++) {
                    if (i > 0)
                        argsBuilder.append(", ");
                    Expression arg = methodCall.getArgument(i);
                    // Recursively expand tracked variables in arguments
                    String expandedArg = expandTrackedVariable(arg);
                    argsBuilder.append(expandedArg);
                }
                return methodName + "(" + argsBuilder.toString() + ")";
            }

            // For get() and fGet() - convert to JBC array access syntax with tracked
            // variable expansion
            if (methodName.equals("get") || methodName.equals("fGet")) {
                int argCount = methodCall.getArguments().size();
                if (argCount < 2) {
                    return patternMatcher.convertMethodCall(methodCall);
                }

                // Expand tracked variables in arguments
                Expression arrayExpr = methodCall.getArgument(0);
                String arrayStr = expandTrackedVariable(arrayExpr);

                // Check if this is multi-value array access (4 arguments: array, mv, sv,
                // default)
                if (argCount >= 3) {
                    Expression arg1Expr = methodCall.getArgument(1);
                    boolean isComponentRef = arg1Expr.toString().startsWith("component_") ||
                            arg1Expr instanceof FieldAccessExpr ||
                            arg1Expr instanceof MethodCallExpr;

                    if (!isComponentRef &&
                            (arg1Expr instanceof IntegerLiteralExpr ||
                                    arg1Expr instanceof LongLiteralExpr ||
                                    arg1Expr instanceof NameExpr)) {
                        // Multi-value access: get(array, mv, sv, default) → array<mv,sv>
                        String mv = expandTrackedVariable(methodCall.getArgument(1));
                        String sv = expandTrackedVariable(methodCall.getArgument(2));

                        StringBuilder fields = new StringBuilder();
                        if (!mv.equals("0")) {
                            fields.append(mv);
                        }
                        if (!sv.equals("0")) {
                            if (fields.length() > 0)
                                fields.append(",");
                            fields.append(sv);
                        }

                        if (fields.length() > 0) {
                            return arrayStr + "<" + fields.toString() + ">";
                        } else {
                            return arrayStr;
                        }
                    } else if (isComponentRef && argCount >= 4) {
                        // Field reference with multi-value index: get(array, field, mv, default) →
                        // array<field,mv>
                        String field = expandTrackedVariable(methodCall.getArgument(1));
                        String mv = expandTrackedVariable(methodCall.getArgument(2));

                        StringBuilder fields = new StringBuilder();
                        if (!field.equals("0")) {
                            fields.append(field);
                        }
                        if (!mv.equals("0")) {
                            if (fields.length() > 0)
                                fields.append(",");
                            fields.append(mv);
                        }

                        if (fields.length() > 0) {
                            return arrayStr + "<" + fields.toString() + ">";
                        } else {
                            return arrayStr;
                        }
                    }
                }

                // Standard field access: get(array, field) → array<field>
                String fieldName = expandTrackedVariable(methodCall.getArgument(1));
                return arrayStr + "<" + fieldName + ">";
            }

            // For comparison ops - recursively expand tracked variables in arguments
            if (methodName.equals("op_equal") || methodName.equals("op_ne") || methodName.equals("op_gt") ||
                    methodName.equals("op_lt") || methodName.equals("op_ge") || methodName.equals("op_le") ||
                    methodName.equals("op_match")) {
                String jbcOp = methodName.substring(3).toUpperCase().replace("EQUAL", "EQ");
                String left = expandTrackedVariable(methodCall.getArgument(0));
                String right = expandTrackedVariable(methodCall.getArgument(1));
                return left + " " + jbcOp + " " + right;
            }
        }

        // Fallback to normal condition conversion
        return convertCondition(expr);
    }

    /**
     * Expand a tracked variable in an expression argument.
     */
    private String expandTrackedVariable(Expression arg) {
        // Check if this is a tracked variable
        if (arg instanceof NameExpr) {
            String varName = ((NameExpr) arg).getNameAsString();
            if (patternMatcher.variableMethodCalls.containsKey(varName)) {
                String expanded = patternMatcher.variableMethodCalls.get(varName);
                patternMatcher.variableMethodCalls.remove(varName);
                return expanded;
            }
        } else if (arg instanceof MethodCallExpr) {
            // Recursively process method calls to expand tracked variables
            MethodCallExpr methodCall = (MethodCallExpr) arg;
            String methodName = methodCall.getNameAsString();

            // For get() and fGet() - convert to JBC array access syntax
            if (methodName.equals("get") || methodName.equals("fGet")) {
                int argCount = methodCall.getArguments().size();
                if (argCount < 2) {
                    return patternMatcher.convertExpression(arg);
                }

                // Expand tracked variables in arguments
                Expression arrayExpr = methodCall.getArgument(0);
                String arrayStr = expandTrackedVariable(arrayExpr);

                // Check if this is multi-value array access (4 arguments: array, mv, sv,
                // default)
                if (argCount >= 3) {
                    Expression arg1Expr = methodCall.getArgument(1);
                    boolean isComponentRef = arg1Expr.toString().startsWith("component_") ||
                            arg1Expr instanceof FieldAccessExpr ||
                            arg1Expr instanceof MethodCallExpr;

                    if (!isComponentRef &&
                            (arg1Expr instanceof IntegerLiteralExpr ||
                                    arg1Expr instanceof LongLiteralExpr ||
                                    arg1Expr instanceof NameExpr)) {
                        // Multi-value access: get(array, mv, sv, default) → array<mv,sv>
                        String mv = expandTrackedVariable(methodCall.getArgument(1));
                        String sv = expandTrackedVariable(methodCall.getArgument(2));

                        StringBuilder fields = new StringBuilder();
                        if (!mv.equals("0")) {
                            fields.append(mv);
                        }
                        if (!sv.equals("0")) {
                            if (fields.length() > 0)
                                fields.append(",");
                            fields.append(sv);
                        }

                        if (fields.length() > 0) {
                            return arrayStr + "<" + fields.toString() + ">";
                        } else {
                            return arrayStr;
                        }
                    } else if (isComponentRef && argCount >= 4) {
                        // Field reference with multi-value index: get(array, field, mv, default) →
                        // array<field,mv>
                        String field = expandTrackedVariable(methodCall.getArgument(1));
                        String mv = expandTrackedVariable(methodCall.getArgument(2));

                        StringBuilder fields = new StringBuilder();
                        if (!field.equals("0")) {
                            fields.append(field);
                        }
                        if (!mv.equals("0")) {
                            if (fields.length() > 0)
                                fields.append(",");
                            fields.append(mv);
                        }

                        if (fields.length() > 0) {
                            return arrayStr + "<" + fields.toString() + ">";
                        } else {
                            return arrayStr;
                        }
                    }
                }

                // Standard field access: get(array, field) → array<field>
                String fieldName = expandTrackedVariable(methodCall.getArgument(1));
                return arrayStr + "<" + fieldName + ">";
            }

            // For FIELD - expand tracked variables in arguments
            if (methodName.equals("FIELD")) {
                StringBuilder argsBuilder = new StringBuilder();
                for (int i = 0; i < methodCall.getArguments().size(); i++) {
                    if (i > 0)
                        argsBuilder.append(", ");
                    Expression innerArg = methodCall.getArgument(i);
                    String expandedArg = expandTrackedVariable(innerArg);
                    argsBuilder.append(expandedArg);
                }
                return methodName + "(" + argsBuilder.toString() + ")";
            }
        }
        // Normal conversion
        return patternMatcher.convertExpression(arg);
    }

    private String convertAssignment(AssignExpr assignExpr) {
        // System.out.println("convertAssignment :" + assignExpr.toString());

        Expression target = assignExpr.getTarget();
        Expression value = assignExpr.getValue();

        String targetStr = convertExpression(target);
        String valueStr = convertExpression(value);

        // Skip TAFJ infrastructure assignments
        if (targetStr.contains("Sys.PostGlobus") ||
                targetStr.equals("this._Sys_PostGlobus")) {
            return "";
        } else if (targetStr.contains("Sys.ReturnTo") ||
                targetStr.equals("this._Sys_ReturnTo")) {
            return "";
        } else {
            return targetStr + " = " + valueStr;
        }
    }

    private String convertExpression(Expression expr) {
        if (expr instanceof NameExpr) {
            String name = ((NameExpr) expr).getNameAsString();
            return patternMatcher.convertVariableName(name);
        } else if (expr instanceof MethodCallExpr) {
            return patternMatcher.convertMethodCall((MethodCallExpr) expr);
        } else if (expr instanceof StringLiteralExpr) {
            return "\"" + ((StringLiteralExpr) expr).getValue() + "\"";
        } else if (expr instanceof IntegerLiteralExpr) {
            return ((IntegerLiteralExpr) expr).getValue();
        } else if (expr instanceof FieldAccessExpr) {
            // Delegate to PatternMatcher for proper FieldAccessExpr handling (this._VAR,
            // etc.)
            return patternMatcher.convertExpression(expr);
        } else if (expr instanceof BooleanLiteralExpr) {
            return ((BooleanLiteralExpr) expr).getValue() ? "1" : "0";
        } else if (expr instanceof UnaryExpr) {
            // Handle negation: !expr
            UnaryExpr unary = (UnaryExpr) expr;
            String inner = convertExpression(unary.getExpression());
            if (unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
                return "!(" + inner + ")";
            }
            return expr.toString();
        } else if (expr instanceof BinaryExpr) {
            // Handle || (OR) and && (AND) operators
            BinaryExpr binary = (BinaryExpr) expr;
            String left = convertExpression(binary.getLeft());
            String right = convertExpression(binary.getRight());
            switch (binary.getOperator()) {
                case BINARY_OR:
                    return left + " OR " + right;
                case BINARY_AND:
                    return left + " AND " + right;
                case EQUALS:
                    return left + " EQ " + right;
                case NOT_EQUALS:
                    return left + " NE " + right;
                default:
                    return expr.toString();
            }
        } else if (expr instanceof ArrayCreationExpr) {
            // Handle new Object[] { ... } structures by converting elements
            ArrayCreationExpr array = (ArrayCreationExpr) expr;
            StringBuilder sb = new StringBuilder();

            array.getInitializer().ifPresent(initializer -> {
                for (Expression element : initializer.getValues()) {
                    String convertedElement = convertExpression(element);
                    if (sb.length() > 0 && !convertedElement.isEmpty()) {
                        sb.append(", ");
                    }
                    sb.append(convertedElement);
                }
            });

            return sb.toString();
        } else {
            return expr.toString();
        }
    }

    private void appendIndented(String text) {
        if (text.startsWith("\n"))
            jbcCode.append("\n");
        for (int i = 0; i < indentLevel; i++) {
            jbcCode.append("    ");
        }
        if (text.startsWith("\n"))
            jbcCode.append(text.substring(1));
        else
            jbcCode.append(text);
    }
}