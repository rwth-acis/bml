package i5.bml.parser.walker;

import generatedParser.BMLBaseListener;
import generatedParser.BMLParser;
import i5.bml.parser.symbols.BlockScope;
import i5.bml.parser.types.*;
import org.antlr.symtab.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.apache.commons.validator.routines.UrlValidator;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import static i5.bml.parser.errors.ParserError.*;

public class DiagnosticsCollector extends BMLBaseListener {

    private final String fileName;

    private final List<Diagnostic> collectedDiagnostics = new ArrayList<>();

    private Scope currentScope;

    public DiagnosticsCollector(String fileName) {
        this.fileName = fileName;
    }

    public void addDiagnostic(String message, ParserRuleContext ctx) {
        Position start;
        Position end;
        start = new Position(ctx.start.getLine(), ctx.start.getCharPositionInLine());
        end = new Position(ctx.stop.getLine(), ctx.start.getCharPositionInLine() + ctx.getText().length());
        var range = new Range(start, end);
        collectedDiagnostics.add(new Diagnostic(range, message, DiagnosticSeverity.Error, "bml"));
    }

    public void addDiagnostic(String message, Token token) {
        var start = new Position(token.getLine(), token.getCharPositionInLine() + 1);
        var end = new Position(token.getLine(), token.getCharPositionInLine() + token.getText().length());
        var range = new Range(start, end);
        collectedDiagnostics.add(new Diagnostic(range, message, DiagnosticSeverity.Error, "bml"));
    }

    public List<Diagnostic> getCollectedDiagnostics() {
        return collectedDiagnostics;
    }

    /*
     * Symbol Table & Scope Generator
     */
    @Override
    public void enterBotDeclaration(BMLParser.BotDeclarationContext ctx) {
        GlobalScope g = new GlobalScope(null);
        ctx.scope = g;
        pushScope(g);
    }

    @Override
    public void exitBotDeclaration(BMLParser.BotDeclarationContext ctx) {
        popScope();
    }

    @Override
    public void enterBotHead(BMLParser.BotHeadContext ctx) {
        if (ctx.name != null) {
            checkAlreadyDefinedElseDefine(ctx.name);
        }
    }

    @Override
    public void enterComponent(BMLParser.ComponentContext ctx) {
        checkAlreadyDefinedElseDefine(ctx.name);
    }

    @Override
    public void enterFunctionDefinition(BMLParser.FunctionDefinitionContext ctx) {
        checkAlreadyDefinedElseDefine(ctx.head.functionName);

        // We still want to create a scope, even if name already exists
        Scope f = new FunctionSymbol(ctx.head.functionName.getText());
        f.setEnclosingScope(currentScope);
        ctx.scope = f;
        pushScope(f);
    }

    @Override
    public void enterFunctionHead(BMLParser.FunctionHeadContext ctx) {
        checkAlreadyDefinedElseDefine(ctx.parameterName);
    }

    @Override
    public void enterBlock(BMLParser.BlockContext ctx) {
        Scope s = new BlockScope(currentScope);
        ctx.scope = s;
        pushScope(s);
    }

    @Override
    public void exitBlock(BMLParser.BlockContext ctx) {
        popScope();
    }

    private void pushScope(Scope s) {
        currentScope = s;
    }

    private void popScope() {
        currentScope = currentScope.getEnclosingScope();
    }

    private void checkAlreadyDefinedElseDefine(Token token) {
        var name = token.getText();

        // Check: name is already defined in scope
        if (currentScope.resolve(name) != null) {
            addDiagnostic(ALREADY_DEFINED.format(name), token);
        } else {
            currentScope.define(new VariableSymbol(name));
        }
    }

