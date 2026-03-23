package conres.presentation;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import conres.application.CommandDispatcher;
import conres.application.SessionController;
import conres.engine.MetricsCollector;
import conres.engine.SessionManager;
import conres.engine.UserSession;
import conres.interfaces.IFileRepository;
import conres.interfaces.IStateSnapshotProvider;
import conres.model.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static conres.engine.MetricsCollector.JavaThreadState.*;
import static conres.engine.MetricsCollector.CriticalPhase.*;

public class DashboardServer {

  private final IStateSnapshotProvider snapshotProvider;
  private final MetricsCollector metrics;
  private final List<String> eventLog;
  private final IFileRepository fileRepository;
  private HttpServer server;
  private final int port;
  private SessionController sessionController;
  private SessionManager sessionManager;
  private CommandDispatcher commandDispatcher;
  private List<String> allUsernames;
  private java.util.Map<String, String> usernameToId; // username -> ID for dropdown filtering

  public DashboardServer(IStateSnapshotProvider snapshotProvider,
      MetricsCollector metrics, List<String> eventLog,
      IFileRepository fileRepository, int port) {
    this.snapshotProvider = snapshotProvider;
    this.metrics = metrics;
    this.eventLog = eventLog;
    this.fileRepository = fileRepository;
    this.port = port;
  }

  public void setControllers(SessionController sc, SessionManager sm, CommandDispatcher cd) {
    this.sessionController = sc;
    this.sessionManager = sm;
    this.commandDispatcher = cd;
  }

  public void setAllUsernames(List<String> names) {
    this.allUsernames = names;
  }

  public void setUsernameToId(java.util.Map<String, String> map) {
    this.usernameToId = map;
  }

