# Knowledge directory

Drop documents here under `<personaId>/`, matching the persona ids PopTalk
uses (`data/personas/<personaId>/` there). PDF, DOCX, HTML, TXT, and
Markdown are all supported.

```
knowledge/
├── _shared/
│   └── company-overview.md
├── alice/
│   └── about.md
└── acme-support/
    └── product-docs.html
```

Anything under `_shared/` is visible to every persona in addition to their
own documents — handy for things that aren't specific to any one persona,
like a company overview or a shared FAQ. Every other folder stays private to
its own persona's API key.

Markdown files can optionally start with a YAML frontmatter block for your
own metadata, and their headings are picked up automatically so search
results can point back to the right section:

```markdown
---
department: sales
---

# Enterprise pricing
## Included seats
...
```

See the [root README](../README.md) for how ingestion and auth actually work.