    /*
     * Type Synthesizer
     */
    @Override
    public void exitComponent(BMLParser.ComponentContext ctx) {
        var typeName = ctx.typeName.getText();
        var componentName = ctx.name.getText();

        // 0. Check whether type is "allowed"
        if (!TypeRegistry.isTypeBuiltin(typeName)) {
            addDiagnostic(UNKNOWN_TYPE.format(typeName), ctx.typeName);
            return;
        }

        // 1. Create instance from blueprint
        AbstractBMLType resolvedType;
        if (TypeRegistry.isTypeComplex(typeName)) {
            resolvedType = (AbstractBMLType) TypeRegistry.resolveComplexType(typeName);
        } else {
            resolvedType = (AbstractBMLType) TypeRegistry.resolveType(typeName);
        }

        // 2. Allow resolvedType to add its desired parameters to optional and required parameter lists
        //noinspection ConstantConditions -> We know that type exists (checked by isTypeBuiltin)
        resolvedType.collectParameters();

        // 2.1 Check parameter types
        resolvedType.checkParameters(this, ctx.params);

        // 2.2 Populate annotated fields with parameters (required vs optional)
        resolvedType.populateParameters(this, ctx.params);

        var registeredType = (AbstractBMLType) TypeRegistry.resolveType(resolvedType.toString());
        if (registeredType == null) {
            // 3. Invoke initializer (e.g., fetch OpenAPI schemas from provided url parameter)
            resolvedType.initializeType();

            // 4. If complex type -> register
            if (TypeRegistry.isTypeComplex(typeName)) {
                TypeRegistry.registerType(resolvedType.toString(), resolvedType);
            }
        } else {
            resolvedType = registeredType;
        }

        // 5. Lastly, we set the type of the corresponding symbol
        var v = currentScope.resolve(componentName);
        ((VariableSymbol) v).setType(resolvedType);
    }

    @Override
    public void exitAssignment(BMLParser.AssignmentContext ctx) {
        // When exiting an assignment, we can assume that the right-hand side type was computed already,
        // since it is an expression
        if (ctx.op.getText().equals("=")) {
            var name = ctx.name.getText();
            // Check: name is already defined in scope
            if (currentScope.resolve(name) != null) {
                addDiagnostic(ALREADY_DEFINED.format(name), ctx.name);
            } else {
                VariableSymbol v = new VariableSymbol(name);
                v.setType(ctx.expression().type);
                currentScope.define(v);
            }
        } else { // Assignment operators with simultaneous arithmetic operation
            // Make sure left-hand side is defined
            var v = currentScope.resolve(ctx.name.getText());
            if (!(v instanceof VariableSymbol)) {
                addDiagnostic(NOT_DEFINED.format(ctx.name.getText()), ctx.name);
            }

            // Type of left-hand side should already be set
            var rightType = ctx.expression().type;
            if (!(rightType instanceof BMLNumber)) {
                addDiagnostic(EXPECTED_BUT_FOUND.format("Number", rightType), ctx.name);
            }
        }
    }

    @Override
    public void exitLiteral(BMLParser.LiteralContext ctx) {
        var terminalNodeType = ((TerminalNode) ctx.getChild(0)).getSymbol().getType();
        ctx.type = switch (terminalNodeType) {
            case BMLParser.IntegerLiteral -> TypeRegistry.resolveType("Number");
            case BMLParser.FloatingPointLiteral -> TypeRegistry.resolveType("Float Number");
            case BMLParser.StringLiteral -> TypeRegistry.resolveType("String");
            case BMLParser.BooleanLiteral -> TypeRegistry.resolveType("Boolean");
            default -> null;
        };

        if (ctx.type == null) {
            addDiagnostic(UNKNOWN_TYPE.format(terminalNodeType), (Token) ctx.getChild(0));
        }
    }

    @Override
    public void exitAtom(BMLParser.AtomContext ctx) {
        if (ctx.literal() != null) {
            ctx.type = ctx.literal().type;
        } else { // Identifier
            var name = ctx.Identifier().getText();
            var r = currentScope.resolve(name);
            if (!(r instanceof VariableSymbol)) {
                addDiagnostic(NOT_DEFINED.format(name), ctx.Identifier().getSymbol());
                // We don't know the type, so we go with Object
                ctx.type = TypeRegistry.resolveType("Object");
            } else {
                ctx.type = ((VariableSymbol) r).getType();
            }
        }
    }

