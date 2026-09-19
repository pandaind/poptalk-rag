# PopTalk RAG

An [MCP](https://modelcontextprotocol.io) server that gives [PopTalk](https://github.com/pandaind/poptalk)
personas access to a retrieval-augmented knowledge base — a separate project,
deployed and versioned independently of PopTalk itself. RAG (chunking,
embeddings, a vector store) is a different problem from "serve a chat
widget," so PopTalk connects to this over the network rather than owning that
logic itself.

## Architecture

- **MCP server** (Spring AI, Streamable HTTP) exposes one tool,
  `search_knowledge_base`, that PopTalk's chat model can call mid-conversation
  — including more than once per turn, for questions that need more than one
  lookup (see [Multi-hop questions](#multi-hop-questions)).
- **Vector store**: PostgreSQL + [pgvector](https://github.com/pgvector/pgvector).
- **Embeddings**: Ollama (local, free, default) or OpenAI — configurable.
- **Search**: hybrid vector + keyword, plus LLM reranking of the candidate
  pool — see [Ranking](#ranking) below.
- **Ingestion**: an Apache Camel pipeline watches `knowledge/<personaId>/`,
  parses whatever it finds (PDF/DOCX/HTML/TXT/MD, via Apache Tika, or raw
  text with YAML frontmatter for `.md`), chunks it, embeds each chunk, and
  upserts it into pgvector — automatically, on a poll interval, with a
  Postgres-persisted idempotent repository so unchanged files are never
  needlessly re-embedded, and content-hash-based duplicate detection so the
  same content ingested under two filenames is only embedded once. Deleted
  files are detected separately and their vectors removed.

## Ranking

Retrieval is three-stage, not plain top-K by cosine similarity alone:

1. **Vector search** — pgvector's HNSW index, ranked by cosine similarity
   (`vector_cosine_ops`), scoped to the caller's persona plus the shared
   namespace. This first stage fetches a *wider* candidate pool than the
   requested `topK` (`RERANK_CANDIDATE_POOL_MULTIPLIER`, capped by
   `RERANK_MAX_CANDIDATE_POOL`), and drops anything below
   `SIMILARITY_THRESHOLD` — without a threshold, low-relevance chunks would
   pad out to `topK` instead of being excluded when there just isn't `topK`
   worth of good matches.
2. **Keyword search (hybrid)** — a Postgres full-text-search leg
   (`plainto_tsquery`/`ts_rank` over a generated `tsvector` column on the
   same table) runs alongside vector search, since pure embedding similarity
   can miss exact-term queries. The two ranked lists are merged with
   Reciprocal Rank Fusion. Toggle with `HYBRID_SEARCH_ENABLED` — see
   `HybridSearchService`.
3. **LLM reranking** — the merged candidate pool is re-scored by a chat model
   (`RERANK_PROVIDER`/`RERANK_MODEL`), which returns only the actual `topK`
   in relevance order, plus a `sufficient` flag: if even the best candidates
   don't really answer the query, `search_knowledge_base` prefixes its
   response with a caveat telling the calling model to say so rather than
   guess, instead of silently returning weak context as if it were solid.

Reranking is a quality improvement, not a hard dependency: if it's disabled
(`RERANK_ENABLED=false`), unconfigured, or the rerank call fails for any
reason, `search_knowledge_base` falls back to the hybrid-search order
directly (no sufficiency caveat is added in that case either, since there's
no judgment to report). See `LlmReranker`, `HybridSearchService`, and
`KnowledgeBaseTool`.

Returned results are grouped into citation-headed blocks (`[source —
heading]`), with consecutive chunks from the same document merged under one
header, and the whole response capped at `MAX_CONTEXT_CHARS` regardless of
`topK`.

## Multi-hop questions

`search_knowledge_base` can be called more than once in the same
conversation turn — Spring AI's tool-calling loop (used by PopTalk's
`AiChatService`) has no call-count limit, so a question needing information
from two different documents already works by the model issuing two
searches. The tool's description says as much, so a persona prompt doesn't
call it only once out of habit.

## Duplicate and stale content

Two files with identical extracted text (e.g. copy-pasted into two
filenames) are only embedded once: `DocumentParsingProcessor` computes a
content hash per file, and `DuplicateDocumentGuard` skips (and logs) any
file whose hash already matches another tracked file for the same persona.
Editing a file re-hashes and re-embeds it normally; a file edited to *become*
a duplicate of another has its own stale vectors cleaned up rather than left
behind.

## Multi-tenancy and auth

Each PopTalk persona gets its **own API key**. That key is the *only* source
of tenant identity in this system — a request's persona is derived from which
key authenticated it, never from a header or a tool argument the calling
model could be prompted into supplying. This matters: without it, a
prompt-injected visitor on one persona's chat widget could ask the model to
fetch a different persona's private knowledge base.

```
PERSONA_API_KEYS=alice:key1,acme-support:key2
```

On the PopTalk side, set the matching key in that persona's
`persona.properties`:

```properties
mcp=true
mcp-api-key=key1
```

## Knowledge layout

```
knowledge/
├── _shared/
│   └── company-overview.md
├── alice/
│   ├── about.md
│   └── faq.pdf
└── acme-support/
    └── product-docs.html
```

Drop files in, and they're picked up on the next poll (`KNOWLEDGE_POLL_INTERVAL_MS`,
default 60s) — or trigger an immediate pass:

Documents under the reserved `_shared/` folder (name configurable via
`SHARED_PERSONA_ID`) are retrievable by every persona, in addition to their
own — see `knowledge/README.md` for details. Every other folder stays
strictly scoped to its own persona's API key.

```bash
curl -X POST http://localhost:8090/admin/reindex -H "Authorization: Bearer <any-valid-key>"
```

### Metadata and markdown frontmatter

Every chunk is tagged with `persona_id`, `source`, `chunk_index`,
`content_hash`, and `ingested_at` automatically. Markdown files additionally
get:

- **Heading path** — extracted from `#`/`##`/... lines, so a chunk under
  `## Enterprise` inside `# Pricing` is tagged `heading_path: "Pricing >
  Enterprise"` and shown in its citation header at query time.
- **YAML frontmatter** — an optional `---`-delimited block at the top of the
  file, merged into every chunk's metadata as-is (any keys — `department`,
  `version`, `effective_date`, `tags`, etc.):

  ```markdown
  ---
  department: sales
  effective_date: 2026-01-01
  tags: [pricing, contracts]
  ---

  # Enterprise pricing
  ...
  ```

PDF/DOCX/HTML/TXT files don't get heading paths or frontmatter — Tika
normalizes them to plain text before this pipeline sees them, so there's no
reliable structure left to recover.

### Chunking

`CHUNK_SIZE`/`MIN_CHUNK_SIZE_CHARS` control `TokenTextSplitter`'s sizing
(defaults match its own built-in defaults). It has no overlap of its own, so
`CHUNK_OVERLAP_CHARS` trailing characters of each chunk are carried into the
next one to reduce context loss at chunk boundaries.

## Running it

```bash
cp .env.example .env
# edit .env — at minimum set PERSONA_API_KEYS
docker-compose up -d
```

This brings up Postgres/pgvector and the app together. By default it points
at `host.docker.internal:11434` for Ollama embeddings — if PopTalk's backend
already has an Ollama instance running, point at that same one rather than
running a second.

### Local development

```bash
docker-compose up -d postgres   # just the database
export $(grep -v '^#' .env | xargs)
./mvnw spring-boot:run
```

## Evaluating retrieval quality

`RetrievalRegressionTest` (`src/test/java/in/pandac/rag/eval/`) is a
retrieval-recall regression check, not full LLM-as-judge answer scoring: it
seeds known documents into a real Testcontainers-backed Postgres/pgvector,
calls `search_knowledge_base` directly for a set of fixture queries
(`src/test/resources/eval/retrieval-cases.yaml`), and asserts each one's
expected source shows up — a repeatable way to check that a chunking/ranking
change hasn't regressed retrieval before shipping it. It uses a deterministic
fake embedding model (not a live Ollama/OpenAI call) so it only needs Docker,
nothing external. Excluded from the default build since Docker isn't
guaranteed in every environment — run it explicitly:

```bash
mvn test -DexcludedGroups=
```

Not covered: end-to-end generated-answer quality (would need an LLM-as-judge
harness — a heavier future addition, not part of this one) and automatic
conflicting-data resolution (deliberately left to the calling LLM, given
`ingested_at`/`effective_date` metadata in citation headers, rather than
built as an automatic mechanism here).

## Known limitation

If a knowledge file is edited at the exact moment the embedding provider is
unreachable, its old (still-good) chunks are removed before the new ones can
be created, leaving that one file's search results empty until a later poll
succeeds. See the comment on `EmbeddingUpsertProcessor` for why, and what a
proper fix would need (a batch/version column, so the delete step doesn't
also catch the rows it just inserted).

## License

MIT