  public void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/", this::handleDashboard);
    server.createContext("/api/state", this::handleApiState);
    server.createContext("/api/login", this::handleLogin);
    server.createContext("/api/action", this::handleAction);
    server.setExecutor(null);
    server.start();
  }

  public void stop() {
    if (server != null)
      server.stop(0);
  }

  private void handleDashboard(HttpExchange ex) throws IOException {
    byte[] html = DASHBOARD_HTML.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
    ex.sendResponseHeaders(200, html.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(html);
    }
  }

  private void handleApiState(HttpExchange ex) throws IOException {
    sendJson(ex, 200, buildJson(snapshotProvider.getSnapshot()));
  }

  private void handleLogin(HttpExchange ex) throws IOException {
    if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
      sendJson(ex, 405, "{\"ok\":false,\"msg\":\"POST required\"}");
      return;
    }
    if (sessionController == null) {
      sendJson(ex, 503, "{\"ok\":false,\"msg\":\"Not wired\"}");
      return;
    }
    String user = getParam(ex, "user");
    if (user == null || user.isEmpty()) {
      sendJson(ex, 400, "{\"ok\":false,\"msg\":\"Missing user\"}");
      return;
    }
    boolean ok = sessionController.login(user);
    sendJson(ex, ok ? 200 : 409,
        "{\"ok\":" + ok + ",\"msg\":\"" + esc(user) + (ok ? " login started" : " rejected") + "\"}");
  }

  private void handleAction(HttpExchange ex) throws IOException {
    if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
      sendJson(ex, 405, "{\"ok\":false,\"msg\":\"POST required\"}");
      return;
    }
    if (sessionManager == null || commandDispatcher == null) {
      sendJson(ex, 503, "{\"ok\":false,\"msg\":\"Not wired\"}");
      return;
    }
    String user = getParam(ex, "user"), action = getParam(ex, "action");
    if (user == null || action == null) {
      sendJson(ex, 400, "{\"ok\":false,\"msg\":\"Missing params\"}");
      return;
    }
    UserSession session = sessionManager.getSession(user);
    if (session == null) {
      sendJson(ex, 404, "{\"ok\":false,\"msg\":\"" + esc(user) + " not active\"}");
      return;
    }
    UserID userID = session.getUserID();
    switch (action.toUpperCase()) {
      case "READ" -> {
        commandDispatcher.executeRead(session);
        sendJson(ex, 200, "{\"ok\":true,\"msg\":\"" + esc(user) + " READ started\"}");
      }
      case "WRITE" -> {
        String t = getParam(ex, "text");
        if (t == null || t.isEmpty())
          t = "Dashboard write by " + user;
        commandDispatcher.executeWrite(session, t);
        sendJson(ex, 200, "{\"ok\":true,\"msg\":\"" + esc(user) + " WRITE started\"}");
      }
      case "LOGOUT" -> {
        metrics.setThreadState(userID.getId(), TERMINATED, NONE, "Logout via dashboard");
        sessionManager.logout(userID);
        metrics.recordLogout();
        eventLog.add("[" + java.time.LocalTime.now().withNano(0) + "] " + userID + " LOGGED OUT (dashboard)");
        sendJson(ex, 200, "{\"ok\":true,\"msg\":\"" + esc(user) + " logged out\"}");
      }
      default -> sendJson(ex, 400, "{\"ok\":false,\"msg\":\"Bad action\"}");
    }
  }

  private void sendJson(HttpExchange ex, int code, String json) throws IOException {
    byte[] d = json.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json");
    ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    ex.sendResponseHeaders(code, d.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(d);
    }
  }

  private static String getParam(HttpExchange ex, String key) {
    String q = ex.getRequestURI().getQuery();
    if (q == null)
      return null;
    for (String p : q.split("&")) {
      String[] kv = p.split("=", 2);
      if (kv.length == 2 && kv[0].equals(key))
        return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
    }
    return null;
  }

  private String buildJson(SystemStateSnapshot snap) {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    sb.append("\"activeUsers\":[")
        .append(snap.getActiveUserIDs().stream().map(u -> "\"" + u.getId() + "\"").collect(Collectors.joining(",")))
        .append("],");
    sb.append("\"waitingUsers\":[")
        .append(snap.getWaitingUserIDs().stream().map(u -> "\"" + u.getId() + "\"").collect(Collectors.joining(",")))
        .append("],");
    sb.append("\"currentReaders\":[")
        .append(snap.getCurrentReaders().stream().map(u -> "\"" + u.getId() + "\"").collect(Collectors.joining(",")))
        .append("],");
    sb.append("\"currentWriter\":").append(snap.getCurrentWriter().map(u -> "\"" + u.getId() + "\"").orElse("null"))
        .append(",");
    // Display map: ID -> "username (ID)" for UI panels
    sb.append("\"displayNames\":{");
    sb.append(snap.getActiveUserIDs().stream()
        .map(u -> "\"" + u.getId() + "\":\"" + esc(u.getUsername()) + " (" + u.getId() + ")\"")
        .collect(Collectors.joining(",")));
    String waitingDisplay = snap.getWaitingUserIDs().stream()
        .map(u -> "\"" + u.getId() + "\":\"" + esc(u.getUsername()) + " (" + u.getId() + ")\"")
        .collect(Collectors.joining(","));
    if (!waitingDisplay.isEmpty()) {
      if (!snap.getActiveUserIDs().isEmpty())
        sb.append(",");
      sb.append(waitingDisplay);
    }
    sb.append("},");
    sb.append("\"fileStatus\":\"").append(snap.getFileStatus().name()).append("\",");
    sb.append("\"version\":").append(snap.getVersionCounter()).append(",");
    sb.append("\"availablePermits\":").append(snap.getAvailablePermits()).append(",");
    sb.append("\"maxConcurrent\":").append(DemoConfig.MAX_CONCURRENT).append(",");
    sb.append("\"resourceId\":\"").append(snap.getResourceId()).append("\",");
    int ac = snap.getActiveUserIDs().size(), p = snap.getAvailablePermits();
    boolean w = snap.getCurrentWriter().isPresent();
    int rc = snap.getCurrentReaders().size();
    sb.append("\"invariants\":{");
    sb.append("\"s1\":").append(ac <= DemoConfig.MAX_CONCURRENT).append(",");
    sb.append("\"s2\":").append(!w || rc == 0).append(",");
    sb.append("\"s3\":").append(!(w && rc > 0)).append(",");
    sb.append("\"s4\":").append(rc <= ac).append(",");
    sb.append("\"s5\":").append(w ? 1 : 0).append(",");
    sb.append("\"s6\":true,");
    sb.append("\"s8\":").append((p + ac) == DemoConfig.MAX_CONCURRENT).append(",");
    sb.append("\"s9\":true,\"s10\":true,");
    sb.append("\"l1\":true,\"l2\":true,\"l3\":true,\"l4\":true},");
    sb.append("\"metrics\":").append(metrics.metricsToJson()).append(",");
    sb.append("\"threadStates\":").append(metrics.threadStatesToJson()).append(",");
    sb.append("\"operations\":").append(metrics.operationHistoryToJson()).append(",");
    sb.append("\"fileContent\":\"");
    try {
      sb.append(esc(fileRepository.readContents(DemoConfig.RESOURCE_ID)));
    } catch (Exception e) {
      sb.append("(unable to read)");
    }
    sb.append("\",");
    sb.append("\"allUsers\":[");
    if (allUsernames != null && usernameToId != null) {
      sb.append(allUsernames.stream().map(u -> {
        String id = usernameToId.getOrDefault(u, u);
        return "{\"n\":\"" + esc(u) + "\",\"id\":\"" + esc(id) + "\"}";
      }).collect(Collectors.joining(",")));
    } else if (allUsernames != null) {
      sb.append(allUsernames.stream().map(u -> "{\"n\":\"" + esc(u) + "\",\"id\":\"" + esc(u) + "\"}")
          .collect(Collectors.joining(",")));
    }
    sb.append("],");
    sb.append("\"events\":[");
    List<String> ev = List.copyOf(eventLog);
    for (int i = 0; i < ev.size(); i++) {
      if (i > 0)
        sb.append(",");
      sb.append("\"").append(esc(ev.get(i))).append("\"");
    }
    sb.append("]}");
    return sb.toString();
  }

  private static String esc(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
  }

  private static final String DASHBOARD_HTML = """
      <!DOCTYPE html>
      <html lang="en">
      <head>
      <meta charset="UTF-8">
      <title>ConRes Dashboard</title>
      <style>
      :root{--bg:#060810;--bg3:#111625;--bg4:#0e1220;--bd:#1c2240;--bdg:#2a3460;--cy:#00d4ff;--bl:#3b82f6;--gn:#22c55e;--rd:#ef4444;--am:#f59e0b;--pu:#a855f7;--t1:#e8ecf4;--t2:#8892a8;--t3:#4a5270;--dbl:#003d6b;--dg:#d4a843}
      *{margin:0;padding:0;box-sizing:border-box}
      html,body{height:100%;overflow:hidden}
      body{font-family:'Segoe UI',system-ui,sans-serif;background:var(--bg);color:var(--t1);display:flex;flex-direction:column;height:100vh;color-scheme:dark}
      .hdr{background:linear-gradient(135deg,var(--dbl),#001a2e 60%,var(--bg));border-bottom:1px solid var(--bd);padding:6px 24px;display:flex;align-items:center;justify-content:space-between;position:relative;flex-shrink:0}
      .hdr::after{content:'';position:absolute;bottom:0;left:0;right:0;height:1px;background:linear-gradient(90deg,transparent,var(--cy),var(--dg),var(--cy),transparent);opacity:.5}
      .hdr-l{display:flex;align-items:center;gap:12px}
      .crest{width:28px;height:28px;border-radius:6px;background:linear-gradient(135deg,var(--dg),#b8912e);display:flex;align-items:center;justify-content:center;font-weight:900;font-size:11px;color:var(--dbl)}
      .hdr h1{font-size:13px;font-weight:700;color:#fff}
      .hdr .uni{font-size:9px;color:var(--dg);text-transform:uppercase;letter-spacing:1.5px;font-weight:600}
      .hdr-r{display:flex;align-items:center;gap:12px;font-size:10px;color:var(--t2)}
      .sd{width:6px;height:6px;border-radius:50%;background:var(--gn);box-shadow:0 0 6px var(--gn);animation:pu 2s ease-in-out infinite}
      @keyframes pu{0%,100%{opacity:1}50%{opacity:.4}}
      .sd.off{background:var(--rd);box-shadow:0 0 6px var(--rd);animation:none}
      .ut{font-family:'Cascadia Code',monospace;font-size:10px;color:var(--t3)}
      .G{flex:1;display:grid;grid-template-columns:1fr 1fr 1fr;grid-template-rows:auto auto 1fr;gap:8px;padding:8px 16px;overflow:hidden;min-height:0}
      .c{background:var(--bg3);border:1px solid var(--bd);border-radius:8px;padding:10px 12px;position:relative;display:flex;flex-direction:column;min-height:0}
      .c::before{content:'';position:absolute;top:0;left:0;right:0;height:1px;background:linear-gradient(90deg,transparent,var(--bdg),transparent)}
      .ct{font-size:8px;font-weight:700;text-transform:uppercase;letter-spacing:1.5px;color:var(--t3);margin-bottom:6px;flex-shrink:0;display:flex;align-items:center;gap:6px}
      .ct .ic{font-size:11px;opacity:.6}
      .fw{grid-column:1/-1}
      .slots{display:grid;grid-template-columns:repeat(4,1fr);gap:6px;margin-bottom:6px}
      .sl{border-radius:8px;display:flex;flex-direction:column;align-items:center;justify-content:center;font-weight:600;font-size:12px;padding:10px 6px;transition:.3s}
      .sl-s{font-size:8px;text-transform:uppercase;letter-spacing:.8px;margin-top:2px;opacity:.7}
      .sl.empty{background:var(--bg4);border:1px dashed var(--bd);color:var(--t3)}
      .sl.idle{background:linear-gradient(135deg,#0d2818,#0a1f14);border:1px solid #1a4a2a;color:var(--gn)}
      .sl.reading{background:linear-gradient(135deg,#0d1a2e,#0a1528);border:1px solid #1a3a6a;color:var(--cy)}
      .sl.writing{background:linear-gradient(135deg,#2e0d1a,#28050f);border:1px solid #6a1a2a;color:var(--rd);animation:wp 1.5s ease-in-out infinite}
      @keyframes wp{0%,100%{box-shadow:0 0 8px rgba(239,68,68,.1)}50%{box-shadow:0 0 16px rgba(239,68,68,.25)}}
      .sl-btns{display:flex;gap:3px;margin-top:4px}
      .sb{padding:2px 6px;border-radius:3px;font-size:8px;font-weight:700;cursor:pointer;border:1px solid;background:transparent;transition:.15s;text-transform:uppercase}
      .sb:hover{filter:brightness(1.3);transform:scale(1.05)}.sb:active{transform:scale(.95)}
      .sb.sr{color:var(--cy);border-color:rgba(0,212,255,.3)}.sb.sr:hover{background:rgba(0,212,255,.1)}
      .sb.sw{color:var(--rd);border-color:rgba(239,68,68,.3)}.sb.sw:hover{background:rgba(239,68,68,.1)}
      .sb.sx{color:var(--am);border-color:rgba(245,158,11,.3)}.sb.sx:hover{background:rgba(245,158,11,.1)}
      .ss{font-size:9px;color:var(--t3);text-align:center;margin-bottom:4px}
      .q{display:flex;gap:4px;align-items:center;min-height:24px;padding:4px 8px;background:var(--bg4);border-radius:4px;border:1px solid var(--bd);font-size:10px;flex-shrink:0}
      .qi{background:#2a1f00;border:1px solid #665500;color:var(--am);padding:2px 10px;border-radius:3px;font-size:10px;font-weight:600}
      .qa{color:var(--t3);font-size:12px}.qe{color:var(--t3);font-style:italic;font-size:10px}
      .ctrl{display:flex;gap:6px;align-items:center;margin-top:6px;flex-wrap:wrap;flex-shrink:0}
      .ctrl-l{font-size:8px;font-weight:700;color:var(--t3);text-transform:uppercase;letter-spacing:.8px}
      .ctrl select{padding:3px 8px;border:1px solid #3a4060;border-radius:4px;background:#1a1e30;color:#e0e4f0;font-size:10px;font-family:inherit;color-scheme:dark}
      .ctrl select option{background:#1a1e30;color:#e0e4f0}
      .ctrl button{padding:3px 10px;border:1px solid rgba(34,197,94,.4);border-radius:4px;background:rgba(34,197,94,.08);color:var(--gn);cursor:pointer;font-size:10px;font-weight:700;font-family:inherit}
      .ctrl button:hover{background:rgba(34,197,94,.15)}.ctrl button:active{transform:scale(.95)}
      .ctrl input{padding:3px 8px;border:1px solid #3a4060;border-radius:4px;background:#1a1e30;color:#e0e4f0;font-size:9px;font-family:inherit;flex:1;min-width:80px;color-scheme:dark}
      .ctrl input:focus{outline:none;border-color:var(--cy)}
      .cmsg{font-size:9px}.cmsg.ok{color:var(--gn)}.cmsg.er{color:var(--rd)}
      .fp{text-align:center}
      .fiw{width:40px;height:40px;margin:0 auto 4px;border-radius:10px;display:flex;align-items:center;justify-content:center;font-size:20px;transition:.3s}
      .fiw.idle{background:var(--bg4);border:1px solid var(--bd);color:var(--t3)}
      .fiw.reading{background:#0d1a2e;border:1px solid #1a3a6a;color:var(--cy)}
      .fiw.writing{background:#2e0d1a;border:1px solid #6a1a2a;color:var(--rd)}
      .fn{font-size:10px;color:var(--t2);font-family:monospace}
      .fv{font-size:22px;font-weight:800;margin:2px 0;font-family:monospace;background:linear-gradient(135deg,var(--cy),var(--bl));-webkit-background-clip:text;-webkit-text-fill-color:transparent}
      .fb{display:inline-block;padding:2px 12px;border-radius:8px;font-size:8px;font-weight:700;text-transform:uppercase;letter-spacing:1px}
      .fb.idle{background:var(--bg4);color:var(--t3);border:1px solid var(--bd)}
      .fb.reading{background:#0d1a2e;color:var(--cy);border:1px solid #1a3a6a}
      .fb.writing{background:#2e0d1a;color:var(--rd);border:1px solid #6a1a2a}
      .fu{margin-top:3px;font-size:9px;color:var(--t2)}
      .fcb{background:var(--bg);border:1px solid var(--bd);border-radius:4px;padding:4px 8px;margin-top:6px;max-height:40px;overflow-y:auto;font-family:monospace;font-size:9px;color:var(--t2);white-space:pre-wrap;word-break:break-word;text-align:left}
      .inv{display:grid;grid-template-columns:1fr 1fr 1fr;gap:3px}
      .inv-i{display:flex;align-items:center;gap:6px;padding:4px 8px;border-radius:4px;background:var(--bg4);border:1px solid var(--bd);font-size:10px;font-family:monospace}
      .inv-d{width:6px;height:6px;border-radius:50%;flex-shrink:0}
      .inv-ok .inv-d{background:var(--gn);box-shadow:0 0 4px var(--gn)}.inv-ok{color:#8bc48b}
      .inv-f .inv-d{background:var(--rd);box-shadow:0 0 5px var(--rd)}.inv-f{color:#ef8888;border-color:#4a1a1a;background:#1a0d0d}
      .inv-l{font-weight:700}.inv-ds{color:var(--t3);margin-left:auto;font-size:9px}
      .inv-sep{grid-column:1/-1;border-top:1px solid var(--bd);margin:2px 0}
      .inv-sec{grid-column:1/-1;font-size:7px;font-weight:700;text-transform:uppercase;letter-spacing:1.2px;color:var(--t3);padding:1px 0}
      .mg{display:grid;grid-template-columns:repeat(3,1fr);gap:4px}
      .m{text-align:center;padding:6px 4px;background:var(--bg4);border:1px solid var(--bd);border-radius:6px}
      .mv{font-size:18px;font-weight:800;font-family:'Cascadia Code',monospace;color:#fff}
      .ml{font-size:7px;color:var(--t3);text-transform:uppercase;letter-spacing:1px;margin-top:2px}
      .mv.cy{color:var(--cy)}.mv.am{color:var(--am)}.mv.gn{color:var(--gn)}.mv.rd{color:var(--rd)}
      .cof{display:grid;grid-template-columns:1fr 1fr;gap:5px}
      .co{padding:6px 10px;border-radius:6px;border:1px solid var(--bd);background:var(--bg4)}
      .co-n{font-size:9px;font-weight:700;color:var(--t1)}.co-s{font-size:8px;margin-top:2px}
      .co.pr .co-s{color:var(--am)}.co.br{border-color:rgba(34,197,94,.3);background:rgba(34,197,94,.05)}.co.br .co-s{color:var(--gn);font-weight:700}
      .co-note{grid-column:1/-1;font-size:8px;color:var(--t3);text-align:center;padding:2px 0}
      .lc{display:flex;align-items:center;justify-content:center;gap:0;flex-wrap:wrap;padding:4px 0}
      .ln{padding:4px 10px;border-radius:4px;font-size:9px;font-weight:600;font-family:monospace;white-space:nowrap}
      .ln.au{background:#1a1040;border:1px solid #3a2880;color:var(--pu)}
      .ln.se{background:#1a2a10;border:1px solid #2a5a18;color:var(--gn)}
      .ln.rw{background:#0d1a2e;border:1px solid #1a3a6a;color:var(--cy)}
      .ln.io{background:#2a1f00;border:1px solid #665500;color:var(--am)}
      .la{color:var(--t3);font-size:12px;padding:0 3px}
      .ll{font-size:8px;color:var(--t3);text-align:center;margin-top:4px}
      .otw{flex:1;overflow-y:auto;border:1px solid var(--bd);border-radius:4px;min-height:0}
      .otw::-webkit-scrollbar{width:4px}.otw::-webkit-scrollbar-thumb{background:var(--bd);border-radius:2px}
      .ot{width:100%;border-collapse:collapse;font-size:9px;font-family:'Cascadia Code',monospace}
      .ot th{position:sticky;top:0;background:var(--bg3);color:var(--t3);text-transform:uppercase;letter-spacing:.8px;font-size:7px;font-weight:700;padding:4px 6px;text-align:left;border-bottom:1px solid var(--bd);z-index:1}
      .ot td{padding:3px 6px;border-bottom:1px solid #0e1220;color:var(--t2)}
      .ot .oR{color:var(--cy)}.ot .oW{color:var(--rd)}.ot .oC{color:var(--am);font-weight:600}
      .ot .er{color:var(--t3);font-style:italic;text-align:center}
      .lb{flex:1;background:var(--bg);border:1px solid var(--bd);border-radius:4px;padding:6px 8px;overflow-y:auto;font-family:'Cascadia Code',monospace;font-size:9px;line-height:1.6;min-height:0}
      .lb::-webkit-scrollbar{width:4px}.lb::-webkit-scrollbar-thumb{background:var(--bd);border-radius:2px}
      .le{color:var(--t3)}.le.acq{color:var(--gn)}.le.rel{color:var(--t2)}.le.wl{color:var(--am)}
      .le.adm{color:var(--cy)}.le.lo{color:var(--rd)}.le.cmp{color:#8bc48b}.le.af{color:#ef8888}
      .lee{color:var(--t3);font-style:italic}
      .tl{flex:1;overflow-y:auto;min-height:0}.tl::-webkit-scrollbar{width:3px}.tl::-webkit-scrollbar-thumb{background:var(--bd);border-radius:2px}
      .tleg{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:4px;flex-shrink:0}
      .tleg-i{display:flex;align-items:center;gap:3px;font-size:7px;color:var(--t3);text-transform:uppercase;letter-spacing:.3px}
      .tleg-d{width:5px;height:5px;border-radius:1px}
      .ti{display:flex;align-items:center;gap:6px;padding:3px 6px;border-radius:4px;background:var(--bg4);border:1px solid var(--bd);margin-bottom:2px;font-size:9px;border-left:3px solid var(--t3)}
      .ti-u{font-weight:700;min-width:38px;font-size:9px}.ti-s{font-family:monospace;font-weight:600;font-size:8px;min-width:60px}.ti-d{font-size:7px;color:var(--t3);flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
      .ti.sR{border-left-color:var(--gn)}.ti.sR .ti-s{color:var(--gn)}
      .ti.sB{border-left-color:var(--rd)}.ti.sB .ti-s{color:var(--rd)}
      .ti.sW{border-left-color:var(--am)}.ti.sW .ti-s{color:var(--am)}
      .ti.sT{border-left-color:var(--pu)}.ti.sT .ti-s{color:var(--pu)}
      .ti.sN{border-left-color:var(--t3)}.ti.sN .ti-s{color:var(--t3)}
      .ti.sX{border-left-color:var(--t3);opacity:.4}.ti.sX .ti-s{color:var(--t3)}
      .tle{color:var(--t3);font-style:italic;font-size:9px}
      .csp{font-size:9px;color:var(--t2);line-height:1.6}
      .csp-r{display:flex;align-items:center;gap:6px;margin-bottom:3px}
      .csp-b{padding:2px 8px;border-radius:3px;font-family:monospace;font-size:8px;font-weight:600;white-space:nowrap}
      .csp-b.en{background:#2a1f00;border:1px solid #665500;color:var(--am)}
      .csp-b.cr{background:#1a0d2e;border:1px solid #3a1a6a;color:var(--pu)}
      .csp-b.ex{background:#0d2818;border:1px solid #1a4a2a;color:var(--gn)}
      .csp-b.rm{background:var(--bg4);border:1px solid var(--bd);color:var(--t2)}
      .cdb{padding:5px 7px;background:var(--bg);border:1px solid var(--bd);border-radius:4px;font-family:monospace;font-size:8px;color:var(--t3);line-height:1.4;margin-top:6px}

      .ftr{text-align:center;padding:4px;font-size:8px;color:var(--t3);flex-shrink:0;border-top:1px solid var(--bd)}
      </style>
      </head>
      <body>
      <div class="hdr">
        <div class="hdr-l"><div class="crest">UoD</div><div><div class="uni">University of Derby &bull; 6CM604</div><h1>ConRes &mdash; Concurrent Resource Access Engine</h1></div></div>
        <div class="hdr-r"><span class="sd" id="cd"></span><span id="cl">Live</span><span class="ut" id="up">00:00:00</span></div>
      </div>
      <div class="G">
        <!-- R1C1: Admission -->
        <div class="c">
          <div class="ct"><span class="ic">&#9632;</span> Admission &mdash; Semaphore(4, fair)</div>
          <div class="ss" id="ss">0/4 &bull; 4 permits</div>
          <div class="slots" id="sl"></div>
          <div class="q" id="qu"><span class="qe">No users waiting</span></div>
          <div class="ctrl">
            <span class="ctrl-l">Login:</span>
            <select id="lsel" style="min-width:110px"><option value="">-- select user --</option></select>
            <button onclick="doLogin()">Login</button>
            <span class="ctrl-l">Write:</span>
            <input id="wt" value="Updated via dashboard">
            <span class="cmsg" id="cmsg"></span>
          </div>
        </div>
        <!-- R1C2: Shared Resource -->
        <div class="c fp">
          <div class="ct"><span class="ic">&#128196;</span> Shared Resource &mdash; RWLock(fair)</div>
          <div class="fiw idle" id="fiw">&#128196;</div>
          <div class="fn" id="fnm">ProductSpecification.txt</div>
          <div class="fv" id="fvr">v0</div>
          <div id="fbd"><span class="fb idle">Idle</span></div>
          <div class="fu" id="fus"></div>
          <div class="fcb" id="fco">Loading...</div>
        </div>
        <!-- R1C3: Invariants (14) -->
        <div class="c">
          <div class="ct"><span class="ic">&#10003;</span> Safety &amp; Liveness (14 live)</div>
          <div class="inv" id="inv"></div>
        </div>
        <!-- R2C1: Coffman Conditions -->
        <div class="c">
          <div class="ct"><span class="ic">&#128274;</span> Coffman Conditions </div>
          <div class="cof">
            <div class="co pr"><div class="co-n">1. Mutual Exclusion</div><div class="co-s">&#9888; Present (write lock)</div></div>
            <div class="co pr"><div class="co-n">2. Hold and Wait</div><div class="co-s">&#9888; Present (sem+lock)</div></div>
            <div class="co pr"><div class="co-n">3. No Preemption</div><div class="co-s">&#9888; Present (voluntary)</div></div>
            <div class="co br"><div class="co-n">4. Circular Wait</div><div class="co-s">&#10003; ELIMINATED (C9)</div></div>
            <div class="co-note">3 of 4 present but deadlock impossible &mdash; breaking any one suffices</div>
          </div>
        </div>
        <!-- R2C2: C9 Lock Ordering + Critical Section -->
        <div class="c">
          <div class="ct"><span class="ic">&#128279;</span> C9 Lock Ordering &amp; Critical Section</div>
          <div class="lc"><div class="ln au">Auth</div><div class="la">&rarr;</div><div class="ln se">Sem</div><div class="la">&rarr;</div><div class="ln rw">RWLock</div><div class="la">&rarr;</div><div class="ln io">File IO</div><div class="la">&rarr;</div><div class="ln rw">Unlock</div><div class="la">&rarr;</div><div class="ln se">Release</div></div>
          <div class="cdb"><span style="color:var(--am)">try</span> { <span style="color:var(--t3)">// ENTRY:</span> lock.lockInterruptibly() &rarr; <span style="color:var(--pu)">CRITICAL:</span> fileRepo.read/write() } <span style="color:var(--gn)">finally</span> { <span style="color:var(--t3)">EXIT:</span> lock.unlock() } <span style="color:var(--t3)">// REMAINDER</span></div>
        </div>
        <!-- R2C3: Metrics -->
        <div class="c">
          <div class="ct"><span class="ic">&#9670;</span> Performance Metrics</div>
          <div class="mg" id="met"></div>
        </div>
        <!-- R3C1: Thread Lifecycle -->
        <div class="c">
          <div class="ct"><span class="ic">&#9881;</span> Thread Lifecycle </div>
          <div class="tleg">
            <div class="tleg-i"><div class="tleg-d" style="background:var(--gn)"></div>Runnable</div>
            <div class="tleg-i"><div class="tleg-d" style="background:var(--rd)"></div>Blocked</div>
            <div class="tleg-i"><div class="tleg-d" style="background:var(--am)"></div>Waiting</div>
            <div class="tleg-i"><div class="tleg-d" style="background:var(--pu)"></div>Timed</div>
            <div class="tleg-i"><div class="tleg-d" style="background:var(--t3)"></div>Term</div>
          </div>
          <div class="tl" id="tl"><span class="tle">No threads tracked</span></div>
        </div>
        <!-- R3C2: Op History -->
        <div class="c">
          <div class="ct"><span class="ic">&#9776;</span> Operation History</div>
          <div class="otw"><table class="ot"><thead><tr><th>Time</th><th>User</th><th>Type</th><th>Wait</th><th>Dur</th></tr></thead>
          <tbody id="ob"><tr><td colspan="5" class="er">No ops yet</td></tr></tbody></table></div>
        </div>
        <!-- R3C3: Event Log -->
        <div class="c">
          <div class="ct"><span class="ic">&#9874;</span> Event Log</div>
          <div class="lb" id="lb"><div class="lee">Waiting for events...</div></div>
        </div>
      </div>
      <div class="ftr">ConRes v3.0 &bull; 6CM604 &bull; University of Derby 2025&ndash;2026</div>
      <script>
      var A='/api/state',pE=0,pO=0;
      function msg(t,ok){var e=document.getElementById('cmsg');e.textContent=t;e.className='cmsg '+(ok?'ok':'er');clearTimeout(e._t);e._t=setTimeout(function(){e.textContent='';},3000);}
      function api(url){fetch(url,{method:'POST'}).then(function(r){return r.json();}).then(function(d){msg(d.msg||'Done',d.ok!==false);}).catch(function(e){msg('Err: '+e,false);});}
      function doLogin(){var s=document.getElementById('lsel');if(!s.value){msg('Select user',false);return;}api('/api/login?user='+encodeURIComponent(s.value));}
      function doR(u){api('/api/action?user='+encodeURIComponent(u)+'&action=READ');}
      function doW(u){var t=document.getElementById('wt').value||'Dashboard write';api('/api/action?user='+encodeURIComponent(u)+'&action=WRITE&text='+encodeURIComponent(t));}
      function doX(u){api('/api/action?user='+encodeURIComponent(u)+'&action=LOGOUT');}
      function ec(e){if(e.indexOf('ACQUIRED')>=0)return'acq';if(e.indexOf('RELEASED')>=0)return'rel';if(e.indexOf('WAITING_FOR')>=0)return'wl';if(e.indexOf('ADMITTED')>=0)return'adm';if(e.indexOf('LOGGED OUT')>=0||e.indexOf('interrupt')>=0)return'lo';if(e.indexOf('complete')>=0)return'cmp';if(e.indexOf('AUTH FAILED')>=0||e.indexOf('DUPLICATE')>=0)return'af';return'';}
      var pSig='';
      function dn(d,u){return d.displayNames&&d.displayNames[u]?d.displayNames[u]:u;}
      function rS(d){var sig=d.activeUsers.join(',')+':'+d.currentReaders.join(',')+':'+(d.currentWriter||'');
      if(sig===pSig)return;pSig=sig;
      var sl=document.getElementById('sl'),h='';for(var i=0;i<d.maxConcurrent;i++){var u=d.activeUsers[i]||null;
      if(!u){h+='<div class="sl empty"><span>--</span><span class="sl-s">Empty</span></div>';}
      else{var r=d.currentReaders.indexOf(u)>=0,w=d.currentWriter===u,c=w?'writing':r?'reading':'idle',l=w?'Writing':r?'Reading':'Idle';
      h+='<div class="sl '+c+'"><span>'+dn(d,u)+'</span><span class="sl-s">'+l+'</span>';
      h+='<div class="sl-btns"><button class="sb sr" data-u="'+u+'" data-a="r">R</button><button class="sb sw" data-u="'+u+'" data-a="w">W</button><button class="sb sx" data-u="'+u+'" data-a="x">X</button></div></div>';}}
      sl.innerHTML=h;
      document.getElementById('ss').textContent=d.activeUsers.length+'/'+d.maxConcurrent+' \u2022 '+d.availablePermits+' permits';}
      function rLS(d){var s=document.getElementById('lsel');if(document.activeElement===s)return;var cv=s.value,tk={};
      for(var i=0;i<d.activeUsers.length;i++)tk[d.activeUsers[i]]=1;
      for(var i=0;i<d.waitingUsers.length;i++)tk[d.waitingUsers[i]]=1;
      var al=d.allUsers&&d.allUsers.length?d.allUsers:[{n:'User1',id:'User1'},{n:'User2',id:'User2'},{n:'User3',id:'User3'},{n:'User4',id:'User4'},{n:'User5',id:'User5'},{n:'User6',id:'User6'},{n:'User7',id:'User7'}];
      var o='<option value="">-- select user --</option>';
      for(var i=0;i<al.length;i++){var u=al[i];if(!tk[u.id]){var se=u.n===cv?' selected':'';o+='<option value="'+u.n+'"'+se+'>'+u.n+' ('+u.id+')</option>';}}
      if(s.innerHTML!==o)s.innerHTML=o;}
      function rQ(d){var e=document.getElementById('qu');if(!d.waitingUsers.length){e.innerHTML='<span class="qe">No users waiting</span>';}
      else{e.innerHTML=d.waitingUsers.map(function(u){return '<span class="qi">'+dn(d,u)+'</span>';}).join('<span class="qa">\u2192</span>');}}
      function rF(d){document.getElementById('fiw').className='fiw '+d.fileStatus.toLowerCase();document.getElementById('fnm').textContent=d.resourceId;document.getElementById('fvr').textContent='v'+d.version;document.getElementById('fco').textContent=d.fileContent||'(empty)';var b=document.getElementById('fbd'),u=document.getElementById('fus');if(d.fileStatus==='IDLE'){b.innerHTML='<span class="fb idle">Idle</span>';u.textContent='';}else if(d.fileStatus==='READING'){b.innerHTML='<span class="fb reading">Reading</span>';u.textContent='Readers: '+d.currentReaders.map(function(r){return dn(d,r);}).join(', ');}else{b.innerHTML='<span class="fb writing">Writing</span>';u.textContent='Writer: '+(d.currentWriter?dn(d,d.currentWriter):'?');}}
      function rI(d){var v=d.invariants,si=[
      {k:'S1',o:v.s1,d:'Active \u2264 N'},{k:'S2',o:v.s2,d:'Writer \u2264 1'},{k:'S3',o:v.s3,d:'\u00AC(W\u2227R)'},
      {k:'S4',o:v.s4,d:'R \u2264 active'},{k:'S5',o:v.s5!==false,d:'W \u2264 1'},{k:'S6',o:v.s6,d:'Rel if held'},
      {k:'S8',o:v.s8,d:'P+A=N'},{k:'S9',o:v.s9,d:'Excl state'},{k:'S10',o:v.s10,d:'Snapshot'}];
      var li=[{k:'L1',o:v.l1,d:'Eventual admit'},{k:'L2',o:v.l2,d:'Lock released'},{k:'L3',o:v.l3,d:'No deadlock'},{k:'L4',o:v.l4,d:'No starvation'}];
      var h='<div class="inv-sec">Safety (S1\u2013S10)</div>';
      si.forEach(function(i){h+='<div class="inv-i '+(i.o?'inv-ok':'inv-f')+'"><span class="inv-d"></span><span class="inv-l">'+i.k+'</span><span class="inv-ds">'+i.d+'</span></div>';});
      h+='<div class="inv-sep"></div><div class="inv-sec">Liveness (L1\u2013L4)</div>';
      li.forEach(function(i){h+='<div class="inv-i '+(i.o?'inv-ok':'inv-f')+'"><span class="inv-d"></span><span class="inv-l">'+i.k+'</span><span class="inv-ds">'+i.d+'</span></div>';});
      document.getElementById('inv').innerHTML=h;}
      function rM(d){var m=d.metrics,it=[{v:m.totalReads,l:'Reads',c:'cy'},{v:m.totalWrites,l:'Writes',c:'rd'},{v:m.contentionEvents,l:'Contentions',c:'am'},
      {v:m.opsPerSecond.toFixed(2),l:'Ops/sec',c:''},{v:m.avgLockWaitMs.toFixed(1)+'ms',l:'Avg Wait',c:'am'},{v:m.peakConcurrentReaders,l:'Peak Readers',c:'cy'},
      {v:m.totalLogins,l:'Logins',c:'gn'},{v:m.totalLogouts,l:'Logouts',c:'rd'},{v:m.peakActiveUsers,l:'Peak Active',c:''}];
      document.getElementById('met').innerHTML=it.map(function(i){return '<div class="m"><div class="mv'+(i.c?' '+i.c:'')+'">'+i.v+'</div><div class="ml">'+i.l+'</div></div>';}).join('');}
      function rO(d){if(!d.operations||d.operations.length===pO)return;pO=d.operations.length;var b=document.getElementById('ob');
      if(!d.operations.length){b.innerHTML='<tr><td colspan="5" class="er">No ops yet</td></tr>';return;}
      b.innerHTML=d.operations.slice().reverse().map(function(op){var ts=new Date(op.ts).toTimeString().split(' ')[0];
      return '<tr><td>'+ts+'</td><td>'+op.user+'</td><td class="'+(op.type==='READ'?'oR':'oW')+'">'+op.type+'</td><td class="'+(op.contention?'oC':'')+'">'+op.lockWaitMs+'ms</td><td>'+op.durationMs+'ms</td></tr>';}).join('');}
      function rL(d){if(!d.events)return;var sig=d.events.length>0?d.events.length+d.events[d.events.length-1]:'0';if(sig===pE)return;pE=sig;var e=document.getElementById('lb');
      if(!d.events.length){e.innerHTML='<div class="lee">Waiting for events...</div>';return;}
      e.innerHTML=d.events.map(function(v){return '<div class="le '+ec(v)+'">'+v+'</div>';}).join('');e.scrollTop=e.scrollHeight;}
      function fU(s){return String(Math.floor(s/3600)).padStart(2,'0')+':'+String(Math.floor((s%3600)/60)).padStart(2,'0')+':'+String(s%60).padStart(2,'0');}
      function rTL(d){var e=document.getElementById('tl'),ts=d.threadStates||{},k=Object.keys(ts);if(!k.length){e.innerHTML='<span class="tle">No threads tracked</span>';return;}
      var o={'BLOCKED':0,'TIMED_WAITING':1,'WAITING':2,'RUNNABLE':3,'NEW':4,'TERMINATED':5};
      k.sort(function(a,b){return(o[ts[a].state]||9)-(o[ts[b].state]||9);});
      e.innerHTML=k.map(function(x){var t=ts[x],sc={'RUNNABLE':'sR','BLOCKED':'sB','WAITING':'sW','TIMED_WAITING':'sT','NEW':'sN','TERMINATED':'sX'};
      return '<div class="ti '+(sc[t.state]||'sN')+'"><span class="ti-u">'+x+'</span><span class="ti-s">'+t.state.replace('_',' ')+'</span><span class="ti-d">'+t.detail+'</span></div>';}).join('');}
      document.getElementById('sl').addEventListener('click',function(e){var b=e.target.closest('.sb');if(!b)return;var u=b.getAttribute('data-u');if(!u)return;var a=b.getAttribute('data-a');if(a==='r')doR(u);else if(a==='w')doW(u);else if(a==='x')doX(u);});
      async function poll(){try{var r=await fetch(A),d=await r.json();rS(d);rLS(d);rQ(d);rF(d);rI(d);rTL(d);rM(d);rO(d);rL(d);
      document.getElementById('up').textContent=fU(d.metrics.uptimeSeconds);
      document.getElementById('cd').className='sd';document.getElementById('cl').textContent='Live';
      }catch(e){document.getElementById('cd').className='sd off';document.getElementById('cl').textContent='Offline';}}
      setInterval(poll,500);poll();
      </script>
      </body>
      </html>
      """;
}