    @Override
    public void exitExpression(BMLParser.ExpressionContext ctx) {
        if (ctx.atom() != null) {
            ctx.type = ctx.atom().type;
        } else if (ctx.op != null) {
            switch (ctx.op.getText()) {
                case "(" -> ctx.type = ctx.expr.type;
                case "." -> {
                    AbstractBMLType prevType = (AbstractBMLType) ctx.expr.type;
                    var currentCtx = ctx.Identifier() != null ? ctx.Identifier() : ctx.functionCall();
                    AbstractBMLType resolvedType = (AbstractBMLType) prevType.resolveAccess(currentCtx);

                    if (resolvedType == null) {
                        if (ctx.Identifier() != null) {
                            addDiagnostic(CANT_RESOLVE_IN.format(currentCtx.getText(), prevType), ctx.Identifier().getSymbol());
                        } else {
                            addDiagnostic(CANT_RESOLVE_IN.format(currentCtx.getText(), prevType),
                                    ctx.functionCall());
                        }

                        ctx.type = TypeRegistry.resolveType("Object");
                    } else {
                        // In case of a function call, we need to unwrap the BMLFunction type to get the return type
                        if (ctx.functionCall() != null) {
                            // If method: check parameters -> delegate check to BMLFunction
                            resolvedType.checkParameters(this, ctx.functionCall().elementExpressionPairList());
                            ctx.type = ((BMLFunction) resolvedType).getReturnType();
                        } else {
                            ctx.type = resolvedType;
                        }
                    }
                }
                case "[" -> {
                    var firstExpression = ctx.expression().get(0);
                    var firstExpressionType = firstExpression.type;
                    var secondExpression = ctx.expression().get(1);
                    var secondExpressionType = secondExpression.type;

                    if (!(firstExpressionType instanceof BMLList)) {
                        addDiagnostic(EXPECTED_BUT_FOUND.format("List", firstExpressionType), firstExpression);
                        ctx.type = TypeRegistry.resolveType("Object");
                    } else if (!(secondExpressionType instanceof BMLNumber)) {
                        addDiagnostic(EXPECTED_BUT_FOUND.format("Number", secondExpressionType), secondExpression);
                        ctx.type = TypeRegistry.resolveType("Object");
                    } else if (((BMLNumber) secondExpressionType).isFloatingPoint()) {
                        addDiagnostic(EXPECTED_BUT_FOUND.format(new BMLNumber(false), secondExpressionType), secondExpression);
                        ctx.type = TypeRegistry.resolveType("Object");
                    } else {
                        // Safe cast because we checked that first expression is a list
                        ctx.type = ((BMLList) firstExpressionType).getItemType();
                    }
                }
                case "!" -> {
                    var exprType = ctx.expr.type;
                    if (!(exprType instanceof BMLBoolean)) {
                        addDiagnostic(EXPECTED_BUT_FOUND.format("boolean", exprType), ctx.left);
                        ctx.type = TypeRegistry.resolveType("Boolean");
                    } else {
                        ctx.type = exprType;
                    }
                }
                case "<", "<=", ">", ">=" -> {
                    var leftType = ctx.left.type;
                    var rightType = ctx.right.type;
                    if (!(leftType instanceof BMLNumber)) {
                        addDiagnostic(EXPECTED_BUT_FOUND.format("Number", leftType), ctx.left);
                    } else if (!(rightType instanceof BMLNumber)) {
                        addDiagnostic(EXPECTED_BUT_FOUND.format("Number", rightType), ctx.right);
                    }

                    ctx.type = TypeRegistry.resolveType("Boolean");
                }
                case "==", "!=" -> {
                    var leftType = ctx.left.type;
                    var rightType = ctx.right.type;
                    if (!leftType.equals(rightType)) {
                        addDiagnostic(INCOMPATIBLE.format(leftType, ctx.op.getText(), rightType), ctx);
                    }

                    ctx.type = TypeRegistry.resolveType("Boolean");
                }
                case "+", "-", "*", "/", "%" -> {
                    if (ctx.left == null) {
                        var expressionType = ctx.expr.type;
                        if (!(expressionType instanceof BMLNumber)) {
                            addDiagnostic(EXPECTED_BUT_FOUND.format("Number", expressionType), ctx.expr);
                            ctx.type = TypeRegistry.resolveType("Number");
                        } else {
                            ctx.type = expressionType;
                        }
                    } else {
                        var leftType = ctx.left.type;
                        var rightType = ctx.right.type;
                        if (!(leftType instanceof BMLNumber)) {
                            addDiagnostic(EXPECTED_BUT_FOUND.format("Number", leftType), ctx.left);
                            ctx.type = TypeRegistry.resolveType("Number");
                        } else if (!(rightType instanceof BMLNumber)) {
                            addDiagnostic(EXPECTED_BUT_FOUND.format("Number", rightType), ctx.right);
                            ctx.type = TypeRegistry.resolveType("Number");
                        } else {
                            var isLeftOrRightFloat = ((BMLNumber) leftType).isFloatingPoint() || ((BMLNumber) rightType).isFloatingPoint();
                            ctx.type = TypeRegistry.resolveType(isLeftOrRightFloat ? "Float Number" : "Number");
                        }
                    }
                }
                case "and", "or" -> {
                    var leftType = ctx.left.type;
                    var rightType = ctx.right.type;
                    if (!(leftType instanceof BMLBoolean)) {
                        addDiagnostic(EXPECTED_BUT_FOUND.format("boolean", leftType), ctx.left);
                        ctx.type = TypeRegistry.resolveType("Boolean");
                    } else if (!(rightType instanceof BMLBoolean)) {
                        addDiagnostic(EXPECTED_BUT_FOUND.format("boolean", rightType), ctx.right);
                        ctx.type = TypeRegistry.resolveType("Boolean");
                    } else {
                        ctx.type = leftType;
                    }
                }
                case "?" -> {
                    var condType = ctx.expression().get(0).type;
                    var firstType = ctx.expression().get(1).type;
                    var secondType = ctx.expression().get(2).type;
                    if (!(condType instanceof BMLBoolean)) {
                        addDiagnostic(EXPECTED_BUT_FOUND.format("boolean", condType), ctx.expression().get(0));
                        ctx.type = TypeRegistry.resolveType("Object");
                    } else if (!firstType.equals(secondType)) {
                        addDiagnostic(INCOMPATIBLE.format(condType + " ? " + firstType, ":", secondType), ctx);
                        ctx.type = TypeRegistry.resolveType("Object");
                    } else {
                        ctx.type = firstType;
                    }
                }
            }
        } else if (ctx.functionCall() != null) {
            var name = ctx.functionCall().functionName.getText();
            var symbol = currentScope.resolve(name);
            if (symbol == null) {
                addDiagnostic(NOT_DEFINED.format(name), ctx.functionCall().functionName);
                ctx.type = TypeRegistry.resolveType("Object");
            } else {
                ctx.type = ((BMLFunction) ((TypedSymbol) symbol).getType()).getReturnType();
            }
        } else { // Initializer
            if (ctx.initializer().mapInitializer() != null) {
                // TODO
                //ctx.initializer().mapInitializer().elementExpressionPairList()
            } else { // List initializer
                // TODO
                // Find type of list items & check they are all equal
                var expressions = ctx.initializer().listInitializer().expression();
                if (expressions.isEmpty()) {
                    Type listType = new BMLList(TypeRegistry.resolveType("Object"));

                    var resolvedType = TypeRegistry.resolveComplexType(listType.toString());
                    if (resolvedType == null) {
                        TypeRegistry.registerType(listType.toString(), listType);
                        ctx.type = listType;
                    } else {
                        ctx.type = resolvedType;
                    }
                } else {
                    var firstItemType = expressions.get(0).type;
                    for (int i = 1, expressionSize = expressions.size(); i < expressionSize; ++i) {
                        if (!firstItemType.equals(expressions.get(i).type)) {
                            addDiagnostic("List initialization requires homogeneous types", ctx.initializer());
                            ctx.type = TypeRegistry.resolveType("Object");
                            break;
                        }
                    }

                    if (ctx.type == null) {
                        Type listType = new BMLList(firstItemType);

                        var resolvedType = TypeRegistry.resolveComplexType(listType.toString());
                        if (resolvedType == null) {
                            TypeRegistry.registerType(listType.toString(), listType);
                            ctx.type = listType;
                        } else {
                            ctx.type = resolvedType;
                        }
                    }
                }
            }
        }
    }

    /*
     * URL Checker
     */
    @Override
    public void exitElementExpressionPair(BMLParser.ElementExpressionPairContext ctx) {
        urlCheck(ctx.expr);
        checkAlreadyDefinedElseDefine(ctx.name);
    }

    private void urlCheck(ParserRuleContext ctx) {
        var url = ctx.getText();
        if (url.equals("url")) {
            url = url.substring(1, url.length() - 1);
            UrlValidator urlValidator = new UrlValidator(new String[]{"http", "https"},
                    UrlValidator.ALLOW_LOCAL_URLS + UrlValidator.ALLOW_ALL_SCHEMES);
            if (!urlValidator.isValid(url)) {
                addDiagnostic("Url '%s' is not valid".formatted(url), ctx);
            }
        }
    }
}
