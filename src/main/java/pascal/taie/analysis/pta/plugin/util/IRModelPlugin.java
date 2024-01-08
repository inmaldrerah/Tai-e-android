/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2022 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2022 Yue Li <yueli@nju.edu.cn>
 *
 * This file is part of Tai-e.
 *
 * Tai-e is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Tai-e is distributed in the hope that it will be useful,but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Tai-e. If not, see <https://www.gnu.org/licenses/>.
 */

package pascal.taie.analysis.pta.plugin.util;

import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.AnalysisException;
import pascal.taie.util.collection.Maps;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

/**
 * Provides common functionalities for implementing the plugins which
 * model the APIs by generating semantically-equivalent IR (Stmt).
 *
 * @see InvokeHandler
 */
public abstract class IRModelPlugin extends ModelPlugin {

    protected final Map<JMethod, Method> handlers = Maps.newMap();

    protected final Map<JMethod, Collection<Stmt>> method2GenStmts = Maps.newMap();

    protected IRModelPlugin(Solver solver) {
        super(solver);
        registerHandlers();
    }

    @Override
    protected void registerHandler(InvokeHandler invokeHandler, Method handler) {
        for (String signature : invokeHandler.signature()) {
            JMethod api = hierarchy.getMethod(signature);
            if (api == null) {
                continue;
            }
            // check handler parameter type
            if (!handler.getParameterTypes()[0].equals(Invoke.class)) {
                throw new RuntimeException("Illegal handler (" +
                        handler + ") parameter type, should be Invoke");
            }
            // check handler return type
            if (!Collection.class.isAssignableFrom(handler.getReturnType())) {
                throw new RuntimeException("Illegal handler (" +
                        handler + ") return type, " +
                        "should be subclass of Collection<Stmt>");
            }
            if (handlers.put(api, handler) != null) {
                throw new RuntimeException(
                        this + " registers multiple handlers for " +
                                api + " (in an IRModelPlugin, at most one handler " +
                                "can be registered for a method)");
            }
        }
    }

    @Override
    public void onStart() {
        handlers.keySet().forEach(solver::addIgnoredMethod);
    }

    @Override
    public void onNewStmt(Stmt stmt, JMethod container) {
        if (stmt instanceof Invoke invoke && !invoke.isDynamic()) {
            JMethod target = invoke.getMethodRef().resolveNullable();
            if (target != null) {
                Method handler = handlers.get(target);
                if (handler != null) {
                    Collection<Stmt> stmts = invokeHandler(handler, invoke);
                    if (!stmts.isEmpty()) {
                        method2GenStmts.computeIfAbsent(
                                        container, __ -> new ArrayList<>())
                                .addAll(stmts);
                    }
                }
            }
        }
    }

    @SuppressWarnings ("unchecked")
    private Collection<Stmt> invokeHandler(Method handler, Invoke invoke) {
        try {
            return (Collection<Stmt>) handler.invoke(this, invoke);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new AnalysisException(e);
        }
    }

    @Override
    public void onNewCSMethod(CSMethod csMethod) {
        Collection<Stmt> genStmts = method2GenStmts.get(csMethod.getMethod());
        if (genStmts != null) {
            solver.addStmts(csMethod, genStmts);
        }
    }
}
