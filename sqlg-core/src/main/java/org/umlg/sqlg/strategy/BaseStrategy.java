package org.umlg.sqlg.strategy;

import com.google.common.base.Preconditions;
import org.apache.commons.lang3.Range;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tinkerpop.gremlin.process.traversal.*;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.DefaultGraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ElementValueTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.LoopTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.TokenTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.HasContainerHolder;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.ChooseStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.LocalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.OptionalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.branch.RepeatStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.*;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.*;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.IdentityStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.SackValueStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.TreeSideEffectStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.ComputerAwareStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.ReducingBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.EventStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.AndP;
import org.apache.tinkerpop.gremlin.process.traversal.util.ConnectiveP;
import org.apache.tinkerpop.gremlin.process.traversal.util.OrP;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.T;
import org.umlg.sqlg.predicate.Existence;
import org.umlg.sqlg.predicate.FullText;
import org.umlg.sqlg.predicate.Text;
import org.umlg.sqlg.sql.parse.AndOrHasContainer;
import org.umlg.sqlg.sql.parse.ReplacedStep;
import org.umlg.sqlg.sql.parse.ReplacedStepTree;
import org.umlg.sqlg.step.*;
import org.umlg.sqlg.step.barrier.SqlgCountGlobalStep;
import org.umlg.sqlg.step.barrier.SqlgLocalStepBarrier;
import org.umlg.sqlg.structure.SqlgGraph;
import org.umlg.sqlg.util.SqlgTraversalUtil;
import org.umlg.sqlg.util.SqlgUtil;

