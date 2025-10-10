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
    Template t = load("template/sections/hero.mustache");
    Map<String,Object> model = new HashMap<>();

    // Use asset_id to lookup from database
    String assetId = text(sec, "asset_id");
    Map<String,Object> asset = lookup.get(assetId);

    if (asset != null) {
      // Use LLM's custom headline/subhead if provided, otherwise fall back to asset data
      model.put("headline", sec.has("headline") ? text(sec, "headline") : asset.getOrDefault("title", ""));
      model.put("subhead", sec.has("subhead") ? text(sec, "subhead") : asset.getOrDefault("caption", ""));

      String imgPath = (String) asset.get("path");
      if (imgPath != null && !imgPath.isEmpty()) {
        Map<String,Object> img = Map.of(
            "path", toAbsolutePath(imgPath),
            "alt", asset.getOrDefault("title", "")
        );
        model.put("img", img);
      }
    }

    if (sec.has("cta")) {
      Map<String,String> cta = new HashMap<>();
      cta.put("text", text(sec.get("cta"), "text"));
      cta.put("href", text(sec.get("cta"), "href"));
      model.put("cta", cta);
    }

    return t.execute(model);
  }

  private String renderTiles(JsonNode sec, Map<String, Map<String,Object>> lookup) {
    Template t = load("template/sections/tiles.mustache");
    Map<String,Object> model = new HashMap<>();
    model.put("title", text(sec, "title"));

    List<Map<String,Object>> items = new ArrayList<>();
    if (sec.has("asset_ids") && sec.get("asset_ids").isArray()) {
      for (JsonNode idNode : sec.get("asset_ids")) {
        String assetId = idNode.asText();
        Map<String,Object> asset = lookup.get(assetId);
        if (asset != null) {
          items.add(Map.of(
              "title", asset.getOrDefault("title", ""),
              "img", Map.of(
                  "path", toAbsolutePath((String) asset.get("path")),
                  "alt", asset.getOrDefault("title", "")
              )
          ));
        }
      }
    }
    model.put("items", items);
    return t.execute(model);
  }

  private String renderTestimonial(JsonNode sec, Map<String, Map<String,Object>> lookup) {
    Template t = load("template/sections/testimonial.mustache");
    Map<String,Object> model = new HashMap<>();

    // Use asset_id to lookup from database
    String assetId = text(sec, "asset_id");
    Map<String,Object> asset = lookup.get(assetId);

    if (asset != null) {
      // Use LLM's custom blurb if provided, otherwise use asset caption
      model.put("blurb", sec.has("blurb") ? text(sec, "blurb") : asset.getOrDefault("caption", ""));
      model.put("title", asset.getOrDefault("title", ""));

      String imgPath = (String) asset.get("path");
      if (imgPath != null && !imgPath.isEmpty()) {
        model.put("img", Map.of(
            "path", toAbsolutePath(imgPath),
            "alt", asset.getOrDefault("title", "")
        ));
      }
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

  // Convert relative path (assets/acme/hero/hero1.jpg) to absolute URL (/assets/acme/hero/hero1.jpg)
  private static String toAbsolutePath(String path) {
    if (path == null || path.isEmpty()) return "";
    return path.startsWith("/") ? path : "/" + path;
  }
}
