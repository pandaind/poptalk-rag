# RAG quality: problems and solutions

This document tracks the standard set of RAG failure modes against what
`poptalk-rag` actually does — what's already solved, what's partial, and the
concrete plan for the rest. OCR is explicitly out of scope.

**Status: implemented.** Everything in the "Plan for the rest" section below
has been built, compiles clean, and the default test suite passes. Two notes
on what shipped vs. the original plan text: the groundedness caveat (#6) is
only added when the reranker explicitly reports `sufficient=false` — not
also when reranking is merely unavailable/disabled, since that's a
deployment choice with no evidence of insufficiency either way, and adding
a caveat there would just be noise. The eval harness (#9) is run via
`mvn test -DexcludedGroups=` rather than a `-Deval=true` flag — the actual
Maven mechanism wired up in `pom.xml`.

## Status

| # | Challenge | Status | Current mechanism |
|---|---|---|---|
| 1 | Document Processing | **Solved** (except OCR) | Apache Tika parses PDF/DOCX/HTML/TXT; markdown is parsed as raw text. Staleness (edits/deletes) and now content-level duplicate detection (`DuplicateDocumentGuard` + a `content_hash` column on `ingested_files`) are both handled. Table-structure preservation within Tika's extraction remains unaddressed — genuinely OCR/format-extraction-adjacent territory, out of scope per the original ask. |
| 2 | Chunking | **Solved** | `TokenTextSplitter` sizing is configurable (`CHUNK_SIZE`/`MIN_CHUNK_SIZE_CHARS`); a trailing-character overlap (`CHUNK_OVERLAP_CHARS`) approximates a sliding window; markdown chunks carry a `heading_path` prefix for self-contained context. |
| 3 | Embeddings | **Solved** | One embedding model per deployment, explicitly selected (`EMBEDDING_PROVIDER`), with `EMBEDDING_DIMENSIONS` required to match — pgvector rejects mismatched inserts rather than silently corrupting the index. |
| 4 | Retrieval | **Solved** | `similarityThreshold`, a wider candidate pool than the final topK, `filterExpression` for tenant + shared-knowledge scoping, now fused with a keyword-search leg (see #6). |
| 5 | Ranking | **Solved** | Three-stage: vector similarity + keyword search (fused via RRF), then `LlmReranker` re-scores the merged pool and returns the true topK in relevance order, plus a sufficiency signal (see #9). |
| 6 | Search Strategy (hybrid) | **Solved** | `HybridSearchService` adds a Postgres full-text-search leg (`content_tsv`/`ts_rank`) alongside vector similarity, merged via Reciprocal Rank Fusion. Toggle: `HYBRID_SEARCH_ENABLED`. |
| 7 | Metadata | **Solved** | `persona_id`/`source`/`chunk_index`/`content_hash`/`ingested_at` on every chunk; markdown files additionally get `heading_path` and any YAML frontmatter fields (department/version/tags/etc.), all filterable via the existing JSON metadata column. |
| 8 | Context (noise/token waste) | **Solved** | Citation-headed result blocks, adjacent same-source chunks merged under one header, and a hard `MAX_CONTEXT_CHARS` cap regardless of `topK`. |
| 9 | Hallucination | **Partially addressed (signal, not enforcement)** | `LlmReranker` reports whether the candidates actually answer the query; `KnowledgeBaseTool` prefixes a caveat when they don't. Actual grounding still depends on the persona's system prompt on the PopTalk side — outside this repo's control by design. |
| 10 | Conflicting Data | **Partially addressed (surfaced, not resolved)** | `ingested_at`/`effective_date` appear in citation headers so a model told to prefer newer sources has what it needs. No automatic contradiction detection — deliberately left to the calling LLM, not built as a mechanism here. |
| 11 | Multi-hop Queries | **Solved (documented)** | Confirmed no `maxSteps`/call-count cap in PopTalk's `AiChatService`; `search_knowledge_base`'s tool description now explicitly invites multiple calls per turn. |
| 12 | Evaluation | **Solved (retrieval regression, not answer-quality)** | `RetrievalRegressionTest` seeds a real Testcontainers Postgres/pgvector and asserts Recall@K against fixture queries — run via `mvn test -DexcludedGroups=`. LLM-as-judge answer scoring remains a future stretch goal. |

## Plan for the rest

Everything below lives in `poptalk-rag` only — no PopTalk-side code changes.
Built as one pass, not staged phases, matching how the rest of this project
has been delivered.

Verified before writing this plan: `PgVectorStore`'s default table
(`vector_store`, columns `id, content, metadata(json), embedding`),
`TokenTextSplitter`'s real constructor (chunk size / min-chars are
configurable, but it has **no overlap parameter**), and that SnakeYAML 2.2 is
already on the classpath transitively (no new dependency needed for
frontmatter parsing).

### 1. Richer metadata (foundation for dedup, freshness, filtering)

`ChunkingProcessor` currently tags each chunk with `persona_id`, `source`,
`chunk_index`. Add, computed once per source file in `DocumentParsingProcessor`
and carried through to every chunk in `ChunkingProcessor`:

- `content_hash` — SHA-256 of the whole parsed document's text. Foundation
  for dedup.
- `ingested_at` — `Instant.now().toString()`, set at chunk-creation time.
- Optional **YAML frontmatter** for `.md` source files: a `---\nkey: value\n---`
  block parsed with `org.yaml.snakeyaml.Yaml` into arbitrary metadata (e.g.
  `department`, `version`, `effective_date`, `tags`). Stripped from the body
  before chunking. Non-markdown files don't get this — documented limitation.
- `heading_path` — for markdown files only, extracted from the raw file text
  (regex over `^#{1,6}\s+.*` lines) before Tika/chunking touches it, giving
  each chunk the nearest preceding heading (e.g. `"Pricing > Enterprise"`).
  PDF/DOCX/HTML don't get this — Tika normalizes them to plain text before we
  see them, so there's no cheap way to recover heading structure.

All of this rides on the vector store's existing JSON `metadata` column — no
schema change needed, since `persona_id` filtering already proves arbitrary
metadata is filterable via `filterExpression`.

**Files:** `DocumentParsingProcessor.java`, `ChunkingProcessor.java`.

### 2. Chunking: configurable sizing + light overlap + heading context

- Make `TokenTextSplitter`'s `chunkSize`/`minChunkSizeChars` configurable via
  `app.chunking.chunk-size` (default 800) and `app.chunking.min-chunk-size-chars`
  (default 350), built via `TokenTextSplitter.builder()`.
- Add simple **overlap** (the splitter itself has none): after splitting,
  prepend the trailing `app.chunking.overlap-chars` (default 100) characters
  of the previous chunk onto each subsequent chunk's text (skip for the first
  chunk of a document).
- Prepend `heading_path` (when present) as a literal one-line prefix on the
  chunk text itself before embedding, e.g. `"Section: Pricing >
  Enterprise\n\n<chunk text>"` — makes the chunk more semantically
  self-contained for retrieval, not just tagged in metadata.

**Files:** `ChunkingProcessor.java`, new `app.chunking.*` properties.

### 3. Content-level duplicate detection

Reuse `IngestedFileTracker`'s existing Postgres table rather than adding new
infrastructure:

- Add a nullable `content_hash VARCHAR(64)` column via
  `ALTER TABLE ingested_files ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64)`.
- Extend `markIngested(...)` to also store the hash; add
  `findDuplicate(personaId, contentHash, excludingRelativePath)`.
- New `DuplicateDocumentGuard` Camel processor, inserted right after
  `DocumentParsingProcessor`: if a duplicate is found, log it, mark the file
  ingested (so it isn't retried every poll) without embedding anything, and
  short-circuit the route.

**Files:** `IngestedFileTracker.java`, new `ingest/DuplicateDocumentGuard.java`,
`IngestionRoute.java`.

### 4. Hybrid search (vector + keyword)

- New `search/HybridSearchSchemaInitializer` (`@PostConstruct`, depends on
  `VectorStore` so it runs after Spring AI's own schema init): adds a
  generated `content_tsv tsvector` column on `vector_store` plus a GIN index
  — additive, idempotent, same pattern already used for
  `CAMEL_MESSAGEPROCESSED`/`ingested_files`.
- New `search/HybridSearchService`: runs the existing vector-similarity leg
  unchanged, plus a keyword leg (parameterized `JdbcTemplate` query against
  `content_tsv @@ plainto_tsquery(...)`, scoped by the same tenant filter),
  merges both ranked lists via **Reciprocal Rank Fusion**
  (`score = Σ 1/(60 + rank)` per leg, deduped by row `id`). The merged list
  feeds `LlmReranker` exactly as today.
- `app.search.hybrid.enabled` (default `true`) as an escape hatch back to
  vector-only.

**Files:** new `search/HybridSearchSchemaInitializer.java`,
`search/HybridSearchService.java`; `KnowledgeBaseTool.java` calls the new
service instead of `vectorStore.similaritySearch(...)` directly.

### 5. Context control (noise / token waste)

In `KnowledgeBaseTool.search()`:

- Prefix each returned block with a citation header:
  `[<source> — <heading_path, if present>]`.
- Merge adjacent chunks from the same `source` with consecutive
  `chunk_index` values into one block instead of two.
- Add `app.rag.max-context-chars` (default 6000): hard-truncate the final
  joined text if it exceeds this, regardless of `topK`.

**Files:** `KnowledgeBaseTool.java`.

### 6. Hallucination: a groundedness signal, not a guarantee

- Extend `LlmReranker`'s structured output:
  `RerankResult(List<Integer> rankedIndices, boolean sufficient)` — one extra
  field in the same LLM call. Update the system prompt to ask for it.
- When `sufficient == false` (or reranking is unavailable), prefix the
  returned text with a caveat telling the calling model the retrieved
  context may be incomplete. This is a signal, not an enforcement mechanism
  — actual grounding still depends on the persona's system prompt on the
  PopTalk side, which is out of scope here.

**Files:** `LlmReranker.java`, `KnowledgeBaseTool.java`.

### 7. Conflicting data: surface freshness, don't auto-resolve

No automatic contradiction detection — that's left to the calling LLM once
it has the right signal. `ingested_at` and `effective_date` (from #1) are
already in the citation header from #5, so a model instructed (via its
persona system prompt) to prefer newer sources has what it needs. Deliberate
scope boundary, not a gap to close later.

### 8. Multi-hop queries: verify + document, no new mechanism

Already works mechanically — confirmed no `maxSteps`/tool-call cap in
PopTalk's `AiChatService`. Action: document this in the README as a verified
property, and tweak `search_knowledge_base`'s `@Tool` description to note it
may be called multiple times per turn for multi-part questions.

### 9. Evaluation harness (retrieval regression, not full LLM-as-judge)

- Add test-scope dependencies: `org.testcontainers:postgresql`,
  `org.testcontainers:junit-jupiter`, `spring-boot-testcontainers` (none
  currently in `pom.xml`).
- New `src/test/resources/eval/retrieval-cases.yaml`: fixtures of
  `{ personaId, query, expectedSourceContains[] }`.
- New `RetrievalRegressionTest` (`@SpringBootTest`, Testcontainers-backed
  real Postgres/pgvector): seeds known documents, calls
  `KnowledgeBaseTool.search()` directly, asserts expected sources show up,
  logs Precision@K/Recall@K. Tagged (`@Tag("eval")`) and run via
  `mvn test -Deval=true` rather than the default build, since it needs a
  reachable embedding model and Docker.
- LLM-as-judge answer-quality scoring is explicitly descoped as a future
  stretch goal.

**Files:** `pom.xml` (test deps), new
`src/test/resources/eval/retrieval-cases.yaml`, new
`src/test/java/in/pandac/rag/eval/RetrievalRegressionTest.java`.

## Explicitly excluded from this pass

OCR, automatic conflict/contradiction resolution, LLM-as-judge end-to-end
answer evaluation, and any PopTalk-side code changes (persona prompting
recommendations are documented, not enforced).

## Delivery order

Dependency-driven, not phased delivery: metadata foundation (1) first since
dedup/hybrid/context build on it, then chunking (2), dedup (3), hybrid
search (4), context control (5), groundedness (6), conflicting-data docs (7),
multi-hop docs (8), and the eval harness (9) last since it exercises
everything above.

## Verification

1. `mvn -o compile` after each major unit, full `mvn -o test` (excluding the
   `eval`-tagged test) at the end.
2. Drop two files with identical content under different names into
   `knowledge/test-persona/`, trigger `/admin/reindex`, confirm only one gets
   embedded and the tracker records the duplicate.
3. Add a markdown file with headings and YAML frontmatter; confirm chunks
   carry `heading_path`/`department`/etc. metadata and the citation header
   appears in `search_knowledge_base`'s output.
4. Query with an exact keyword that a pure-embedding search might miss;
   confirm the hybrid leg surfaces the right chunk
   (`app.search.hybrid.enabled=true` vs `false`).
5. Ask a question the knowledge base can't answer at all; confirm the
   groundedness caveat appears in the tool's returned text.
6. Run `mvn test -Deval=true` (Docker required) and confirm the retrieval
   regression test passes against the seeded fixtures.
7. Commit and push per the standing rule: correct global git identity
   (`pandaind` / `chittaranjan@hotmail.com`), no AI co-authorship.
