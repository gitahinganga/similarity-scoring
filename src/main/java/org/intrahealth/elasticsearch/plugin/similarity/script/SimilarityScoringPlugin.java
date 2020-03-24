/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.intrahealth.elasticsearch.plugin.similarity.script;

import org.apache.lucene.index.LeafReaderContext;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.ScriptPlugin;
import org.elasticsearch.script.ScoreScript;
import org.elasticsearch.script.ScoreScript.LeafFactory;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.ScriptEngine;
import org.elasticsearch.script.ScriptFactory;
import org.elasticsearch.search.lookup.SearchLookup;
import org.intrahealth.elasticsearch.plugin.similarity.MatcherService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Main plugin implementation and configuration.
 */
public class SimilarityScoringPlugin extends Plugin implements ScriptPlugin {

    /**
     * Returns a {@link ScriptEngine} instance.
     *
     * @param settings Node settings
     * @param contexts The contexts that {@link ScriptEngine#compile(String, String, ScriptContext, Map)} may be called with
     */
    @Override
    public ScriptEngine getScriptEngine(Settings settings, Collection<ScriptContext<?>> contexts) {
        return new SimilarityScriptEngine();
    }

    /**
     * Custom {@link ScriptEngine} implementation for string similarity.
     */
    private static class SimilarityScriptEngine implements ScriptEngine {

        /**
         * The language name used in the script APIs to refer to this scripting backend.
         */
        @Override
        public String getType() {
            return "similarity_scripts";
        }

        /**
         * Compiles the script.
         *
         * @param scriptName   the name of the script. {@code null} if it is anonymous (inline). For a stored script, its the
         *                     identifier.
         * @param scriptSource actual source of the script
         * @param context      the context this script will be used for
         * @param params       compile-time parameters (such as flags to the compiler)
         *
         * @return A compiled script of the FactoryType from {@link ScriptContext}
         */
        @Override
        public <FactoryType> FactoryType compile(
                String scriptName,
                String scriptSource,
                ScriptContext<FactoryType> context,
                Map<String, String> params
        ) {
            if (context.equals(ScoreScript.CONTEXT) == false) {
                throw new IllegalArgumentException(getType() + " scripts cannot be used for context [" + context.name + "]");
            }
            if ("string_similarity".equals(scriptSource)) {
                ScoreScript.Factory factory = new SimilarityFactory();
                return context.factoryClazz.cast(factory);
            }
            throw new IllegalArgumentException("Unknown script name " + scriptSource);
        }

        /**
         * Script {@link ScriptContext}s supported by this engine.
         */
        @Override
        public Set<ScriptContext<?>> getSupportedContexts() {
            return Set.of(ScoreScript.CONTEXT);
        }

    }

    /**
     * A factory to construct an instance of {@link SimilarityLeafFactory}.
     */
    private static class SimilarityFactory implements ScoreScript.Factory, ScriptFactory {

        /**
         * @return a new instance of {@link SimilarityLeafFactory}.
         */
        @Override
        public LeafFactory newFactory(Map<String, Object> params, SearchLookup lookup) {
            return new SimilarityLeafFactory(params, lookup);
        }

    }

    /**
     * A factory to construct new {@link ScoreScript} instances.
     */
    private static class SimilarityLeafFactory implements LeafFactory {

        private final MatcherService matcherService = new MatcherService();
        private Map<String, Object> params;
        private List<MatcherModel> matchers;
        private SearchLookup lookup;

        SimilarityLeafFactory(Map<String, Object> params, SearchLookup lookup) {
            if (params.containsKey("matchers") == false) {
                throw new IllegalArgumentException("Missing parameter [matchers]");
            }
            this.params = params;
            this.matchers = MatcherModelParser.parseMatcherModels(params);
            this.lookup = lookup;
        }

        @Override
        public boolean needs_score() {
            return false;
        }

