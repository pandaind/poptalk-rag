# PopTalk RAG

An [MCP](https://modelcontextprotocol.io) server that gives [PopTalk](https://github.com/pandaind/poptalk)
personas access to a retrieval-augmented knowledge base — a separate project,
deployed and versioned independently of PopTalk itself. RAG (chunking,
embeddings, a vector store) is a different problem from "serve a chat
widget," so PopTalk connects to this over the network rather than owning that
logic itself.

## Architecture

- **MCP server** (Spring AI, Streamable HTTP) exposes one tool,
  `search_knowledge_base`, that PopTalk's chat model can call mid-conversation.
- **Vector store**: PostgreSQL + [pgvector](https://github.com/pgvector/pgvector).
- **Embeddings**: Ollama (local, free, default) or OpenAI — configurable.
- **Ingestion**: an Apache Camel pipeline watches `knowledge/<personaId>/`,
  parses whatever it finds (PDF/DOCX/HTML/TXT/MD, via Apache Tika), chunks it,
  embeds each chunk, and upserts it into pgvector — automatically, on a poll
  interval, with a Postgres-persisted idempotent repository so unchanged
  files are never needlessly re-embedded. Deleted files are detected
  separately and their vectors removed.

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
├── alice/
│   ├── about.md
│   └── faq.pdf
└── acme-support/
    └── product-docs.html
```

Drop files in, and they're picked up on the next poll (`KNOWLEDGE_POLL_INTERVAL_MS`,
default 60s) — or trigger an immediate pass:

```bash
curl -X POST http://localhost:8090/admin/reindex -H "Authorization: Bearer <any-valid-key>"
```

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

## Known limitation

If a knowledge file is edited at the exact moment the embedding provider is
unreachable, its old (still-good) chunks are removed before the new ones can
be created, leaving that one file's search results empty until a later poll
succeeds. See the comment on `EmbeddingUpsertProcessor` for why, and what a
proper fix would need (a batch/version column, so the delete step doesn't
also catch the rows it just inserted).

## License

MIT
