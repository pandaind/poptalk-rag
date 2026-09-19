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
