package edu.uci.ics.cs221.index.inverted;

import java.util.Objects;

public class DocID{
    private int segmentId,localDocId;
    public DocID(int segmentId,int localDocId){
        this.segmentId=segmentId;
        this.localDocId = localDocId;
    }
    @Override
    public boolean equals(Object object){
        DocID docID = (DocID)object;
        return docID.getSegmentId()==this.segmentId &&
                docID.getLocalDocId()==this.localDocId;
    }
    @Override
    public int hashCode() {
        return Objects.hash(this.segmentId)+Objects.hash(this.localDocId);
    }


    public void setSegmentId(int segmentId) {
        this.segmentId = segmentId;
    }

    public void setLocalDocId(int localDocId) {
        this.localDocId = localDocId;
    }

    public int getSegmentId() {
        return segmentId;
    }

    public int getLocalDocId() {
        return localDocId;
    }
}
