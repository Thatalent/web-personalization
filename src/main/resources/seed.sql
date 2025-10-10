-- Sample asset data for ACME site
-- Note: Embeddings are initialized as zero vectors (64 dimensions) and should be updated by your embedding service

-- Hero images
INSERT INTO asset (id, site, type, title, caption, path, embedding)
VALUES
    ('acme_hero_1', 'acme', 'hero', 'Pro gear', 'Light and fast', 'assets/acme/hero/hero1.jpg', array_fill(0, ARRAY[64])::vector),
    ('acme_hero_2', 'acme', 'hero', 'Adventure awaits', 'Built for exploration', 'assets/acme/hero/hero2.jpg', array_fill(0, ARRAY[64])::vector);

-- Product tiles
INSERT INTO asset (id, site, type, title, caption, path, embedding)
VALUES
    ('acme_tiles_1', 'acme', 'tiles', 'Trainer', 'Daily trainer', 'assets/acme/tiles/t1.jpg', array_fill(0, ARRAY[64])::vector),
    ('acme_tiles_2', 'acme', 'tiles', 'Runner', 'Race day ready', 'assets/acme/tiles/t2.jpg', array_fill(0, ARRAY[64])::vector),
    ('acme_tiles_3', 'acme', 'tiles', 'Trail', 'Off-road beast', 'assets/acme/tiles/t3.jpg', array_fill(0, ARRAY[64])::vector);

-- Testimonials
INSERT INTO asset (id, site, type, title, caption, path, embedding)
VALUES
    ('acme_test_1', 'acme', 'testimonial', 'Jordan P.', 'Switched last month—never looking back.', 'assets/acme/testimonial/avatar1.jpg', array_fill(0, ARRAY[64])::vector),
    ('acme_test_2', 'acme', 'testimonial', 'Alex M.', 'Best purchase I made this year!', 'assets/acme/testimonial/avatar2.jpg', array_fill(0, ARRAY[64])::vector),
    ('acme_test_3', 'acme', 'testimonial', 'Sam K.', 'Quality and comfort in perfect balance.', 'assets/acme/testimonial/avatar3.jpg', array_fill(0, ARRAY[64])::vector);
