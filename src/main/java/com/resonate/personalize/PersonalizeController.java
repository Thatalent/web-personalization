package com.resonate.personalize;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Atomic content endpoint for the two components referenced in the HTML:
 *   - dream-<content>
 *   - course-<content>
 * Where <content> is one of: image | text
 *
 * GET  /v1/personalize?site=acme&component=dream&content=image&ids={100123},{100122}
 * POST /v1/personalize  body: { "site":"acme","component":"course","content":"text","ids":["{100123}"] }
 *
 * Response:
 *  - image: { "type":"image", "url":"...", "alt":"..." }
 *  - text:  { "type":"text",  "html":"<p>...</p>" }
 */
@RestController
@CrossOrigin(origins = {"http://localhost:5500"}, allowCredentials = "true")
@RequestMapping("/v1")
public class PersonalizeController {

  private final IdHasher idHasher;
  private final EmbeddingService emb;
  private final AttributeCatalog catalog;
  private final AssetRepository repo;
  private final ChatClient chat;
  private final ObjectMapper om;
  private final String version;

  public PersonalizeController(IdHasher idHasher,
                               EmbeddingService emb,
                               AttributeCatalog catalog,
                               AssetRepository repo,
                               ChatClient chat,
                               ObjectMapper om,
                               @Value("${app.version:2025-10-01}") String version) {
    this.idHasher = idHasher;
    this.emb = emb;
    this.catalog = catalog;
    this.repo = repo;
    this.chat = chat;
    this.om = om;
    this.version = version;
  }

  public record Req(
      String site,
      String component, // dream | course
      String content,   // image | text
      List<String> ids,
      Float alpha,      // optional: weight for hashed IDs
      Float beta        // optional: weight for label text
  ) {}

  // --- GET -> normalized to Req then handled by same code ---
  @GetMapping(value = "/personalize", produces = MediaType.APPLICATION_JSON_VALUE)
  public Map<String,Object> get(
      @RequestParam String site,
      @RequestParam String component,
      @RequestParam String content,
      @RequestParam(required = false) String ids,
      @RequestParam(required = false) Float alpha,
      @RequestParam(required = false) Float beta
  ) {
    List<String> idList = ids == null ? List.of()
        : Arrays.stream(ids.split(","))
            .map(String::trim)
            .map(s -> s.replace("{","").replace("}",""))
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    return personalize(new Req(site, component, content, idList, alpha, beta));
  }

  // --- GET /personalize/image -> returns just the URL string ---
  @GetMapping(value = "/personalize/image", produces = MediaType.TEXT_PLAIN_VALUE)
  public String getImage(
      @RequestParam String site,
      @RequestParam String component,
      @RequestParam(required = false) String ids,
      @RequestParam(required = false) Float alpha,
      @RequestParam(required = false) Float beta
  ) {
    List<String> idList = ids == null ? List.of()
        : Arrays.stream(ids.split(","))
            .map(String::trim)
            .map(s -> s.replace("{","").replace("}",""))
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    
    Map<String,Object> result = personalize(new Req(site, component, "image", idList, alpha, beta));
    String url = (String) result.getOrDefault("url", "");
    return "redirect:" + url; // so client can handle as a redirect
  }

  // --- GET /personalize/text -> returns personalized text content ---
  @GetMapping(value = "/personalize/text", produces = MediaType.TEXT_PLAIN_VALUE)
  public String getText(
      @RequestParam String site,
      @RequestParam String component,
      @RequestParam(required = false) String ids,
      @RequestParam(required = false) Float alpha,
      @RequestParam(required = false) Float beta
  ) {
    List<String> idList = ids == null ? List.of()
        : Arrays.stream(ids.split(","))
            .map(String::trim)
            .map(s -> s.replace("{","").replace("}",""))
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    
    // Get hints for personalization
    List<String> normalizedIds = idList.stream()
        .map(AttributeCatalog::normalizeId)
        .sorted()
        .toList();
    String hints = catalog.hintsFor(normalizedIds, 24);
    
    // Generate personalized text based on component type
    String content = generatePersonalizedText(component, hints);
    return content;
  }

