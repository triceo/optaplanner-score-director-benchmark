package org.optaplanner.sdb.problems;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Collectors;

import org.openjdk.jmh.infra.Blackhole;
import org.optaplanner.core.config.heuristic.selector.common.SelectionCacheType;
import org.optaplanner.core.config.heuristic.selector.common.SelectionOrder;
import org.optaplanner.core.config.heuristic.selector.entity.EntitySelectorConfig;
import org.optaplanner.core.config.heuristic.selector.move.MoveSelectorConfig;
import org.optaplanner.core.config.heuristic.selector.move.composite.UnionMoveSelectorConfig;
import org.optaplanner.core.config.heuristic.selector.move.generic.ChangeMoveSelectorConfig;
import org.optaplanner.core.config.heuristic.selector.value.ValueSelectorConfig;
import org.optaplanner.core.config.score.director.ScoreDirectorFactoryConfig;
import org.optaplanner.core.config.solver.EnvironmentMode;
import org.optaplanner.core.impl.domain.solution.descriptor.SolutionDescriptor;
import org.optaplanner.core.impl.heuristic.HeuristicConfigPolicy;
import org.optaplanner.core.impl.heuristic.move.Move;
import org.optaplanner.core.impl.heuristic.selector.move.MoveSelector;
import org.optaplanner.core.impl.heuristic.selector.move.MoveSelectorFactory;
import org.optaplanner.core.impl.localsearch.scope.LocalSearchPhaseScope;
import org.optaplanner.core.impl.localsearch.scope.LocalSearchStepScope;
import org.optaplanner.core.impl.score.director.InnerScoreDirector;
import org.optaplanner.core.impl.score.director.InnerScoreDirectorFactory;
import org.optaplanner.core.impl.solver.scope.SolverScope;
import org.optaplanner.sdb.Example;
import org.optaplanner.sdb.ScoreDirectorType;

abstract class AbstractProblem<Solution_> implements Problem {

    // Each fork starts from a different place.
    private static final int RANDOM_SEED = (int) Math.random() * 1_000;
    private static final double PROBABILITY_OF_UNDO = 0.9;

    private final InnerScoreDirectorFactory<Solution_, ?> scoreDirectorFactory;
    private final Solution_ originalSolution;
    private final MoveSelectorFactory<Solution_> moveSelectorFactory;

    private InnerScoreDirector<Solution_, ?> scoreDirector;
    private MoveSelector<Solution_> moveSelector;
    private Iterator<Move<Solution_>> moveIterator;
    private LocalSearchPhaseScope<Solution_> phaseScope;
    private LocalSearchStepScope<Solution_> stepScope;
    private Move<Solution_> move;
    private boolean willUndo = true;

    protected AbstractProblem(final Example example, final ScoreDirectorType scoreDirectorType) {
        final ScoreDirectorFactoryConfig scoreDirectorFactoryConfig =
                buildScoreDirectorFactoryConfig(Objects.requireNonNull(scoreDirectorType));
        scoreDirectorFactory =
                ScoreDirectorType.buildScoreDirectorFactory(scoreDirectorFactoryConfig, buildSolutionDescriptor());
        originalSolution = ProblemInitializer.getSolution(example, scoreDirectorFactory.getSolutionDescriptor(),
                this::buildScoreDirectorFactoryConfig, this::readOriginalSolution); // Expensive.
        moveSelectorFactory = buildMoveSelectorFactory(scoreDirectorFactory.getSolutionDescriptor());
    }

    abstract protected ScoreDirectorFactoryConfig buildScoreDirectorFactoryConfig(ScoreDirectorType scoreDirectorType);

    abstract protected SolutionDescriptor<Solution_> buildSolutionDescriptor();

    abstract protected Solution_ readOriginalSolution();

