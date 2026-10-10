# PopTalk RAG

An [MCP](https://modelcontextprotocol.io) server that gives [PopTalk](https://github.com/pandaind/poptalk)
personas access to a knowledge base built from their own documents. It's a
separate project on purpose — parsing files, chunking, embeddings, and a
vector store are a different problem from serving a chat widget, so PopTalk
connects to this over the network instead of owning that logic itself.

Drop files into a persona's folder and this keeps itself in sync
automatically, picking up new, changed, and deleted files on a poll. When a
persona's chat model needs more than it already knows, it calls a single
tool, `search_knowledge_base`, which searches with both meaning and exact
keywords and reranks the results before handing them back, so what actually
answers the question tends to land at the top.

## Multi-tenancy

Every persona gets its own API key, and that key is the only thing that
decides which documents a request can see — never something the calling
model is told or asked to supply itself. That distinction matters: without
it, a visitor could try to talk a persona's chat model into fetching a
different persona's private documents.

```
knowledge/
├── _shared/               # visible to every persona
│   └── company-overview.md
├── alice/
│   ├── about.md
│   └── faq.pdf
└── acme-support/
    └── product-docs.html
```

Give each persona its own key in `PERSONA_API_KEYS` here, and the matching
`mcp-api-key` in that persona's `persona.properties` on the PopTalk side. See
[`knowledge/README.md`](knowledge/README.md) for how to organize what goes
in the folder itself.

## Pulling from a static site

If your content lives on a static site, you don't need to copy files to this server. A site
generator can publish its pages as one versioned `knowledge.json`, and this server pulls it on a
schedule. [Meteorite](https://github.com/pandaind/meteorite) does this with
`chat.knowledge.publish: true`.

```env
KNOWLEDGE_SITE_SOURCES=acme-support=https://example.com/knowledge.json
KNOWLEDGE_SITE_POLL_MS=600000      # default: every 10 minutes
```

Each document is written to `knowledge/<personaId>/<path>.md` with front matter (including the
page's public `url`), and the normal ingestion takes over, so chunking, embedding, change
detection and deletion behave exactly as for hand-placed files.

It is safe to leave running: a failed request, a non-JSON or oversized response, an unknown
manifest version, unsafe file paths or an empty document list change nothing on disk, so an
outage of the site can never wipe a knowledge base. Unchanged sites cost one conditional
request (`ETag`). Files you placed in the same folder are never overwritten or removed.

## Running it

```bash
cp .env.example .env
# edit .env — at minimum set PERSONA_API_KEYS
docker-compose up -d
```

That brings up Postgres/pgvector and the app together. It defaults to
`host.docker.internal:11434` for embeddings — if PopTalk already has an
Ollama instance running, point at that one instead of starting a second.

Files are picked up within a minute by default; if you don't want to wait,
`POST /admin/reindex` with any valid persona key triggers an immediate pass.

For local development, run just the database in Docker and the app itself
with Maven:

```bash
docker-compose up -d postgres
export $(grep -v '^#' .env | xargs)
./mvnw spring-boot:run
```

`.env.example` documents every setting worth tuning — embeddings provider,
reranking, chunk sizing, and so on.

## License

MIT
