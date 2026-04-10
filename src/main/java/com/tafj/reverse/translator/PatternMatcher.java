package com.tafj.reverse.translator;

import com.github.javaparser.ast.expr.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.*;

public class PatternMatcher {

    // Track array contents for expansion in get() calls
    private Map<String, List<String>> arrayContents = null;

    /**
     * Set the array contents map for variable expansion
     */
    public void setArrayContents(Map<String, List<String>> arrayContents) {
        this.arrayContents = arrayContents;
    }

    /**
     * Convert Java variable name to JBC format _R_ACCT → R.ACCT
     */
    public String convertVariableName(String javaVar) {
        if (javaVar == null)
            return "";
        // Remove this.
        String name = javaVar.startsWith("this._") ? javaVar.substring(6) : javaVar;

        // Remove underscore prefix
        name = name.startsWith("_") ? name.substring(1) : name;

        // Remove trailing underscore
        name = name.endsWith("_") ? name.substring(0, name.length() - 1) : name;

        // Replace underscores with dots
        name = name.replace("_", ".");

        return name;
    }

    /**
     * Extract component name from Java import
     */
    public String extractComponentName(String importName) {
        if (importName.startsWith("com.temenos.t24.component_")) {
            // component_ATMFRM_Foundation_18_cl → ATMFRM.Foundation
            String name = importName.replaceAll("com.temenos.t24.component_", "").replaceAll("_\\d+_cl$", "");

            // Insert dots between word segments
            name = name.replaceAll("_", ".");

            return name;
        }
        return null;
    }

    /**
     * Convert set() call to JBC assignment
     * Handles two patterns:
     * - set(_VAR, value) → VAR = value
     * - set(_VAR, field1, field2, ..., value) → VAR<field1,field2,...> = value
     */
    public String convertSetCall(MethodCallExpr setCall) {
        return convertSetCall(setCall, null);
    }

    /**
     * Convert set() call to JBC assignment with tracked variable expansion
     */
    public String convertSetCall(MethodCallExpr setCall, Map<String, String> variableMethodCalls) {
        int argCount = setCall.getArguments().size();
        if (argCount < 2)
            return null;

        Expression target = setCall.getArgument(0);
        String targetStr = convertExpression(target);

        // Check if this is a multi-value array assignment (3+ arguments)
        if (argCount > 2) {
            // Last argument is the value, everything in between are field indices
            Expression value = setCall.getArgument(argCount - 1);
            String valueStr = convertExpression(value);

            // Build field list from arguments 1 to n-1
            StringBuilder fields = new StringBuilder();
            for (int i = 1; i < argCount - 1; i++) {
                Expression fieldExpr = setCall.getArgument(i);
                String fieldStr;

                // Check if field expression is a tracked variable from method call
                if (fieldExpr instanceof NameExpr && variableMethodCalls != null) {
                    String varName = ((NameExpr) fieldExpr).getNameAsString();
                    if (variableMethodCalls.containsKey(varName)) {
                        fieldStr = variableMethodCalls.get(varName);
                    } else {
                        fieldStr = convertFieldExpression(fieldExpr);
                    }
                } else {
                    fieldStr = convertFieldExpression(fieldExpr);
                }

                if(!fieldStr.equals("0")){
                    if (fields.length() > 0) fields.append(",");
                    fields.append(fieldStr);
                }
            }

            return targetStr + "<" + fields.toString() + "> = " + valueStr;
        }

        // Simple assignment: set(var, value)
        Expression value = setCall.getArgument(1);
        String valueStr = convertExpression(value);

        return targetStr + " = " + valueStr;
    }

