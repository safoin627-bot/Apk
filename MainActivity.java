LoadstringServer.java
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LoadstringServer {

    static final Path DATA_DIR = Paths.get("scripts");
    static final Path META_FILE = Paths.get("meta.json");
    static final int MAX_RECENT = 50;
    static final long MAX_SIZE = 10L * 1024 * 1024;
    static final SecureRandom RNG = new SecureRandom();
    static final Object LOCK = new Object();
    static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "3000"));

    public static void main(String[] args) throws Exception {
        Files.createDirectories(DATA_DIR);
        if (!Files.exists(META_FILE)) Files.writeString(META_FILE, "[]");

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/upload", LoadstringServer::handleUpload);
        server.createContext("/api/recent", LoadstringServer::handleRecent);
        server.createContext("/raw/", LoadstringServer::handleRaw);
        server.createContext("/", LoadstringServer::handleIndex);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("listening on " + PORT);
    }

    static void handleIndex(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) {
            send(ex, 404, "text/plain", "-- not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        send(ex, 200, "text/html; charset=utf-8", INDEX.getBytes(StandardCharsets.UTF_8));
    }

    static void handleUpload(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "method".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] body = readAll(ex.getRequestBody(), MAX_SIZE + 1024);
        if (body.length > MAX_SIZE + 512) {
            send(ex, 400, "application/json", "{\"error\":\"too large\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String ctype = ex.getRequestHeaders().getFirst("Content-Type");
        if (ctype == null) ctype = "";
        String source = null;

        if (ctype.startsWith("application/json")) {
            String json = new String(body, StandardCharsets.UTF_8);
            source = extractJsonString(json, "source");
        } else if (ctype.startsWith("multipart/form-data")) {
            String boundary = extractBoundary(ctype);
            if (boundary != null) source = extractMultipartFile(body, boundary);
        }

        if (source == null || source.trim().isEmpty()) {
            send(ex, 400, "application/json", "{\"error\":\"no source\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }

        String id = randomId();
        Files.writeString(DATA_DIR.resolve(id + ".lua"), source, StandardCharsets.UTF_8);

        synchronized (LOCK) {
            List<Map<String, Object>> meta = readMeta();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", id);
            entry.put("preview", preview(source));
            entry.put("size", source.getBytes(StandardCharsets.UTF_8).length);
            entry.put("created", System.currentTimeMillis());
            meta.add(0, entry);
            if (meta.size() > MAX_RECENT) meta = meta.subList(0, MAX_RECENT);
            writeMeta(meta);
        }

        String proto = header(ex, "X-Forwarded-Proto", "http");
        String host = header(ex, "X-Forwarded-Host", header(ex, "Host", "localhost:" + PORT));
        String rawUrl = proto + "://" + host + "/raw/" + id;
        String loadstring = "loadstring(game:HttpGet(\"" + rawUrl + "\"))()";

        String json = "{\"id\":\"" + id + "\",\"rawUrl\":\"" + escapeJson(rawUrl) +
                "\",\"loadstring\":\"" + escapeJson(loadstring) + "\"}";
        send(ex, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    static void handleRaw(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String id = path.substring("/raw/".length()).replaceAll("[^a-fA-F0-9]", "");
        Path file = DATA_DIR.resolve(id + ".lua");
        if (!Files.exists(file)) {
            send(ex, 404, "text/plain; charset=utf-8", "-- not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        send(ex, 200, "text/plain; charset=utf-8", Files.readAllBytes(file));
    }

    static void handleRecent(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder("[");
        List<Map<String, Object>> meta = readMeta();
        int n = Math.min(10, meta.size());
        for (int i = 0; i < n; i++) {
            Map<String, Object> m = meta.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"id\":\"").append(m.get("id")).append("\",");
            sb.append("\"preview\":\"").append(escapeJson(String.valueOf(m.get("preview")))).append("\",");
            sb.append("\"size\":").append(m.get("size")).append(",");
            sb.append("\"created\":").append(m.get("created")).append("}");
        }
        sb.append("]");
        send(ex, 200, "application/json", sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---------- helpers ----------

    static void send(HttpExchange ex, int code, String ctype, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", ctype);
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    static byte[] readAll(InputStream in, long cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        long total = 0;
        while ((r = in.read(buf)) != -1) {
            total += r;
            if (total > cap + 4096) break;
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }

    static String header(HttpExchange ex, String name, String fallback) {
        String v = ex.getRequestHeaders().getFirst(name);
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    static String randomId() {
        byte[] b = new byte[4];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static String preview(String s) {
        String p = s.replaceAll("\\s+", " ").trim();
        return p.length() > 80 ? p.substring(0, 80) : p;
    }

    static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    static String extractJsonString(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher m = p.matcher(json);
        if (!m.find()) return null;
        return unescapeJson(m.group(1));
    }

    static String unescapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'u':
                        if (i + 4 < s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                        break;
                    default: sb.append(n);
                }
            } else sb.append(c);
        }
        return sb.toString();
    }

    static String extractBoundary(String ctype) {
        Pattern p = Pattern.compile("boundary=([^;]+)");
        Matcher m = p.matcher(ctype);
        if (!m.find()) return null;
        String b = m.group(1).trim();
        if (b.startsWith("\"") && b.endsWith("\"")) b = b.substring(1, b.length() - 1);
        return b;
    }

    static String extractMultipartFile(byte[] body, String boundary) {
        String marker = "--" + boundary;
        String data = new String(body, StandardCharsets.ISO_8859_1);
        int start = data.indexOf(marker);
        while (start != -1) {
            int headerEnd = data.indexOf("\r\n\r\n", start);
            if (headerEnd == -1) break;
            String headers = data.substring(start, headerEnd);
            if (headers.contains("name=\"file\"") || headers.contains("filename=")) {
                int contentStart = headerEnd + 4;
                int next = data.indexOf("\r\n" + marker, contentStart);
                if (next == -1) next = data.indexOf(marker, contentStart);
                if (next != -1) {
                    byte[] part = Arrays.copyOfRange(body, contentStart, next);
                    return new String(part, StandardCharsets.UTF_8);
                }
            }
            start = data.indexOf(marker, start + marker.length());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> readMeta() {
        try {
            if (!Files.exists(META_FILE)) return new ArrayList<>();
            String json = Files.readString(META_FILE).trim();
            if (json.isEmpty() || json.equals("[]")) return new ArrayList<>();
            return parseMetaArray(json);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    static List<Map<String, Object>> parseMetaArray(String json) {
        List<Map<String, Object>> list = new ArrayList<>();
        Pattern obj = Pattern.compile("\\{([^{}]*)\\}");
        Matcher m = obj.matcher(json);
        while (m.find()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            String body = m.group(1);
            String id = extractJsonString(body, "id");
            String prev = extractJsonString(body, "preview");
            Long size = extractJsonLong(body, "size");
            Long created = extractJsonLong(body, "created");
            if (id != null) entry.put("id", id);
            if (prev != null) entry.put("preview", prev);
            if (size != null) entry.put("size", size);
            if (created != null) entry.put("created", created);
            if (!entry.isEmpty()) list.add(entry);
        }
        return list;
    }

    static Long extractJsonLong(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)");
        Matcher m = p.matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }

    static void writeMeta(List<Map<String, Object>> meta) throws IOException {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < meta.size(); i++) {
            Map<String, Object> m = meta.get(i);
            if (i > 0) sb.append(",\n");
            sb.append("  {\"id\":\"").append(m.get("id")).append("\",");
            sb.append("\"preview\":\"").append(escapeJson(String.valueOf(m.get("preview")))).append("\",");
            sb.append("\"size\":").append(m.get("size")).append(",");
            sb.append("\"created\":").append(m.get("created")).append("}");
        }
        sb.append("\n]");
        Files.writeString(META_FILE, sb.toString());
    }

    static final String INDEX = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Loadstring API</title>
<style>
:root{--bg:#0a0a0c;--panel:#121216;--line:#1e1e24;--txt:#e8e8ec;--dim:#7a7a86;--acc:#7c5cff;--ok:#3ddc97}
*{box-sizing:border-box;margin:0;padding:0}
body{background:var(--bg);color:var(--txt);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;min-height:100vh;padding:40px 20px}
.wrap{max-width:820px;margin:0 auto}
h1{font-size:28px;font-weight:700;letter-spacing:-.02em;margin-bottom:4px}
.sub{color:var(--dim);font-size:14px;margin-bottom:28px}
.tag{display:inline-block;font-size:11px;padding:3px 8px;border:1px solid var(--line);border-radius:20px;color:var(--dim);margin-right:6px}
.card{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:22px;margin-bottom:18px}
.step{color:var(--dim);font-size:12px;letter-spacing:.08em;text-transform:uppercase;margin-bottom:14px}
.drop{border:1.5px dashed var(--line);border-radius:10px;padding:34px 16px;text-align:center;cursor:pointer;transition:.15s;color:var(--dim);font-size:14px}
.drop:hover,.drop.over{border-color:var(--acc);color:var(--txt)}
textarea{width:100%;min-height:150px;background:#0d0d10;border:1px solid var(--line);border-radius:10px;padding:14px;color:var(--txt);font-family:ui-monospace,Menlo,monospace;font-size:13px;resize:vertical;outline:none}
textarea:focus{border-color:var(--acc)}
.or{text-align:center;color:var(--dim);font-size:11px;letter-spacing:.15em;margin:14px 0}
button{background:var(--acc);color:#fff;border:none;border-radius:9px;padding:11px 20px;font-size:14px;font-weight:600;cursor:pointer;transition:.15s}
button:hover{filter:brightness(1.12)}
button:disabled{opacity:.5;cursor:not-allowed}
.out{background:#0d0d10;border:1px solid var(--line);border-radius:10px;padding:14px;font-family:ui-monospace,Menlo,monospace;font-size:13px;word-break:break-all;color:var(--dim);min-height:44px}
.out.live{color:var(--ok)}
.copy{margin-top:10px;background:transparent;border:1px solid var(--line);color:var(--txt);font-size:12px;padding:7px 14px}
.copy:hover{border-color:var(--acc)}
ul{list-style:none}
li{padding:11px 0;border-bottom:1px solid var(--line);font-size:13px;display:flex;justify-content:space-between;gap:12px;align-items:center}
li:last-child{border-bottom:none}
li code{color:var(--acc);font-size:12px}
li span{color:var(--dim);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1}
.err{color:#ff5c7c;font-size:13px;margin-top:10px}
.bar{height:3px;background:var(--line);border-radius:3px;overflow:hidden;margin-top:12px;display:none}
.bar.on{display:block}
.bar i{display:block;height:100%;width:0;background:var(--acc);transition:.3s}
</style>
</head>
<body>
<div class="wrap">
  <div><span class="tag">LUA</span><span class="tag">RAW HOSTING</span><span class="tag">INSTANT</span></div>
  <h1 style="margin-top:16px">Source in. Loadstring out.</h1>
  <p class="sub">Drop or paste your Lua/Luau script. Hosted on a raw URL, ready-to-run loadstring in one click.</p>

  <div class="card">
    <div class="step">01 · Add source</div>
    <div class="drop" id="drop">Drop file here — or click to upload</div>
    <input type="file" id="file" accept=".lua,.luau,.txt" hidden>
    <div class="or">— OR PASTE SOURCE —</div>
    <textarea id="src" placeholder="print('hello')"></textarea>
    <div class="bar" id="bar"><i></i></div>
    <div class="err" id="err"></div>
    <div style="margin-top:16px;text-align:right"><button id="go">Generate loadstring</button></div>
  </div>

  <div class="card">
    <div class="step">02 · Your loadstring</div>
    <div class="out" id="out">-- waiting for source --</div>
    <button class="copy" id="copy" style="display:none">Copy</button>
  </div>

  <div class="card">
    <div class="step">Recent</div>
    <ul id="recent"><li style="color:var(--dim)">No loadstrings yet.</li></ul>
  </div>
</div>
<script>
const drop=document.getElementById('drop'),file=document.getElementById('file'),
src=document.getElementById('src'),go=document.getElementById('go'),
out=document.getElementById('out'),copy=document.getElementById('copy'),
err=document.getElementById('err'),bar=document.getElementById('bar'),
recent=document.getElementById('recent');
drop.onclick=()=>file.click();
drop.ondragover=e=>{e.preventDefault();drop.classList.add('over')};
drop.ondragleave=()=>drop.classList.remove('over');
drop.ondrop=e=>{e.preventDefault();drop.classList.remove('over');if(e.dataTransfer.files[0])loadFile(e.dataTransfer.files[0])};
file.onchange=e=>{if(e.target.files[0])loadFile(e.target.files[0])};
function loadFile(f){const r=new FileReader();r.onload=()=>{src.value=r.result;drop.textContent=f.name};r.readAsText(f)}
go.onclick=async()=>{
  err.textContent='';const source=src.value.trim();
  if(!source){err.textContent='No source.';return}
  go.disabled=true;bar.classList.add('on');bar.querySelector('i').style.width='40%';
  try{
    const r=await fetch('/api/upload',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({source})});
    const d=await r.json();
    if(!r.ok)throw new Error(d.error||'fail');
    bar.querySelector('i').style.width='100%';
    out.textContent=d.loadstring;out.classList.add('live');
    copy.style.display='inline-block';
    copy.onclick=()=>{navigator.clipboard.writeText(d.loadstring);copy.textContent='Copied';setTimeout(()=>copy.textContent='Copy',1200)};
    loadRecent();
  }catch(e){err.textContent=e.message}
  finally{go.disabled=false;setTimeout(()=>{bar.classList.remove('on');bar.querySelector('i').style.width='0'},600)}
};
async function loadRecent(){
  try{
    const r=await fetch('/api/recent');const d=await r.json();
    if(!d.length){recent.innerHTML='<li style="color:var(--dim)">No loadstrings yet.</li>';return}
    recent.innerHTML=d.map(x=>`<li><span>${esc(x.preview)}</span><code>${x.id}</code></li>`).join('');
  }catch{}
}
function esc(s){return s.replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
loadRecent();
</script>
</body>
</html>
""";
          }
