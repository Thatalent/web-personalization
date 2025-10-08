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
      Output STRICT JSON with keys: layout, voice, sections[]. Avoid claims about health/finance.
      Headlines ≤ 8 words. Keep copy concise and brand-neutral.
      """;

    Map<String,Object> user = Map.of(
        "site", req.site(),
        "seed", req.seed()==null? 0 : req.seed(),
        "heroes", brief(heroes, 80),
        "tiles",  brief(tiles, 60),
        "testimonials", brief(tests, 40),
        "layouts", List.of("hero+tiles+testimonial","hero+tiles","hero+testimonial"),
        "schema_hint", "Match composer-schema.json strictly."
    );

    String raw = chat.prompt().system(sys).user(om.writeValueAsString(user)).call().content();

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
