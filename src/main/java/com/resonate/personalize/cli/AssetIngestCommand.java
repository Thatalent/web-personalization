package com.resonate.personalize.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVReader;
import com.resonate.personalize.EmbeddingService;
import com.resonate.personalize.PgVectorUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.io.FileReader;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Command(
    name = "ingest",
    mixinStandardHelpOptions = true,
    description = "Scan assets and upsert rows into Postgres (pgvector) with Ollama embeddings."
)
public class AssetIngestCommand implements Runnable {

  @Option(names = "--root", required = true, description = "Asset root folder (e.g., ./assets)")
  Path root;

  @Option(names = "--site", description = "Site/tenant if not present in folder structure (assets/<type>/*).")
  String defaultSite;

  @Option(names = "--map", description = "Optional CSV or JSON metadata (cols/keys: path|id, title, caption, site, type).")
  Path mapFile;

  @Option(names = "--type-alias", description = "Map folder names, e.g., dream=hero. Repeatable.", split = ",")
  List<String> typeAliasArgs = new ArrayList<>();

  @Option(names = "--init", description = "Create extension/table/indexes if needed.")
  boolean init;

  @Option(names = "--dry-run", description = "Preview changes without writing to DB.")
  boolean dryRun;

  @Autowired JdbcTemplate jdbc;
  @Autowired EmbeddingService embeddingService;
  @Autowired ObjectMapper om;

  private static final Set<String> IMG_EXT = Set.of(".jpg",".jpeg",".png",".webp",".gif",".svg",".avif");
  private static final int EMBED_DIM = 768;

  @Override public void run() {
    try {
      if (init) createSchema();

      Map<String,String> typeAlias = parseAliases(typeAliasArgs);
      // sensible defaults for your project
      // typeAlias.putIfAbsent("dream", "hero"); // Keep dream as dream
      // typeAlias.putIfAbsent("course", "tiles");

      Map<String,Meta> meta = loadMetadata(mapFile);

      List<Path> files = listImages(root);
      if (files.isEmpty()) {
        System.out.println("No images under: " + root.toAbsolutePath());
        return;
      }

      String upsert = """
        INSERT INTO asset (id,site,type,title,caption,path,meta,embedding)
        VALUES (?,?,?,?,?,?, ?::jsonb, ?::vector)
        ON CONFLICT (id) DO UPDATE SET
          site=EXCLUDED.site,
          type=EXCLUDED.type,
          title=EXCLUDED.title,
          caption=EXCLUDED.caption,
          path=EXCLUDED.path,
          meta=EXCLUDED.meta,
          embedding=EXCLUDED.embedding
      """;

      int total = 0;
      for (Path p : files) {
        Rel rel = infer(root, p, defaultSite, typeAlias);
        Meta m  = chooseMeta(p, root, meta);

        String site = m.site != null ? m.site : rel.site;
        String type = m.type != null ? m.type : rel.type;
        String title = nvl(m.title, smartTitle(p));
        String caption = nvl(m.caption, title);

        String text = (site + " " + type + " " + title + " " + caption).trim();
        float[] vec = embeddingService.embedText(text);
        if (vec.length != EMBED_DIM) vec = resize(vec, EMBED_DIM);

        String id = site + "_" + type + "_" + p.getFileName().toString();
        String json = "{}";
        String pathStr = root.relativize(p).toString().replace("\\","/");

        System.out.printf("UPSERT id=%s site=%s type=%s path=%s%n", id, site, type, pathStr);
        if (!dryRun) {
          jdbc.update(upsert, id, site, type, title, caption, pathStr, json, PgVectorUtils.toPgVector(vec));
        }
        total++;
      }

      System.out.println("Done. Upserted " + total + " rows" + (dryRun ? " (dry run)" : "") + ".");
    } catch (Exception e) {
      e.printStackTrace();
      throw new CommandLine.ExecutionException(new CommandLine(this), e.getMessage(), e);
    }
  }

  // ---------- helpers ----------
  private void createSchema() throws SQLException {
    jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector;");
    jdbc.execute("""
        CREATE TABLE IF NOT EXISTS asset (
          id TEXT PRIMARY KEY,
          site TEXT NOT NULL,
          type TEXT NOT NULL,
          title TEXT,
          caption TEXT,
          path TEXT NOT NULL,
          meta JSONB DEFAULT '{}'::jsonb,
          embedding VECTOR(768)
        )
    """);
    jdbc.execute("CREATE INDEX IF NOT EXISTS asset_site_idx ON asset (site);");
    jdbc.execute("CREATE INDEX IF NOT EXISTS asset_type_idx ON asset (type);");
    jdbc.execute("""
        DO $$ BEGIN
          IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'asset_embed_idx') THEN
            EXECUTE 'CREATE INDEX asset_embed_idx ON asset USING ivfflat (embedding vector_cosine_ops) WITH (lists=100);';
          END IF;
        END $$;
    """);
  }

  private static class Rel { String site; String type; }
  private static class Meta {
    String title; String caption; String site; String type;
  }

