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

If a Markdown file's front matter has a `url:` (an `http(s)` address), search results cite it
as `[source — heading] <url>`, so the model can point visitors at a real page instead of a file
path. Static-site generators that export their pages here, such as
[Meteorite](https://github.com/pandaind/meteorite) (`chat.knowledge.dir`), set it for you.

Lines starting with `#` inside fenced code blocks (``` or ~~~) are code, not headings, and do not
affect the heading path shown in citations.

See the [root README](../README.md) for how ingestion and auth actually work.
