/*
 * Copyright (c) 2014, Oracle America, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  * Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 *  * Neither the name of Oracle nor the names of its contributors may be used
 *    to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
 * THE POSSIBILITY OF SUCH DAMAGE.
 */

package ai.timefold.solver.jmh.scoredirector;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;

import ai.timefold.solver.jmh.common.AbstractMain;

import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Main extends AbstractMain<Configuration> {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    public Main() {
        super("scoredirector");
    }

    @Override
    protected Configuration readConfiguration(InputStream inputStream) throws IOException {
        return Configuration.read(inputStream);
    }

    @Override
    protected Configuration getDefaultConfiguration() {
        return Configuration.getDefault();
    }

    public static void main(String[] args) throws RunnerException, IOException {
        var main = new Main();
        var configuration = main.readConfiguration();
        var options = main.getBaseJmhConfig(configuration);
        options = processBenchmark(options, configuration, ScoreDirectorType.CONSTRAINT_STREAMS);
        options = processBenchmark(options, configuration, ScoreDirectorType.CONSTRAINT_STREAMS_JUSTIFIED);
        options = processBenchmark(options, configuration, ScoreDirectorType.EASY);
        options = processBenchmark(options, configuration, ScoreDirectorType.INCREMENTAL);
        options = main.initAsyncProfiler(options);

        var runResults = new Runner(options.build()).run();
        main.convertJfrToFlameGraphs();

        var relativeScoreErrorThreshold = configuration.getRelativeScoreErrorThreshold();
        var thresholdForPrint = ((int) Math.round(relativeScoreErrorThreshold * 10_000)) / 100.0D;
        runResults.forEach(result -> {
            Result<?> primaryResult = result.getPrimaryResult();
            var score = primaryResult.getScore();
            var scoreError = primaryResult.getScoreError();
            var relativeScoreError = scoreError / score;

            var benchParams = result.getParams();
            var benchmarkName = benchParams.getBenchmark() + " " + benchParams.getParam("csExample");
            var relativeScoreErrorForPrint = ((int) Math.round(relativeScoreError * 10_000)) / 100.0D;
            if (relativeScoreError > relativeScoreErrorThreshold) {
                LOGGER.warn("Score error for '{}' is too high: ± {} % (threshold: ± {} %).", benchmarkName,
                        relativeScoreErrorForPrint, thresholdForPrint);
            } else if (relativeScoreError > (relativeScoreErrorThreshold * 0.9)) {
                LOGGER.info("Score error for '{}' approaching threshold: ± {} % (threshold: ± {} %).", benchmarkName,
                        relativeScoreErrorForPrint, thresholdForPrint);
            }
        });
    }

    private static ChainedOptionsBuilder processBenchmark(ChainedOptionsBuilder options, Configuration configuration,
            ScoreDirectorType scoreDirectorType) {
        var supportedExampleNames = getSupportedExampleNames(configuration, scoreDirectorType);
        if (supportedExampleNames.length > 0) {
            options = options.include(scoreDirectorType.getBenchmarkClass().getSimpleName())
                    .param(scoreDirectorType.getBenchmarkParamName(), supportedExampleNames);
        }
        return options;
    }

    private static String[] getSupportedExampleNames(Configuration configuration, ScoreDirectorType scoreDirectorType) {
        if (!configuration.getEnabledScoreDirectorTypes().contains(scoreDirectorType)) {
            LOGGER.warn("No examples enabled for score director type " + scoreDirectorType);
            return new String[0];
        }
        var examples = configuration.getEnabledExamples()
                .stream()
                .filter(example -> example.isSupportedOn(scoreDirectorType))
                .map(Enum::name)
                .toArray(String[]::new);
        LOGGER.info("Examples enabled for score director type {}: {}", scoreDirectorType, examples);
        return examples;
    }

}