  // --- POST (same contract, body JSON) ---
  @PostMapping(value = "/personalize", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public Map<String,Object> post(@RequestBody Req req) {
    return personalize(req);
  }

  // -------- Core handler --------
  private Map<String,Object> personalize(Req req) {
    final String component = normComponent(req.component());
    final String content   = normContent(req.content());
    if (component == null || content == null) {
      return Map.of("error", "Invalid component or content. component=[dream|course], content=[image|text]");
    }

    // Normalize IDs deterministically for caching
    List<String> ids = Optional.ofNullable(req.ids()).orElse(List.of())
        .stream().map(AttributeCatalog::normalizeId).sorted().toList();
    String key = "%s:v=%s:ids=%s:part=%s:%s".formatted(
        req.site(), version, String.join("+", ids), component, content);

    // Build HYBRID query vector (hashed IDs + text labels)
    float[] qHash = idHasher.embedIds(ids); // 768-d (make sure D=768 in IdHasher)
    String hints = catalog.hintsFor(ids, 24); // "Female | 100-200k | Runner | ..."
    float[] qText = hints.isEmpty() ? zeros(768) : emb.embedText(hints);
    float alpha = req.alpha() == null ? 0.6f : clamp(req.alpha(), 0f, 1f);
    float beta  = req.beta()  == null ? 0.4f : clamp(req.beta(),  0f, 1f);
    float[] q = l2norm(weightedSum(qHash, alpha, qText, beta));

    // If image requested → pick best asset for this component
    if ("image".equals(content)) {
      String type = component;
      var chosen = firstNonEmpty(
          repo.knn(req.site(), type, q, 1),
          // fallback: try other types if this site has few assets
          repo.knn(req.site(), otherType(type), q, 1),
          repo.knn(req.site(), "testimonial", q, 1)
      );
      if (chosen != null) {
        String path = (String) chosen.get("path");       // your repo stores local/relative path
        String url = "http://localhost:5500/code/web-personalization/assets/" + path;  // Relative path from home directory for localhost:5500
        String alt = (String) Optional.ofNullable(chosen.get("title")).orElse("");
        return Map.of("key", key, "type", "image", "url", url, "alt", alt, "hints", hints);
      }
      // graceful no-match
      return Map.of("key", key, "type", "image", "url", "", "alt", "", "hints", hints);
    }

    // If text requested → ask the LLM for short copy (brief & safe)
    String sys = """
      You write very short, safe, on-brand microcopy. Avoid medical/financial claims.
      Keep it concise (max ~25 words), friendly and clear. Output plain text only (no JSON).
      """;
    Map<String,Object> user = new LinkedHashMap<>();
    user.put("site", req.site());
    user.put("component", component); // dream | course
    user.put("hints", hints);
    user.put("purpose", component.equals("dream")
        ? generateDreamPurpose(hints)
        : "Describe a course or offering succinctly with a helpful, welcoming tone.");
    // TINY context from the nearest asset captions to keep text grounded
    var forContext = repo.knn(req.site(),component, q, 3);
    user.put("asset_captions", forContext.stream()
        .map(m -> String.valueOf(m.getOrDefault("caption","")))
        .filter(s -> !s.isBlank())
        .limit(3).toList());

    System.out.println("User hints: " + hints);

    String text = chat.prompt().system(sys).user(om.valueToTree(user).toString()).call().content();
    // Return as HTML (allow direct insertion into your placeholder)
    String html = sanitizeLine(text);
    return Map.of("key", key, "type", "text", "html", html, "hints", hints);
  }

  // ------ helpers ------
  private static String normComponent(String s){
    if (s==null) return null;
    String v = s.trim().toLowerCase(Locale.ROOT);
    return switch (v) { case "dream","course" -> v; default -> null; };
  }
  private static String normContent(String s){
    if (s==null) return null;
    String v = s.trim().toLowerCase(Locale.ROOT);
    return switch (v) { case "image","text" -> v; default -> null; };
  }
  private static String mapComponentToAssetType(String component){
    // Aligns your two component families to the asset "type" used in the DB
    // You can change these to match your current asset folders.
    return switch (component) {
      case "dream" -> "dream";
      case "course" -> "course";
      default -> "hero";
    };
  }
  private static String otherType(String t){ return "dream".equals(t) ? "course" : ("course".equals(t) ? "dream" : "hero"); }
  private static Map<String,Object> firstNonEmpty(List<Map<String,Object>>... lists){
    for (var lst : lists) if (lst != null && !lst.isEmpty()) return lst.getFirst();
    return null;
  }

  private static float[] weightedSum(float[] a, float aw, float[] b, float bw) {
    int d = Math.max(a.length, b.length);
    float[] out = new float[d];
    for (int i = 0; i < d; i++) {
      float av = i < a.length ? a[i] : 0f;
      float bv = i < b.length ? b[i] : 0f;
      out[i] = aw * av + bw * bv;
    }
    return out;
  }
  private static float[] l2norm(float[] x){
    double n=0; for (float v : x) n += v*v;
    if (n<=0) return x;
    float inv = (float)(1.0/Math.sqrt(n));
    for (int i=0;i<x.length;i++) x[i]*=inv;
    return x;
  }
  private static float[] zeros(int d){ return new float[d]; }

  private static float clamp(float v, float lo, float hi){ return Math.max(lo, Math.min(hi, v)); }

  private static String sanitizeLine(String s){
    String t = s == null ? "" : s.trim();
    // Avoid accidental newlines or markdown from the model
    t = t.replaceAll("\\s+", " ");
    // Wrap as a simple paragraph so your client placeholder can accept HTML
    return "<p>"+escapeHtml(t)+"</p>";
  }
  private static String escapeHtml(String s){
    return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
  }

  private String generateDreamPurpose(String hints) {
    if (hints == null || hints.trim().isEmpty()) {
      return "Inspire a prospective learner about flexible, supportive education.";
    }
    
    String sys = """
      You create personalized educational inspiration messages. Based on user attributes/hints,
      craft a brief purpose statement (max 15 words) that connects their dreams and goals to education.
      Focus on their aspirations, lifestyle, and interests. Output only the purpose statement, no quotes.
      """;
    
    String userPrompt = "User attributes: " + hints + 
        "\nCreate an inspiring educational purpose statement that speaks to their dreams and goals.";
    
    try {
      String generated = chat.prompt().system(sys).user(userPrompt).call().content();
      return generated.trim().replaceAll("\"", "").replaceAll("\\s+", " ");
    } catch (Exception e) {
      // Fallback to default if LLM call fails
      return "Inspire a prospective learner about flexible, supportive education.";
    }
  }

  private String generatePersonalizedText(String component, String hints) {
    if ("dream".equals(component)) {
      return generateDreamText(hints);
    } else if ("course".equals(component)) {
      return generateCourseText(hints);
    } else {
      return "Discover your potential with our flexible, personalized education programs.";
    }
  }

  private String generateDreamText(String hints) {
    String sys = """
      You write compelling educational content about "Competency-Based Education Is Breaking Tradition".
      Connect this concept to the user's sports dreams and athletic goals. Show how competency-based 
      education can help them balance their athletic pursuits with academic achievement.
      Keep it concise (max 50 words), inspirational, and focused on their athletic dreams.
      Output plain text only, no quotes or formatting.
      """;
    
    String userPrompt = "User attributes: " + hints + 
        "\nExplain how Competency-Based Education Is Breaking Tradition can help them achieve their athletic dreams and goals.";
    
    try {
      String generated = chat.prompt().system(sys).user(userPrompt).call().content();
      return generated.trim().replaceAll("\"", "").replaceAll("\\s+", " ");
    } catch (Exception e) {
      return "Competency-Based Education Is Breaking Tradition - advance at your own pace while pursuing your athletic dreams.";
    }
  }

  private String generateCourseText(String hints) {
    String sys = """
      You write compelling educational content about "Use What You Know to Graduate Faster".
      Connect this concept to the user's academic and career interests shown in their attributes.
      Show how they can leverage their existing knowledge and experience to accelerate their studies.
      Keep it concise (max 50 words), practical, and focused on their specific field of interest.
      Output plain text only, no quotes or formatting.
      """;
    
    String userPrompt = "User attributes: " + hints + 
        "\nExplain how 'Use What You Know to Graduate Faster' applies to their academic/career interests.";
    
    try {
      String generated = chat.prompt().system(sys).user(userPrompt).call().content();
      return generated.trim().replaceAll("\"", "").replaceAll("\\s+", " ");
    } catch (Exception e) {
      return "Use What You Know to Graduate Faster - leverage your experience to accelerate your academic journey.";
    }
  }
}
