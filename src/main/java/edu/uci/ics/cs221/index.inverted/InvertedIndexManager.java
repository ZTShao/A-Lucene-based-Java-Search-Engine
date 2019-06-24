package edu.uci.ics.cs221.index.inverted;

import com.google.common.base.Preconditions;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import edu.uci.ics.cs221.analysis.Analyzer;
import edu.uci.ics.cs221.storage.Document;
import edu.uci.ics.cs221.storage.DocumentStore;
import edu.uci.ics.cs221.storage.MapdbDocStore;


import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.primitives.Ints.asList;
import static edu.uci.ics.cs221.index.inverted.PageFileChannel.PAGE_SIZE;

/**
 * This class manages an disk-based inverted index and all the documents in the inverted index.
 * <p>
 * Please refer to the project 2 wiki page for implementation guidelines.
 */
public class InvertedIndexManager {

    private static final int metaDataLength = 20;
    /**
     * The default flush threshold, in terms of number of documents.
     * For example, a new Segment should be automatically created whenever there's 1000 documents in the buffer.
     * <p>
     * This is only the initial default threshold when none of the segments are "merged" segments.
     * If a merge happened, you should adjust the threshold to a higher number so that all segments have even amount of data.
     * Therefore, you should store the flush threshold to disk.
     * <p>
     * In test cases, the default flush threshold could possibly be set to any number.
     */
    public static int DEFAULT_FLUSH_THRESHOLD = 1000;
    /**
     * The default merge threshold, in terms of number of segments in the inverted index.
     * When the number of segments reaches the threshold, a merge should be automatically triggered.
     * <p>
     * In test cases, the default merge threshold could possibly be set to any number.
     */
    public static int DEFAULT_MERGE_THRESHOLD = 20;
    private Map<String, List<Integer>> invertedList = new HashMap<>();
    private Map<Integer, Document> documentMap = new HashMap<>();
    private Table<String, Integer, List<Integer>> positions = HashBasedTable.create();
    private String indexFolder;
    private Analyzer analyzer;
    private Compressor compressor;
    Compressor naivePositionalSizeCompressor = new NaiveCompressor();
    private int docNum;   //just record to detect when to flush,local docID
    private int segNum;


    private InvertedIndexManager(String indexFolder, Analyzer analyzer) {
        this.indexFolder = indexFolder;
        this.analyzer = analyzer;
        this.compressor = new NaiveCompressor();
        this.docNum = 0;
        this.segNum = 0;
    }

    private InvertedIndexManager(String indexFolder, Analyzer analyzer, Compressor compressor) {
        this.indexFolder = indexFolder;
        this.analyzer = analyzer;
        this.compressor = compressor;
        this.docNum = 0;
        this.segNum = 0;
    }

