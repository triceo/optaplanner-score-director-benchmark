package ai.timefold.solver.jmh.scoredirector.problems;

import java.io.File;
import java.util.Objects;

import ai.timefold.solver.core.api.score.stream.ConstraintStreamImplType;
import ai.timefold.solver.core.config.score.director.ScoreDirectorFactoryConfig;
import ai.timefold.solver.core.impl.domain.solution.descriptor.SolutionDescriptor;
import ai.timefold.solver.examples.examination.domain.Exam;
import ai.timefold.solver.examples.examination.domain.Examination;
import ai.timefold.solver.examples.examination.domain.FollowingExam;
import ai.timefold.solver.examples.examination.domain.LeadingExam;
import ai.timefold.solver.examples.examination.persistence.ExaminationSolutionFileIO;
import ai.timefold.solver.examples.examination.score.ExaminationConstraintProvider;
import ai.timefold.solver.jmh.scoredirector.Example;
import ai.timefold.solver.jmh.scoredirector.ScoreDirectorType;
import ai.timefold.solver.persistence.common.api.domain.solution.SolutionFileIO;

public final class ExaminationProblem extends AbstractProblem<Examination> {

    public ExaminationProblem(ScoreDirectorType scoreDirectorType) {
        super(Example.EXAMINATION, scoreDirectorType);
    }

    @Override
    protected ScoreDirectorFactoryConfig buildScoreDirectorFactoryConfig(ScoreDirectorType scoreDirectorType) {
        var scoreDirectorFactoryConfig = buildInitialScoreDirectorFactoryConfig();
        var nonNullScoreDirectorType = Objects.requireNonNull(scoreDirectorType);
        if (nonNullScoreDirectorType == ScoreDirectorType.CONSTRAINT_STREAMS
                || nonNullScoreDirectorType == ScoreDirectorType.CONSTRAINT_STREAMS_JUSTIFIED) {
            return scoreDirectorFactoryConfig
                    .withConstraintProviderClass(ExaminationConstraintProvider.class)
                    .withConstraintStreamImplType(ConstraintStreamImplType.BAVET);
        }
        throw new UnsupportedOperationException("Score director: " + scoreDirectorType);
    }

    @Override
    protected SolutionDescriptor<Examination> buildSolutionDescriptor() {
        return SolutionDescriptor.buildSolutionDescriptor(Examination.class, Exam.class, LeadingExam.class,
                FollowingExam.class);
    }

    @Override
    protected Examination readOriginalSolution() {
        final SolutionFileIO<Examination> solutionFileIO = new ExaminationSolutionFileIO();
        return solutionFileIO.read(new File("data/examination-comp_set8.json"));
    }

}
