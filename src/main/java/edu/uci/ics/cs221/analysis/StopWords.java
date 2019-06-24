package edu.uci.ics.cs221.analysis;

import java.util.*;

/**
 * A list of stop words.
 * Please use this list and don't change it for uniform behavior in testing.
 */
public class StopWords {

    public static Set<String> stopWords = new HashSet<>();
    static {
        stopWords.addAll(Arrays.asList(
                "i",
                "me",
                "my",
                "myself",
                "we",
                "our",
                "ours",
                "ourselves",
                "you",
                "your",
                "yours",
                "yourself",
                "yourselves",
                "he",
                "him",
                "his",
                "himself",
                "she",
                "her",
                "hers",
                "herself",
                "it",
                "its",
                "itself",
                "they",
                "them",
                "their",
                "theirs",
                "themselves",
                "what",
                "which",
                "who",
                "whom",
                "this",
                "that",
                "these",
                "those",
                "am",
                "is",
                "are",
                "was",
                "were",
                "be",
                "been",
                "being",
                "have",
                "has",
                "had",
                "having",
                "do",
                "does",
                "did",
                "doing",
                "a",
                "an",
                "the",
                "and",
                "but",
                "if",
                "or",
                "because",
                "as",
                "until",
                "while",
                "of",
                "at",
                "by",
                "for",
                "with",
                "about",
                "against",
                "between",
                "into",
                "through",
                "during",
                "before",
                "after",
                "above",
                "below",
                "to",
                "from",
                "up",
                "down",
                "in",
                "out",
                "on",
                "off",
                "over",
                "under",
                "again",
                "further",
                "then",
                "once",
                "here",
                "there",
                "when",
                "where",
                "why",
                "how",
                "all",
                "any",
                "both",
                "each",
                "few",
                "more",
                "most",
                "other",
                "some",
                "such",
                "no",
                "nor",
                "not",
                "only",
                "own",
                "same",
                "so",
                "than",
                "too",
                "very",
                "s",
                "t",
                "can",
                "will",
                "just",
                "don",
                "should",
                "now"
        ));
    }
    public static List<String> removeStopWords(List<String> text){
        for(int i=0;i<text.size();i++){
            if(StopWords.stopWords.contains(text.get(i))) {
                text.remove(i);
                i--;
            }
        }
        return text;
    }
    public static String listToText(List<String> reducedTextList){
        StringBuilder result = new StringBuilder();
        for(int i=0;i<reducedTextList.size();i++){
            result.append(reducedTextList.get(i));
            if(i!=reducedTextList.size()-1) result.append(" ");
        }
        return result.toString();
    }
}
