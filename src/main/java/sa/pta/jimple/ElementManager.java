package sa.pta.jimple;

import sa.callgraph.JimpleCallUtils;
import sa.pta.element.Variable;
import sa.pta.statement.Allocation;
import sa.pta.statement.Assign;
import sa.pta.statement.Call;
import sa.pta.statement.InstanceLoad;
import sa.pta.statement.InstanceStore;
import sa.util.AnalysisException;
import sa.util.MutableInteger;
import soot.Body;
import soot.Local;
import soot.RefLikeType;
import soot.SootClass;
import soot.SootField;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.Value;
import soot.jimple.AssignStmt;
import soot.jimple.IdentityStmt;
import soot.jimple.InstanceFieldRef;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.InvokeStmt;
import soot.jimple.NewExpr;
import soot.jimple.NullConstant;
import soot.jimple.ReturnStmt;
import soot.jimple.ThrowStmt;
import soot.jimple.internal.JimpleLocal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

class ElementManager {

    private Map<Type, JimpleType> types = new HashMap<>();

    private Map<SootMethod, JimpleMethod> methods = new HashMap<>();

    private Map<JimpleMethod, Map<Local, JimpleVariable>> vars = new HashMap<>();

    private Map<SootField, JimpleField> fields = new HashMap<>();

    private MethodBuilder methodBuilder = new MethodBuilder();

    private NewVariableManager varManager = new NewVariableManager();

    JimpleMethod getMethod(SootMethod method) {
        return methods.computeIfAbsent(method, this::createMethod);
    }

    private JimpleMethod createMethod(SootMethod method) {
        JimpleType jType = getType(method.getDeclaringClass());
        JimpleMethod jMethod = new JimpleMethod(method, jType);
        if (method.isNative()) {
            methodBuilder.buildNative(jMethod);
        } else if (!method.isAbstract()) {
            Body body = method.retrieveActiveBody();
            methodBuilder.buildConcrete(jMethod, body);
        }
        return jMethod;
    }

    private JimpleType getType(SootClass sootClass) {
        return types.computeIfAbsent(sootClass.getType(), JimpleType::new);
    }

    private JimpleType getType(Type type) {
        return types.computeIfAbsent(type, JimpleType::new);
    }

    private JimpleField getField(SootField sootField) {
        return fields.computeIfAbsent(sootField, (f) ->
                new JimpleField(sootField,
                        getType(sootField.getDeclaringClass()),
                        getType(sootField.getType())));
    }

    private JimpleVariable getVariable(Local var, JimpleMethod container) {
        return var.getType() instanceof RefLikeType
                ? vars.computeIfAbsent(container, (m) -> new HashMap<>())
                    .computeIfAbsent(var, (v) -> {
                        JimpleType type = getType(var.getType());
                        return new JimpleVariable(var, type, container);
                    })
                : null; // returns null for variables with non-reference type
    }

    /**
     * Converts Value to Variable.
     * If the value is Local, return it directly, else assign the value to
     * a temporary variable and return the variable.
     */
    private JimpleVariable getVariable(Value value, JimpleMethod container) {
        if (value instanceof Local) {
            return getVariable((Local) value, container);
        } else if (value instanceof NullConstant) {
            return varManager.getTempVariable("null$",
                    getType(value.getType()), container);
        } else {
            // TODO: handle string constants
            // TODO: handle class constants
            // TODO: handle other cases
            throw new AnalysisException("Cannot handle value: " + value);
        }
    }

    private JimpleCallSite createCallSite(InvokeExpr invoke, JimpleMethod container) {
        JimpleCallSite callSite = new JimpleCallSite(
                invoke, JimpleCallUtils.getCallKind(invoke));
        callSite.setMethod(getMethod(invoke.getMethod()));
        if (invoke instanceof InstanceInvokeExpr) {
            callSite.setReceiver(getVariable(
                    ((InstanceInvokeExpr) invoke).getBase(), container));
        }
        callSite.setArguments(
                invoke.getArgs()
                        .stream()
                        .map(v -> getVariable(v, container))
                        .collect(Collectors.toList())
        );
        callSite.setContainerMethod(container);
        return callSite;
    }

    private class MethodBuilder {

        private RelevantUnitSwitch sw = new RelevantUnitSwitch();

        /**
         * Build parameters, this variable (if exists), and
         * return variable (if exists) for the given native method.
         */
        private void buildNative(JimpleMethod method) {
            SootMethod sootMethod = method.getSootMethod();
            if (!sootMethod.isStatic()) {
                method.setThisVar(varManager.getThisVariable(method));
            }
            int paramCount = sootMethod.getParameterCount();
            if (paramCount > 0) {
                List<Variable> params = new ArrayList<>(paramCount);
                for (int i = 0; i < paramCount; ++i) {
                    params.add(varManager.getParameter(method, i));
                }
                method.setParameters(params);
            }
            if (sootMethod.getReturnType() instanceof RefLikeType) {
                method.addReturnVar(varManager.getReturnVariable(method));
            }
        }

