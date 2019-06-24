package edu.uci.ics.cs221.analysis.japanese;

import edu.uci.ics.cs221.analysis.JapaneseTokenizer;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
public class Team3JapaneseTokenizerTest {
    @Test
    public void team3TestCase1(){
        String text = "私は学校に行きたくありません。";
        List<String> expected = Arrays.asList("私","は","学校","に", "行き", "たく", "ありま", "せん");
        JapaneseTokenizer tokenizer = new JapaneseTokenizer();
        assertEquals(expected, tokenizer.tokenize(text));
    }
    //Our testcase 2
    @Test
    public void team3TestCase2(){
        String text = "おはようございます";
        List<String> expected = Arrays.asList("おはよう", "ご", "ざいます");
        JapaneseTokenizer tokenizer = new JapaneseTokenizer();
        assertEquals(expected, tokenizer.tokenize(text));
    }
    @Test
    public void team3TestCase3(){
        String text = "『日本語』は本当に楽しいです";
        List<String> expected = Arrays.asList("日本語", "は", "本当に", "楽しい", "です");
        JapaneseTokenizer tokenizer = new JapaneseTokenizer();
        assertEquals(expected, tokenizer.tokenize(text));
    }
}
