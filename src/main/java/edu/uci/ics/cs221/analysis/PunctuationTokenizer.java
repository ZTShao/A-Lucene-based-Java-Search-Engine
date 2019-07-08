package edu.uci.ics.cs221.analysis;

import java.time.temporal.ChronoField;
import java.util.*;

/**
 * Project 1, task 1: Implement a simple tokenizer based on punctuations and white spaces.
 * <p>
 * For example: the text "I am Happy Today!" should be tokenized to ["happy", "today"].
 * <p>
 * Requirements:
 * - White spaces (space, tab, newline, etc..) and punctuations provided below should be used to tokenize the text.
 * - White spaces and punctuations should be removed from the result tokens.
 * - All tokens should be converted to lower case.
 * - Stop words should be filtered out. Use the stop word list provided in `StopWords.java`
 */
public class PunctuationTokenizer implements Tokenizer {

    public static Set<String> punctuations = new HashSet<>();

    static {
        punctuations.addAll(Arrays.asList(",", ".", ";", "?", "!"));
    }

    public PunctuationTokenizer() {
    }

    public List<String> tokenize(String text) {
        List<String> result;
        if (text == "" || text == null) return new ArrayList<>();
        List<String> reducedTextList = dropPunctuations(text);
        //drop all the punctuations and turn into a list
        result = StopWords.removeStopWords(reducedTextList);
        //System.out.println(result);
        return result;//Then remove all stop words
    }

    public static List<String> dropPunctuations(String text) {
        String lowerText = text.toLowerCase(); //Firstly change all characters to lowercase.
        char[] charText = lowerText.toCharArray();
        //if there is any punctuations as in the set, make it ' '.
        for (int i = 0; i < charText.length; i++) {
            if (punctuations.contains(String.valueOf(charText[i]))) charText[i] = ' ';
        }
        //split the new string to cut off all the spaces and \t as well as \n.
        String[] reducedText = new String(charText).split("\\s+");

        List<String> reducedTextList = new ArrayList<>();
        for (String i : reducedText) {
            //The first string can be ""
            if (!i.equals("")) {
                reducedTextList.add(i);
            }
        }
        //System.out.println(reducedTextList);
        return reducedTextList;
    }
}