import java.time.Duration;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * @author Pieter Martin (https://github.com/pietermartin)
 * Date: 2017/03/04
 */
public abstract class BaseStrategy {

    static final List<Class> CONSECUTIVE_STEPS_TO_REPLACE = Arrays.asList(
            VertexStep.class,
            EdgeVertexStep.class,
            GraphStep.class,
            EdgeOtherVertexStep.class,
            OrderGlobalStep.class,
            RangeGlobalStep.class,
            ChooseStep.class,
            OptionalStep.class,
            RepeatStep.class,
            SelectStep.class,
            SelectOneStep.class,
            DropStep.class,
            PropertiesStep.class,
            PropertyMapStep.class,
            MaxGlobalStep.class,
            MinGlobalStep.class,
            SumGlobalStep.class,
            MeanGlobalStep.class,
            GroupStep.class,
            CountGlobalStep.class
    );
    public static final String PATH_LABEL_SUFFIX = "P~~~";
    public static final String EMIT_LABEL_SUFFIX = "E~~~";
    public static final String SQLG_PATH_FAKE_LABEL = "sqlgPathFakeLabel";
    public static final String SQLG_PATH_ORDER_RANGE_LABEL = "sqlgPathOrderRangeLabel";
    private static final List<BiPredicate> SUPPORTED_BI_PREDICATE = Arrays.asList(
            Compare.eq, Compare.neq, Compare.gt, Compare.gte, Compare.lt, Compare.lte
    );
    public static final List<BiPredicate> SUPPORTED_LABEL_BI_PREDICATE = Arrays.asList(
            Compare.eq, Compare.neq, Contains.within, Contains.without
    );
    public static final List<BiPredicate> SUPPORTED_ID_BI_PREDICATE = Arrays.asList(
            Compare.eq, Compare.neq, Contains.within, Contains.without
    );

    final Traversal.Admin<?, ?> traversal;
    final SqlgGraph sqlgGraph;
    SqlgStep sqlgStep = null;
    private final Stack<ReplacedStepTree.TreeNode> optionalStepStack = new Stack<>();
    private final Stack<ReplacedStepTree.TreeNode> chooseStepStack = new Stack<>();
    ReplacedStepTree.TreeNode currentTreeNodeNode;
    ReplacedStep<?, ?> currentReplacedStep;
    /**
     * reset is used in {@link VertexStrategy#combineSteps()} where it allows the optimization to continue.
     */
    boolean reset = false;

    BaseStrategy(Traversal.Admin<?, ?> traversal) {
        this.traversal = traversal;
        Optional<Graph> graph = traversal.getGraph();
        Preconditions.checkState(graph.isPresent(), "BUG: SqlgGraph must be present on the traversal.");
        this.sqlgGraph = (SqlgGraph) graph.get();
    }

    abstract void combineSteps();

    /**
     * sqlgStep is either a {@link SqlgGraphStep} or {@link SqlgVertexStep}.
     *
     * @return false if optimization must be terminated.
     */
    boolean handleStep(ListIterator<Step<?, ?>> stepIterator, MutableInt pathCount) {
        Step<?, ?> step = stepIterator.next();
        if (step instanceof GraphStep) {
            doFirst(stepIterator, step, pathCount);
        } else if (this.sqlgStep == null) {
            boolean keepGoing = doFirst(stepIterator, step, pathCount);
            stepIterator.previous();
            return keepGoing;
        } else {
            if (step instanceof VertexStep || step instanceof EdgeVertexStep || step instanceof EdgeOtherVertexStep) {
                handleVertexStep(stepIterator, (AbstractStep<?, ?>) step, pathCount);
            } else if (step instanceof RepeatStep) {
                if (!unoptimizableRepeatStep()) {
                    handleRepeatStep((RepeatStep<?>) step, pathCount);
                } else {
                    this.currentReplacedStep.addLabel((pathCount) + BaseStrategy.PATH_LABEL_SUFFIX + BaseStrategy.SQLG_PATH_FAKE_LABEL);
                    return false;
                }
            } else if (step instanceof OptionalStep) {
                if (!unoptimizableOptionalStep((OptionalStep<?>) step)) {
                    this.optionalStepStack.clear();
                    handleOptionalStep(1, (OptionalStep<?>) step, this.traversal, pathCount);
                    this.optionalStepStack.clear();
                    //after choose steps the optimization starts over in VertexStrategy
                    this.reset = true;
                } else {
                    return false;
                }
            } else if (step instanceof ChooseStep) {
                if (!unoptimizableChooseStep((ChooseStep<?, ?, ?>) step)) {
                    this.chooseStepStack.clear();
                    handleChooseStep(1, (ChooseStep<?, ?, ?>) step, this.traversal, pathCount);
                    this.chooseStepStack.clear();
                    //after choose steps the optimization starts over
                    this.reset = true;
                } else {
                    return false;
                }
            } else if (step instanceof OrderGlobalStep) {
                stepIterator.previous();
                handleOrderGlobalSteps(stepIterator, pathCount);
                handleRangeGlobalSteps(stepIterator, pathCount);
            } else if (step instanceof RangeGlobalStep) {
                handleRangeGlobalSteps(stepIterator, pathCount);
            } else if (step instanceof SelectStep || (step instanceof SelectOneStep)) {
                handleOrderGlobalSteps(stepIterator, pathCount);
                handleRangeGlobalSteps(stepIterator, pathCount);
                //select step can not be followed by a PropertyStep
                if (step instanceof SelectOneStep) {
                    SelectOneStep selectOneStep = (SelectOneStep) step;
                    String key = (String) selectOneStep.getScopeKeys().iterator().next();
                    if (stepIterator.hasNext()) {
                        Step<?, ?> next = stepIterator.next();
                        if (next instanceof PropertiesStep) {
                            //get the step for the label
                            Optional<ReplacedStep<?, ?>> labeledReplacedStep = this.sqlgStep.getReplacedSteps().stream().filter(
                                    r -> {
                                        //Take the first
                                        if (!r.getLabels().isEmpty()) {
                                            String label = r.getLabels().iterator().next();
                                            String stepLabel = SqlgUtil.originalLabel(label);
                                            return stepLabel.equals(key);
                                        } else {
                                            return false;
                                        }
                                    }
                            ).findAny();
                            Preconditions.checkState(labeledReplacedStep.isPresent());
                            ReplacedStep<?, ?> replacedStep = labeledReplacedStep.get();
                            handlePropertiesStep(replacedStep, next);
                            return true;
                        } else {
                            stepIterator.previous();
                            return false;
                        }
                    }
                }
            } else if (step instanceof DropStep && (!this.sqlgGraph.getSqlDialect().isMariaDb())) {

                Traversal.Admin<?, ?> root = TraversalHelper.getRootTraversal(this.traversal);
                final Optional<EventStrategy> eventStrategyOptional = root.getStrategies().getStrategy(EventStrategy.class);
                if (eventStrategyOptional.isPresent()) {
                    //Do nothing, it will go via the SqlgDropStepBarrier.
                } else {
                    //MariaDB does not support target and source together.
                    //Table 'E_ab' is specified twice, both as a target for 'DELETE' and as a separate source for data
                    //This has been fixed in 10.3.1, waiting for it to land in the repo.
                    handleDropStep();
                }
                return false;
            } else if (step instanceof DropStep && this.sqlgGraph.getSqlDialect().isMariaDb()) {
                return false;
            } else if (step instanceof PropertiesStep) {
                return handlePropertiesStep(this.currentReplacedStep, step);
            } else if (step instanceof PropertyMapStep) {
                return handlePropertyMapStep(step);
            } else if (step instanceof MaxGlobalStep) {
                return handleMaxGlobalStep(this.currentReplacedStep, step);
            } else if (step instanceof MinGlobalStep) {
                return handleMinGlobalStep(this.currentReplacedStep, step);
            } else if (step instanceof SumGlobalStep) {
                return handleSumGlobalStep(this.currentReplacedStep, step);
            } else if (step instanceof MeanGlobalStep) {
                return handleMeanGlobalStep(this.currentReplacedStep, step);
            } else if (step instanceof GroupStep) {
                return handleGroupStep(this.currentReplacedStep, step);
            } else if (step instanceof CountGlobalStep) {
                if (handleCountGlobalStep(this.currentReplacedStep, step)) {
                    TraversalHelper.replaceStep((Step)step, new SqlgCountGlobalStep(this.traversal), this.traversal);
                }
                return false;
            } else {
                throw new IllegalStateException("Unhandled step " + step.getClass().getName());
            }
        }
        return true;
    }

    private boolean handleMaxGlobalStep(ReplacedStep<?, ?> replacedStep, Step<?, ?> step) {
        replacedStep.setAggregateFunction(Pair.of(GraphTraversal.Symbols.max, Collections.emptyList()));
        return false;
    }

    private boolean handleMinGlobalStep(ReplacedStep<?, ?> replacedStep, Step<?, ?> step) {
        replacedStep.setAggregateFunction(Pair.of(GraphTraversal.Symbols.min, Collections.emptyList()));
        return false;
    }

    private boolean handleSumGlobalStep(ReplacedStep<?, ?> replacedStep, Step<?, ?> step) {
        replacedStep.setAggregateFunction(Pair.of(GraphTraversal.Symbols.sum, Collections.emptyList()));
        return false;
    }

    private boolean handleCountGlobalStep(ReplacedStep<?, ?> replacedStep, Step<?, ?> step) {
        if (step.getPreviousStep() instanceof PropertiesStep) {
            return false;
        } else {
            replacedStep.setAggregateFunction(Pair.of(GraphTraversal.Symbols.count, Collections.emptyList()));
            return true;
        }
    }

    private boolean handleMeanGlobalStep(ReplacedStep<?, ?> replacedStep, Step<?, ?> step) {
        replacedStep.setAggregateFunction(Pair.of(GraphTraversal.Symbols.mean, Collections.emptyList()));
        return false;
    }

    private boolean handleGroupStep(ReplacedStep<?, ?> replacedStep, Step<?, ?> step) {
        GroupStep<?, ?, ?> groupStep = (GroupStep<?, ?, ?>) step;
        List<Traversal.Admin<?, ?>> localChildren = groupStep.getLocalChildren();
        if (localChildren.size() == 2) {
            Traversal.Admin<?, ?> groupByTraversal = localChildren.get(0);
            Traversal.Admin<?, ?> aggregateOverTraversal = localChildren.get(1);
            boolean isPropertiesStep = false;
            List<String> groupByKeys  = new ArrayList<>();
            if (groupByTraversal instanceof ElementValueTraversal) {
                ElementValueTraversal<?> elementValueTraversal = (ElementValueTraversal) groupByTraversal;
                groupByKeys.add(elementValueTraversal.getPropertyKey());
            } else if (groupByTraversal instanceof DefaultGraphTraversal) {
                List<Step> groupBySteps = groupByTraversal.getSteps();
                if ((groupBySteps.get(0) instanceof PropertiesStep) || (groupBySteps.get(0) instanceof PropertyMapStep)) {
                    isPropertiesStep = groupBySteps.get(0) instanceof PropertiesStep;
                    List<String> groupBys = getRestrictedProperties(groupBySteps.get(0));
                    groupByKeys.addAll(groupBys);
                } else {
                    return false;
                }
            } else if (groupByTraversal instanceof TokenTraversal){
                TokenTraversal<?, ?> tokenTraversal = (TokenTraversal) groupByTraversal;
                if (tokenTraversal.getToken() == T.label) {
                    groupByKeys.add(T.label.getAccessor());
                } else {
                    return false;
                }
            } else {
                return false;
            }
            List<Step> valueTraversalSteps = aggregateOverTraversal.getSteps();
            if (valueTraversalSteps.size() == 2) {
                Step one = valueTraversalSteps.get(0);
                Step two = valueTraversalSteps.get(1);
                if (one instanceof PropertiesStep && two instanceof ReducingBarrierStep) {
                    PropertiesStep propertiesStep = (PropertiesStep) one;
                    List<String> aggregationFunctionProperty = getRestrictedProperties(propertiesStep);
                    if (aggregationFunctionProperty == null) {
                        return false;
                    }
                    handlePropertiesStep(replacedStep, propertiesStep);

                    if (replacedStep.getRestrictedProperties() == null) {
                        replacedStep.setRestrictedProperties(new HashSet<>(groupByKeys));
                    } else {
                        replacedStep.getRestrictedProperties().addAll(groupByKeys);
                    }
                    replacedStep.setGroupBy(groupByKeys);

                    if (two instanceof MaxGlobalStep) {
                        replacedStep.setAggregateFunction(Pair.of(GraphTraversal.Symbols.max, aggregationFunctionProperty));
                    } else if (two instanceof MinGlobalStep) {
                        replacedStep.setAggregateFunction(Pair.of(GraphTraversal.Symbols.min, aggregationFunctionProperty));
                    } else if (two instanceof SumGlobalStep) {
                        replacedStep.setAggregateFunction(Pair.of(GraphTraversal.Symbols.sum, aggregationFunctionProperty));
                    } else if (two instanceof MeanGlobalStep) {
                        replacedStep.setAggregateFunction(Pair.of(GraphTraversal.Symbols.mean, aggregationFunctionProperty));
                    } else {
                        throw new IllegalStateException(String.format("Unhandled group by aggregation %s", two.getClass().getSimpleName()));
                    }
                    SqlgGroupStep<?, ?> sqlgPropertiesStep = new SqlgGroupStep<>(this.traversal, groupByKeys, aggregationFunctionProperty.get(0), isPropertiesStep);
                    //noinspection unchecked
                    TraversalHelper.replaceStep((Step) step, sqlgPropertiesStep, this.traversal);
                }
            }
            return false;
        }
        return false;
    }

    private boolean handlePropertyMapStep(Step<?, ?> step) {
        Step<?, ?> dropStep = SqlgTraversalUtil.stepAfter(this.traversal, DropStep.class, step);
        if (dropStep != null) {
            return false;
        }
        Step<?, ?> orderGlobalStep = SqlgTraversalUtil.stepAfter(this.traversal, OrderGlobalStep.class, step);
        if (orderGlobalStep != null) {
            return false;
        }
        Step<?, ?> selectOneStep = SqlgTraversalUtil.stepAfter(this.traversal, SelectOneStep.class, step);
        if (selectOneStep != null) {
            return false;
        }
        Step<?, ?> selectStep = SqlgTraversalUtil.stepAfter(this.traversal, SelectStep.class, step);
        if (selectStep != null) {
            return false;
        }
        Step<?, ?> lambdaStep = SqlgTraversalUtil.lastLambdaHolderBefore(this.traversal, step);
        if (lambdaStep == null) {
            PropertyMapStep propertyMapStep = (PropertyMapStep) step;
            List<String> propertiesToRestrict = getRestrictedProperties(step);
            if (propertiesToRestrict != null) {
                if (this.currentReplacedStep.getRestrictedProperties() == null) {
                    this.currentReplacedStep.setRestrictedProperties(new HashSet<>(propertiesToRestrict));
                } else {
                    this.currentReplacedStep.getRestrictedProperties().addAll(propertiesToRestrict);
                }
                SqlgPropertyMapStep<?, ?> sqlgPropertiesStep = new SqlgPropertyMapStep<>(
                        traversal,
                        propertyMapStep.isIncludeTokens(),
                        propertyMapStep.getReturnType(),
                        propertyMapStep.getPropertyKeys());

                for (String label : step.getLabels()) {
                    sqlgPropertiesStep.addLabel(label);
                }
                //noinspection unchecked
                TraversalHelper.replaceStep((Step) step, sqlgPropertiesStep, traversal);
            }
        }
        return true;
    }

    private boolean handlePropertiesStep(ReplacedStep replacedStep, Step<?, ?> step) {
        Step<?, ?> dropStep = SqlgTraversalUtil.stepAfter(this.traversal, DropStep.class, step);
        if (dropStep != null) {
            return false;
        }
        Step<?, ?> orderGlobalStep = SqlgTraversalUtil.stepAfter(this.traversal, OrderGlobalStep.class, step);
        if (orderGlobalStep != null) {
            return false;
        }
        Step<?, ?> lambdaStep = SqlgTraversalUtil.lastLambdaHolderBefore(this.traversal, step);
        if (lambdaStep != null) {
            return false;
        }
        List<String> propertiesToRestrict = getRestrictedProperties(step);
        if (propertiesToRestrict != null) {
            if (replacedStep.getRestrictedProperties() == null) {
                replacedStep.setRestrictedProperties(new HashSet<>(propertiesToRestrict));
            } else {
                replacedStep.getRestrictedProperties().addAll(propertiesToRestrict);
            }
        }
        return true;
    }

    private void handleDropStep() {
        this.currentReplacedStep.markAsDrop();
    }

    protected abstract boolean doFirst(ListIterator<Step<?, ?>> stepIterator, Step<?, ?> step, MutableInt pathCount);

    private void handleVertexStep(ListIterator<Step<?, ?>> stepIterator, AbstractStep<?, ?> step, MutableInt pathCount) {
        this.currentReplacedStep = ReplacedStep.from(
                this.sqlgGraph.getTopology(),
                step,
                pathCount.getValue()
        );
        //Important to add the replacedStep before collecting the additional steps.
        //In particular the orderGlobalStep needs to the currentStepDepth setted.
        ReplacedStepTree.TreeNode treeNodeNode = this.sqlgStep.addReplacedStep(this.currentReplacedStep);
        handleHasSteps(stepIterator, pathCount.getValue());
        handleOrderGlobalSteps(stepIterator, pathCount);
        handleRangeGlobalSteps(stepIterator, pathCount);
        handleConnectiveSteps(stepIterator);
        //if called from ChooseStep then the VertexStep is nested inside the ChooseStep and not one of the traversal's direct steps.
        int index = TraversalHelper.stepIndex(step, this.traversal);
        if (index != -1) {
            this.traversal.removeStep(step);
        }
        if (this.currentReplacedStep.getLabels().isEmpty()) {
            boolean precedesPathStep = precedesPathOrTreeStep(this.traversal);
            if (precedesPathStep) {
                this.currentReplacedStep.addLabel(pathCount.getValue() + BaseStrategy.PATH_LABEL_SUFFIX + BaseStrategy.SQLG_PATH_FAKE_LABEL);
            }
        }
        pathCount.increment();
        this.currentTreeNodeNode = treeNodeNode;
    }

    private void handleRepeatStep(RepeatStep<?> repeatStep, MutableInt pathCount) {
        List<? extends Traversal.Admin<?, ?>> repeatTraversals = repeatStep.getGlobalChildren();
        Traversal.Admin admin = repeatTraversals.get(0);
        LoopTraversal loopTraversal;
        long numberOfLoops;
        loopTraversal = (LoopTraversal) repeatStep.getUntilTraversal();
        numberOfLoops = loopTraversal.getMaxLoops();
        for (int i = 0; i < numberOfLoops; i++) {
            @SuppressWarnings("unchecked")
            ListIterator<Step<?, ?>> repeatStepIterator = admin.getSteps().listIterator();
            while (repeatStepIterator.hasNext()) {
                Step internalRepeatStep = repeatStepIterator.next();
                if (internalRepeatStep instanceof RepeatStep.RepeatEndStep) {
                    break;
                } else if (internalRepeatStep instanceof VertexStep || internalRepeatStep instanceof EdgeVertexStep || internalRepeatStep instanceof EdgeOtherVertexStep) {
                    ReplacedStep<?, ?> replacedStepToEmit;
                    //this means the ReplacedStep before the RepeatStep need to be emitted.
                    //i.e. the currentReplacedStep before running handleVertexStep needs to be emitted.
                    if (repeatStep.emitFirst) {
                        replacedStepToEmit = this.currentReplacedStep;
                        pathCount.decrement();
                        //noinspection ConstantConditions
                        replacedStepToEmit.setEmit(repeatStep.getEmitTraversal() != null);
                        replacedStepToEmit.setUntilFirst(repeatStep.untilFirst);
                        if (repeatStep.getLabels().isEmpty()) {
                            replacedStepToEmit.addLabel(pathCount + BaseStrategy.EMIT_LABEL_SUFFIX + BaseStrategy.SQLG_PATH_FAKE_LABEL);
                        } else {
                            for (String label : repeatStep.getLabels()) {
                                replacedStepToEmit.addLabel(pathCount + BaseStrategy.EMIT_LABEL_SUFFIX + label);
                            }
                        }
                        pathCount.increment();
                    }
                    handleVertexStep(repeatStepIterator, (AbstractStep<?, ?>) internalRepeatStep, pathCount);
                    pathCount.decrement();
                    if (!repeatStep.emitFirst) {
                        replacedStepToEmit = this.currentReplacedStep;
                        //noinspection ConstantConditions
                        replacedStepToEmit.setEmit(repeatStep.getEmitTraversal() != null);
                        replacedStepToEmit.setUntilFirst(repeatStep.untilFirst);
                        if (repeatStep.getLabels().isEmpty()) {
                            replacedStepToEmit.addLabel(pathCount + BaseStrategy.EMIT_LABEL_SUFFIX + BaseStrategy.SQLG_PATH_FAKE_LABEL);
                        } else {
                            for (String label : repeatStep.getLabels()) {
                                replacedStepToEmit.addLabel(pathCount + BaseStrategy.EMIT_LABEL_SUFFIX + label);
                            }
                        }
                    }
                    pathCount.increment();
                    //If there is an emit we can not continue the optimization.
                    this.reset = repeatStep.getEmitTraversal() != null;
                } else {
                    throw new IllegalStateException("Unhandled step nested in RepeatStep " + internalRepeatStep.getClass().getName());
                }
            }
        }
        this.traversal.removeStep(repeatStep);
    }

    private void handleOptionalStep(int optionalStepNestedCount, OptionalStep<?> optionalStep, Traversal.Admin<?, ?> traversal, MutableInt pathCount) {
        //The currentTreeNode here is the node that will need the left join in the sql generation
        this.optionalStepStack.add(this.currentTreeNodeNode);
        Preconditions.checkState(this.optionalStepStack.size() == optionalStepNestedCount);

        Traversal.Admin<?, ?> optionalTraversal = optionalStep.getLocalChildren().get(0);

        ReplacedStep<?, ?> previousReplacedStep = this.sqlgStep.getReplacedSteps().get(this.sqlgStep.getReplacedSteps().size() - 1);
        previousReplacedStep.setLeftJoin(true);

        @SuppressWarnings("unchecked")
        List<Step<?, ?>> optionalTraversalSteps = new ArrayList(optionalTraversal.getSteps());
        ListIterator<Step<?, ?>> optionalStepsIterator = optionalTraversalSteps.listIterator();
        while (optionalStepsIterator.hasNext()) {
            Step internalOptionalStep = optionalStepsIterator.next();
            if (internalOptionalStep instanceof VertexStep || internalOptionalStep instanceof EdgeVertexStep || internalOptionalStep instanceof EdgeOtherVertexStep) {
                handleVertexStep(optionalStepsIterator, (AbstractStep<?, ?>) internalOptionalStep, pathCount);
                //if the chooseStepStack size is greater than the chooseStepNestedCount then it means the just executed
                //handleVertexStep is after nested chooseSteps.
                //This means that this VertexStep applies to the nested chooseSteps where the chooseStep was not chosen.
                //I.e. there was no results for the chooseSteps traversal.
                for (int i = optionalStepNestedCount; i < this.chooseStepStack.size(); i++) {
                    ReplacedStepTree.TreeNode treeNode = this.chooseStepStack.get(i);
                    this.currentReplacedStep.markAsJoinToLeftJoin();
                    treeNode.addReplacedStep(this.currentReplacedStep);
                }
            } else if (internalOptionalStep instanceof OptionalStep) {
                handleOptionalStep(optionalStepNestedCount + 1, (OptionalStep) internalOptionalStep, traversal, pathCount);
            } else if (internalOptionalStep instanceof ComputerAwareStep.EndStep) {
                break;
            } else if (internalOptionalStep instanceof HasStep) {
                handleHasSteps(optionalStepsIterator, pathCount.getValue());
            } else {
                throw new IllegalStateException("Unhandled step nested in OptionalStep " + internalOptionalStep.getClass().getName());
            }
        }
        //the chooseStep might be a ChooseStep nested inside another ChooseStep.
        //In that case it will not be a direct step of the traversal.
        if (traversal.getSteps().contains(optionalStep)) {
            traversal.removeStep(optionalStep);
        }
    }

    private void handleChooseStep(int chooseStepNestedCount, ChooseStep<?, ?, ?> chooseStep, Traversal.Admin<?, ?> traversal, MutableInt pathCount) {
        //The currentTreeNode here is the node that will need the left join in the sql generation
        this.chooseStepStack.add(this.currentTreeNodeNode);
        Preconditions.checkState(this.chooseStepStack.size() == chooseStepNestedCount);
        List<? extends Traversal.Admin<?, ?>> globalChildren = chooseStep.getGlobalChildren();
        Preconditions.checkState(globalChildren.size() == 2, "ChooseStep's globalChildren must have size 2, one for true and one for false");

        ReplacedStep<?, ?> previousReplacedStep = this.sqlgStep.getReplacedSteps().get(this.sqlgStep.getReplacedSteps().size() - 1);
        previousReplacedStep.setLeftJoin(true);
        Traversal.Admin<?, ?> trueTraversal;
        Traversal.Admin<?, ?> a = globalChildren.get(0);
        Traversal.Admin<?, ?> b = globalChildren.get(1);
        if (a.getSteps().stream().anyMatch(s -> s instanceof IdentityStep<?>)) {
            trueTraversal = b;
        } else {
            trueTraversal = a;
        }
        @SuppressWarnings("unchecked")
        List<Step<?, ?>> trueTraversalSteps = new ArrayList(trueTraversal.getSteps());
        ListIterator<Step<?, ?>> trueTraversalStepsIterator = trueTraversalSteps.listIterator();
        while (trueTraversalStepsIterator.hasNext()) {
            Step internalChooseStep = trueTraversalStepsIterator.next();
            if (internalChooseStep instanceof VertexStep || internalChooseStep instanceof EdgeVertexStep || internalChooseStep instanceof EdgeOtherVertexStep) {
                handleVertexStep(trueTraversalStepsIterator, (AbstractStep<?, ?>) internalChooseStep, pathCount);
                //if the chooseStepStack size is greater than the chooseStepNestedCount then it means the just executed
                //handleVertexStep is after nested chooseSteps.
                //This means that this VertexStep applies to the nested chooseSteps where the chooseStep was not chosen.
                //I.e. there was no results for the chooseSteps traversal.
                for (int i = chooseStepNestedCount; i < this.chooseStepStack.size(); i++) {
                    ReplacedStepTree.TreeNode treeNode = this.chooseStepStack.get(i);
                    this.currentReplacedStep.markAsJoinToLeftJoin();
                    treeNode.addReplacedStep(this.currentReplacedStep);
                }
            } else if (internalChooseStep instanceof ChooseStep) {
                handleChooseStep(chooseStepNestedCount + 1, (ChooseStep) internalChooseStep, traversal, pathCount);
            } else if (internalChooseStep instanceof ComputerAwareStep.EndStep) {
                break;
            } else {
                throw new IllegalStateException("Unhandled step nested in ChooseStep " + internalChooseStep.getClass().getName());
            }
        }
        //the chooseStep might be a ChooseStep nested inside another ChooseStep.
        //In that case it will not be a direct step of the traversal.
        if (traversal.getSteps().contains(chooseStep)) {
            traversal.removeStep(chooseStep);
        }
    }

    protected abstract SqlgStep constructSqlgStep(Step startStep);

    protected abstract boolean isReplaceableStep(Class<? extends Step> stepClass);

    protected abstract void replaceStepInTraversal(Step stepToReplace, SqlgStep sqlgStep);

    void handleHasSteps(ListIterator<Step<?, ?>> iterator, int pathCount) {
        //Collect the hasSteps
        int countToGoPrevious = 0;
        while (iterator.hasNext()) {
            Step<?, ?> currentStep = iterator.next();
            countToGoPrevious++;
            String notNullKey;
            String nullKey;
            if (currentStep instanceof HasContainerHolder) {
                HasContainerHolder hasContainerHolder = (HasContainerHolder) currentStep;
                List<HasContainer> hasContainers = hasContainerHolder.getHasContainers();
                List<HasContainer> toRemoveHasContainers = new ArrayList<>();
                if (isNotWithMultipleColumnValue(hasContainerHolder)) {
                    toRemoveHasContainers.addAll(isForSqlgSchema(this.currentReplacedStep, hasContainers));
                    toRemoveHasContainers.addAll(isForGuiSchema(this.currentReplacedStep, hasContainers));
                    toRemoveHasContainers.addAll(optimizeLabelHas(this.currentReplacedStep, hasContainers));
                    //important to do optimizeIdHas after optimizeLabelHas as it might add its labels to the previous labelHasContainers labels.
                    //i.e. for neq and without 'or' logic
                    toRemoveHasContainers.addAll(optimizeIdHas(this.currentReplacedStep, hasContainers));
                    toRemoveHasContainers.addAll(optimizeHas(this.currentReplacedStep, hasContainers));
                    toRemoveHasContainers.addAll(optimizeWithInOut(this.currentReplacedStep, hasContainers));
                    toRemoveHasContainers.addAll(optimizeBetween(this.currentReplacedStep, hasContainers));
                    toRemoveHasContainers.addAll(optimizeInside(this.currentReplacedStep, hasContainers));
                    toRemoveHasContainers.addAll(optimizeOutside(this.currentReplacedStep, hasContainers));
                    toRemoveHasContainers.addAll(optimizeTextContains(this.currentReplacedStep, hasContainers));
                    if (toRemoveHasContainers.size() == hasContainers.size()) {
                        if (!currentStep.getLabels().isEmpty()) {
                            final IdentityStep identityStep = new IdentityStep<>(this.traversal);
                            currentStep.getLabels().forEach(label -> this.currentReplacedStep.addLabel(pathCount + BaseStrategy.PATH_LABEL_SUFFIX + label));
                            //noinspection unchecked
                            TraversalHelper.insertAfterStep(identityStep, currentStep, this.traversal);
                        }
                        if (this.traversal.getSteps().contains(currentStep)) {
                            this.traversal.removeStep(currentStep);
                        }
                        iterator.remove();
                        countToGoPrevious--;
                    }
                }
            } else if ((notNullKey = isNotNullStep(currentStep)) != null) {
                this.currentReplacedStep.addHasContainer(new HasContainer(notNullKey, new P<>(Existence.NOTNULL, null)));
                if (!currentStep.getLabels().isEmpty()) {
                    final IdentityStep identityStep = new IdentityStep<>(this.traversal);
                    currentStep.getLabels().forEach(label -> this.currentReplacedStep.addLabel(pathCount + BaseStrategy.PATH_LABEL_SUFFIX + label));
                    //noinspection unchecked
                    TraversalHelper.insertAfterStep(identityStep, currentStep, this.traversal);
                }
                if (this.traversal.getSteps().contains(currentStep)) {
                    this.traversal.removeStep(currentStep);
                }
                iterator.remove();
                countToGoPrevious--;
            } else if ((nullKey = isNullStep(currentStep)) != null) {
                this.currentReplacedStep.addHasContainer(new HasContainer(nullKey, new P<>(Existence.NULL, null)));
                if (!currentStep.getLabels().isEmpty()) {
                    final IdentityStep identityStep = new IdentityStep<>(this.traversal);
                    currentStep.getLabels().forEach(label -> this.currentReplacedStep.addLabel(pathCount + BaseStrategy.PATH_LABEL_SUFFIX + label));
                    //noinspection unchecked
                    TraversalHelper.insertAfterStep(identityStep, currentStep, this.traversal);
                }
                if (this.traversal.getSteps().contains(currentStep)) {
                    this.traversal.removeStep(currentStep);
                }
                iterator.remove();
                countToGoPrevious--;
            } else if (currentStep instanceof IdentityStep) {
                // do nothing
            } else {
                for (int i = 0; i < countToGoPrevious; i++) {
                    iterator.previous();
                }
                break;
            }
        }
    }

    private List<HasContainer> isForSqlgSchema(ReplacedStep<?, ?> currentReplacedStep, List<HasContainer> hasContainers) {
        for (HasContainer hasContainer : hasContainers) {
            if (hasContainer.getKey().equals(TopologyStrategy.TOPOLOGY_SELECTION_SQLG_SCHEMA)) {
                currentReplacedStep.markForSqlgSchema();
                return Collections.singletonList(hasContainer);
            }
        }
        return Collections.emptyList();
    }

    private List<HasContainer> isForGuiSchema(ReplacedStep<?, ?> currentReplacedStep, List<HasContainer> hasContainers) {
        for (HasContainer hasContainer : hasContainers) {
            if (hasContainer.getKey().equals(TopologyStrategy.TOPOLOGY_SELECTION_GLOBAL_UNIQUE_INDEX)) {
                currentReplacedStep.markForGuiSchema();
                return Collections.singletonList(hasContainer);
            }
        }
        return Collections.emptyList();
    }

    /**
     * if this is a has(property) step, returns the property key, otherwise returns null
     *
     * @param currentStep the step
     * @return the property which should be not null
     */
    private String isNotNullStep(Step<?, ?> currentStep) {
        if (currentStep instanceof TraversalFilterStep<?>) {
            TraversalFilterStep<?> tfs = (TraversalFilterStep<?>) currentStep;
            List<?> c = tfs.getLocalChildren();
            if (c != null && c.size() == 1) {
                Traversal.Admin<?, ?> a = (Traversal.Admin<?, ?>) c.iterator().next();
                Step<?, ?> s = a.getEndStep();
                if (a.getSteps().size() == 1 && s instanceof PropertiesStep<?>) {
                    PropertiesStep<?> ps = (PropertiesStep<?>) s;
                    String[] keys = ps.getPropertyKeys();
                    if (keys != null && keys.length == 1) {
                        return keys[0];
                    }
                }
            }
        }
        return null;
    }

    /**
     * if this is a hasNot(property) step, returns the property key, otherwise returns null
     *
     * @param currentStep the step
     * @return the property which should be not null
     */
    private String isNullStep(Step<?, ?> currentStep) {
        if (currentStep instanceof NotStep<?>) {
            NotStep<?> tfs = (NotStep<?>) currentStep;
            List<?> c = tfs.getLocalChildren();
            if (c != null && c.size() == 1) {
                Traversal.Admin<?, ?> a = (Traversal.Admin<?, ?>) c.iterator().next();
                Step<?, ?> s = a.getEndStep();
                if (a.getSteps().size() == 1 && s instanceof PropertiesStep<?>) {
                    PropertiesStep<?> ps = (PropertiesStep<?>) s;
                    String[] keys = ps.getPropertyKeys();
                    if (keys != null && keys.length == 1) {
                        return keys[0];
                    }
                }
            }
        }
        return null;
    }

    void handleOrderGlobalSteps(ListIterator<Step<?, ?>> iterator, MutableInt pathCount) {
        //Collect the OrderGlobalSteps
        while (iterator.hasNext()) {
            Step<?, ?> step = iterator.next();
            if (step instanceof OrderGlobalStep) {
                if (optimizableOrderGlobalStep((OrderGlobalStep) step)) {
                    //add the label if any
                    for (String label : step.getLabels()) {
                        this.currentReplacedStep.addLabel(pathCount.getValue() + BaseStrategy.PATH_LABEL_SUFFIX + label);
                    }
                    //The step might not be here. For instance if it was nested in a chooseStep where the chooseStep logic already removed the step.
                    if (this.traversal.getSteps().contains(step)) {
                        this.traversal.removeStep(step);
                    }
                    iterator.previous();
                    Step previousStep = iterator.previous();
                    if (previousStep instanceof SelectOneStep) {
                        SelectOneStep selectOneStep = (SelectOneStep) previousStep;
                        String key = (String) selectOneStep.getScopeKeys().iterator().next();
                        this.currentReplacedStep.getSqlgComparatorHolder().setPrecedingSelectOneLabel(key);
                        @SuppressWarnings("unchecked")
                        List<Pair<Traversal.Admin<?, ?>, Comparator<?>>> comparators = convertComparatorPair(((OrderGlobalStep) step).getComparators());
                        //get the step for the label
                        Optional<ReplacedStep<?, ?>> labeledReplacedStep = this.sqlgStep.getReplacedSteps().stream().filter(
                                r -> {
                                    //Take the first
                                    if (!r.getLabels().isEmpty()) {
                                        String label = r.getLabels().iterator().next();
                                        String stepLabel = SqlgUtil.originalLabel(label);
                                        return stepLabel.equals(key);
                                    } else {
                                        return false;
                                    }
                                }
                        ).findAny();
                        Preconditions.checkState(labeledReplacedStep.isPresent());
                        ReplacedStep<?, ?> replacedStep = labeledReplacedStep.get();
                        replacedStep.getSqlgComparatorHolder().setComparators(comparators);
                        //add a label if the step does not yet have one and is not a leaf node
                        if (replacedStep.getLabels().isEmpty()) {
                            replacedStep.addLabel(pathCount.getValue() + BaseStrategy.PATH_LABEL_SUFFIX + BaseStrategy.SQLG_PATH_ORDER_RANGE_LABEL);
                        }
                    } else if (previousStep instanceof OptionalStep) {
                        throw new RuntimeException("not yet implemented");
//                    } else if (previousStep instanceof ChooseStep) {
//                        //The order applies to the current replaced step and the previous ChooseStep
//                        @SuppressWarnings("unchecked")
//                        List<Pair<Traversal.Admin<?, ?>, Comparator<?>>> comparators = convertComparatorPair(((OrderGlobalStep) step).getComparators());
//                        this.currentReplacedStep.getSqlgComparatorHolder().setComparators(comparators);
//                        //add a label if the step does not yet have one and is not a leaf node
//                        if (this.currentReplacedStep.getLabels().isEmpty()) {
//                            this.currentReplacedStep.addLabel(pathCount.getValue() + BaseStrategy.PATH_LABEL_SUFFIX + BaseStrategy.SQLG_PATH_ORDER_RANGE_LABEL);
//                        }
                    } else {
                        @SuppressWarnings("unchecked")
                        List<Pair<Traversal.Admin<?, ?>, Comparator<?>>> comparators = convertComparatorPair(((OrderGlobalStep) step).getComparators());
                        this.currentReplacedStep.getSqlgComparatorHolder().setComparators(comparators);
                        //add a label if the step does not yet have one and is not a leaf node
                        if (this.currentReplacedStep.getLabels().isEmpty()) {
                            this.currentReplacedStep.addLabel(pathCount.getValue() + BaseStrategy.PATH_LABEL_SUFFIX + BaseStrategy.SQLG_PATH_ORDER_RANGE_LABEL);
                        }
                    }
                    iterator.next();
                    iterator.next();
                } else {
                    return;
                }
            } else {
                //break on the first step that is not a OrderGlobalStep
                iterator.previous();
                break;
            }
        }
    }

    void handleConnectiveSteps(ListIterator<Step<?, ?>> iterator) {
        //Collect the hasSteps
        int countToGoPrevious = 0;
        while (iterator.hasNext()) {
            Step<?, ?> currentStep = iterator.next();
            countToGoPrevious++;
            if (currentStep instanceof ConnectiveStep) {
                Optional<AndOrHasContainer> outerAndOrHasContainer = handleConnectiveStepInternal((ConnectiveStep) currentStep);
                if (outerAndOrHasContainer.isPresent()) {
                    this.currentReplacedStep.addAndOrHasContainer(outerAndOrHasContainer.get());
                    this.traversal.removeStep(currentStep);
                    iterator.remove();
                    countToGoPrevious--;
                }
            } else if (currentStep instanceof IdentityStep) {
                // do nothing
            } else {
                for (int i = 0; i < countToGoPrevious; i++) {
                    iterator.previous();
                }
                break;
            }
        }
    }

    private Optional<AndOrHasContainer> handleConnectiveStepInternal(ConnectiveStep connectiveStep) {
        AndOrHasContainer.TYPE type = AndOrHasContainer.TYPE.from(connectiveStep);
        AndOrHasContainer outerAndOrHasContainer = new AndOrHasContainer(type);
        @SuppressWarnings("unchecked")
        List<Traversal.Admin<?, ?>> localTraversals = connectiveStep.getLocalChildren();
        for (Traversal.Admin<?, ?> localTraversal : localTraversals) {
            if (!TraversalHelper.hasAllStepsOfClass(localTraversal, HasStep.class, ConnectiveStep.class, TraversalFilterStep.class, NotStep.class)) {
                return Optional.empty();
            }
            AndOrHasContainer andOrHasContainer = new AndOrHasContainer(AndOrHasContainer.TYPE.NONE);
            outerAndOrHasContainer.addAndOrHasContainer(andOrHasContainer);
            for (Step<?, ?> step : localTraversal.getSteps()) {
                if (step instanceof HasStep) {
                    HasStep<?> hasStep = (HasStep) step;
                    for (HasContainer hasContainer : hasStep.getHasContainers()) {
                        boolean hasContainerKeyNotIdOrLabel = hasContainerKeyNotIdOrLabel(hasContainer);
                        if (hasContainerKeyNotIdOrLabel && SUPPORTED_BI_PREDICATE.contains(hasContainer.getBiPredicate())) {
                            andOrHasContainer.addHasContainer(hasContainer);
                        } else if (hasContainerKeyNotIdOrLabel && hasContainer.getPredicate() instanceof AndP) {
                            AndP<?> andP = (AndP) hasContainer.getPredicate();
                            List<? extends P<?>> predicates = andP.getPredicates();
                            if (predicates.size() == 2) {
                                if (predicates.get(0).getBiPredicate() == Compare.gte && predicates.get(1).getBiPredicate() == Compare.lt) {
                                    andOrHasContainer.addHasContainer(hasContainer);
                                } else if (predicates.get(0).getBiPredicate() == Compare.gt && predicates.get(1).getBiPredicate() == Compare.lt) {
                                    andOrHasContainer.addHasContainer(hasContainer);
                                }
                            }
                        } else if (hasContainerKeyNotIdOrLabel && hasContainer.getPredicate() instanceof OrP) {
                            OrP<?> orP = (OrP) hasContainer.getPredicate();
                            List<? extends P<?>> predicates = orP.getPredicates();
                            if (predicates.size() == 2) {
                                if (predicates.get(0).getBiPredicate() == Compare.lt && predicates.get(1).getBiPredicate() == Compare.gt) {
                                    andOrHasContainer.addHasContainer(hasContainer);
                                }
                            }
                        } else if (hasContainerKeyNotIdOrLabel && hasContainer.getBiPredicate() instanceof Text ||
                                hasContainer.getBiPredicate() instanceof FullText) {
                            andOrHasContainer.addHasContainer(hasContainer);
                        } else {
                            return Optional.empty();
                        }
                    }
                } else if (step instanceof TraversalFilterStep) {
                    String notNullKey = isNotNullStep(step);
                    if (notNullKey != null) {
                        andOrHasContainer.addHasContainer(new HasContainer(notNullKey, new P<>(Existence.NOTNULL, null)));
                    } else {
                        return Optional.empty();
                    }
                } else if (step instanceof NotStep) {
                    String nullKey = isNullStep(step);
                    if (nullKey != null) {
                        andOrHasContainer.addHasContainer(new HasContainer(nullKey, new P<>(Existence.NULL, null)));
                    } else {
                        return Optional.empty();
                    }
                } else {
                    ConnectiveStep connectiveStepLocalChild = (ConnectiveStep) step;
                    Optional<AndOrHasContainer> result = handleConnectiveStepInternal(connectiveStepLocalChild);
                    if (result.isPresent()) {
                        andOrHasContainer.addAndOrHasContainer(result.get());
                    } else {
                        return Optional.empty();
                    }
                }
            }
        }
        return Optional.of(outerAndOrHasContainer);
    }

    private boolean optimizableOrderGlobalStep(OrderGlobalStep step) {
        @SuppressWarnings("unchecked")
        List<Pair<Traversal.Admin<?, ?>, Comparator<?>>> comparators = convertComparatorPair(step.getComparators());
        for (Pair<Traversal.Admin<?, ?>, Comparator<?>> comparator : comparators) {
            Traversal.Admin<?, ?> defaultGraphTraversal = comparator.getLeft();
            List<CountGlobalStep> countGlobalSteps = TraversalHelper.getStepsOfAssignableClassRecursively(CountGlobalStep.class, defaultGraphTraversal);
            if (!countGlobalSteps.isEmpty()) {
                return false;
            }
            List<LambdaMapStep> lambdaMapSteps = TraversalHelper.getStepsOfAssignableClassRecursively(LambdaMapStep.class, defaultGraphTraversal);
            if (!lambdaMapSteps.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private List<Pair<Traversal.Admin<?, ?>, Comparator<?>>> convertComparatorPair(List<org.javatuples.Pair<Traversal.Admin<?, ?>, Comparator<?>>> comparators) {
        List<Pair<Traversal.Admin<?, ?>, Comparator<?>>> result = new ArrayList<>();
        for (org.javatuples.Pair<Traversal.Admin<?, ?>, Comparator<?>> comparator : comparators) {
            result.add(Pair.of(comparator.getValue0(), comparator.getValue1()));
        }
        return result;
    }

    void handleRangeGlobalSteps(ListIterator<Step<?, ?>> iterator, MutableInt pathCount) {
        //Collect the OrderGlobalSteps
        //noinspection LoopStatementThatDoesntLoop
        while (iterator.hasNext()) {
            Step<?, ?> step = iterator.next();
            if (step instanceof RangeGlobalStep) {
                //add the label if any
                for (String label : step.getLabels()) {
                    this.currentReplacedStep.addLabel(pathCount.getValue() + BaseStrategy.PATH_LABEL_SUFFIX + label);
                }
                //The step might not be here. For instance if it was nested in a chooseStep where the chooseStep logic already removed the step.
                if (this.traversal.getSteps().contains(step)) {
                    this.traversal.removeStep(step);
                }
                RangeGlobalStep<?> rgs = (RangeGlobalStep<?>) step;
                long high = rgs.getHighRange();
                if (high == -1) {
                    //skip step
                    this.currentReplacedStep.setSqlgRangeHolder(SqlgRangeHolder.from(rgs.getLowRange()));
                } else {
                    this.currentReplacedStep.setSqlgRangeHolder(SqlgRangeHolder.from(Range.between(rgs.getLowRange(), high)));
                }
                //add a label if the step does not yet have one and is not a leaf node
                if (this.currentReplacedStep.getLabels().isEmpty()) {
                    this.currentReplacedStep.addLabel(pathCount.getValue() + BaseStrategy.PATH_LABEL_SUFFIX + BaseStrategy.SQLG_PATH_ORDER_RANGE_LABEL);
                }
                this.reset = true;
                break;
            } else {
                //break on the first step that is not a RangeGlobalStep
                iterator.previous();
                break;
            }
        }
    }

    static boolean precedesPathOrTreeStep(Traversal.Admin<?, ?> traversal) {
        if (traversal.getParent() != null && traversal.getParent() instanceof SqlgLocalStepBarrier) {
            SqlgLocalStepBarrier sqlgLocalStepBarrier = (SqlgLocalStepBarrier) traversal.getParent();
            if (precedesPathOrTreeStep(sqlgLocalStepBarrier.getTraversal())) {
                return true;
            }
        }
        Predicate<Step> p = s -> s.getClass().equals(PathStep.class) ||
                s.getClass().equals(TreeStep.class) ||
                s.getClass().equals(TreeSideEffectStep.class) ||
                s.getClass().equals(PathFilterStep.class) ||
                s.getClass().equals(EdgeOtherVertexStep.class);
        return SqlgTraversalUtil.anyStepRecursively(p, traversal);
    }

    void addHasContainerForIds(SqlgGraphStep sqlgGraphStep) {
        HasContainer idHasContainer = new HasContainer(T.id.getAccessor(), P.within(sqlgGraphStep.getIds()));
        this.currentReplacedStep.addIdHasContainer(idHasContainer);
        sqlgGraphStep.clearIds();
    }

    private boolean isNotWithMultipleColumnValue(HasContainerHolder currentStep) {
        for (HasContainer h : currentStep.getHasContainers()) {
            P<?> predicate = h.getPredicate();
            //noinspection unchecked
            if (predicate.getValue() instanceof ZonedDateTime ||
                    predicate.getValue() instanceof Period ||
                    predicate.getValue() instanceof Duration ||
                    (predicate.getValue() instanceof List && containsWithMultipleColumnValue((List<Object>) predicate.getValue())) ||
                    (predicate instanceof ConnectiveP && isConnectivePWithMultipleColumnValue((ConnectiveP) h.getPredicate()))) {


                return false;
            }

        }
        return true;
    }

    private boolean hasContainerKeyNotIdOrLabel(HasContainer hasContainer) {
        return !(hasContainer.getKey().equals(TopologyStrategy.TOPOLOGY_SELECTION_SQLG_SCHEMA) || hasContainer.getKey().equals(TopologyStrategy.TOPOLOGY_SELECTION_GLOBAL_UNIQUE_INDEX) ||
                hasContainer.getKey().equals(T.id.getAccessor()) || (hasContainer.getKey().equals(T.label.getAccessor())));
    }

    private List<HasContainer> optimizeIdHas(ReplacedStep<?, ?> replacedStep, List<HasContainer> hasContainers) {
        List<HasContainer> result = new ArrayList<>();
        for (HasContainer hasContainer : hasContainers) {
            if (hasContainer.getKey().equals(T.id.getAccessor()) && SUPPORTED_ID_BI_PREDICATE.contains(hasContainer.getBiPredicate())) {
                replacedStep.addIdHasContainer(hasContainer);
                result.add(hasContainer);
            }
        }
        return result;
    }

    private List<HasContainer> optimizeLabelHas(ReplacedStep<?, ?> replacedStep, List<HasContainer> hasContainers) {
        List<HasContainer> result = new ArrayList<>();
        for (HasContainer hasContainer : hasContainers) {
            if (hasContainer.getKey().equals(T.label.getAccessor()) && SUPPORTED_LABEL_BI_PREDICATE.contains(hasContainer.getBiPredicate())) {
                replacedStep.addLabelHasContainer(hasContainer);
                result.add(hasContainer);
            }
        }
        return result;
    }

    private List<HasContainer> optimizeHas(ReplacedStep<?, ?> replacedStep, List<HasContainer> hasContainers) {
        List<HasContainer> result = new ArrayList<>();
        for (HasContainer hasContainer : hasContainers) {
            if (hasContainerKeyNotIdOrLabel(hasContainer) && SUPPORTED_BI_PREDICATE.contains(hasContainer.getBiPredicate())) {
                replacedStep.addHasContainer(hasContainer);
                result.add(hasContainer);
            }
        }
        return result;
    }

    private List<HasContainer> optimizeWithInOut(ReplacedStep<?, ?> replacedStep, List<HasContainer> hasContainers) {
        List<HasContainer> result = new ArrayList<>();
        for (HasContainer hasContainer : hasContainers) {
            if (hasContainerKeyNotIdOrLabel(hasContainer) && (hasContainer.getBiPredicate() == Contains.without || hasContainer.getBiPredicate() == Contains.within)) {
                replacedStep.addHasContainer(hasContainer);
                result.add(hasContainer);
            }
        }
        return result;
    }


    private List<HasContainer> optimizeBetween(ReplacedStep<?, ?> replacedStep, List<HasContainer> hasContainers) {
        List<HasContainer> result = new ArrayList<>();
        for (HasContainer hasContainer : hasContainers) {
            if (hasContainerKeyNotIdOrLabel(hasContainer) && hasContainer.getPredicate() instanceof AndP) {
                AndP<?> andP = (AndP) hasContainer.getPredicate();
                List<? extends P<?>> predicates = andP.getPredicates();
                if (predicates.size() == 2) {
                    if (predicates.get(0).getBiPredicate() == Compare.gte && predicates.get(1).getBiPredicate() == Compare.lt) {
                        replacedStep.addHasContainer(hasContainer);
                        result.add(hasContainer);
                    }
                }
            }
        }
        return result;
    }

    private List<HasContainer> optimizeInside(ReplacedStep<?, ?> replacedStep, List<HasContainer> hasContainers) {
        List<HasContainer> result = new ArrayList<>();
        for (HasContainer hasContainer : hasContainers) {
            if (hasContainerKeyNotIdOrLabel(hasContainer) && hasContainer.getPredicate() instanceof AndP) {
                AndP<?> andP = (AndP) hasContainer.getPredicate();
                List<? extends P<?>> predicates = andP.getPredicates();
                if (predicates.size() == 2) {
                    if (predicates.get(0).getBiPredicate() == Compare.gt && predicates.get(1).getBiPredicate() == Compare.lt) {
                        replacedStep.addHasContainer(hasContainer);
                        result.add(hasContainer);
                    }
                }
            }
        }
        return result;
    }

    private List<HasContainer> optimizeOutside(ReplacedStep<?, ?> replacedStep, List<HasContainer> hasContainers) {
        List<HasContainer> result = new ArrayList<>();
        for (HasContainer hasContainer : hasContainers) {
            if (hasContainerKeyNotIdOrLabel(hasContainer) && hasContainer.getPredicate() instanceof OrP) {
                OrP<?> orP = (OrP) hasContainer.getPredicate();
                List<? extends P<?>> predicates = orP.getPredicates();
                if (predicates.size() == 2) {
                    if (predicates.get(0).getBiPredicate() == Compare.lt && predicates.get(1).getBiPredicate() == Compare.gt) {
                        replacedStep.addHasContainer(hasContainer);
                        result.add(hasContainer);
                    }
                }
            }
        }
        return result;
    }

    private List<HasContainer> optimizeTextContains(ReplacedStep<?, ?> replacedStep, List<HasContainer> hasContainers) {
        List<HasContainer> result = new ArrayList<>();
        for (HasContainer hasContainer : hasContainers) {
            if (hasContainerKeyNotIdOrLabel(hasContainer) && hasContainer.getBiPredicate() instanceof Text ||
                    hasContainer.getBiPredicate() instanceof FullText
            ) {
                replacedStep.addHasContainer(hasContainer);
                result.add(hasContainer);
            }
        }
        return result;
    }

    private boolean containsWithMultipleColumnValue(List<Object> values) {
        for (Object value : values) {
            if (value instanceof ZonedDateTime ||
                    value instanceof Period ||
                    value instanceof Duration) {
                return true;
            }
        }
        return false;
    }

    private boolean isConnectivePWithMultipleColumnValue(ConnectiveP connectiveP) {
        @SuppressWarnings("unchecked")
        List<P<?>> ps = connectiveP.getPredicates();
        for (P<?> predicate : ps) {
            if (predicate.getValue() instanceof ZonedDateTime ||
                    predicate.getValue() instanceof Period ||
                    predicate.getValue() instanceof Duration) {
                return true;
            }
        }
        return false;
    }

    boolean canNotBeOptimized() {
        @SuppressWarnings("unchecked") final List<Step<?, ?>> steps = new ArrayList(this.traversal.asAdmin().getSteps());
        final ListIterator<Step<?, ?>> stepIterator = steps.listIterator();
        List<Step<?, ?>> toCome = steps.subList(stepIterator.nextIndex(), steps.size());
        return toCome.stream().anyMatch(s ->
                s.getClass().equals(Order.class) ||
                        s.getClass().equals(LambdaCollectingBarrierStep.class) ||
                        s.getClass().equals(SackValueStep.class)
        );
    }

    boolean unoptimizableOptionalStep(OptionalStep<?> optionalStep) {
        if (!this.optionalStepStack.isEmpty()) {
            return true;
        }

        Traversal.Admin<?, ?> optionalTraversal = optionalStep.getLocalChildren().get(0);
        List<Step> optionalTraversalSteps = new ArrayList<>(optionalTraversal.getSteps());

        //Can not optimize if the traversal contains a RangeGlobalStep
        List<Step> rangeGlobalSteps = optionalTraversalSteps.stream().filter(p -> p.getClass().equals(RangeGlobalStep.class)).collect(Collectors.toList());
        if (rangeGlobalSteps.size() > 1) {
            return true;
        }
        if (rangeGlobalSteps.size() > 0) {
            Step rangeGlobalStep = rangeGlobalSteps.get(0);
            //Only if the rangeGlobalStep is the last step can it be optimized
            if (optionalTraversalSteps.get(optionalTraversalSteps.size() - 1) != rangeGlobalStep) {
                return true;
            }
        }

        ListIterator<Step> stepListIterator = optionalTraversalSteps.listIterator();
        while (stepListIterator.hasNext()) {
            Step internalOptionalStep = stepListIterator.next();
            if (!(internalOptionalStep instanceof VertexStep || internalOptionalStep instanceof EdgeVertexStep ||
                    internalOptionalStep instanceof EdgeOtherVertexStep || internalOptionalStep instanceof ComputerAwareStep.EndStep ||
                    internalOptionalStep instanceof OptionalStep || internalOptionalStep instanceof HasStep ||
                    internalOptionalStep instanceof OrderGlobalStep || internalOptionalStep instanceof RangeGlobalStep)) {
                return true;
            }
        }

        List<Step> optionalSteps = optionalTraversalSteps.stream().filter(p -> p.getClass().equals(OptionalStep.class)).collect(Collectors.toList());
        for (Step step : optionalSteps) {
            if (unoptimizableOptionalStep((OptionalStep<?>) step)) {
                return true;
            }
        }
        return false;
    }

    boolean unoptimizableChooseStep(ChooseStep<?, ?, ?> chooseStep) {
        if (!this.chooseStepStack.isEmpty()) {
            return true;
        }
        List<? extends Traversal.Admin<?, ?>> traversalAdmins = chooseStep.getGlobalChildren();
        if (traversalAdmins.size() != 2) {
            return true;
        }
        Traversal.Admin<?, ?> predicate = chooseStep.getLocalChildren().get(0);
        List<Step> predicateSteps = new ArrayList<>(predicate.getSteps());
        if (!(predicate.getSteps().get(predicate.getSteps().size() - 1) instanceof HasNextStep)) {
            return true;
        }
        //Remove the HasNextStep
        predicateSteps.remove(predicate.getSteps().size() - 1);

        Traversal.Admin<?, ?> globalChildOne = chooseStep.getGlobalChildren().get(0);
        List<Step> globalChildOneSteps = new ArrayList<>(globalChildOne.getSteps());
        globalChildOneSteps.remove(globalChildOneSteps.size() - 1);

        Traversal.Admin<?, ?> globalChildTwo = chooseStep.getGlobalChildren().get(1);
        List<Step> globalChildTwoSteps = new ArrayList<>(globalChildTwo.getSteps());
        globalChildTwoSteps.remove(globalChildTwoSteps.size() - 1);

        boolean hasIdentity = globalChildOne.getSteps().stream().anyMatch(s -> s instanceof IdentityStep);
        if (!hasIdentity) {
            hasIdentity = globalChildTwo.getSteps().stream().anyMatch(s -> s instanceof IdentityStep);
            if (hasIdentity) {
                //Identity found check predicate and true are the same
                if (!predicateSteps.equals(globalChildOneSteps)) {
                    return true;
                }
            } else {
                //Identity not found
                return true;
            }
        } else {
            //Identity found check predicate and true are the same
            if (!predicateSteps.equals(globalChildTwoSteps)) {
                return true;
            }
        }
        List<Step> localSteps = predicateSteps.stream().filter(p -> p.getClass().equals(LocalStep.class)).collect(Collectors.toList());
        if (!localSteps.isEmpty()) {
            return true;
        }

        List<Step> rangeGlobalSteps = predicateSteps.stream().filter(p -> p.getClass().equals(RangeGlobalStep.class)).collect(Collectors.toList());
        if (rangeGlobalSteps.size() > 1) {
            return true;
        }
        if (rangeGlobalSteps.size() > 0) {
            Step rangeGlobalStep = rangeGlobalSteps.get(0);
            //Only if the rangeGlobalStep is the last step can it be optimized
            if (predicateSteps.get(predicateSteps.size() - 1) != rangeGlobalStep) {
                return true;
            }
        }

        Traversal.Admin<?, ?> trueTraversal;
        if (globalChildOne.getSteps().stream().anyMatch(s -> s instanceof IdentityStep<?>)) {
            trueTraversal = globalChildTwo;
        } else {
            trueTraversal = globalChildOne;
        }
        @SuppressWarnings("unchecked")
        List<Step<?, ?>> trueTraversalSteps = new ArrayList(trueTraversal.getSteps());
        for (Step<?, ?> internalChooseStep : trueTraversalSteps) {
            if (!(internalChooseStep instanceof VertexStep || internalChooseStep instanceof EdgeVertexStep ||
                    internalChooseStep instanceof EdgeOtherVertexStep || internalChooseStep instanceof ComputerAwareStep.EndStep ||
                    internalChooseStep instanceof ChooseStep || internalChooseStep instanceof HasStep ||
                    internalChooseStep instanceof OrderGlobalStep || internalChooseStep instanceof RangeGlobalStep)) {
                return true;
            }
        }

        List<Step> chooseSteps = globalChildTwo.getSteps().stream().filter(p -> p.getClass().equals(ChooseStep.class)).collect(Collectors.toList());
        for (Step step : chooseSteps) {
            if (unoptimizableChooseStep((ChooseStep<?, ?, ?>) step)) {
                return true;
            }
        }
        return false;
    }

    private boolean unoptimizableRepeatStep() {
        List<RepeatStep> repeatSteps = TraversalHelper.getStepsOfAssignableClassRecursively(RepeatStep.class, this.traversal);
        boolean hasUntil = repeatSteps.stream().filter(s -> s.getClass().equals(RepeatStep.class)).allMatch(repeatStep -> repeatStep.getUntilTraversal() != null);
        boolean hasUnoptimizableUntil = false;
        if (hasUntil) {
            hasUnoptimizableUntil = repeatSteps.stream().filter(s -> s.getClass().equals(RepeatStep.class)).noneMatch(repeatStep -> repeatStep.getUntilTraversal() instanceof LoopTraversal);
        }
        boolean badRepeat = !hasUntil || hasUnoptimizableUntil;
        //Check if the repeat step only contains optimizable steps
        if (!badRepeat) {
            List<Step> collectedRepeatInternalSteps = new ArrayList<>();
            for (Step step : repeatSteps) {
                RepeatStep repeatStep = (RepeatStep) step;
                @SuppressWarnings("unchecked")
                List<Traversal.Admin> repeatTraversals = repeatStep.<Traversal.Admin>getGlobalChildren();
                Traversal.Admin admin = repeatTraversals.get(0);
                @SuppressWarnings("unchecked")
                List<Step> repeatInternalSteps = admin.getSteps();
                collectedRepeatInternalSteps.addAll(repeatInternalSteps);
            }
            return !collectedRepeatInternalSteps.stream().filter(s -> !s.getClass().equals(RepeatStep.RepeatEndStep.class))
                    .allMatch((s) -> isReplaceableStep(s.getClass()));
        } else {
            return true;
        }
    }

    private List<String> getRestrictedProperties(Step<?, ?> step) {
        List<String> ret = null;
        if (step instanceof PropertiesStep<?>) {
            PropertiesStep<?> ps = (PropertiesStep<?>) step;
            ret = Arrays.asList(ps.getPropertyKeys());
        } else if (step instanceof PropertyMapStep<?, ?>) {
            PropertyMapStep<?, ?> pms = (PropertyMapStep<?, ?>) step;
            ret = Arrays.asList(pms.getPropertyKeys());
        }
        // if no property keys are provided, all properties should be returned
        if (ret != null && ret.isEmpty()) {
            ret = null;
        }

        return ret;
    }
}