        @Override
        public ScoreScript newInstance(LeafReaderContext ctx) throws IOException {
            double NOT_SCORED = 2;
            return new ScoreScript(params, lookup, ctx) {

                @Override
                public double execute(ExplanationHolder explanation) {
                    double totalScore = NOT_SCORED;
                    for (MatcherModel matcherModel : matchers) {
                        String value = String.valueOf(lookup.source().get(matcherModel.fieldName));
                        double score = matcherService.matchScore(matcherModel.matcherName, matcherModel.value, value);
                        if (score > matcherModel.high) {
                            score = matcherModel.high;
                        }
                        if (score < matcherModel.low) {
                            score = matcherModel.low;
                        }
                        totalScore = totalScore == NOT_SCORED ? score : combineScores(totalScore, score);
                    }
                    ;
                    return totalScore;
                }

                /**
                 * From: https://github.com/larsga/Duke/blob/master/duke-core/src/main/java/no/priv/garshol/duke/utils/Utils.java
                 * Combines two probabilities using Bayes' theorem. This is the
                 * approach known as "naive Bayes", very well explained here:
                 * http://www.paulgraham.com/naivebayes.html
                 */
                public double combineScores(double score1, double score2) {
                    return (score1 * score2) / ((score1 * score2) + ((1.0 - score1) * (1.0 - score2)));
                }

            };
        }

    }

    /**
     * Encapsulates a field with its value, preferred matcher and the high and low values to be used for scoring.
     */
    private static class MatcherModel {

        /**
         * The name of the field to be matched.
         */
        private String fieldName;

        /**
         * The value of the field to be matched.
         */
        private String value;

        /**
         * The name of the matcher to use for matching.
         */
        private String matcherName;

        /**
         * The score to assign a perfect match. Should be high, non-zero and between 0 and 1.
         */
        private double high;

        /**
         * The score to assign a perfect match. Should be low, non-zero and between 0 and 1.
         */
        private double low;

        /**
         * Constructs a new instance of a MatcherModel.
         */
        MatcherModel(String fieldName, Object value, String matcherName, double high, double low) {
            this.fieldName = fieldName;
            this.value = String.valueOf(value);
            this.matcherName = matcherName;
            this.high = high;
            this.low = low;
        }

    }

    /**
     * Converts each matcher entry from the script to a {@link MatcherModel}.
     */
    private static class MatcherModelParser {

        private static String FIELD = "field";
        private static String VALUE = "value";
        private static String MATCHER = "matcher";
        private static String HIGH = "high";
        private static String LOW = "low";

        @SuppressWarnings("unchecked")
        public static List<MatcherModel> parseMatcherModels(Map<String, Object> params) {
            List<MatcherModel> matcherModels = new ArrayList<>();
            List<Map<String, Object>> script = (List<Map<String, Object>>) params.get("matchers");
            script.forEach(entry -> {
                checkMatcherConfiguration(entry);
                String fieldName = String.valueOf(entry.get(FIELD));
                String value = String.valueOf(entry.get(VALUE));
                String matcherName = String.valueOf(entry.get(MATCHER));
                double high = (double) entry.get(HIGH);
                double low = (double) entry.get(LOW);
                matcherModels.add(new MatcherModel(fieldName, value, matcherName, high, low));
            });
            return matcherModels;
        }

        private static void checkMatcherConfiguration(Map<String, Object> entry) {
            if (!entry.containsKey(FIELD)) {
                throw new IllegalArgumentException("Invalid matcher configuration. Missing: [" + FIELD + "] property.");
            }
            if (!entry.containsKey(VALUE)) {
                throw new IllegalArgumentException("Invalid matcher configuration. Missing: [" + VALUE + "] property.");
            }
            if (!entry.containsKey(MATCHER)) {
                throw new IllegalArgumentException("Invalid matcher configuration. Missing: [" + MATCHER + "] property.");
            }
            if (!entry.containsKey(HIGH)) {
                throw new IllegalArgumentException("Invalid matcher configuration. Missing: [" + HIGH + "] property.");
            }
            if (!entry.containsKey(LOW)) {
                throw new IllegalArgumentException("Invalid matcher configuration. Missing: [" + LOW + "] property.");
            }
        }

    }
}