        private void buildConcrete(JimpleMethod method, Body body) {
            // add this variable and parameters
            if (!method.isStatic()) {
                method.setThisVar(getVariable(body.getThisLocal(), method));
            }
            method.setParameters(
                    body.getParameterLocals()
                            .stream()
                            .map(param -> getVariable(param, method))
                            .collect(Collectors.toList())
            );
            // add statements
            for (Unit unit : body.getUnits()) {
                unit.apply(sw);
                if (sw.isRelevant()) {
                    if (unit instanceof AssignStmt) {
                        build(method, (AssignStmt) unit);
                    } else if (unit instanceof IdentityStmt) {
                        build(method, (IdentityStmt) unit);
                    } else if (unit instanceof InvokeStmt) {
                        build(method, ((InvokeStmt) unit));
                    } else if (unit instanceof ReturnStmt) {
                        build(method, (ReturnStmt) unit);
                    } else if (unit instanceof ThrowStmt) {
                        build(method, (ThrowStmt) unit);
                    } else {
                        throw new RuntimeException("Cannot handle statement: " + unit);
                    }
                }
            }
        }

        private void build(JimpleMethod method, AssignStmt stmt) {
            Value left = stmt.getLeftOp();
            Value right = stmt.getRightOp();
            if (left instanceof Local) {
                Variable lhs = getVariable(left, method);
                if (right instanceof NewExpr) {
                    // x = new T();
                    method.addStatement(new Allocation(lhs, stmt, getType(right.getType())));
                } else if (right instanceof Local) {
                    // x = y;
                    method.addStatement(new Assign(lhs, getVariable((Local) right, method)));
                } else if (right instanceof InstanceFieldRef) {
                    // x = y.f;
                    InstanceFieldRef ref = (InstanceFieldRef) right;
                    JimpleVariable base = getVariable(ref.getBase(), method);
                    InstanceLoad load = new InstanceLoad(lhs, base, getField(ref.getField()));
                    base.addLoad(load);
                    method.addStatement(load);
                } else if (right instanceof InvokeExpr) {
                    // x = o.m();
                    JimpleCallSite callSite =
                            createCallSite((InvokeExpr) right, method);
                    Call call = new Call(callSite, lhs);
                    callSite.setCall(call);
                    method.addStatement(call);
                } else {
                    // TODO: x = new T[];
                    // TODO: x = y[i];
                    // TODO: x = T.f;
                    // TODO: x = "x";
                    // TODO: x = T.class;
                    // TODO: x = other cases
                }
            } else if (left instanceof InstanceFieldRef) {
                // x.f = y;
                InstanceFieldRef ref = (InstanceFieldRef) left;
                JimpleVariable base = getVariable(ref.getBase(), method);
                InstanceStore store = new InstanceStore(base,
                        getField(ref.getField()),
                        getVariable(right, method));
                base.addStore(store);
                method.addStatement(store);
            } else {
                // TODO: T.f = x;
                // TODO: x[i] = y;
            }
        }

        private void build(JimpleMethod method, IdentityStmt stmt) {
            // identity statement is for parameter passing and catch statements
            // parameters have been handled when creating JimpleMethod
            // currently ignore catch statements
        }

        private void build(JimpleMethod method, InvokeStmt stmt) {
            // x.m();
            JimpleCallSite callSite =
                    createCallSite(stmt.getInvokeExpr(), method);
            Call call = new Call(callSite, null);
            callSite.setCall(call);
            method.addStatement(call);
        }

        private void build(JimpleMethod method, ReturnStmt stmt) {
            if (stmt.getOp().getType() instanceof RefLikeType) {
                method.addReturnVar(getVariable(stmt.getOp(), method));
            }
        }

        private void build(JimpleMethod method, ThrowStmt stmt) {
            // currently ignore throw statements
        }
    }

    /**
     * Manager for new created variables during method creation.
     */
    private class NewVariableManager {

        private Map<JimpleMethod, MutableInteger> varNumbers = new HashMap<>();

        private JimpleVariable getTempVariable(
                String baseName, JimpleType type, JimpleMethod container) {
            String varName = baseName + getNewNumber(container);
            return getNewVariable(varName, type, container);
        }

        private int getNewNumber(JimpleMethod container) {
            return varNumbers.computeIfAbsent(container,
                    m -> new MutableInteger(0))
                    .increase();
        }

        private JimpleVariable getThisVariable(JimpleMethod container) {
            return getNewVariable("@this", container.getClassType(), container);
        }

        private JimpleVariable getParameter(JimpleMethod container, int index) {
            JimpleType type = getType(container.getSootMethod().getParameterType(index));
            return getNewVariable("@parameter" + index, type, container);
        }

        private JimpleVariable getReturnVariable(JimpleMethod container) {
            JimpleType type = getType(container.getSootMethod().getReturnType());
            return getNewVariable("@return", type, container);
        }

        private JimpleVariable getNewVariable(
                String varName, JimpleType type, JimpleMethod container) {
            Local local = new JimpleLocal(varName, type.getSootType());
            return new JimpleVariable(local, type, container);
        }
    }
}