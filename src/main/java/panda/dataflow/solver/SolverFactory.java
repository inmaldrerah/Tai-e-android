/*
 * Panda - A Program Analysis Framework for Java
 *
 * Copyright (C) 2020 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2020 Yue Li <yueli@nju.edu.cn>
 * All rights reserved.
 *
 * This software is designed for the "Static Program Analysis" course at
 * Nanjing University, and it supports a subset of Java features.
 * Panda is only for educational and academic purposes, and any form of
 * commercial use is disallowed.
 */

package panda.dataflow.solver;

import panda.dataflow.analysis.DataFlowAnalysis;
import soot.toolkits.graph.DirectedGraph;

public enum SolverFactory {

    INSTANCE;

    public static SolverFactory v() {
        return INSTANCE;
    }

    public <Domain, Node>
    Solver<Domain, Node> newSolver(DataFlowAnalysis<Domain, Node> problem,
                                   DirectedGraph<Node> cfg) {
        return new IterativeSolver<>(problem, cfg);
    }
}