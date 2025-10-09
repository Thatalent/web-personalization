package com.resonate.personalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1")
public class ComposeController {

  private final IdHasher idHasher;
  private final AssetRepository repo;
  private final ChatClient chat;
  private final HtmlRenderer renderer;
  private final ObjectMapper om;
  private final String assetsRoot;
  private final String version;
  private final JsonSchema schema;

  public ComposeController(IdHasher idHasher,
                           AssetRepository repo,
                           ChatClient chat,
                           HtmlRenderer renderer,
                           ObjectMapper om,
                           @Value("${app.assets.root:assets}") String assetsRoot,
                           @Value("${app.version}") String version) throws Exception {
    this.idHasher = idHasher;
    this.repo = repo;
    this.chat = chat;
    this.renderer = renderer;
    this.om = om;
    this.assetsRoot = assetsRoot;
    this.version = version;
    var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    this.schema = factory.getSchema(new ClassPathResource("composer-schema.json").getInputStream());
  }

  public record ComposeReq(String site, List<String> ids, Integer seed) {}

  @PostMapping(value = "/compose", produces = MediaType.APPLICATION_JSON_VALUE)
  public @ResponseBody Map<String,Object> compose(@RequestBody ComposeReq req) throws Exception {
    var ids = Optional.ofNullable(req.ids()).orElse(List.of()).stream().map(String::valueOf).sorted().toList();
    var key = req.site()+":v="+version+":ids="+String.join("+", ids);

    // 1) Request vector from hashed IDs (deterministic, local)
    var qVec = idHasher.embedIds(ids);

    // 2) Retrieve candidates
    var heroes = repo.knn(req.site(), "hero", qVec, 5);
    var tiles  = repo.knn(req.site(), "tiles", qVec, 12);
    var tests  = repo.knn(req.site(), "testimonial", qVec, 4);

    // Build lookup for rendering later
    Map<String, Map<String,Object>> lookup = new HashMap<>();
    heroes.forEach(r -> lookup.put((String)r.get("id"), r));
    tiles.forEach(r -> lookup.put((String)r.get("id"), r));
    tests.forEach(r -> lookup.put((String)r.get("id"), r));

    // 3) Compose via LLM (JSON only)
    String sys = """
      You are a content composer. Choose a layout and SHORT copy for the provided candidate assets.
      
      OUTPUT REQUIREMENTS (STRICT):
      - Output ONLY valid JSON (no preamble/markdown/explanation).
      - Top-level keys: layout, voice, sections.
      - Each section MUST reference assets by ID:
        • For single-asset sections (e.g., hero, testimonial): include "asset_id": "<CANDIDATE_ID>"
        • For multi-asset sections (e.g., tiles): include "asset_ids": ["<ID1>","<ID2>",...]
      - Do NOT use a "content" object or any "image.path" fields.
      - Use ONLY the candidate IDs provided in the input.
      - Keep copy concise. Headlines ≤ 8 words. No health/finance claims.
      
      STRUCTURE (schema):
      {
        "layout": "string (must be one of the provided layouts)",
        "voice": { "tone": "string", "reading_level": "string" },
        "sections": [
          {
            "type": "hero" | "tiles" | "testimonial",
            // hero & testimonial:
            "asset_id": "string",
            "headline": "string (optional for hero)",
            "subhead": "string (optional for hero)",
            "blurb": "string (optional for testimonial)",
            "title": "string (optional for tiles/testimonial)",
            "cta": { "text": "string", "href": "string" } (optional),
            // tiles:
            "asset_ids": ["string", ...] (for tiles only)
          }
        ]
      }
      
      Your response must start with { and end with } with nothing before or after.
      """;

    Map<String,Object> user = Map.of(
        "site", req.site(),
        "seed", req.seed()==null? 0 : req.seed(),
        "heroes", brief(heroes, 80),
        "tiles",  brief(tiles, 60),
        "testimonials", brief(tests, 40),
        "layouts", List.of("hero+tiles+testimonial","hero+tiles","hero+testimonial"),
        "schema_hint", "Match composer-schema.json strictly. Return pure JSON only."
    );

    String raw = chat.prompt()
        .system(sys)
        .user(om.writeValueAsString(user))
        .call()
        .content()
        .trim();

    // 4) Validate JSON
    JsonNode node = om.readTree(raw);
    Set<ValidationMessage> errors = schema.validate(node);
    if (!errors.isEmpty()) throw new IllegalArgumentException("Composer schema error: "+errors);

    // 5) Render HTML partial
    String html = renderer.render(node.get("sections"), lookup);

    // 6) Response (JSON + HTML)
    Map<String,Object> resp = new LinkedHashMap<>();
    resp.put("key", key);
    resp.put("layout", node.get("layout"));
    resp.put("voice", node.get("voice"));
    resp.put("sections", node.get("sections"));
    resp.put("html", html);
    resp.put("debug", Map.of(
        "ids", ids,
        "heroes", heroes.size(),
        "tiles", tiles.size(),
        "testimonials", tests.size()
    ));
    return resp;
  }

