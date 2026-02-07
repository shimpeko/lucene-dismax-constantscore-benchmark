# Lucene dis_max + constant_score benchmark

Small standalone reproduction of a dis_max + constant_score regression between Lucene 9.12.2 and 10.3.2.

## Run

```
./gradlew -q run -PluceneVersion=9.12.2 --no-daemon
./gradlew -q run -PluceneVersion=10.3.2 --no-daemon
```

Optionally run:
```
export JAVA_HOME=/opt/homebrew/opt/openjdk
```

Optional dataset controls:
```
-PdatasetPath=data/wiki.jsonl
```

Optional query controls:
```
-PtermMode=term        # term or terms (TermInSetQuery)
-Pdismax=true          # true or false
-PtermCount=20         # number of terms used from TERMS[]
-PprintDocFreq=false   # default true
```

## Data

The benchmark indexes a committed JSONL file at `data/wiki.jsonl`:
- `title` field: page title
- `body` field: cleaned page text

The query uses dis_max with constant_score branches for `title` and `body`.

## Output format

```
Lucene version: <version>
Java version: <version>
Docs: <indexed>, warmup: <warmup>, iters: <iters>
Total ms: <total>, Avg ms/iter: <avg>
```
