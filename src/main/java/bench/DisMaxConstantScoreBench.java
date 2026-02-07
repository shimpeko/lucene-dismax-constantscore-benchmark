package bench;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.DisjunctionMaxQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.Version;

public class DisMaxConstantScoreBench {
  private static final String[] TERMS = {
      "names",
      "nbsp",
      "part",
      "st",
      "are",
      "last",
      "at",
      "united",
      "but",
      "year",
      "name",
      "its",
      "to",
      "mostly",
      "his",
      "http",
      "they",
      "hard",
      "bay",
      "title",
      "city",
      "state",
      "country",
      "river",
      "mountain",
      "school",
      "music",
      "film",
      "game"
  };

  private static final int WARMUP_ITERS = 50000;
  private static final int MEASURE_ITERS = 1000000;
  private static final int TOP_K = 20;

  public static void main(String[] args) throws Exception {
    System.out.println("Lucene version: " + Version.LATEST);
    System.out.println("Java version: " + System.getProperty("java.version"));

    Directory directory = new ByteBuffersDirectory();
    Analyzer analyzer = new WhitespaceAnalyzer();

    IndexWriterConfig config = new IndexWriterConfig(analyzer);
    config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

    String datasetPath = System.getProperty("datasetPath", "data/wiki.jsonl").trim();
    if (datasetPath.isEmpty()) {
      throw new IllegalArgumentException("Set -DdatasetPath=/path/to/wiki.jsonl");
    }
    int indexedDocs;
    try (IndexWriter writer = new IndexWriter(directory, config)) {
      indexedDocs = indexFromJsonLines(Path.of(datasetPath), writer);
      writer.commit();
      writer.forceMerge(1);
    }

    System.out.println("Docs: " + indexedDocs + ", warmup: " + WARMUP_ITERS + ", iters: " + MEASURE_ITERS);
    System.out.println("Mode: " + getTermMode() + ", dismax: " + isDisMaxEnabled() + ", termCount: " + getTermCount());

    try (DirectoryReader reader = DirectoryReader.open(directory)) {
      IndexSearcher searcher = new IndexSearcher(reader);
      if (isPrintDocFreqEnabled()) {
        printDocFreqs(reader);
      }
      Query query = buildQuery();

      for (int i = 0; i < WARMUP_ITERS; i++) {
        searcher.search(query, TOP_K);
      }

      long start = System.nanoTime();
      for (int i = 0; i < MEASURE_ITERS; i++) {
        searcher.search(query, TOP_K);
      }
      long end = System.nanoTime();

      double totalMs = (end - start) / 1_000_000.0;
      double avgMs = totalMs / MEASURE_ITERS;

      System.out.printf("Total ms: %.3f, Avg ms/iter: %.6f%n", totalMs, avgMs);
    }

    directory.close();
  }

  private static int indexFromJsonLines(Path path, IndexWriter writer) throws Exception {
    if (!Files.isRegularFile(path)) {
      throw new IllegalArgumentException("datasetPath is not a file: " + path);
    }
    ObjectMapper mapper = new ObjectMapper();
    int count = 0;
    try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        JsonNode node = mapper.readTree(line);
        String title = node.path("title").asText("");
        String body = node.path("body").asText("");
        if (body.isBlank()) {
          continue;
        }
        Document doc = new Document();
        doc.add(new StringField("id", Integer.toString(count), Field.Store.NO));
        doc.add(new TextField("title", title, Field.Store.NO));
        doc.add(new TextField("body", body, Field.Store.NO));
        writer.addDocument(doc);
        count++;
      }
    }
    return count;
  }

  private static Query buildQuery() {
    // Query shape:
    // dis_max (tie=0)
    //   - boost 25 * constant_score( SHOULD( title terms, minShouldMatch=1 ) )
    //   - boost 10 * constant_score( SHOULD( body terms, minShouldMatch=1 ) )
    // or, if dismax=false:
    //   - constant_score( SHOULD( title+body terms, minShouldMatch=1 ) )
    if (isDisMaxEnabled()) {
      Query branchA = buildDismaxBranch(new String[] {"title"}, 25f);
      Query branchB = buildDismaxBranch(new String[] {"body"}, 10f);
      return new DisjunctionMaxQuery(List.of(branchA, branchB), 0f);
    }
    Query merged = buildTermQuery(new String[] {"title", "body"});
    return new BoostQuery(new ConstantScoreQuery(merged), 25f);
  }

  private static Query buildDismaxBranch(String[] fields, float boost) {
    Query branch = buildTermQuery(fields);
    Query constantScore = new ConstantScoreQuery(branch);
    if (boost == 1f) {
      return constantScore;
    }
    return new BoostQuery(constantScore, boost);
  }

  private static Query buildTermQuery(String[] fields) {
    TermMode mode = getTermMode();
    int termCount = getTermCount();
    String[] terms = sliceTerms(termCount);

    if (mode == TermMode.TERM_IN_SET) {
      BooleanQuery.Builder builder = new BooleanQuery.Builder();
      for (String field : fields) {
        List<BytesRef> refs = new java.util.ArrayList<>(terms.length);
        for (String term : terms) {
          refs.add(new BytesRef(term));
        }
        builder.add(new org.apache.lucene.search.TermInSetQuery(field, refs), BooleanClause.Occur.SHOULD);
      }
      builder.setMinimumNumberShouldMatch(1);
      return builder.build();
    } else {
      BooleanQuery.Builder builder = new BooleanQuery.Builder();
      for (String field : fields) {
        for (String term : terms) {
          builder.add(new org.apache.lucene.search.TermQuery(new Term(field, term)), BooleanClause.Occur.SHOULD);
        }
      }
      builder.setMinimumNumberShouldMatch(1);
      return builder.build();
    }
  }

  private enum TermMode {
    TERM_QUERY,
    TERM_IN_SET
  }

  private static TermMode getTermMode() {
    String mode = System.getProperty("termMode", "term").trim().toLowerCase();
    if ("terms".equals(mode) || "terminset".equals(mode) || "term_in_set".equals(mode)) {
      return TermMode.TERM_IN_SET;
    }
    return TermMode.TERM_QUERY;
  }

  private static boolean isDisMaxEnabled() {
    return Boolean.parseBoolean(System.getProperty("dismax", "true").trim());
  }

  private static int getTermCount() {
    String value = System.getProperty("termCount", Integer.toString(TERMS.length)).trim();
    try {
      int count = Integer.parseInt(value);
      if (count <= 0) {
        return TERMS.length;
      }
      return Math.min(count, TERMS.length);
    } catch (NumberFormatException e) {
      return TERMS.length;
    }
  }

  private static boolean isPrintDocFreqEnabled() {
    return Boolean.parseBoolean(System.getProperty("printDocFreq", "true").trim());
  }

  private static String[] sliceTerms(int count) {
    if (count >= TERMS.length) {
      return TERMS;
    }
    String[] out = new String[count];
    System.arraycopy(TERMS, 0, out, 0, count);
    return out;
  }

  private static void printDocFreqs(DirectoryReader reader) throws IOException {
    int termCount = getTermCount();
    String[] terms = sliceTerms(termCount);
    String[] fields = {"title", "body"};
    System.out.println("DocFreqs (first " + termCount + " terms):");
    for (String term : terms) {
      StringBuilder sb = new StringBuilder();
      sb.append("  ").append(term);
      for (String field : fields) {
        int df = reader.docFreq(new Term(field, term));
        sb.append(" ").append(field).append("=").append(df);
      }
      System.out.println(sb);
    }
  }
}
