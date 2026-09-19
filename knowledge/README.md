# Knowledge directory

Drop files here under `<personaId>/`, matching the persona ids configured in
PopTalk (`backend/data/personas/<personaId>/` there). Supported formats:
`.md`, `.txt`, `.pdf`, `.docx`, `.html`, `.htm`.

Example:

```
knowledge/
└── alice/
    ├── about.md
    └── faq.pdf
```

## Sharing knowledge across personas

Files under the reserved `_shared/` folder (configurable via
`SHARED_PERSONA_ID`) are retrievable by *every* persona, in addition to their
own documents — useful for content that isn't specific to any one persona
(company-wide policies, a shared product catalog, etc.):

```
knowledge/
├── _shared/
│   └── company-overview.md
├── alice/
│   └── about.md
└── acme-support/
    └── product-docs.html
```

No per-persona API key grants access to another *persona's* folder — only
to `_shared/`, which every key implicitly includes.

See the root [README](../README.md) for how ingestion and auth work.