    protected MoveSelectorFactory<Solution_> buildMoveSelectorFactory(SolutionDescriptor<Solution_> solutionDescriptor) {
        // Create a union move selector over all entities and variables.
        // We go via move config, so we don't have to worry about differences between chained and non-chained problems.
        List<MoveSelectorConfig> moveSelectorConfigs = solutionDescriptor.getGenuineEntityDescriptors().stream()
                .flatMap(entityDescriptor -> {
                            EntitySelectorConfig entitySelectorConfig =
                                    new EntitySelectorConfig(entityDescriptor.getEntityClass());
                            return entityDescriptor.getGenuineVariableDescriptorList().stream()
                                    .map(variableDescriptor -> {
                                        String variableName = variableDescriptor.getVariableName();
                                        ValueSelectorConfig valueSelectorConfig = new ValueSelectorConfig(variableName);
                                        ChangeMoveSelectorConfig moveSelectorConfig = new ChangeMoveSelectorConfig();
                                        moveSelectorConfig.setEntitySelectorConfig(entitySelectorConfig);
                                        moveSelectorConfig.setValueSelectorConfig(valueSelectorConfig);
                                        return moveSelectorConfig;
                                    });
                        }
                ).collect(Collectors.toList());
        if (moveSelectorConfigs.size() == 1) {
            return MoveSelectorFactory.create(moveSelectorConfigs.get(0));
        } else {
            UnionMoveSelectorConfig unionMoveSelectorConfig = new UnionMoveSelectorConfig();
            unionMoveSelectorConfig.setMoveSelectorList(moveSelectorConfigs);
            return MoveSelectorFactory.create(unionMoveSelectorConfig);
        }
    }

    @Override
    public final void setupTrial() {
        // Prepare the move selector that will pick different move for each invocation.
        // Reproducible random selection without caching; we need the selection to never end.
        final HeuristicConfigPolicy<Solution_> policy = new HeuristicConfigPolicy.Builder<>(EnvironmentMode.REPRODUCIBLE,
                null, null, null, scoreDirectorFactory)
                .build();
        moveSelector = moveSelectorFactory.buildMoveSelector(policy, SelectionCacheType.JUST_IN_TIME,
                SelectionOrder.RANDOM);
    }

    @Override
    public final void setupIteration() {
        // We only care about incremental performance; therefore calculate the entire solution outside of invocation.
        scoreDirector = scoreDirectorFactory.buildScoreDirector(false, false);
        scoreDirector.setWorkingSolution(scoreDirector.cloneSolution(originalSolution)); // Use fresh solution again.
        scoreDirector.triggerVariableListeners();
        scoreDirector.calculateScore();
        // Prepare the lifecycle.
        SolverScope<Solution_> solverScope = new SolverScope<>();
        solverScope.setScoreDirector(scoreDirector);
        solverScope.setWorkingRandom(new Random(RANDOM_SEED)); // Each iteration in a fork starts from the same place.
        phaseScope = new LocalSearchPhaseScope<>(solverScope);
        moveSelector.solvingStarted(solverScope);
        moveSelector.phaseStarted(phaseScope);
    }

    @Override
    public final void setupInvocation() {
        if (stepScope == null) {
            stepScope = new LocalSearchStepScope<>(phaseScope);
            moveSelector.stepStarted(stepScope);
            moveIterator = moveSelector.iterator();
        }
        willUndo = Math.random() <= PROBABILITY_OF_UNDO;
        do { // Find the first doable move.
            move = moveIterator.next();
        } while (!move.isMoveDoable(scoreDirector));
    }

    /**
     * Designed to emulate the solver.
     * The solver typically tries all sorts of moves, calculates their score, and undoes them.
     * After a certain amount of such moves, it picks one move and that move is finally not undone.
     * So we do the same here, and the probability of a move being undone is defined by {@link #PROBABILITY_OF_UNDO}.
     *
     * <p>
     * We're benchmarking the actual operations inside the score director:
     *
     * <ul>
     *     <li>Speed of variable updates.</li>
     *     <li>Speed of score calculation on those updates.</li>
     * </ul>
     *
     * <p>
     * Unfortunately, we also benchmark a bit of the overhead of the move. Hopefully, that is not too much.
     * More importantly, it is a constant overhead and therefore should not affect the results.
     *
     * @param blackHole use to prevent byproducts from being optimized away
     * @return in order to prevent results from being optimized away
     */
    @Override
    public final Object runInvocation(Blackhole blackHole) {
        Move<Solution_> undo = move.doMove(scoreDirector);
        if (willUndo) { // The solver first calculates the score and then undoes it...
            blackHole.consume(scoreDirector.calculateScore());
            return undo.doMove(scoreDirector);
        } else { // ... except occasionally, it does not undo.
            blackHole.consume(undo);
            return scoreDirector.calculateScore();
        }
    }

    @Override
    public final void tearDownInvocation() {
        if (!willUndo) { // Move was not undone; this signifies the end of the step.
            endStep();
        }
    }

    private void endStep() {
        moveSelector.stepEnded(stepScope);
        stepScope = null;
    }

    @Override
    public final void tearDownIteration() {
        scoreDirector.close();
        if (stepScope != null) { // Clean up in case the last move was undone.
            endStep();
        }
    }

    @Override
    public final void teardownTrial() {
        // No need to do anything.
    }

}