    /**
     * Convert get() call to JBC field access
     * Handles two patterns:
     * - get(_VAR, getCOMPONENT()._FIELD, 0, 0) → VAR<COMPONENT.FIELD>
     * - get(_VAR, component_XX_cl._Field, 0, 0) → VAR<COMPONENT.Field>
     * - get(_VAR, mv, sv, default) → VAR<mv,sv>  (multi-value array access)
     * - get(_VAR, trackedArray) → VAR<field1,field2,...> (array expansion)
     */
    public String convertGetCall(MethodCallExpr getCall) {
        int argCount = getCall.getArguments().size();

        if (argCount < 2)
            return null;

        Expression varExpr = getCall.getArgument(0);
        String varName = convertExpression(varExpr);

        // Check if second argument is a tracked array variable
        Expression arg1 = getCall.getArgument(1);
        if (arg1 instanceof NameExpr && arrayContents != null) {
            String varName2 = ((NameExpr) arg1).getNameAsString();
            if (arrayContents.containsKey(varName2)) {
                List<String> fields = arrayContents.get(varName2);
                StringBuilder fieldList = new StringBuilder();
                for (int i = 0; i < fields.size(); i++) {
                    String field = fields.get(i);
                    if (!field.equals("0") && !field.isEmpty()) {
                        if (fieldList.length() > 0) fieldList.append(",");
                        fieldList.append(field);
                    }
                }
                if (fieldList.length() > 0) {
                    return varName + "<" + fieldList.toString() + ">";
                } else {
                    return varName;
                }
            }
        }

        // Check if this is multi-value array access (4 arguments: array, mv, sv, default)
        // But only if argument 2 is a simple literal/number (not a component field reference)
        if (argCount >= 3) {
            Expression arg1Expr = getCall.getArgument(1);

            // Check if arg1 is a simple literal or number (multi-value access)
            // Component field references should NOT be treated as multi-value indices
            boolean isComponentRef = arg1Expr.toString().startsWith("component_") ||
                                     arg1Expr instanceof FieldAccessExpr ||
                                     arg1Expr instanceof MethodCallExpr;

            if (!isComponentRef &&
                (arg1Expr instanceof IntegerLiteralExpr ||
                 arg1Expr instanceof LongLiteralExpr ||
                 arg1Expr instanceof NameExpr)) {
                // This is multi-value access: get(array, mv, sv, default)
                // Convert to: array<mv,sv> (skip 0 values)
                Expression mvExpr = arg1Expr;
                Expression svExpr = getCall.getArgument(2);

                String mv = convertExpression(mvExpr);
                String sv = convertExpression(svExpr);

                // Build field list, skipping 0 values
                StringBuilder fields = new StringBuilder();
                if (!mv.equals("0")) {
                    fields.append(mv);
                }
                if (!sv.equals("0")) {
                    if (fields.length() > 0) fields.append(",");
                    fields.append(sv);
                }

                if (fields.length() > 0) {
                    return varName + "<" + fields.toString() + ">";
                } else {
                    return varName;
                }
            }
            // Otherwise, fall through to field access handling (component field reference)
        }

        // Standard field access: get(array, field)
        Expression fieldExpr = getCall.getArgument(1);
        String fieldName = convertFieldExpression(fieldExpr);

        return varName + "<" + fieldName + ">";
    }

    /**
     * Convert get() call to JBC field access with explicit array tracking
     */
    public String convertGetCall(MethodCallExpr getCall, Map<String, List<String>> arrayContents) {
        Map<String, List<String>> saved = this.arrayContents;
        this.arrayContents = arrayContents;
        try {
            return convertGetCall(getCall);
        } finally {
            this.arrayContents = saved;
        }
    }