  private static Meta chooseMeta(Path p, Path root, Map<String,Meta> meta) {
    String rel = root.relativize(p).toString().replace("\\","/");
    Meta m = meta.getOrDefault(rel, meta.getOrDefault(p.getFileName().toString(), new Meta()));
    return m == null ? new Meta() : m;
  }

  private static String smartTitle(Path p) {
    String s = p.getFileName().toString().replaceFirst("\\.[^.]+$","");
    s = s.replace('_',' ').replace('-',' ').trim();
    return s.isEmpty() ? p.getFileName().toString() : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  private static String nvl(String a, String b){ return a != null && !a.isBlank() ? a : b; }

  private static float[] toFloatArray(List<Double> v){
    float[] out = new float[v.size()];
    for (int i=0;i<v.size();i++) out[i] = v.get(i).floatValue();
    return out;
  }
  private static float[] resize(float[] v, int d){
    if (v.length == d) return v;
    float[] out = new float[d];
    System.arraycopy(v, 0, out, 0, Math.min(v.length, d));
    return out;
  }

  private static List<Path> listImages(Path root) throws Exception {
    try (var s = Files.walk(root)) {
      return s.filter(Files::isRegularFile)
          .filter(p -> IMG_EXT.contains(ext(p)))
          .collect(Collectors.toList());
    }
  }

  private static String ext(Path p){
    String n = p.getFileName().toString();
    int i = n.lastIndexOf('.');
    return i<0 ? "" : n.substring(i).toLowerCase(Locale.ROOT);
  }

  private static Rel infer(Path root, Path file, String defaultSite, Map<String,String> alias){
    Path rel = root.relativize(file);
    String site = defaultSite != null ? defaultSite : "default";
    String type = "hero";

    if (rel.getNameCount() >= 3) {
      // assets/site/type/file.ext
      site = rel.getName(0).toString();
      type = rel.getName(1).toString().toLowerCase(Locale.ROOT);
    } else if (rel.getNameCount() >= 2 && defaultSite != null) {
      // assets/type/file.ext with default site
      site = defaultSite;
      type = rel.getName(0).toString().toLowerCase(Locale.ROOT);
    } else if (rel.getNameCount() >= 2) {
      // assets/site/file.ext - treat site as both site and type
      site = rel.getName(0).toString();
      type = rel.getName(0).toString().toLowerCase(Locale.ROOT); // Use folder name as type, not filename
    } else if (rel.getNameCount() == 1 && defaultSite != null) {
      site = defaultSite;
      site = defaultSite;
    }
    Rel r = new Rel();
    r.site = site;
    r.type = alias.getOrDefault(type, type);
    return r;
  }

  private Map<String,String> parseAliases(List<String> items){
    Map<String,String> m = new HashMap<>();
    if (items == null) return m;
    for (String it : items) {
      if (it == null) continue;
      String s = it.trim();
      if (s.isEmpty()) continue;
      String[] parts = s.split("=");
      if (parts.length == 2) m.put(parts[0].trim().toLowerCase(Locale.ROOT), parts[1].trim().toLowerCase(Locale.ROOT));
    }
    return m;
  }

  private Map<String,Meta> loadMetadata(Path f) {
    Map<String,Meta> out = new LinkedHashMap<>();
    if (f == null) return out;
    try {
      if (f.toString().toLowerCase(Locale.ROOT).endsWith(".csv")) {
        try (CSVReader rdr = new CSVReader(new FileReader(f.toFile()))) {
          String[] header = rdr.readNext();
          if (header == null) return out;
          Map<String,Integer> idx = new HashMap<>();
          for (int i=0;i<header.length;i++) idx.put(header[i].trim().toLowerCase(Locale.ROOT), i);
          String[] row;
          while ((row = rdr.readNext()) != null) {
            String key = val(row, idx, "path");
            if (key == null || key.isBlank()) key = val(row, idx, "id");
            if (key == null || key.isBlank()) key = val(row, idx, "file");
            if (key == null || key.isBlank()) continue;
            Meta m = new Meta();
            m.title   = val(row, idx, "title");
            m.caption = val(row, idx, "caption");
            m.site    = val(row, idx, "site");
            m.type    = val(row, idx, "type");
            out.put(key, m);
          }
        }
      } else if (f.toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
        List<Map<String,Object>> rows = om.readValue(Files.readString(f), new TypeReference<>() {});
        for (var r : rows) {
          String key = str(r.get("path"));
          if (key == null || key.isBlank()) key = str(r.get("id"));
          if (key == null || key.isBlank()) key = str(r.get("file"));
          if (key == null || key.isBlank()) continue;
          Meta m = new Meta();
          m.title   = str(r.get("title"));
          m.caption = str(r.get("caption"));
          m.site    = str(r.get("site"));
          m.type    = str(r.get("type"));
          out.put(key, m);
        }
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to read metadata: " + f + " - " + e.getMessage(), e);
    }
    return out;
  }

  private static String val(String[] row, Map<String,Integer> idx, String key){
    Integer i = idx.get(key);
    return i == null || i >= row.length ? null : Optional.ofNullable(row[i]).map(String::trim).orElse(null);
  }
  private static String str(Object o){ return o == null ? null : String.valueOf(o).trim(); }
}
