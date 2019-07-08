package edu.uci.ics.cs221.search;

import edu.uci.ics.cs221.index.inverted.InvertedIndexManager;
import edu.uci.ics.cs221.index.inverted.Pair;
import edu.uci.ics.cs221.storage.Document;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static com.google.common.primitives.Ints.asList;

public class IcsSearchEngine {
    private InvertedIndexManager indexManager;
    private Path documentDirectory;
    private Path webPagesFolder;
    private int[] linkNumOfEachPage;
    private Map<Integer, List<Integer>> listOfPagesToEachPage;
    private int NUM_OF_FILE;
    private double[] pageRankScore = new double[NUM_OF_FILE];
    private static final String idGraphFileName = "id-graph.tsv";
    private static final double oneMinusDamping = 0.15;
    private static final double dampingFactor = 0.85;

    /**
     * Initializes an IcsSearchEngine from the directory containing the documents and the
     */
    public static IcsSearchEngine createSearchEngine(Path documentDirectory, InvertedIndexManager indexManager) {
        return new IcsSearchEngine(documentDirectory, indexManager);
    }

    private IcsSearchEngine(Path documentDirectory, InvertedIndexManager indexManager) {
        if (Files.exists(documentDirectory) && Files.isDirectory(documentDirectory)) {
            this.documentDirectory = documentDirectory;
            this.indexManager = indexManager;
            this.webPagesFolder = documentDirectory.resolve("cleaned");
        } else throw new RuntimeException("This is not a Directory!");
    }

    /**
     * Writes all ICS web page documents in the document directory to the inverted index.
     */
    public void writeIndex() {
        //URL documentResource = IcsSearchEngine.class.getClassLoader().getResource(documentDirectory.toString());
        try {
            File[] allFiles = new File(webPagesFolder.toUri()).listFiles();
            NUM_OF_FILE = allFiles.length;
            for (File file : allFiles) {
                Document cur = readDocumentFromFile(file.toString());
                indexManager.addDocument(cur);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Computes the page rank score from the "id-graph.tsv" file in the document directory.
     * The results of the computation can be saved in a class variable and will be later retrieved by `getPageRankScores`.
     */
    public void computePageRank(int numIterations) throws IOException {
        listOfPagesToEachPage = new HashMap<>();
        linkNumOfEachPage = new int[NUM_OF_FILE];
        Arrays.fill(pageRankScore, 1);

        InputStream is = new FileInputStream(documentDirectory + "/" + idGraphFileName);
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        String curLine;
        while ((curLine = reader.readLine()) != null) {
            String[] dataPair = curLine.split("\\s+");
            int pageID = Integer.valueOf(dataPair[0]);
            linkNumOfEachPage[pageID]++;

            int pageItLinksTo = Integer.valueOf(dataPair[1]);
            if (!listOfPagesToEachPage.containsKey(pageItLinksTo)) {
                listOfPagesToEachPage.put(pageItLinksTo, new ArrayList<>(asList(pageID)));
            } else listOfPagesToEachPage.get(pageItLinksTo).add(pageID);
        }

        for (int i = 0; i < numIterations; i++) {
            double[] newPageRankScore = new double[NUM_OF_FILE];
            for (int j = 0; j < NUM_OF_FILE; j++) {
                double secondPara = 0;
                if (!listOfPagesToEachPage.containsKey(j)) {
                    newPageRankScore[j] = pageRankScore[j];
                    continue;
                }
                List<Integer> linkList = listOfPagesToEachPage.get(j);
                for (int link : linkList) {
                    secondPara += pageRankScore[link] / linkNumOfEachPage[link];
                }
                newPageRankScore[j] = oneMinusDamping + dampingFactor * secondPara;
            }
            pageRankScore = newPageRankScore;
        }
    }

    /**
     * Gets the page rank score of all documents previously computed. Must be called after `cmoputePageRank`.
     * Returns an list of <DocumentID - Score> Pairs that is sorted by score in descending order (high scores first).
     */
    public List<Pair<Integer, Double>> getPageRankScores() {
        List<Pair<Integer, Double>> pageRankScores = new ArrayList<>();
        for (int i = 0; i < pageRankScore.length; i++) {
            pageRankScores.add(new Pair(i, pageRankScore[i]));
        }
        pageRankScores.sort(new Comparator<Pair<Integer, Double>>() {
            @Override
            public int compare(Pair<Integer, Double> o1, Pair<Integer, Double> o2) {
                return o2.getRight().compareTo(o1.getRight());
            }
        });
        return pageRankScores;
    }

    /**
     * Searches the ICS document corpus and returns the top K documents ranked by combining TF-IDF and PageRank.
     * <p>
     * The search process should first retrieve ALL the top documents from the InvertedIndex by TF-IDF rank,
     * by calling `searchTfIdf(query, null)`.
     * <p>
     * Then the corresponding PageRank score of each document should be retrieved. (`computePageRank` will be called beforehand)
     * For each document, the combined score is  tfIdfScore + pageRankWeight * pageRankScore.
     * <p>
     * Finally, the top K documents of the combined score are returned. Each element is a pair of <Document, combinedScore>
     * <p>
     * <p>
     * Note: We could get the Document ID by reading the first line of the document.
     * This is a workaround because our project doesn't support multiple fields. We cannot keep the documentID in a separate column.
     */
    public Iterator<Pair<Document, Double>> searchQuery(List<String> query, int topK, double pageRankWeight) {
        List<Pair<Document, Double>> searchTfIdfResult = indexManager.searchTfIdfHelper(query, null);
        Queue<Pair<Document, Double>> priorityQueue = new PriorityQueue<>(topK, new Comparator<Pair<Document, Double>>() {
            @Override
            public int compare(Pair<Document, Double> o1, Pair<Document, Double> o2) {
                return (int) (o1.getRight() - o2.getRight());
            }
        });
        for (Pair<Document, Double> record : searchTfIdfResult) {
            int id = Integer.valueOf(record.getLeft().getText().split("\n")[0]);
            double combinedScore = record.getRight() + pageRankWeight * pageRankScore[id];
            if (priorityQueue.size() < topK) {
                priorityQueue.add(new Pair<>(record.getLeft(), combinedScore));
            } else {
                if (combinedScore > priorityQueue.peek().getRight()) {
                    priorityQueue.poll();
                    priorityQueue.add(new Pair<>(record.getLeft(), combinedScore));
                }
            }
        }
        List<Pair<Document, Double>> result = new ArrayList<>();
        int len = priorityQueue.size();
        for (int i = 0; i < len; i++) {
            result.add(priorityQueue.poll());
        }
        Collections.reverse(result);
        return result.iterator();
    }

    private Document readDocumentFromFile(String fileName) throws IOException {
        InputStream is = new FileInputStream(fileName);
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        String id = reader.readLine();
        String url = reader.readLine();
        String document = reader.readLine();
        reader.close();
        is.close();
        return new Document(id + "\n" + url + "\n" + document);
    }
}
