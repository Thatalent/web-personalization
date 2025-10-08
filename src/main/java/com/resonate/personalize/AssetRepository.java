package com.resonate.personalize;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Repository
public class AssetRepository {
  private final JdbcTemplate jdbc;

  public AssetRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @SuppressWarnings("unchecked")
  public List<Map<String,Object>> knn(String site, String type, float[] query, int k) {
    String vec = PgVectorUtils.toPgVector(query);
    String sql =
        "SELECT id, title, caption, path, meta, " +
            "       1 - (embedding <#> ?::vector) AS score " + // cosine similarity
            "FROM asset WHERE site = ? AND type = ? " +
            "ORDER BY embedding <#> ?::vector ASC LIMIT ?";
    return jdbc.query(sql, rs -> {
      List<Map<String,Object>> rows = new ArrayList<>();
      while (rs.next()) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("title", rs.getString("title"));
        m.put("caption", rs.getString("caption"));
        m.put("path", rs.getString("path"));
        m.put("meta", rs.getString("meta"));
        m.put("score", rs.getDouble("score"));
        rows.add(m);
      }
      return rows;
    }, vec, site, type, vec, k);
  }

  public Optional<Map<String,Object>> byId(String id) {
    var list = jdbc.queryForList("SELECT * FROM asset WHERE id = ?", id);
    return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
  }

  public void upsert(String id, String site, String type, String title, String caption, String path, float[] emb) {
    jdbc.update("""
      INSERT INTO asset (id,site,type,title,caption,path,embedding)
      VALUES (?,?,?,?,?,?, ?::vector)
      ON CONFLICT (id) DO UPDATE SET title=EXCLUDED.title, caption=EXCLUDED.caption,
        path=EXCLUDED.path, embedding=EXCLUDED.embedding
    """, id, site, type, title, caption, path, PgVectorUtils.toPgVector(emb));
  }

  public static String fileTitle(Path p){
    var n = p.getFileName().toString().replace('_',' ').replaceAll("\\.[^.]+$","");
    return n.substring(0,1).toUpperCase()+n.substring(1);
  }

  public static String fileCaption(Path p){ return fileTitle(p); }

  public static boolean fileExists(String path){ return Files.exists(Path.of(path)); }
}
