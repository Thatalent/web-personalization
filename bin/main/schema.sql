CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS asset (
                                     id         TEXT PRIMARY KEY,
                                     site       TEXT NOT NULL,
                                     type       TEXT NOT NULL,          -- 'hero' | 'tiles' | 'testimonial'
                                     title      TEXT,
                                     caption    TEXT,
                                     path       TEXT NOT NULL,          -- local path, e.g. assets/acme/hero/hero1.jpg
                                     meta       JSONB DEFAULT '{}'::jsonb,
                                     embedding  VECTOR(768)
);

CREATE INDEX IF NOT EXISTS asset_site_idx ON asset (site);
CREATE INDEX IF NOT EXISTS asset_type_idx ON asset (type);
CREATE INDEX IF NOT EXISTS asset_embed_idx ON asset USING ivfflat (embedding vector_cosine_ops) WITH (lists=100);
