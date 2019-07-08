package edu.uci.ics.cs221.index.inverted;

public class ScoreRecord {
    private DocID docID;
    private double score;

    public ScoreRecord(DocID docID, double score) {
        this.docID = docID;
        this.score = score;
    }

    public DocID getDocID() {
        return docID;
    }

    public void setDocID(DocID docID) {
        this.docID = docID;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }
}
