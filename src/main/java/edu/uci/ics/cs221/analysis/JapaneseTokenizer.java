package edu.uci.ics.cs221.analysis;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class JapaneseTokenizer implements  Tokenizer {
    List<String> dictLines;
    Map<String,Double> wordFreq;

    public static Set<String> japanesePunctuations = new HashSet<>();
    static {
        japanesePunctuations.addAll(Arrays.asList("、", "。", "「","」","『","』", "?", "!"));
    }

    public JapaneseTokenizer(){
        try {
            // load the dictionary corpus
            URL dictResource = WordBreakTokenizer.class.getClassLoader().getResource("japanese.txt");
            dictLines = Files.readAllLines(Paths.get(dictResource.toURI()));

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        wordFreq = new HashMap();
        double sum = 0;
        String[] words = new String[dictLines.size()];
        Double[] frequency = new Double[dictLines.size()];

        for(int i=0;i<dictLines.size();i++){
            String[] pair = dictLines.get(i).split("\\s+");
            words[i] = pair[4];
            frequency[i] = Double.valueOf(pair[6]);
            sum+=frequency[i];
        }

        for(int i=0;i<dictLines.size();i++){
            wordFreq.put(words[i],frequency[i]/sum);
        }


    }
    @Override
    public List<String> tokenize(String text){
        if(dictLines==null) throw new UnsupportedOperationException("JapaneseTokenizer hasn't been initialized!");
        if (text == null|| text=="") throw new IllegalArgumentException("Invalid input!");
        text = japanesePunctuations(text);
        int len = text.length();
        int[] segment = largestSegment(text);

        List<String> result = new ArrayList<>();
        if(segment[0]>0){
            result.add(text.substring(0,segment[0]));
        }
        List<List<String>> allCombines = findJapaneseCombines(text.substring(segment[0],segment[1]+1));
        result.addAll(findOptimal(allCombines));
        if(segment[1]<len-1){
            result.add(text.substring(segment[1]+1,len));
        }
        return result;
    }

    public int[] largestSegment(String text){
        int n = text.length();
        boolean[][] dp = new boolean[n][n];
        int[] result = new int[2];
        for(int len=1;len<=n;len++){
            for(int start=0;start<=n-len;start++){
                int end = start+len-1;
                String sub = text.substring(start,end+1);
                if(wordFreq.containsKey(sub)){
                    dp[start][end]=true;
                }else{
                    for(int k=start;k<end;k++){
                        dp[start][end]=dp[start][k]&&dp[k+1][end];
                    }
                }
            }
        }
//        for(int i=0;i<n;i++){
//            for (int j=0;j<n;j++){
//                System.out.print(dp[i][j]);
//                System.out.print(" ");
//            }
//            System.out.println("");
//        }

        int maxLen = 0;
        for(int i=0;i<n;i++){
            for(int j=n-1;j>=i;j--){
                if(dp[i][j] && j-i+1>maxLen){
                    maxLen = j-i+1;
                    result[0]=i;
                    result[1]=j;
                }
            }
        }
        return result;
    }

    public List<List<String>> findJapaneseCombines(String text){
        List<List<String>> result = new ArrayList<>();
        findJapaneseCombinesHelper(text,result,new ArrayList<>());
        return result;
    }

    public void findJapaneseCombinesHelper(String text, List<List<String>> result, List<String> cur){
        if(text.length()==0){
            result.add(cur);
            return;
        }
        for(int i=1;i<=text.length();i++){
            if(wordFreq.containsKey(text.substring(0,i))){
                List<String> now = new ArrayList<>(cur);
                now.add(text.substring(0,i));
                findJapaneseCombinesHelper(text.substring(i,text.length()),result,now);
            }
        }

    }
    public String japanesePunctuations(String text){
        char[] japaneseText = text.toCharArray();
        for(int i=0;i<japaneseText.length;i++){
            if(japanesePunctuations.contains(String.valueOf(japaneseText[i]))){
                japaneseText[i]=' ';
            }
        }
        String result = new String(japaneseText);
        result = result.replace(" ","");
        return result;
    }

    public List<String> findOptimal(List<List<String>> result){
        double[] scores = new double[result.size()];
        Arrays.fill(scores,1);
        for(int i=0;i<result.size();i++){
            for(int j=0;j<result.get(i).size();j++){
                scores[i]*=wordFreq.get(result.get(i).get(j));
            }
        }
        int maxIndex = 0;
        double maxScore = scores[maxIndex];
        for(int i=1;i<scores.length;i++){
            if(scores[i]>maxScore){
                maxIndex = i;
                maxScore = scores[i];
            }
        }
        return result.get(maxIndex);
    }
    // do a dp to memorize which segments of characters in the text are valid.

}

