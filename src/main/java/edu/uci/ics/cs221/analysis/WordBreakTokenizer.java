package edu.uci.ics.cs221.analysis;


import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Project 1, task 2: Implement a Dynamic-Programming based Word-Break Tokenizer.
 *
 * Word-break is a problem where given a dictionary and a string (text with all white spaces removed),
 * determine how to break the string into sequence of words.
 * For example:
 * input string "catanddog" is broken to tokens ["cat", "and", "dog"]
 *
 * We provide an English dictionary corpus with frequency information in "resources/cs221_frequency_dictionary_en.txt".
 * Use frequency statistics to choose the optimal way when there are many alternatives to break a string.
 * For example,
 * input string is "ai",
 * dictionary and probability is: "a": 0.1, "i": 0.1, and "ai": "0.05".
 *
 * Alternative 1: ["a", "i"], with probability p("a") * p("i") = 0.01
 * Alternative 2: ["ai"], with probability p("ai") = 0.05
 * Finally, ["ai"] is chosen as result because it has higher probability.
 *
 * Requirements:
 *  - Use Dynamic Programming for efficiency purposes.
 *  - Use the the given dictionary corpus and frequency statistics to determine optimal alternative.
 *      The probability is calculated as the product of each token's probability, assuming the tokens are independent.
 *  - A match in dictionary is case insensitive. Output tokens should all be in lower case.
 *  - Stop words should be removed.
 *  - If there's no possible way to break the string, throw an exception.
 *
 */
public class WordBreakTokenizer implements Tokenizer {

    List<String> dictLines;
    Map<String,Double> wordFreq;
    Map<Integer,Integer> index = new HashMap<>();
    int[][] dp;
    double[][] textProb;

    public WordBreakTokenizer() {
        try {
            // load the dictionary corpus
            URL dictResource = WordBreakTokenizer.class.getClassLoader().getResource("cs221_frequency_dictionary_en.txt");
            dictLines = Files.readAllLines(Paths.get(dictResource.toURI()));

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        wordFreq = new HashMap();
        double sum = 0;
        String[] words = new String[dictLines.size()];
        Double[] frequency = new Double[dictLines.size()];

        //deal with the first big endian sign code "\uFEFF"
        String[] firstPair = dictLines.get(0).split("\\s+");
        if(firstPair[0].startsWith("\uFEFF")){
            words[0] = firstPair[0].substring(1);
            frequency[0] = Double.valueOf(firstPair[1]);
        }
        sum+=frequency[0];

        //calculate the total sum of frequencies
        for(int i=1;i<dictLines.size();i++){
            String[] pair = dictLines.get(i).split("\\s+");
            words[i] = pair[0];
            frequency[i] = Double.valueOf(pair[1]);
            sum+=frequency[i];
        }
        //store words and their corresponding ratio in the hashmap
        for(int i=0;i<dictLines.size();i++){
            wordFreq.put(words[i],frequency[i]/sum);
        }
    }

    public List<String> tokenize(String text){
        if(dictLines==null) throw new UnsupportedOperationException("WordBreakTokenizer hasn't been initialized!");
        if (text == null|| text=="") return Arrays.asList();
        text = text.toLowerCase();
        if(judge(text)!=-2) {
            List<String> result = findBestPath(dp[0][text.length()-1],text);
            return StopWords.removeStopWords(result);
        }
        else throw new UnsupportedOperationException("cannot break");
    }
    // use 2 dp matrix, the first one to memorize the division of string
    // and the second to memorize the probability of existing strings.
    public int judge(String text){
        int n = text.length();
        dp = new int[n][n];
        for(int i=0;i<dp.length;i++){
            Arrays.fill(dp[i],-2); //Init dp with all -2
        }
        textProb = new double[n][n];
        for(int len=1;len<=n;len++){
            for(int start=0;start<=n-len;start++){
                int end = start+len-1;
                String sub = text.substring(start,end+1);
                if(wordFreq.containsKey(sub)){
                    dp[start][end]=-1;  //-1 means it is a word
                    textProb[start][end] = Math.log(wordFreq.get(sub));
                }else{
                    int cut=-2;
                    double best=-Double.MAX_VALUE;
                    for(int k=start;k<end;k++){
                        if(dp[start][k]!=-2&&dp[k+1][end]!=-2){
                            double now = textProb[start][k] + textProb[k+1][end];
                            if(now>best){
                                best = now;
                                cut = k;
                            }
                        }
                    }
                    textProb[start][end] = best;
                    dp[start][end] = cut;
                }
            }
        }
        return dp[0][n-1];
    }

    //backtrack the path by recursively check the previous index stored and cut the text into List of String.
    public List<String> findBestPath(int lastCut,String text){
        List<String> result = new ArrayList<>();
        if(lastCut==-1){
            result.add(text);
            return result;
        }
        findPathHelper(lastCut,0,text.length()-1);

        for(int i=0;i<text.length();i++){
            if(index.containsKey(i)){
                result.add(text.substring(i,index.get(i)+1));
            }
        }

        return result;
    }
    //recursion fuction, recursively find the previous dividing point and
    //get the cut index.
    public void findPathHelper( int thisCut, int start, int end){
        if(dp[start][thisCut]==-1&&dp[thisCut+1][end]!=-1){
            index.put(start,thisCut);
            findPathHelper(dp[thisCut+1][end],thisCut+1,end);
        }
        else if(dp[thisCut+1][end]==-1&&dp[start][thisCut]!=-1){
            index.put(thisCut+1,end);
            findPathHelper(dp[start][thisCut],start,thisCut);
        }
        else if(dp[start][thisCut]==-1&&dp[thisCut+1][end]==-1){
            index.put(start,thisCut);
            index.put(thisCut+1,end);

        }
        else{
            findPathHelper(dp[start][thisCut],start,thisCut);
            findPathHelper(dp[thisCut+1][end],thisCut+1,end);
        }
    }
}
