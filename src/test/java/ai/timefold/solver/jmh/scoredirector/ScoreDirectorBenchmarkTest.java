package ai.timefold.solver.jmh.scoredirector;

import java.util.stream.Stream;

import ai.timefold.solver.jmh.scoredirector.problems.Problem;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ScoreDirectorBenchmarkTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScoreDirectorBenchmarkTest.class);

    @ParameterizedTest
    @MethodSource("scoreDirectorTypeAndExampleProvider")
    void runTest(ScoreDirectorType scoreDirectorType, Example example) {
        Assumptions.assumeTrue(example.isSupportedOn(scoreDirectorType),
                "Example " + example + " not supported on " + scoreDirectorType + ".");
        LOGGER.info("Testing {} for {}.", scoreDirectorType, example);
        Assertions.assertDoesNotThrow(() -> {
            final Problem problem = example.create(scoreDirectorType);
            problem.setupTrial();
            problem.setupIteration();
            problem.setupInvocation();
            problem.runInvocation();
            problem.tearDownInvocation();
            problem.tearDownIteration();
            problem.teardownTrial();
        });
    }

    public static Stream<Arguments> scoreDirectorTypeAndExampleProvider() {
        return Stream.of(ScoreDirectorType.values())
                .flatMap(scoreDirectorType -> Stream.of(Example.values())
                        .map(example -> Arguments.arguments(scoreDirectorType, example)));
    }

}
