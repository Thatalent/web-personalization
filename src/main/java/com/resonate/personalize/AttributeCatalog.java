package com.resonate.personalize;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Loads a CSV mapping of Attribute_Id -> Attribute_Name and exposes lookups + hint strings.
 */
@Component
public class AttributeCatalog {

  private final boolean enabled;
  private final Map<String, String> idToName = new HashMap<>();

  public AttributeCatalog(@Value("${app.attributes.enabled:true}") boolean enabled,
                          @Value("${app.attributes.csv:}") Resource csv) {
    this.enabled = enabled;
    if (enabled && csv != null) {
      try (var in = csv.getInputStream();
           var rdr = new CSVReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        String[] header = rdr.readNext();
        if (header == null) return;
        int idIdx = -1, nameIdx = -1;
        for (int i = 0; i < header.length; i++) {
          String h = header[i] == null ? "" : header[i].trim();
          if (h.equalsIgnoreCase("Attribute_Id")) idIdx = i;
          if (h.equalsIgnoreCase("Attribute_Name")) nameIdx = i;
        }
        if (idIdx < 0 || nameIdx < 0) return;

        String[] row;
        while ((row = rdr.readNext()) != null) {
          if (row.length <= Math.max(idIdx, nameIdx)) continue;
          String rawId = safe(row[idIdx]);
          String name  = safe(row[nameIdx]);
          if (rawId.isEmpty() || name.isEmpty()) continue;
          String id = normalizeId(rawId);
          idToName.put(id, name);
        }
      } catch (Exception e) {
        throw new RuntimeException("Failed to load attribute mapping CSV", e);
      }
    }
  }

  public boolean isEnabled() { return enabled; }

  /** Returns a human label for a given opaque ID, or empty string if unknown. */
  public String nameFor(String id) {
    if (!enabled) return "";
    return idToName.getOrDefault(normalizeId(id), "");
  }

  /** Build a concise “hints” string from an ID list for embeddings and prompting. */
  public String hintsFor(List<String> ids, int maxItems) {
    if (!enabled) return "";
    LinkedHashSet<String> labels = new LinkedHashSet<>();
    for (String id : ids) {
      String n = nameFor(id);
      if (!n.isEmpty()) labels.add(n);
      if (labels.size() >= maxItems) break;
    }
    return String.join(" | ", labels);
  }

  /** For debugging / analytics. */
  public Map<String, String> namesFor(List<String> ids) {
    LinkedHashMap<String, String> out = new LinkedHashMap<>();
    for (String id : ids) out.put(id, nameFor(id));
    return out;
  }

  private static String safe(String s) { return s == null ? "" : s.trim(); }

  /** Normalize numeric-looking IDs like "10001.0" -> "10001". */
  public static String normalizeId(String raw) {
    String s = raw.trim();
    if (s.matches("^-?\\d+\\.0+$")) {
      int dot = s.indexOf('.');
      return s.substring(0, dot);
    }
    return s;
  }
}
