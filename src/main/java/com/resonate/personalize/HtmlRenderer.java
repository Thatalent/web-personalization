package com.resonate.personalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class HtmlRenderer {

  private final Mustache.Compiler mustache;
  private final String assetsRoot;

  public HtmlRenderer(Mustache.Compiler mustache,
                      @Value("${app.assets.root:assets}") String assetsRoot) {
    this.mustache = mustache;
    this.assetsRoot = assetsRoot;
  }

  public String render(JsonNode sections, Map<String, Map<String,Object>> assetLookup) {
    StringBuilder out = new StringBuilder();
    out.append("<!-- personalized partial start -->\n");
    for (JsonNode sec : sections) {
      String type = sec.get("type").asText("");
      switch (type) {
        case "hero" -> out.append(renderHero(sec, assetLookup));
        case "tiles" -> out.append(renderTiles(sec, assetLookup));
        case "testimonial" -> out.append(renderTestimonial(sec, assetLookup));
        default -> { /* ignore unknown sections */ }
      }
      out.append('\n');
    }
    out.append("<!-- personalized partial end -->");
    return out.toString();
  }

  private String renderHero(JsonNode sec, Map<String, Map<String,Object>> lookup) {
    Template t = load("templates/sections/hero.mustache");
    Map<String,Object> model = new HashMap<>();
    model.put("headline", text(sec,"headline"));
    model.put("subhead", text(sec,"subhead"));
    if (sec.has("cta")) {
      Map<String,String> cta = new HashMap<>();
      cta.put("text", text(sec.get("cta"), "text"));
      cta.put("href", text(sec.get("cta"), "href"));
      model.put("cta", cta);
    }
    String assetId = text(sec,"asset_id");
    if (!assetId.isEmpty() && lookup.containsKey(assetId)) {
      Map<String,Object> a = lookup.get(assetId);
      Map<String,Object> img = Map.of(
          "path", (String)a.get("path"),
          "alt", Optional.ofNullable((String)a.get("title")).orElse(""),
          "exists", AssetRepository.fileExists((String)a.get("path"))
      );
      model.put("img", img);
    }
    return t.execute(model);
  }

  private String renderTiles(JsonNode sec, Map<String, Map<String,Object>> lookup) {
    Template t = load("templates/sections/tiles.mustache");
    Map<String,Object> model = new HashMap<>();
    model.put("title", text(sec,"title"));
    List<Map<String,Object>> items = new ArrayList<>();
    if (sec.has("asset_ids")) {
      for (JsonNode idn : sec.get("asset_ids")) {
        String id = idn.asText();
        if (lookup.containsKey(id)) {
          Map<String,Object> a = lookup.get(id);
          items.add(Map.of(
              "title", Optional.ofNullable((String)a.get("title")).orElse(""),
              "img", Map.of(
                  "path", (String)a.get("path"),
                  "alt", Optional.ofNullable((String)a.get("caption")).orElse("")
              )
          ));
        }
      }
    }
    model.put("items", items);
    return t.execute(model);
  }

  private String renderTestimonial(JsonNode sec, Map<String, Map<String,Object>> lookup) {
    Template t = load("templates/sections/testimonial.mustache");
    Map<String,Object> model = new HashMap<>();
    model.put("blurb", text(sec,"blurb"));
    model.put("title", text(sec,"title"));
    String assetId = text(sec,"asset_id");
    if (!assetId.isEmpty() && lookup.containsKey(assetId)) {
      Map<String,Object> a = lookup.get(assetId);
      model.put("img", Map.of(
          "path", (String)a.get("path"),
          "alt", Optional.ofNullable((String)a.get("title")).orElse("Avatar")
      ));
    }
    return t.execute(model);
  }

  private Template load(String cp) {
    try {
      var res = new ClassPathResource(cp);
      try (var rdr = new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8)) {
        return mustache.compile(rdr);
      }
    } catch (Exception e) {
      throw new RuntimeException("Template not found: " + cp, e);
    }
  }

  private static String text(JsonNode node, String key) {
    return node.has(key) && !node.get(key).isNull() ? node.get(key).asText("") : "";
  }
}