  // Quick HTML-only preview for demos: GET /v1/compose.html?site=acme&ids=100122,900301
  @GetMapping(value = "/compose.html", produces = MediaType.TEXT_HTML_VALUE)
  public String composeHtml(@RequestParam String site, @RequestParam String ids,
                            @RequestParam(defaultValue = "0") int seed) throws Exception {
    var list = Arrays.stream(ids.split(",")).map(String::trim).filter(s->!s.isEmpty()).sorted().toList();
    var body = compose(new ComposeReq(site, list, seed));
    return (String) body.get("html");
  }

  // Component endpoint: GET /v1/component/{type}?site=acme&ids=100122,900301
  // Returns just the HTML for a single component type (hero, tiles, testimonial)
  @GetMapping(value = "/component/{type}", produces = MediaType.TEXT_HTML_VALUE)
  public String renderComponent(@PathVariable String type,
                                 @RequestParam String site,
                                 @RequestParam String ids,
                                 @RequestParam(defaultValue = "0") int seed) throws Exception {
    var idList = Arrays.stream(ids.split(",")).map(String::trim).filter(s->!s.isEmpty()).sorted().toList();

    // Hash IDs to get query vector
    var qVec = idHasher.embedIds(idList);

    // Retrieve candidates based on component type
    List<Map<String,Object>> candidates;
    Map<String, Map<String,Object>> lookup = new HashMap<>();

    switch (type.toLowerCase()) {
      case "hero" -> {
        candidates = repo.knn(site, "hero", qVec, 1);
        candidates.forEach(r -> lookup.put((String)r.get("id"), r));
      }
      case "tiles" -> {
        candidates = repo.knn(site, "tiles", qVec, 3);
        candidates.forEach(r -> lookup.put((String)r.get("id"), r));
      }
      case "testimonial" -> {
        candidates = repo.knn(site, "testimonial", qVec, 1);
        candidates.forEach(r -> lookup.put((String)r.get("id"), r));
      }
      default -> throw new IllegalArgumentException("Unknown component type: " + type);
    }

    // Build a simple component config from the database assets
    Map<String,Object> componentConfig = new HashMap<>();
    componentConfig.put("type", type.toLowerCase());

    if (type.equalsIgnoreCase("tiles") && !candidates.isEmpty()) {
      List<String> assetIds = candidates.stream()
          .map(c -> (String) c.get("id"))
          .toList();
      componentConfig.put("asset_ids", assetIds);
      componentConfig.put("title", ""); // No title by default
    } else if (!candidates.isEmpty()) {
      // For hero and testimonial - single asset
      componentConfig.put("asset_id", candidates.get(0).get("id"));
    }

    // Render the single component
    return renderer.renderComponent(componentConfig, lookup);
  }

  private static List<Map<String,Object>> brief(List<Map<String,Object>> rows, int cap){
    return rows.stream().map(m -> Map.of(
        "id", m.get("id"),
        "title", m.getOrDefault("title",""),
        "caption", crop((String)m.getOrDefault("caption",""), cap),
        "path", m.get("path"),
        "score", m.get("score")
    )).collect(Collectors.toList());
  }

  private static String crop(String s, int n){ if (s==null) return ""; return s.length()<=n? s : s.substring(0,n-1)+"…"; }
}