    /**
     * Convert field expression to JBC field name
     * Handles patterns like:
     * - getATMFRM_Foundation()._AtmParameter_AtmParaAmtFmt → ATMFRM.Foundation.AtmParameter.AtmParaAmtFmt
     * - component_ATMFRM_Foundation_18_cl._AtmParameter_AtmParaAmtFmt → ATMFRM.Foundation.AtmParameter.AtmParaAmtFmt
     */
    public String convertFieldExpression(Expression fieldExpr) {
        if (fieldExpr == null)
            return "";

        String fieldStr = fieldExpr.toString();

        // Handle component_... pattern
        if (fieldStr.startsWith("component_")) {
            // component_ATMFRM_Foundation_18_cl._AtmParameter_AtmParaAmtFmt
            String[] parts = fieldStr.split("\\.");
            StringBuilder sb = new StringBuilder();

            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].trim();

                if (part.startsWith("component_")) {
                    // Remove "component_" prefix and "_XX_cl" suffix
                    String componentName = part.substring(10);
                    componentName = componentName.replaceAll("_\\d+_cl$", "");
                    // Convert ATMFRM_Foundation to ATMFRM.Foundation
                    componentName = convertUnderscoreToDots(componentName);
                    sb.append(componentName);
                } else if (part.startsWith("_")) {
                    // Convert _AtmParameter_AtmParaAmtFmt to AtmParameter.AtmParaAmtFmt
                    if (sb.length() > 0) {
                        sb.append(".");
                    }
                    String fieldName = part.substring(1); // Remove leading underscore
                    sb.append(convertUnderscoreToDots(fieldName));
                } else {
                    // Regular field name
                    if (sb.length() > 0) {
                        sb.append(".");
                    }
                    sb.append(convertUnderscoreToDots(part));
                }
            }
            return sb.toString();
        }

        // Handle getComponent()._Field pattern (MethodCallExpr)
        if (fieldExpr instanceof MethodCallExpr) {
            MethodCallExpr methodCall = (MethodCallExpr) fieldExpr;
            String methodName = methodCall.getNameAsString();

            if (methodName.startsWith("get") && methodCall.getArguments().isEmpty()) {
                // Extract component from method name
                String componentName = methodName.substring(3);
                componentName = convertUnderscoreToDots(componentName);

                // Check if there's a field being accessed on the result
                if (methodCall.getScope().isPresent()) {
                    String scopeStr = methodCall.getScope().get().toString();
                    if (scopeStr.startsWith("_")) {
                        String fieldName = scopeStr.substring(1);
                        return convertUnderscoreToDots(fieldName) + "." + componentName;
                    }
                }

                return componentName;
            }
        }

        // Fallback: use convertExpression
        return convertExpression(fieldExpr);
    }

    /**
     * Convert variable or method call expression to JBC format
     * Handles chained method calls like: this.getEB_SystemTables().getRIntercoParameter()
     */
    public String convertVariableOrMethodCall(Expression expr) {
        if (expr == null)
            return "";

        if (expr instanceof NameExpr) {
            return convertVariableName(((NameExpr) expr).getNameAsString());
        } else if (expr instanceof MethodCallExpr) {
            return convertMethodCallChain((MethodCallExpr) expr);
        } else {
            return convertExpression(expr);
        }
    }

    /**
     * Convert a method call chain to JBC format
     * Handles: this.getEB_SystemTables().getRIntercoParameter() → EB.SystemTables.getRIntercoParameter()
     */
    private String convertMethodCallChain(MethodCallExpr methodCall) {
        StringBuilder result = new StringBuilder();
        
        // Build the scope chain
        if (methodCall.getScope().isPresent()) {
            Expression scope = methodCall.getScope().get();
            String scopeStr = scope.toString();
            
            // Handle "this." prefix - remove it
            if (scopeStr.startsWith("this.")) {
                scopeStr = scopeStr.substring(5);
            } else if (scopeStr.equals("this")) {
                scopeStr = "";
            }
            
            // If scope is also a method call, recursively convert it
            if (scope instanceof MethodCallExpr) {
                result.append(convertMethodCallChain((MethodCallExpr) scope));
            } else if (!scopeStr.isEmpty()) {
                // Convert variable name
                result.append(convertVariableName(scopeStr));
            }
            
            // Add dot separator if we have a scope
            if (result.length() > 0) {
                result.append(".");
            }
        }
        
        // Add the method name (without "get" prefix if present)
        String methodName = methodCall.getNameAsString();
        if (methodName.startsWith("get") && methodName.length() > 3) {
            // Keep the method name but convert underscores to dots
            result.append(methodName.substring(3));
        } else {
            result.append(methodName);
        }
        
        // Convert underscores to dots
        return result.toString().replace("_", ".");
    }

    /**
     * Convert underscore-separated name to dot-separated
     * e.g., ATMFRM_Foundation → ATMFRM.Foundation
     */
    private String convertUnderscoreToDots(String name) {
        return name.replace("_", ".");
    }

    /**
     * Convert FOR loop components to JBC syntax
     */
    public String convertForLoop(Expression init, Expression compare, Expression update) {
        StringBuilder forLine = new StringBuilder("FOR ");

        // Parse initialization (e.g., int i = 1)
        if (init instanceof VariableDeclarationExpr) {
            VariableDeclarationExpr varDecl = (VariableDeclarationExpr) init;
            String varName = varDecl.getVariables().get(0).getNameAsString();
            Expression initValue = varDecl.getVariables().get(0).getInitializer().orElse(null);

            forLine.append(convertVariableName(varName));
            forLine.append(" = ");
            forLine.append(convertExpression(initValue));
        }

        // Parse comparison (e.g., i <= 10)
        if (compare instanceof BinaryExpr) {
            BinaryExpr binExpr = (BinaryExpr) compare;

            forLine.append(" TO ");
            forLine.append(convertExpression(binExpr.getRight()));
        }

        // Parse update (e.g., i++)
        if (update != null) {
            // Check for step value
            if (update instanceof UnaryExpr) {
                UnaryExpr unary = (UnaryExpr) update;
                if (unary.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT) {
                    // Default step of 1
                }
            }
        }

        return forLine.toString();
    }

    /**
     * Convert binary operation to JBC comparison
     */
    public String convertBinaryOp(MethodCallExpr opMethod, String jbcOperator) {
        if (opMethod.getArguments().size() < 2)
            return "";

        Expression left = opMethod.getArgument(0);
        Expression right = opMethod.getArgument(1);

        return convertExpression(left) + " " + jbcOperator + " " + convertExpression(right);
    }

    /**
     * Convert method call to JBC equivalent
     */
    public String convertMethodCall(MethodCallExpr methodCall) {
        String methodName = methodCall.getNameAsString();

        // Ignore scope for TAFJ runtime methods (this.FMT, this.op_cat, etc.)
        // The scope "this" doesn't affect the translation

        switch (methodName) {
        case "ABS":
            return "ABS(" + convertArguments(methodCall) + ")";
        case "FMT":
            return "FMT(" + convertArguments(methodCall) + ")";
        case "FIELD":
            return "FIELD(" + convertArguments(methodCall) + ")";
        case "INDEX":
            return "INDEX(" + convertArguments(methodCall) + ")";
        case "LEN":
            return "LEN(" + convertArguments(methodCall) + ")";
        case "LOCATE":
            // LOCATE(value, array, start, increment, decrement, padChar, padDir, variable, mode)
            // JBC syntax: LOCATE value IN array<start,increment> SETTING variable THEN
            if (methodCall.getArguments().size() >= 8) {
                Expression valueExpr = methodCall.getArgument(0);
                Expression arrayExpr = methodCall.getArgument(1);
                Expression startExpr = methodCall.getArgument(2);
                Expression incExpr = methodCall.getArgument(3);
                Expression varExpr = methodCall.getArgument(7);
                
                String value = convertExpression(valueExpr);
                String array = convertExpression(arrayExpr);
                String start = convertExpression(startExpr);
                String inc = convertExpression(incExpr);
                String variable = convertExpression(varExpr);
                
                return "LOCATE " + value + " IN " + array + "<" + start + "," + inc + "> SETTING " + variable + " THEN\n";
            }
            return "LOCATE(" + convertArguments(methodCall) + ")";
        case "MINIMUM":
            return "MINIMUM(" + convertArguments(methodCall) + ")";
        case "MAXIMUM":
            return "MAXIMUM(" + convertArguments(methodCall) + ")";
        case "DCOUNT":
            // DCOUNT has 2 arguments: string and delimiter
            // Just convert arguments normally, jAtVariable.VM will be converted to @VM
            return "DCOUNT(" + convertArguments(methodCall) + ")";
        case "DEL":
            // DEL(array, mv, sv, pad) → DEL array<mv,sv>
            // TAFJ call: this.DEL(this._CompanyList, 1, this._CurCompPos, 0)
            if (methodCall.getArguments().size() >= 3) {
                Expression arrayExpr = methodCall.getArgument(0);
                Expression mvExpr = methodCall.getArgument(1);
                Expression svExpr = methodCall.getArgument(2);

                String array = convertExpression(arrayExpr);
                String mv = convertExpression(mvExpr);
                String sv = convertExpression(svExpr);

                StringBuilder fields = new StringBuilder();
                if (!mv.equals("0")) fields.append(mv);
                if (!sv.equals("0")) {
                    if (fields.length() > 0) fields.append(",");
                    fields.append(sv);
                }

                if (fields.length() > 0) {
                    return "DEL " + array + "<" + fields.toString() + ">";
                } else {
                    return "DEL " + array;
                }
            }
            // Fallback: just convert arguments
            return "DEL(" + convertArguments(methodCall) + ")";
        case "NOT":
            // NOT(expr) → NOT(expr)
            if (methodCall.getArguments().size() >= 1) {
                return "NOT(" + convertExpression(methodCall.getArgument(0)) + ")";
            }
            return "NOT";
        case "concat":
            // For .concat() method - need to include the scope (what's being concatenated to)
            // e.g., op_cat(A,B).concat(C) should be A:B:C
            StringBuilder concatResult = new StringBuilder();
            
            // First, get the scope (the object being concatenated to)
            if (methodCall.getScope().isPresent()) {
                Expression scope = methodCall.getScope().get();
                String scopeStr = convertExpression(scope);
                concatResult.append(scopeStr);
            }
            
            // Then add the argument with colon prefix
            if (methodCall.getArguments().size() > 0) {
                concatResult.append(":").append(convertExpression(methodCall.getArgument(0)));
            }
            return concatResult.toString();
        case "get":
            return convertGetCall(methodCall);
        case "set":
            return convertSetCall(methodCall);
        case "op_cat":
            // Concatenate arguments with colon (JBC concatenation) - no spaces
            return convertArguments(methodCall, ":");
        case "valueOf":
            // Character.valueOf(...) or Double.valueOf(...) - just return the argument
            return convertArguments(methodCall);
        case "fGet":
            String args = convertArguments(methodCall);
            int commaIdx = args.indexOf(",");
            if (commaIdx > 0) {
                return args.substring(0, commaIdx).trim() + "[" + args.substring(commaIdx + 1).trim() + "]";
            }
            return args;
        case "op_add":
            return convertArguments(methodCall, "+");
        case "op_equal":
            return convertBinaryOp(methodCall, "EQ");
        case "boolVal":
            // boolVal wraps conditions - just return the inner expression
            if (methodCall.getArguments().size() > 0) {
                return convertExpression(methodCall.getArgument(0));
            }
            return "";
        case "op_sub":
            return convertArguments(methodCall, "-");
        case "op_ne":
            return convertBinaryOp(methodCall, "NE");
        case "op_gt":
            return convertBinaryOp(methodCall, "GT");
        case "op_lt":
            return convertBinaryOp(methodCall, "LT");
        case "op_ge":
            return convertBinaryOp(methodCall, "GE");
        case "op_le":
            return convertBinaryOp(methodCall, "LE");
        case "op_match":
            return convertBinaryOp(methodCall, "MATCHES");
        default:
            // Check for component method calls (e.g., component.getSomething())
            if (methodCall.hasScope() && !methodName.equals("_l") && !methodName.startsWith("lbl_")) {
                Expression scopeExpr = methodCall.getScope().get();
                String scope = scopeExpr.toString();
                // Handle Optional[...] wrapper from JavaParser
                if (scope.startsWith("Optional[")) {
                    scope = scope.substring(9, scope.length() - 1);
                }

                // Skip "this." scope for TAFJ runtime methods - convert arguments only
                if (scope.equals("this")) {
                    // This is a TAFJ runtime method like this.INDEX(), this.ABS(), etc.
                    // Just output the method name with converted arguments
                    
                    // Handle op_ methods with this. prefix
                    if (methodName.equals("op_and")) {
                        String arg0 = convertExpression(methodCall.getArgument(0));
                        String arg1 = convertExpression(methodCall.getArgument(1));
                        return arg0 + " AND " + arg1;
                    }
                    if (methodName.equals("op_or")) {
                        String arg0 = convertExpression(methodCall.getArgument(0));
                        String arg1 = convertExpression(methodCall.getArgument(1));
                        return arg0 + " OR " + arg1;
                    }
                    if (methodName.equals("boolVal")) {
                        return convertExpression(methodCall.getArgument(0));
                    }
                    if (methodName.equals("NOT")) {
                        return "NOT(" + convertExpression(methodCall.getArgument(0)) + ")";
                    }
                    
                    if (methodCall.getArguments().isEmpty() && methodName.startsWith("get")) {
                        // Getter method with no arguments - remove "get" prefix, convert underscores, and add ()
                        return methodName.substring(3).replace("_", ".") + "()";
                    }
                    return methodName + "(" + convertArguments(methodCall) + ")";
                }

                // Convert the scope expression properly (handles chained method calls)
                String convertedScope = convertExpression(scopeExpr);
                
                // Remove trailing () from scope if present (component getter methods)
                String scopeStr = convertedScope;
                if (scopeStr.endsWith("()")) {
                    scopeStr = scopeStr.substring(0, scopeStr.length() - 2);
                }
                
                String finalStr = scopeStr;

                // Add the current method name with parentheses (keep it as a method call)
                String convertedMethodName = methodName.replace("_", ".");
                
                if (methodCall.getArguments().isNonEmpty()) {
                    ArrayList<String> argsList = new ArrayList<>();
                    for (Expression arg : methodCall.getArguments()) {
                        if (arg instanceof CastExpr) {
                            CastExpr castexpr = (CastExpr) arg;
                            argsList.add(convertVariableName(castexpr.getExpression().toString()));
                        }
                    }
                    if (!argsList.isEmpty()) {
                        finalStr += "." + convertedMethodName + "(" + String.join(",", argsList) + ")";
                    } else {
                        // For non-CastExpr arguments, use convertArguments
                        finalStr += "." + convertedMethodName + "(" + convertArguments(methodCall) + ")";
                    }
                } else {
                    // No arguments - add empty parentheses
                    finalStr += "." + convertedMethodName + "()";
                }

                return finalStr;
            } else if (methodName.startsWith("lbl_")) {
                return "GOSUB " + methodName.substring(4).replace("_", ".") + "\n";
            } else if (methodName.startsWith("get")) {
                String componentName = extractComponentName(methodName);
                if (componentName != null) {
                    return componentName + "." + methodCall.getArguments().get(0).toString();
                }
            }

            return null;
        }

    }

    /**
     * Convert method arguments to JBC format
     */
    private String convertArguments(MethodCallExpr methodCall) {
        return convertArguments(methodCall, ",");
    }

    /**
     * Convert method arguments to JBC format with custom separator
     */
    private String convertArguments(MethodCallExpr methodCall, String separator) {
        StringBuilder args = new StringBuilder();
        int[] index = { 0 };
        methodCall.getArguments().forEach(arg -> {
            if (index[0] > 0)
                args.append(separator);
            args.append(convertExpression(arg));
            index[0]++;
        });
        return args.toString();
    }

    /**
     * Convert expression to string
     */
    public String convertExpression(Expression expr) {
        if (expr == null)
            return "";

        if (expr instanceof NameExpr) {
            return convertVariableName(((NameExpr) expr).getNameAsString());
        } else if (expr instanceof StringLiteralExpr) {
            return "'" + ((StringLiteralExpr) expr).getValue() + "'";
        } else if (expr instanceof IntegerLiteralExpr) {
            // Remove 'L' suffix from long literals
            return ((IntegerLiteralExpr) expr).getValue().replace("L", "");
        } else if (expr instanceof LongLiteralExpr) {
            // Handle LongLiteralExpr separately
            return ((LongLiteralExpr) expr).asNumber().toString();
        } else if (expr instanceof MethodCallExpr) {
            String converted = convertMethodCall((MethodCallExpr) expr);
            return converted != null ? converted : expr.toString();
        } else if (expr instanceof CastExpr) {
            // Handle cast expressions - just return the inner expression
            // e.g., (Object) this._AVAIL_BAL -> AVAIL.BAL
            CastExpr castExpr = (CastExpr) expr;
            return convertExpression(castExpr.getExpression());
        } else if (expr instanceof FieldAccessExpr) {
            // Handle jAtVariable.VM -> @VM
            FieldAccessExpr fieldAccess = (FieldAccessExpr) expr;
            String scope = fieldAccess.getScope().toString();
            String field = fieldAccess.getNameAsString();

            if (scope.equals("jAtVariable") && field.equals("VM")) {
                return "@VM";
            }
            else if (scope.equals("jAtVariable") && field.equals("FM")) {
                return "@FM";
            }
            else if (scope.equals("jAtVariable") && field.equals("SM")) {
                return "@SM";
            }
            else if (scope.equals("jAtVariable") && field.equals("IM")) {
                return "@IM";
            }
            // Handle this._VAR pattern -> VAR
            if (scope.equals("this") && field.startsWith("_")) {
                return convertVariableName(field);
            }
            // Handle other field access
            return scope + "." + field;
        } else {
            String strExpr = expr.toString();
            if (strExpr.startsWith("this._")) {
                // Remove "this." prefix and use convertVariableName for proper conversion
                return convertVariableName(strExpr.substring(5));
            } else if (strExpr.startsWith("this.get")) {
                return strExpr.substring(8).replace("_", ".").replace("().", ".");
            } else if (strExpr.startsWith("component_")) {
                String strExprSplit[] = strExpr.split("\\.");
                StringBuilder sb = new StringBuilder();
                for (String str : strExprSplit) {

                    if (str.startsWith("component_")) {
                        sb.append(str.substring(10).replaceAll("_\\d+_cl", "").replace("_", "."));
                    } else {
                        if (!sb.isEmpty()) {
                            sb.append(".");
                            if (str.startsWith("_"))
                                sb.append(str.substring(1).replace("_", "."));
                            else
                                sb.append(str.replace("_", "."));
                        }
                    }
                }
                return sb.toString();
            } else {
                return strExpr;
            }
        }
    }

    /**
     * Fallback pattern-based translation when AST fails
     */
    public String fallbackTranslate(String javaSource) {
        String jbc = javaSource;
        // Variable names
        Matcher matcher = Pattern.compile("_([A-Z_]+)").matcher(jbc);
        jbc = matcher.replaceAll(result -> replaceVariableMatch(result));

        // GOSUB
        jbc = jbc.replaceAll("_Sys_PostGlobus = lbl_([A-Z_]+)\\(\\)", "GOSUB $1");

        // RETURN
        jbc = jbc.replaceAll("_Sys_ReturnTo = LABEL_NULL;.*?return LABEL_NULL", "RETURN");

        // set() calls
        jbc = jbc.replaceAll("set\\(_([A-Z_]+),\\s*([^\"]+)\\)", "$1 = $2");

        // Labels
        jbc = jbc.replaceAll("protected int lbl_([A-Z_]+)\\(\\)", "$1:");

        return jbc;
    }

    private String replaceVariableMatch(MatchResult m) {
        String varName = m.group(1);
        return varName.replace("_", ".");
    }
}