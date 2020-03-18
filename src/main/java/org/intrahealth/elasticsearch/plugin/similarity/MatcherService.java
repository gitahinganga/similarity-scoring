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
package org.intrahealth.elasticsearch.plugin.similarity;

import java.util.HashMap;
import java.util.Map;

import info.debatty.java.stringsimilarity.Cosine;
import info.debatty.java.stringsimilarity.Jaccard;
import info.debatty.java.stringsimilarity.JaroWinkler;
import info.debatty.java.stringsimilarity.LongestCommonSubsequence;
import info.debatty.java.stringsimilarity.NormalizedLevenshtein;
import info.debatty.java.stringsimilarity.SorensenDice;
import info.debatty.java.stringsimilarity.interfaces.NormalizedStringSimilarity;

public class MatcherService {

    private Map<String, NormalizedStringSimilarity> matchers = new HashMap<>();

    public double matchScore(String matcherName, String left, String right) {
        NormalizedStringSimilarity matcher = getMatcher(matcherName); 
        return matcher.similarity(left, right);
    }


    private NormalizedStringSimilarity getMatcher(String matcherName) {
        if (matchers.containsKey(matcherName)) {
            return matchers.get(matcherName);
        }
        switch (matcherName) {
            case "cosine":
                matchers.put(matcherName, new Cosine());
                break;
            case "dice":
                matchers.put(matcherName, new SorensenDice());
                break;
            case "jaccard":
                matchers.put(matcherName, new Jaccard());
                break;
            case "jaro-winkler":
                matchers.put(matcherName, new JaroWinkler());
                break;
            case "levenshtein":
                matchers.put(matcherName, new NormalizedLevenshtein());
                break;
            case "longest-common-subsequence":
                matchers.put(matcherName, new NormalizedLongestCommonSubsequenceSimilarity());
                break;
            default:
                throw new IllegalArgumentException("The matcher ["+ matcherName +"] is not supported.");
        }

        return matchers.get(matcherName);
    }

    private static class NormalizedLongestCommonSubsequenceSimilarity implements NormalizedStringSimilarity {

        private static final long serialVersionUID = -4193799401678247955L;

        LongestCommonSubsequence lcs = new LongestCommonSubsequence();

        @Override
        public double similarity(String s1, String s2) {
            double distance = lcs.distance(s1, s2);
            return distance / Math.max(s1.length(), s2.length());
        }
    }
}
