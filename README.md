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

## Output format

```
Lucene version: <version>
Java version: <version>
Docs: 5000, warmup: 200, iters: 1000
Total ms: <total>, Avg ms/iter: <avg>
```
