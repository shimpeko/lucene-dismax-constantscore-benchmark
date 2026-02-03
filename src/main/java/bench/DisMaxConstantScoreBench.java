package bench;

import java.io.IOException;
import java.util.List;

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
import org.apache.lucene.util.Version;

public class DisMaxConstantScoreBench {
  private static final String[] TERMS = {
      "chovolate",
      "chocolatee",
      "chlcolate",
      "chlocolate",
      "chcolate",
      "chocholate",
      "choolate",
      "cicholate",
      "chocoloate",
      "chocoloatw"
  };

  private static final int DOC_COUNT = 10000;
  private static final int WARMUP_ITERS = 100;
  private static final int MEASURE_ITERS = 5000;
  private static final int TOP_K = 20;

  public static void main(String[] args) throws Exception {
    System.out.println("Lucene version: " + Version.LATEST);
    System.out.println("Java version: " + System.getProperty("java.version"));
    System.out.println("Docs: " + DOC_COUNT + ", warmup: " + WARMUP_ITERS + ", iters: " + MEASURE_ITERS);

    Directory directory = new ByteBuffersDirectory();
    Analyzer analyzer = new WhitespaceAnalyzer();

    IndexWriterConfig config = new IndexWriterConfig(analyzer);
    config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

    String content = String.join(" ", TERMS);

    try (IndexWriter writer = new IndexWriter(directory, config)) {
      for (int i = 0; i < DOC_COUNT; i++) {
        Document doc = new Document();
        doc.add(new StringField("id", Integer.toString(i), Field.Store.NO));
        doc.add(new TextField("field1", content, Field.Store.NO));
        doc.add(new TextField("field2", content, Field.Store.NO));
        doc.add(new TextField("field3", content, Field.Store.NO));
        writer.addDocument(doc);
      }
      writer.commit();
      writer.forceMerge(1);
    }

    try (DirectoryReader reader = DirectoryReader.open(directory)) {
      IndexSearcher searcher = new IndexSearcher(reader);
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

  private static Query buildQuery() {
    // Query shape:
    // dis_max (tie=0)
    //   - boost 25 * constant_score( SHOULD( field1|field2 terms, minShouldMatch=1 ) )
    //   - boost 10 * constant_score( SHOULD( field3 terms, minShouldMatch=1 ) )
    Query branchA = buildBranch(new String[] {"field1", "field2"}, 25f);
    Query branchB = buildBranch(new String[] {"field3"}, 10f);
    return new DisjunctionMaxQuery(List.of(branchA, branchB), 0f);
  }

  private static Query buildBranch(String[] fields, float boost) {
    BooleanQuery.Builder builder = new BooleanQuery.Builder();
    for (String term : TERMS) {
      for (String field : fields) {
        builder.add(new org.apache.lucene.search.TermQuery(new Term(field, term)), BooleanClause.Occur.SHOULD);
      }
    }
    builder.setMinimumNumberShouldMatch(1);

    Query branch = builder.build();
    Query constantScore = new ConstantScoreQuery(branch);
    return new BoostQuery(constantScore, boost);
  }
}