    /**
     * Creates an inverted index manager with the folder and an analyzer
     */
    public static InvertedIndexManager createOrOpen(String indexFolder, Analyzer analyzer) {
        try {
            Path indexFolderPath = Paths.get(indexFolder);
            if (Files.exists(indexFolderPath) && Files.isDirectory(indexFolderPath)) {
                if (Files.isDirectory(indexFolderPath)) {
                    return new InvertedIndexManager(indexFolder, analyzer);
                } else {
                    throw new RuntimeException(indexFolderPath + " already exists and is not a directory");
                }
            } else {
                Files.createDirectories(indexFolderPath);
                return new InvertedIndexManager(indexFolder, analyzer);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Creates a positional index with the given folder, analyzer, and the compressor.
     * Compressor must be used to compress the inverted lists and the position lists.
     */
    public static InvertedIndexManager createOrOpenPositional(String indexFolder, Analyzer analyzer, Compressor compressor) {
        try {
            Path indexFolderPath = Paths.get(indexFolder);
            if (Files.exists(indexFolderPath) && Files.isDirectory(indexFolderPath)) {
                if (Files.isDirectory(indexFolderPath)) {
                    return new InvertedIndexManager(indexFolder, analyzer, compressor);
                } else {
                    throw new RuntimeException(indexFolderPath + " already exists and is not a directory");
                }
            } else {
                Files.createDirectories(indexFolderPath);
                return new InvertedIndexManager(indexFolder, analyzer, compressor);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Adds a document to the inverted index.
     * Document should live in a in-memory buffer until `flush()` is called to write the segment to disk.
     *
     * @param document
     */
    public void addDocument(Document document) {

        checkNotNull(document);
        documentMap.put(docNum, document);
        String docText = document.getText();
        if(document.getText().equals("")) return;
        List<String> docWords = analyzer.analyze(docText);
        Map<String, List<Integer>> positionMap = new HashMap<>();
        int position = 0;
        for (String word : docWords) {
            if (invertedList.containsKey(word)) {
                if (!invertedList.get(word).contains(docNum)) {
                    invertedList.get(word).add(docNum);
                }
            } else {
                invertedList.put(word, new ArrayList<>(asList(docNum)));
            }

            if (positionMap.containsKey(word)) {
                positionMap.get(word).add(position);
            } else {
                positionMap.put(word, new ArrayList<>(asList(position)));
            }
            position++;
        }
        for (String word : positionMap.keySet()) {
            positions.put(word, docNum, positionMap.get(word));
        }
        docNum++;
        if (docNum >= DEFAULT_FLUSH_THRESHOLD) {
            flush();
        }
    }

    /**
     * Flushes all the documents in the in-memory segment buffer to disk. If the buffer is empty, it should not do anything.
     * flush() writes the segment to disk containing the posting list and the corresponding document store.
     * The structure of dict: keyword length + keyword + inverted list offset(bytes) + inverted list size(integer length)+
     * inverted list length(bytes) + positional list offsets length(bytes)
     */
    public void flush() {
        if (documentMap.isEmpty()) {
            return;
        }
        String documentStorePath = MapdbPathBuilder(segNum);
        String dicPath = dicFilePathBuilder(segNum);
        Set<String> keyWords = invertedList.keySet();

        Path dicFilePath = Paths.get(dicPath);
        PageFileChannel dicPageFileChannel = PageFileChannel.createOrOpen(dicFilePath);

        Path listFilePath = Paths.get(listFilePathBuilder(segNum));
        PageFileChannel listPageFileChannel = PageFileChannel.createOrOpen(listFilePath);

        Path positionFilePath = Paths.get(positionalFilePathBuilder(segNum));
        PageFileChannel positionalPageFileChannel = PageFileChannel.createOrOpen(positionFilePath);

        ByteBuffer dicByteBuffer = ByteBuffer.allocate(PAGE_SIZE);
        ByteBuffer invertedListByteBuffer = ByteBuffer.allocate(PAGE_SIZE);
        ByteBuffer positionalByteBuffer = ByteBuffer.allocate(PAGE_SIZE);
        dicByteBuffer.putInt(keyWords.size());

        int listOffset = 0;
        int positionOffset = 0;

        for (String keyword : keyWords) {
            List<Integer> docList = invertedList.get(keyword);
            byte[] invertedListByte = compressor.encode(docList);
            int invertedListByteLength = invertedListByte.length;
            byte[] byteArray = keyword.getBytes();

            List<Integer> positionalListSize = new ArrayList<>();
            List<Integer> positionalOffsetForEachKeyword = new ArrayList<>();
            for (int docID : docList) {
                List<Integer> positionalList = positions.get(keyword, docID);
                positionalListSize.add(positionalList.size());
                byte[] positionalListByte = compressor.encode(positionalList);
                positionalOffsetForEachKeyword.add(positionOffset);
                positionOffset += positionalListByte.length;
                putAllBytes(positionalListByte, positionalByteBuffer, positionalPageFileChannel);
            }

            positionalOffsetForEachKeyword.add(positionOffset);
            byte[] positionalOffsetByte = compressor.encode(positionalOffsetForEachKeyword);
            putAllBytes(invertedListByte, invertedListByteBuffer, listPageFileChannel);
            putAllBytes(positionalOffsetByte, invertedListByteBuffer, listPageFileChannel);
            putAllBytes( naivePositionalSizeCompressor.encode(positionalListSize),invertedListByteBuffer,listPageFileChannel);


            int dicBlockLength = byteArray.length + metaDataLength;
            if (dicByteBuffer.remaining() < dicBlockLength) {
                if (byteArray.length == PAGE_SIZE) {
                    //add long string length.
                    dicPageFileChannel.appendPage(dicByteBuffer);
                    dicByteBuffer = ByteBuffer.allocate(PAGE_SIZE);
                    dicByteBuffer.putInt(byteArray.length);
                    dicPageFileChannel.appendPage(dicByteBuffer);
                    dicByteBuffer = ByteBuffer.allocate(PAGE_SIZE);
                    dicByteBuffer.put(byteArray);
                    dicPageFileChannel.appendPage(dicByteBuffer);
                    dicByteBuffer = ByteBuffer.allocate(PAGE_SIZE);
                    dicByteBuffer.putInt(listOffset);
                    dicByteBuffer.putInt(docList.size());
                    dicByteBuffer.putInt(invertedListByteLength);
                    dicByteBuffer.putInt(positionalOffsetByte.length);
                } else {
                    dicPageFileChannel.appendPage(dicByteBuffer);
                    dicByteBuffer = ByteBuffer.allocate(PAGE_SIZE);
                    dicByteBuffer.putInt(byteArray.length);
                    if(dicByteBuffer.remaining()<byteArray.length){
                        dicPageFileChannel.appendPage(dicByteBuffer);
                        dicByteBuffer = ByteBuffer.allocate(PAGE_SIZE);;
                    }
                    dicByteBuffer.put(byteArray);
                    if(dicByteBuffer.remaining()<4){
                        dicPageFileChannel.appendPage(dicByteBuffer);
                        dicByteBuffer = ByteBuffer.allocate(PAGE_SIZE);;
                    }
                    dicByteBuffer.putInt(listOffset);
                    if(dicByteBuffer.remaining()<4){
                        dicPageFileChannel.appendPage(dicByteBuffer);
                        dicByteBuffer = ByteBuffer.allocate(PAGE_SIZE);;
                    }
                    dicByteBuffer.putInt(docList.size());
                    if(dicByteBuffer.remaining()<4){
                        dicPageFileChannel.appendPage(dicByteBuffer);
                        dicByteBuffer = ByteBuffer.allocate(PAGE_SIZE);;
                    }
                    dicByteBuffer.putInt(invertedListByteLength);
                    if(dicByteBuffer.remaining()<4){
                        dicPageFileChannel.appendPage(dicByteBuffer);
                        dicByteBuffer = ByteBuffer.allocate(PAGE_SIZE);;
                    }
                    dicByteBuffer.putInt(positionalOffsetByte.length);
                    if(dicByteBuffer.remaining()<4){
                        dicPageFileChannel.appendPage(dicByteBuffer);
                        dicByteBuffer = ByteBuffer.allocate(PAGE_SIZE);;
                    }
                }
            } else {
                dicByteBuffer.putInt(byteArray.length);
                dicByteBuffer.put(byteArray);
                dicByteBuffer.putInt(listOffset);
                dicByteBuffer.putInt(docList.size());
                dicByteBuffer.putInt(invertedListByteLength);
                dicByteBuffer.putInt(positionalOffsetByte.length);
            }
            listOffset += invertedListByteLength + positionalOffsetByte.length + positionalListSize.size()*4;
        }

        dicPageFileChannel.appendPage(dicByteBuffer);
        listPageFileChannel.appendPage(invertedListByteBuffer);
        positionalPageFileChannel.appendPage(positionalByteBuffer);
        dicPageFileChannel.close();
        listPageFileChannel.close();
        positionalPageFileChannel.close();

        Iterator<Map.Entry<Integer, Document>> iter = documentMap.entrySet().iterator();
        DocumentStore documentStore = MapdbDocStore.createWithBulkLoad(documentStorePath, iter);
        documentStore.close();

        invertedList = new HashMap<>();
        documentMap = new HashMap<>();
        positions = HashBasedTable.create();
        segNum++;
        docNum = 0;
        if (segNum >= DEFAULT_MERGE_THRESHOLD) {
            mergeAllSegments();
        }
    }

    /**
     * Merges all the disk segments of the inverted index pair-wise.
     */
    public void mergeAllSegments() {
        // merge only happens at even number of segments
        Preconditions.checkArgument(getNumSegments() % 2 == 0);
        for (int i = 0; i < getNumSegments(); i += 2) {
            Set<String> keyWords = new HashSet<>();
            //Merge document store into a temp file
            DocumentStore inDiskDocStore1 = MapdbDocStore.createOrOpen(MapdbPathBuilder(i));
            DocumentStore inDiskDocStore2 = MapdbDocStore.createOrOpen(MapdbPathBuilder(i + 1));
            String dicFile1 = dicFilePathBuilder(i);
            String dicFile2 = dicFilePathBuilder(i + 1);
            String listFile1 = listFilePathBuilder(i);
            String listFile2 = listFilePathBuilder(i + 1);
            String positionalFile1 = positionalFilePathBuilder(i);
            String positionalFile2 = positionalFilePathBuilder(i + 1);
            String tempDicFile = indexFolder + "/tempsegment";
            String tempListFile = indexFolder + "/tempsegmentforList";
            String tempPosFile = indexFolder + "/tempsegmentforPos";


            Map<String, List<Integer>> dictVal1 = readDict(dicFile1);
            Map<String, List<Integer>> dictVal2 = readDict(dicFile2);
            Set<String> keywords1 = dictVal1.keySet();
            Set<String> keywords2 = dictVal2.keySet();
            Iterator<String> wordIt1 = keywords1.iterator();
            Iterator<String> wordIt2 = keywords2.iterator();
            while (wordIt1.hasNext()) keyWords.add(wordIt1.next());
            while (wordIt2.hasNext()) keyWords.add(wordIt2.next());

            PageFileChannel dictpageFileChannel = PageFileChannel.createOrOpen(Paths.get(tempDicFile));
            ByteBuffer dictbyteBuffer = ByteBuffer.allocate(PAGE_SIZE);
            dictbyteBuffer.putInt(keyWords.size());
            PageFileChannel listpageFileChannel = PageFileChannel.createOrOpen(Paths.get(tempListFile));
            ByteBuffer listByteBuffer = ByteBuffer.allocate(PAGE_SIZE);
            PageFileChannel pospageFileChannel = PageFileChannel.createOrOpen(Paths.get(tempPosFile));
            ByteBuffer posByteBuffer = ByteBuffer.allocate(PAGE_SIZE);
            PageFileChannel pospageFileChannel1 = PageFileChannel.createOrOpen(Paths.get(positionalFile1));
            PageFileChannel pospageFileChannel2 = PageFileChannel.createOrOpen(Paths.get(positionalFile2));
            PageFileChannel listpageFileChannel1 = PageFileChannel.createOrOpen(Paths.get(listFile1));
            PageFileChannel listpageFileChannel2 = PageFileChannel.createOrOpen(Paths.get(listFile2));

            int offset = (int) inDiskDocStore1.size();
            int[] listOffset = {0};
            int posOffset = 0;
            for (String keyword : keyWords) {
                //two lists: one is the inverted list, the other is the pointer list
                List<List<Integer>> docList1 = readDocList(keyword, dictVal1, listpageFileChannel1);
                List<List<Integer>> docList2 = readDocList(keyword, dictVal2, listpageFileChannel2);

                List<Integer> posSizeList1 = readPosSizeList(keyword,dictVal1,listpageFileChannel1);
                List<Integer> posSizeList2 = readPosSizeList(keyword,dictVal2,listpageFileChannel2);

                for(int j = 0;docList1 != null && j<docList1.get(1).size()-1;j++){
                    byte[] posList1 = readPosList(docList1.get(1).get(j),docList1.get(1).get(j+1), pospageFileChannel1);
                    putAllBytes(posList1, posByteBuffer, pospageFileChannel);
                }
                for(int j = 0;docList2!=null && j<docList2.get(1).size()-1;j++){
                    byte[] posList2 = readPosList(docList2.get(1).get(j),docList2.get(1).get(j+1), pospageFileChannel2);
                    putAllBytes(posList2, posByteBuffer, pospageFileChannel);
                }

                if (docList2 == null) {
                    if (docList1 == null) continue;
                    for(int k=0;k<docList1.get(1).size()-1;k++){
                        int temp = docList1.get(1).get(k);
                        docList1.get(1).set(k, posOffset);
                        posOffset += docList1.get(1).get(k + 1) - temp;
                    }
                    docList1.get(1).set(docList1.get(1).size()-1, posOffset);
                    List<Integer> listBytes =  writeDocList(docList1, listByteBuffer, listpageFileChannel,posSizeList1);
                    dictbyteBuffer = writeDict(keyword, dictpageFileChannel, dictbyteBuffer, listBytes,listOffset,
                            docList1.get(0).size());
                    continue;
                }
                if(docList1==null){
                    docList1 = docList2;
                    posSizeList1=posSizeList2;
                    for (int k = 0; k < docList1.get(1).size() - 1; k++) {
                        int listOrigin = docList1.get(0).get(k);
                        int newNum = listOrigin + offset;
                        docList1.get(0).set(k,newNum);
                        int temp = docList1.get(1).get(k);
                        docList1.get(1).set(k, posOffset);
                        posOffset += docList1.get(1).get(k + 1) - temp;
                        inDiskDocStore1.addDocument(newNum, inDiskDocStore2.getDocument(listOrigin));
                    }
                    docList1.get(1).set(docList1.get(1).size()-1,posOffset);
                }else{
                    for (int k = 0; k < docList1.get(1).size() - 1; k++) {
                        int temp = docList1.get(1).get(k);
                        docList1.get(1).set(k, posOffset);
                        posOffset += docList1.get(1).get(k + 1) - temp;
                    }
                    docList1.get(1).remove(docList1.get(1).size() - 1);
                    for (int j = 0; j < docList2.get(1).size(); j++) {
                        if (j < docList2.get(1).size() - 1) {
                            int listOrigin = docList2.get(0).get(j);
                            int newNum = listOrigin + offset;
                            docList1.get(0).add(newNum);
                            posSizeList1.add(posSizeList2.get(j));
                            docList1.get(1).add(posOffset);
                            posOffset += docList2.get(1).get(j + 1) - docList2.get(1).get(j);
                            inDiskDocStore1.addDocument(newNum, inDiskDocStore2.getDocument(listOrigin));
                        } else {
                            docList1.get(1).add(posOffset);
                        }
                    }
                }

                List<Integer> listBytes = writeDocList(docList1, listByteBuffer, listpageFileChannel,posSizeList1);
                dictbyteBuffer = writeDict(keyword, dictpageFileChannel, dictbyteBuffer, listBytes,listOffset,docList1.get(0).size());
            }
            dictpageFileChannel.appendPage(dictbyteBuffer);
            dictpageFileChannel.close();
            listpageFileChannel1.close();
            listpageFileChannel2.close();
            pospageFileChannel.appendPage(posByteBuffer);
            pospageFileChannel.close();
            pospageFileChannel1.close();
            pospageFileChannel2.close();
            listpageFileChannel.appendPage(listByteBuffer);
            listpageFileChannel.close();
            inDiskDocStore1.close();
            inDiskDocStore2.close();

            File newMapDBFile = new File(MapdbPathBuilder(i));
            File oldMapDBFile = new File(MapdbPathBuilder(i + 1));
            File oldDicFile1 = new File(dicFile1);
            File oldDicFile2 = new File(dicFile2);
            File oldListFile1 = new File(listFile1);
            File oldListFile2 = new File(listFile2);
            File oldPosFile1 = new File(positionalFile1);
            File oldPosFile2 = new File(positionalFile2);
            File newDicFile = new File(tempDicFile);
            File newListFile = new File(tempListFile);
            File newPosFile = new File(tempPosFile);

            oldMapDBFile.delete();
            oldDicFile1.delete();
            oldDicFile2.delete();
            oldListFile1.delete();
            oldListFile2.delete();
            oldPosFile1.delete();
            oldPosFile2.delete();

            newMapDBFile.renameTo(new File(MapdbPathBuilder(i / 2)));
            newDicFile.renameTo(new File(dicFilePathBuilder(i / 2)));
            newListFile.renameTo(new File(listFilePathBuilder(i / 2)));
            newPosFile.renameTo(new File(positionalFilePathBuilder(i / 2)));
        }
        segNum /= 2;
    }

    /**
     * Performs a single keyword search on the inverted index.
     * You could assume the analyzer won't convert the keyword into multiple tokens.
     * If the keyword is empty, it should not return anything.
     *
     * @param keyword keyword, cannot be null.
     * @return a iterator of documents matching the query
     */
    public Iterator<Document> searchQuery(String keyword) {
        List<String> after = analyzer.analyze(keyword);
        if (after.size() != 0) {
            keyword = after.get(0);
        } else throw new IllegalArgumentException();
        List<Document> docList = new ArrayList<>();
        for (int segment = 0; segment < segNum; segment++) {
            docList.addAll(searchDocInSegment(keyword, segment));
        }
        return docList.iterator();
    }

    /**
     * Performs an AND boolean search on the inverted index.
     *
     * @param keywords a list of keywords in the AND query
     * @return a iterator of documents matching the query
     */
    public Iterator<Document> searchAndQuery(List<String> keywords) {
        Preconditions.checkNotNull(keywords);
        return searchAndQueryHelper(keywords).iterator();
    }

    /**
     * Performs an OR boolean search on the inverted index.
     *
     * @param keywords a list of keywords in the OR query
     * @return a iterator of documents matching the query
     */
    public Iterator<Document> searchOrQuery(List<String> keywords) {
        Preconditions.checkNotNull(keywords);
        searchArgumentAnalyze(keywords);
        List<Document> searchResult = new ArrayList<>();
        for (int segment = 0; segment < segNum; segment++) {
            List<List<Integer>> docIdGroup = new ArrayList<>();
            findAllId(docIdGroup, keywords, segment);
            Set<Integer> isInvolved = new HashSet<>();
            DocumentStore documentStore = MapdbDocStore.createOrOpen(MapdbPathBuilder(segment));
            for (List<Integer> curList : docIdGroup) {
                for (int i : curList) {
                    if (isInvolved.add(i)) searchResult.add(documentStore.getDocument(i));
                }
            }
            documentStore.close();
        }
        return searchResult.iterator();
    }

    /**
     * Performs a phrase search on a positional index.
     * Phrase search means the document must contain the consecutive sequence of keywords in the exact order.
     * <p>
     * You could assume that the analyzer won't convert each keyword into multiple tokens.
     * Throws UnsupportedOperationException if the inverted index is not a positional index.
     *
     * @param phrase, a consecutive sequence of keywords
     * @return a iterator of documents matching the query
     */
    public Iterator<Document> searchPhraseQuery(List<String> phrase) {
        Preconditions.checkNotNull(phrase);
        return searchPhraseQueryHelper(phrase).iterator();
    }

    /**
     * Iterates through all the documents in all disk segments.
     */
    public Iterator<Document> documentIterator() {
        Set<Document> docSet = new HashSet<>();
        for (int i = 0; i < segNum; i++) {
            DocumentStore inDiskDocStore = MapdbDocStore.createOrOpen(MapdbPathBuilder(i));
            Iterator<Map.Entry<Integer, Document>> it = inDiskDocStore.iterator();
            while (it.hasNext()) {
                docSet.add(it.next().getValue());
            }
            inDiskDocStore.close();
        }
        return docSet.iterator();
    }

    /**
     * Deletes all documents in all disk segments of the inverted index that match the query.
     *
     * @param keyword
     */
    public void deleteDocuments(String keyword) {
        throw new UnsupportedOperationException();
    }

    /**
     * Gets the total number of segments in the inverted index.
     * This function is used for checking correctness in test cases.
     *
     * @return number of index segments.
     */
    public int getNumSegments() {
        return segNum;
    }

    /**
     * Reads a disk segment into memory based on segmentNum.
     * This function is mainly used for checking correctness in test cases.
     *
     * @param segmentNum n-th segment in the inverted index (start from 0).
     * @return in-memory data structure with all contents in the index segment, null if segmentNum don't exist.
     */
    public InvertedIndexSegmentForTest getIndexSegment(int segmentNum) {
        if (segmentNum > segNum - 1) {
            return null;
        }
        Map<String, List<Integer>> inDiskInvertedList = new HashMap<>();
        DocumentStore inDiskDocStore = MapdbDocStore.createOrOpen(MapdbPathBuilder(segmentNum));
        Iterator<Map.Entry<Integer, Document>> iter = inDiskDocStore.iterator();
        Map<Integer, Document> docMap = new HashMap<>();
        while (iter.hasNext()) {
            Map.Entry<Integer, Document> entry = iter.next();
            docMap.put(entry.getKey(), entry.getValue());
        }
        inDiskDocStore.close();

        String filename = dicFilePathBuilder(segmentNum);
        Map<String, List<Integer>> dictVal = readDict(filename);
        PageFileChannel p = PageFileChannel.createOrOpen(Paths.get(listFilePathBuilder(segmentNum)));

        Set<String> keywords = dictVal.keySet();
        //read all the list
        for (String keyword : keywords) {
            inDiskInvertedList.put(keyword, readDocList(keyword, dictVal, p).get(0));
        }
        p.close();
        return new InvertedIndexSegmentForTest(inDiskInvertedList, docMap);
    }

    /**
     * Reads a disk segment of a positional index into memory based on segmentNum.
     * This function is mainly used for checking correctness in test cases.
     * <p>
     * Throws UnsupportedOperationException if the inverted index is not a positional index.
     *
     * @param segmentNum n-th segment in the inverted index (start from 0).
     * @return in-memory data structure with all contents in the index segment, null if segmentNum don't exist.
     */
    public PositionalIndexSegmentForTest getIndexSegmentPositional(int segmentNum) {
        Map<String, List<Integer>> invertedListsforTest = new HashMap<>();
        Map<Integer, Document> documentsforTest = new HashMap<>();
        Table<String, Integer, List<Integer>> positionsforTest = HashBasedTable.create();
        String dictFileName = dicFilePathBuilder(segmentNum);
        Map<String, List<Integer>> dictVal = readDict(dictFileName);
        if(dictVal.size()==0){
            return null;
        }

        PageFileChannel listpageFileChannel = PageFileChannel.createOrOpen(Paths.get(listFilePathBuilder(segmentNum)));
        PageFileChannel pospageFileChannel = PageFileChannel.createOrOpen(Paths.get(positionalFilePathBuilder(segmentNum)));
        int[] posLength = new int[1];
        Map<String,List<List<Integer>>> allDocList = readAllDocList(dictVal,listpageFileChannel,posLength);
        byte[] allPosBytes = readAllPosList(pospageFileChannel,posLength);


        for(String keyword:dictVal.keySet()){
            List<List<Integer>> docList = allDocList.get(keyword);
            invertedListsforTest.put(keyword,docList.get(0));
            for(int i=0;i<docList.get(0).size();i++){
                int start = docList.get(1).get(i);
                int end =  docList.get(1).get(i+1);
                int onePosLen = end-start;
                byte[] posListByte = new byte[onePosLen];
                for(int j=0;j<onePosLen;j++){
                    posListByte[j] = allPosBytes[start+j];
                }

                List<Integer> posList = compressor.decode(posListByte);
                positionsforTest.put(keyword,docList.get(0).get(i),posList);
            }
        }
        DocumentStore inDiskDocStore = MapdbDocStore.createOrOpen(MapdbPathBuilder(segmentNum));
        Iterator<Map.Entry<Integer, Document>> iter = inDiskDocStore.iterator();
        while (iter.hasNext()) {
            Map.Entry<Integer, Document> entry = iter.next();
            documentsforTest.put(entry.getKey(), entry.getValue());
        }
        inDiskDocStore.close();
        listpageFileChannel.close();
        pospageFileChannel.close();

        PositionalIndexSegmentForTest result =new PositionalIndexSegmentForTest(invertedListsforTest,documentsforTest,
                positionsforTest);
        return result;
    }

    /**
     * Performs top-K ranked search using TF-IDF.
     * Returns an iterator that returns the top K documents with highest TF-IDF scores.
     *
     * Each element is a pair of <Document, Double (TF-IDF Score)>.
     *
     * If parameter `topK` is null, then returns all the matching documents.
     *
     * Unlike Boolean Query and Phrase Query where order of the documents doesn't matter,
     * for ranked search, order of the document returned by the iterator matters.
     *
     * @param keywords, a list of keywords in the query
     * @param topK, number of top documents weighted by TF-IDF, all documents if topK is null
     * @return a iterator of top-k ordered documents matching the query
     */
    public Iterator<Pair<Document, Double>> searchTfIdf(List<String> keywords, Integer topK){
        return searchTfIdfHelper(keywords,topK).iterator();
    }

    /**
     * Returns the total number of documents within the given segment.
     */
    public int getNumDocuments(int segmentNum) {
        String documentStorePath = MapdbPathBuilder(segmentNum);
        DocumentStore documentStore = MapdbDocStore.createOrOpenReadOnly(documentStorePath);
        int documentNum = (int)documentStore.size();
        documentStore.close();
        return documentNum;
    }

    /**
     * Returns the number of documents containing the token within the given segment.
     * The token should be already analyzed by the analyzer. The analyzer shouldn't be applied again.
     */
    public int getDocumentFrequency(int segmentNum, String token) {
        Map<String,List<Integer>> dict = readDict(dicFilePathBuilder(segmentNum));
        if(dict.get(token)==null) return 0;
        return dict.get(token).get(1);    // get the list length

    }

    /**
     * Private functions that might be used.
     */
    private String MapdbPathBuilder(int dbNum) {
        return indexFolder +"/"+ "documentStore" + dbNum + ".db";
    }

    private Map<String, List<Integer>> readDict(String filename) {
        Path filePath = Paths.get(filename);
        PageFileChannel pageFileChannel = PageFileChannel.createOrOpen(filePath);

        int curPage = 0;
        ByteBuffer byteBuffer = pageFileChannel.readPage(curPage);
        Map<String, List<Integer>> dictValues = new HashMap<>();

        int wordsNum = byteBuffer.getInt();
        int alreadyRead = 0;
        while (alreadyRead < wordsNum) {
            if (byteBuffer.position() > PAGE_SIZE-4) {
                byteBuffer = pageFileChannel.readPage(++curPage);
            }
            List<Integer> keywordData = new ArrayList<>();
            int curWordLength = byteBuffer.getInt();
            if (curWordLength == 0) {
                byteBuffer = pageFileChannel.readPage(++curPage);
                curWordLength = byteBuffer.getInt();
/*                if(curWordLength>1000){
                    System.out.println(filename);
                    System.out.println(curWordLength);
                }*/
                if (curWordLength == PAGE_SIZE) {
                    byteBuffer = pageFileChannel.readPage(++curPage);
                    byte[] wordByte = new byte[curWordLength];
                    byteBuffer.get(wordByte, 0, PAGE_SIZE);
                    String word = new String(wordByte, StandardCharsets.UTF_8);
                    byteBuffer = pageFileChannel.readPage(++curPage);
                    keywordData.add(byteBuffer.getInt());
                    keywordData.add(byteBuffer.getInt());
                    keywordData.add(byteBuffer.getInt());
                    keywordData.add(byteBuffer.getInt());
                    dictValues.put(word, keywordData);
                    alreadyRead++;
                    continue;
                }
            }
            byte[] wordByte = new byte[curWordLength];
            byteBuffer.get(wordByte, 0, curWordLength);
            String word = new String(wordByte, StandardCharsets.UTF_8);
            if(byteBuffer.position()>PAGE_SIZE-4){
                byteBuffer = pageFileChannel.readPage(++curPage);
            }
            keywordData.add(byteBuffer.getInt());
            keywordData.add(byteBuffer.getInt());
            if(byteBuffer.position()>PAGE_SIZE-4){
                byteBuffer = pageFileChannel.readPage(++curPage);
            }
            keywordData.add(byteBuffer.getInt());
            keywordData.add(byteBuffer.getInt());
            dictValues.put(word, keywordData);
            alreadyRead++;
        }
        pageFileChannel.close();
        return dictValues;
    }

    private List<Integer> readPosSizeList(String word,Map<String, List<Integer>> dictValues,
                                          PageFileChannel pageFileChannel){
        if (!dictValues.containsKey(word)) {
            return null;
        }
        List<Integer> metaData = dictValues.get(word);
        // The structure of dict: keyword length + keyword + inverted list offset(bytes) + inverted list size(integer length)+
        //  inverted list length(bytes) + positional list offsets length(bytes)
        int offset =  metaData.get(0)+metaData.get(2)+metaData.get(3);
        int listPage = offset / PAGE_SIZE;
        int inPageOffset = offset % PAGE_SIZE;

        int length = metaData.get(1)*4;
        ByteBuffer byteBuffer = pageFileChannel.readPage(listPage);
        byteBuffer.position(inPageOffset);
        byte[] encodedList = new byte[length];
        for (int i = 0; i < length; i++) {
            encodedList[i] = byteBuffer.get();
            if (byteBuffer.position() == PAGE_SIZE) {
                byteBuffer = pageFileChannel.readPage(++listPage);
            }
        }
        List<Integer> posSizeList = naivePositionalSizeCompressor.decode(encodedList);
        return posSizeList;
    }

    private List<List<Integer>> readDocList(String word, Map<String, List<Integer>> dictValues,
                                            PageFileChannel pageFileChannel) {
        if (!dictValues.containsKey(word)) {
            return null;
        }
        //read all the list
        List<List<Integer>> ret = new ArrayList<>();
        List<Integer> intDocList;
        List<Integer> pointerList;

        List<Integer> metaData = dictValues.get(word);
        int listPage = metaData.get(0) / PAGE_SIZE;
        int inPageOffset = metaData.get(0) % PAGE_SIZE;

        int listByteLength = metaData.get(2);
        int pointerListLength = metaData.get(3);

        ByteBuffer byteBuffer = pageFileChannel.readPage(listPage);
        byteBuffer.position(inPageOffset);
        byte[] encodedList = new byte[listByteLength];
        for (int i = 0; i < listByteLength; i++) {
            encodedList[i] = byteBuffer.get();
            if (byteBuffer.position() == PAGE_SIZE) {
                byteBuffer = pageFileChannel.readPage(++listPage);
            }
        }
        byte[] encodedPointer = new byte[pointerListLength];
        for (int i = 0; i < pointerListLength; i++) {
            encodedPointer[i] = byteBuffer.get();
            if (byteBuffer.position() == PAGE_SIZE) {
                byteBuffer = pageFileChannel.readPage(++listPage);
            }
        }
        intDocList = compressor.decode(encodedList);
        pointerList = compressor.decode(encodedPointer);
        ret.add(intDocList);
        ret.add(pointerList);
        return ret;
    }

    private List<Integer> readInvertedList(String word, Map<String, List<Integer>> dictValues,
                                           PageFileChannel pageFileChannel) {
        if (!dictValues.containsKey(word)) {
            return null;
        }
        List<Integer> metaData = dictValues.get(word);
        int listPage = metaData.get(0) / PAGE_SIZE;
        int inPageOffset = metaData.get(0) % PAGE_SIZE;

        int listByteLength = metaData.get(2);

        ByteBuffer byteBuffer = pageFileChannel.readPage(listPage);
        byteBuffer.position(inPageOffset);
        byte[] encodedList = new byte[listByteLength];
        for (int i = 0; i < listByteLength; i++) {
            encodedList[i] = byteBuffer.get();
            if (byteBuffer.position() == PAGE_SIZE) {
                byteBuffer = pageFileChannel.readPage(++listPage);
            }
        }
        return compressor.decode(encodedList);
    }


    private byte[] readPosList(int offset,int nextOffset, PageFileChannel pageFileChannel) {

        int pageOffset = offset / PAGE_SIZE;
        int inpageOffset = offset % PAGE_SIZE;

        ByteBuffer byteBuffer = pageFileChannel.readPage(pageOffset);
        byteBuffer.position(inpageOffset);
        int length = nextOffset-offset;
        byte[] posBytes = new byte[length];
        for (int i = 0; i < length; i++) {
            posBytes[i] = byteBuffer.get();
            if (byteBuffer.position() == PAGE_SIZE) {
                byteBuffer = pageFileChannel.readPage(++pageOffset);
            }
        }
        return posBytes;
    }

    private ByteBuffer writeDict(String keyword, PageFileChannel pageFileChannel, ByteBuffer byteBuffer,
                                 List<Integer> listBytes, int[] listOffset,int listLength) {
        //write keywords dictionary into a file
        //in case of the length of one keyword is too large
        byte[] byteArray = keyword.getBytes();
        int len = byteArray.length + metaDataLength;
        if (byteBuffer.remaining() < len) {
            if (byteArray.length == PAGE_SIZE) {
                //add long string length.
                pageFileChannel.appendPage(byteBuffer);
                byteBuffer = ByteBuffer.allocate(PAGE_SIZE);
                byteBuffer.putInt(byteArray.length);

                pageFileChannel.appendPage(byteBuffer);
                byteBuffer = ByteBuffer.allocate(PAGE_SIZE);
                byteBuffer.put(byteArray);

                pageFileChannel.appendPage(byteBuffer);
                byteBuffer = ByteBuffer.allocate(PAGE_SIZE);
                byteBuffer.putInt(listOffset[0]);
                byteBuffer.putInt(listLength);
                byteBuffer.putInt(listBytes.get(0));
                byteBuffer.putInt(listBytes.get(1));
                listOffset[0] +=listBytes.get(0)+listBytes.get(1)+listLength*4;
                return byteBuffer;
            }
            pageFileChannel.appendPage(byteBuffer);
            byteBuffer = ByteBuffer.allocate(PAGE_SIZE);
            byteBuffer.putInt(byteArray.length);
            byteBuffer.put(byteArray);
            byteBuffer.putInt(listOffset[0]);
            byteBuffer.putInt(listLength);
            byteBuffer.putInt(listBytes.get(0));
            byteBuffer.putInt(listBytes.get(1));
            listOffset[0] += listBytes.get(0) + listBytes.get(1) + listLength*4;
        } else {
            byteBuffer.putInt(byteArray.length);
            byteBuffer.put(byteArray);
            byteBuffer.putInt(listOffset[0]);
            byteBuffer.putInt(listLength);
            byteBuffer.putInt(listBytes.get(0));
            byteBuffer.putInt(listBytes.get(1));
            listOffset[0] += listBytes.get(0) + listBytes.get(1) + listLength*4;
        }
        return byteBuffer;
    }

    private List<Integer> writeDocList(List<List<Integer>> docList, ByteBuffer byteBuffer, PageFileChannel pageFileChannel,
                                      List<Integer> posSizeList) {
        //write list into segment
        List<Integer> ret = new ArrayList<>();
        for (int i = 0; i < docList.size(); i++) {
            byte[] listBytes = compressor.encode(docList.get(i));
            ret.add(listBytes.length);
            putAllBytes(listBytes, byteBuffer, pageFileChannel);
        }
        byte[] posSizeListBytes = naivePositionalSizeCompressor.encode(posSizeList);
        putAllBytes(posSizeListBytes,byteBuffer,pageFileChannel);

        return ret;
    }

    private void putAllBytes(byte[] bytes, ByteBuffer byteBuffer, PageFileChannel pageFileChannel) {
        for (int i = 0; i < bytes.length; i++) {
            if (byteBuffer.remaining() == 0) {
                pageFileChannel.appendPage(byteBuffer);
                byteBuffer.clear();
            }
            byteBuffer.put(bytes[i]);
        }
    }

    private String listFilePathBuilder(int segNum) {
        return indexFolder + "/"+"invertedSegment" + segNum;
    }

    private String dicFilePathBuilder(int segNum) {
        return indexFolder + "/"+"dicSegment" + segNum;
    }

    private String positionalFilePathBuilder(int segNum) {
        return indexFolder +"/"+ "positionalSegment" + segNum;
    }

    private List<Document> searchDocInSegment(String keyword, int segment) {
        List<Document> documentList = new ArrayList<>();
        String filename = dicFilePathBuilder(segment);

        Map<String, List<Integer>> dicValue = readDict(filename);
        PageFileChannel p = PageFileChannel.createOrOpen(Paths.get(listFilePathBuilder(segment)));
        List<Integer> docIdList = readInvertedList(keyword,dicValue,p);
        DocumentStore documentStore = MapdbDocStore.createOrOpen(MapdbPathBuilder(segment));
        if (docIdList!=null) {
            for (int i : docIdList) {
                documentList.add(documentStore.getDocument(i));
            }
        }
        documentStore.close();
        p.close();
        return documentList;
    }


    private void findAllId(List<List<Integer>> docIdGroup, List<String> keywords, int segment) {
        Map<String, List<Integer>> dictValue = readDict(dicFilePathBuilder(segment));
        PageFileChannel p = PageFileChannel.createOrOpen(Paths.get(listFilePathBuilder(segment)));
        for (String keyword : keywords) {
            List<Integer> curList = readInvertedList(keyword, dictValue, p);
            if (curList != null) docIdGroup.add(curList);
        }
        p.close();
    }

    private List<Integer> findCommonGroup(List<List<Integer>> docIdGroup) {
        List<Integer> commonGroup = docIdGroup.get(0);
        for (int i = 1; i < docIdGroup.size(); i++) {
            commonGroup = new ArrayList<>(mergeTwoList(commonGroup, docIdGroup.get(i)));
        }
        return commonGroup;
    }

    private List<Integer> mergeTwoList(List<Integer> list1, List<Integer> list2) {
        List<Integer> commonId = new ArrayList<>();
        for (int i : list1) {
            if (binarySearch(i, list2)) commonId.add(i);
        }
        return commonId;
    }

    private boolean binarySearch(int docId, List<Integer> list2) {
        int start = 0;
        int end = list2.size() - 1;
        while (start <= end) {
            int mid = (start + end) / 2;
            if (list2.get(mid) == docId) return true;
            else if (list2.get(mid) > docId) end = mid - 1;
            else start = mid + 1;
        }
        return false;
    }


    private Map<String,List<List<Integer>>> readAllDocList(Map<String,List<Integer>> dictVal,
                                                           PageFileChannel pageFileChannel,
                                                           int[] posLength){
        Map<String,List<List<Integer>>> result = new HashMap<>();
        int totalLength = 0;
        for(String word:dictVal.keySet()){
            List<Integer> metaData = dictVal.get(word);
            totalLength+=metaData.get(2);
            totalLength+=metaData.get(3);
        }
        byte[] allBytes = new byte[totalLength];
        int listPage = 0;
        ByteBuffer byteBuffer = pageFileChannel.readPage(listPage);

        for(int i=0;i<totalLength;i++){
            allBytes[i] = byteBuffer.get();
            if (byteBuffer.position() == PAGE_SIZE) {
                byteBuffer = pageFileChannel.readPage(++listPage);
            }
        }
        for(String word:dictVal.keySet()) {
            List<List<Integer>> ret = new ArrayList<>();
            List<Integer> intDocList;
            List<Integer> pointerList;
            List<Integer> metaData = dictVal.get(word);
            int bytesOffset = metaData.get(0);
            int listByteLength = metaData.get(2);
            int pointerListLength = metaData.get(3);

            byte[] encodedList = new byte[listByteLength];
            for (int i = 0; i < listByteLength; i++) {
                encodedList[i] = allBytes[bytesOffset+i];
            }
            byte[] encodedPointer = new byte[pointerListLength];
            for (int i = 0; i < pointerListLength; i++) {
                encodedPointer[i] = allBytes[bytesOffset+listByteLength+i];
            }
            pointerList = compressor.decode(encodedPointer);
            intDocList = compressor.decode(encodedList);
            ret.add(intDocList);
            ret.add(pointerList);
            posLength[0] = pointerList.get(pointerList.size()-1);
            result.put(word,ret);
        }
        return result;
    }

    private byte[] readAllPosList(PageFileChannel pageFileChannel,int[] totalLen){
        int page = 0;
        ByteBuffer byteBuffer = pageFileChannel.readPage(page);
        byte[] posBytes = new byte[totalLen[0]];
        for (int i = 0; i < totalLen[0]; i++) {
            posBytes[i] = byteBuffer.get();
            if (byteBuffer.position() == PAGE_SIZE) {
                byteBuffer = pageFileChannel.readPage(++page);
            }
        }
        return posBytes;
    }

    private List<Document> searchAndQueryHelper(List<String> keywords) {
        searchArgumentAnalyze(keywords);
        List<Document> searchAndDocument = new ArrayList<>();
        for (int segment = 0; segment < segNum; segment++) {
            Map<String, List<Integer>> dicForThisSegment = readDict(dicFilePathBuilder(segment));
            List<Integer> commonGroup = commonDocIDinOneSegmentAndSearch(keywords,segment,dicForThisSegment);
            if (commonGroup.size() != 0) {
                DocumentStore documentStore = MapdbDocStore.createOrOpen(MapdbPathBuilder(segment));
                for (int i : commonGroup) searchAndDocument.add(documentStore.getDocument(i));
                documentStore.close();
            }
        }
        return searchAndDocument;
    }

    private List<Integer> commonDocIDinOneSegmentAndSearch(List<String> keywords, int segment,
                                                           Map<String,List<Integer>> dicForThisSegment) {
        List<List<Integer>> docIdGroup = new ArrayList<>();
        if (searchAndDocId(docIdGroup, keywords, segment,dicForThisSegment)) {
            Collections.sort(docIdGroup, new Comparator<List<Integer>>() {
                @Override
                public int compare(List<Integer> o1, List<Integer> o2) {
                    return o1.size() - o2.size();
                }
            });
            return findCommonGroup(docIdGroup);
        } else return new ArrayList<>();
    }

    private void searchArgumentAnalyze(List<String> keywords){
        for (int i = 0; i < keywords.size(); i++) {
            List<String> after = analyzer.analyze(keywords.get(i));
            if (after.size() != 0) keywords.set(i, after.get(0));
            else{
                keywords.remove(i);
                i--;
            }
        }
    }

    private boolean searchAndDocId(List<List<Integer>> docIdGroup, List<String> keywords,
                                   int segment, Map<String, List<Integer>> dicForThisSegment) {
        PageFileChannel p = PageFileChannel.createOrOpen(Paths.get(listFilePathBuilder(segment)));
        for (String keyword : keywords) {
            List<Integer> curList = readInvertedList(keyword, dicForThisSegment, p);
            if (curList != null) docIdGroup.add(curList);
            else {
                p.close();
                return false;
            }
        }
        p.close();
        return true;
    }

    private List<Document> searchPhraseQueryHelper(List<String> phrase) {
        searchArgumentAnalyze(phrase);
        List<Document> phraseSearchResult = new ArrayList<>();
        if(phrase.size()==0) return phraseSearchResult;
        for (int segment = 0; segment < segNum; segment++) {
            Map<String,List<Integer>> dicForThisSegment = readDict(dicFilePathBuilder(segment));
            List<Integer> commonId = commonDocIDinOneSegmentAndSearch(phrase,segment,dicForThisSegment);
            if(commonId.size()==0) continue;
            else{
                List<Integer> resultId = new ArrayList<>();
                for(int docID:commonId){
                    if(exactOrderSearchSuccess(phrase,docID,dicForThisSegment,segment)) resultId.add(docID);
                }
                if(resultId.size()!=0){
                    DocumentStore documentStore = MapdbDocStore.createOrOpen(MapdbPathBuilder(segment));
                    for (int id : resultId) phraseSearchResult.add(documentStore.getDocument(id));
                    documentStore.close();
                }
            }
        }
        return phraseSearchResult;
    }

    private boolean exactOrderSearchSuccess(List<String> phrase, int docID,
                                            Map<String,List<Integer>> dicForThisSegment,int segment){
        List<Integer> possiblePosList = getPosList(phrase.get(0),docID,dicForThisSegment,segment);
        for(int i=1;i<phrase.size();i++) {
            List<Integer> posForNextWord = getPosList(phrase.get(i), docID,dicForThisSegment,segment);
            for (int j = 0; j < possiblePosList.size(); j++) {
                if (!binarySearch(possiblePosList.get(j) + i, posForNextWord)) {
                    possiblePosList.remove(j);
                    j--;
                }
            }
            if(possiblePosList.size()==0) return false;
        }
        return true;
    }

    private List<Integer> getPosList(String keyword, int docId,
                                     Map<String,List<Integer>> dicForThisSegment,int segment){
        PageFileChannel invertedListPageFileChannel = PageFileChannel.createOrOpen(Paths.get(listFilePathBuilder(segment)));
        List<List<Integer>> invertedAndPosList = readDocList(keyword,dicForThisSegment,invertedListPageFileChannel);
        invertedListPageFileChannel.close();
        return getPosList(docId,segment,invertedAndPosList);
    }

    private List<Integer> getPosList(int docId,int segment,
                                     List<List<Integer>> invertedAndPosList){
        int index = invertedAndPosList.get(0).indexOf(docId);
        int offset = invertedAndPosList.get(1).get(index);
        int nextOffset = invertedAndPosList.get(1).get(index+1);
        PageFileChannel posListPageFileChannel = PageFileChannel.createOrOpen(Paths.get(positionalFilePathBuilder(segment)));
        byte[] positionListByte = readPosList(offset,nextOffset,posListPageFileChannel);
        posListPageFileChannel.close();
        return compressor.decode(positionListByte);
    }

    public List<Pair<Document,Double>> searchTfIdfHelper(List<String> keywords, Integer topK){
        searchArgumentAnalyze(keywords);
        List<String> afterAnalyzed = keywords;
        Map<String,Integer> wordFreq = new HashMap<>();
        Map<String,Integer> queryWordFreq = new HashMap<>();
        Map<String,Double> queryTfIdf = new HashMap<>();
        Map<DocID,Double> dotProductAccumulator = new HashMap<>();
        Map<DocID,Double> vectorLengthAccumulator = new HashMap<>();

        Queue<ScoreRecord> priorityQueue;
        List<ScoreRecord> normalQueue = new ArrayList<>();
        if(topK==null){
            priorityQueue = null;
        }else if(topK==0){
            return new ArrayList<>();
        }else{
            priorityQueue = new PriorityQueue<>(topK, new Comparator<ScoreRecord>() {
                @Override
                public int compare(ScoreRecord o1, ScoreRecord o2) {
                    if(o2.getScore()>o1.getScore()){
                        return -1;
                    }else if(o2.getScore()<o1.getScore()){
                        return 1;
                    }
                    return 0;
                }
            });
        }

        int allDocNum = 0;
        for(int i=0;i<segNum;i++){
            allDocNum+=getNumDocuments(i);
            for(String keyword:afterAnalyzed){
                int docFreq = getDocumentFrequency(i,keyword);
                if(!wordFreq.containsKey(keyword)){
                    wordFreq.put(keyword,docFreq);
                }else{
                    int prev = wordFreq.get(keyword);
                    wordFreq.put(keyword,prev+docFreq);
                }
            }
        }
        for(String keyword:afterAnalyzed){
            if(!queryWordFreq.containsKey(keyword)){
                queryWordFreq.put(keyword,1);
            }else{
                int prev = queryWordFreq.get(keyword);
                queryWordFreq.put(keyword,prev+1);
            }
        }
        for(String keyword:wordFreq.keySet()){
            int prev = wordFreq.get(keyword);
            wordFreq.put(keyword,prev/queryWordFreq.get(keyword));
        }
        for(String keyword:queryWordFreq.keySet()){
            double tf_idf = queryWordFreq.get(keyword)*idf(allDocNum,wordFreq.get(keyword));
            queryTfIdf.put(keyword,tf_idf);
        }

        for(int segment=0;segment<segNum;segment++){
            PageFileChannel doclistPageFileChannel = PageFileChannel.createOrOpen(Paths.get(listFilePathBuilder(segment)));
            DocumentStore documentStore = MapdbDocStore.createOrOpen(MapdbPathBuilder(segment));
            for(String keyword:wordFreq.keySet()){

                Map<String,List<Integer>> dicVal = readDict(dicFilePathBuilder(segment));

                List<List<Integer>> docList = readDocList(keyword,dicVal,doclistPageFileChannel);
                List<Integer> posSizeList = readPosSizeList(keyword,dicVal,doclistPageFileChannel);
                if(docList==null){
                    continue;
                }
                for(int i=0;i<docList.get(0).size();i++){

                    int tf = posSizeList.get(i);
                    DocID docID = new DocID(segment,docList.get(0).get(i));
                    double tfidf = tf*idf(allDocNum,wordFreq.get(keyword));
                    if(!dotProductAccumulator.containsKey(docID)){
                        dotProductAccumulator.put(docID,tfidf*queryTfIdf.get(keyword));
                        vectorLengthAccumulator.put(docID,Math.pow(tfidf,2));
                    }else{
                        double prev1 = dotProductAccumulator.get(docID);
                        double prev2 = vectorLengthAccumulator.get(docID);
                        dotProductAccumulator.put(docID,prev1+tfidf*queryTfIdf.get(keyword));
                        vectorLengthAccumulator.put(docID,prev2+Math.pow(tfidf,2));
                    }
                }
            }
            Iterator<Integer> docIdIter = documentStore.keyIterator();
            if(priorityQueue==null){
                while(docIdIter.hasNext()){
                    DocID docID = new DocID(segment,docIdIter.next());
                    if(dotProductAccumulator.containsKey(docID)){
                        normalQueue.add(new ScoreRecord(docID,
                                dotProductAccumulator.get(docID)/Math.sqrt(vectorLengthAccumulator.get(docID))));
                    }
                }
            }else{
                while(docIdIter.hasNext()){
                    DocID docID = new DocID(segment,docIdIter.next());
                    double score;
                    if(dotProductAccumulator.containsKey(docID)) {
                        score = dotProductAccumulator.get(docID) / Math.sqrt(vectorLengthAccumulator.get(docID));
                    }else{
                        continue;
                    }
                    if(priorityQueue.size()>=topK){
                        if(priorityQueue.peek().getScore()<score){
                            priorityQueue.poll();
                            priorityQueue.add(new ScoreRecord(docID, score));
                        }
                    }else{
                        priorityQueue.add(new ScoreRecord(docID, score));
                    }
                }
            }
            documentStore.close();
            doclistPageFileChannel.close();
        }
        normalQueue.sort((o1, o2) -> {
            if(o1.getScore()<o2.getScore()){
                return 1;
            }else if(o1.getScore()>o2.getScore()){
                return -1;
            }
            return 0;
        });
        List<Pair<Document,Double>> topDocuments = new ArrayList<>();
        if(priorityQueue==null){
            for(int i=0;i<normalQueue.size();i++){
                ScoreRecord cur = normalQueue.get(i);
                int segmentID = cur.getDocID().getSegmentId();
                int docID = cur.getDocID().getLocalDocId();
                DocumentStore documentStore = MapdbDocStore.createOrOpen(MapdbPathBuilder(segmentID));
                topDocuments.add(new Pair(documentStore.getDocument(docID),cur.getScore()));
                documentStore.close();
            }
        }else{
            int len = priorityQueue.size();
            for(int i=0;i<len;i++){
                if(i>topK-1){
                    break;
                }
                ScoreRecord cur = priorityQueue.poll();
                int segmentID = cur.getDocID().getSegmentId();
                int docID = cur.getDocID().getLocalDocId();
                DocumentStore documentStore = MapdbDocStore.createOrOpen(MapdbPathBuilder(segmentID));
                topDocuments.add(new Pair(documentStore.getDocument(docID),cur.getScore()));
                documentStore.close();
            }
            Collections.reverse(topDocuments);
        }
        return topDocuments;
    }

    private double idf(int N,int docFreq){
        if(docFreq==0){
            return 0;
        }
        if(N==docFreq){
            return (double)1;
        }
        return Math.log((double)(N)/(double)docFreq)/Math.log((double)10);
    }
}
