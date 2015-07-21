package org.umlg.sqlg.process;

import com.google.common.collect.Multimap;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.TraverserGenerator;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;

import java.util.EnumSet;
import java.util.Set;

/**
 * Created by pieter on 2015/07/20.
 */
public class SqlgGraphStepTraverserGenerator implements TraverserGenerator {

    private static final SqlgGraphStepTraverserGenerator INSTANCE = new SqlgGraphStepTraverserGenerator();
    private static final Set<TraverserRequirement> REQUIREMENTS = EnumSet.of(
            TraverserRequirement.OBJECT,
            TraverserRequirement.BULK,
            TraverserRequirement.SINGLE_LOOP,
            TraverserRequirement.PATH,
            TraverserRequirement.SACK,
            TraverserRequirement.SIDE_EFFECTS
            //TODO add in custom requirement
    );

    private SqlgGraphStepTraverserGenerator() {
    }

    @Override
    public <S> Traverser.Admin<S> generate(final S pair, final Step<S, ?> startStep, final long initialBulk) {
        Pair<S, Multimap<String, Object>> p = (Pair<S, Multimap<String, Object>>) pair;
        return new SqlGraphStepTraverser<>(p.getLeft(), p.getRight(), startStep, initialBulk);
    }

    @Override
    public Set<TraverserRequirement> getProvidedRequirements() {
        return REQUIREMENTS;
    }

    public static SqlgGraphStepTraverserGenerator instance() {
        return INSTANCE;
    }
}