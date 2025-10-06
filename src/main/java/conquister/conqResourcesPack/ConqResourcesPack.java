package conquister.conqResourcesPack;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.*;

public final class ConqResourcesPack extends JavaPlugin implements Listener {
    private List<ResourcePackSource> sources = new ArrayList<>();
    private boolean viaVersionEnabled = false;
    private File packsFolder, mergedPacksFolder, customOverlayFolder;
    private HttpServer httpServer;
    private Map<String, MergedPack> packCache = new ConcurrentHashMap<>();
    private Map<String, Object> buildLocks = new ConcurrentHashMap<>();
    private FoliaLib foliaLib;

    @Override
    public void onEnable() {
        // Initialize FoliaLib
        foliaLib = new FoliaLib(this);

        packsFolder = new File(getDataFolder(), "packs");
        mergedPacksFolder = new File(getDataFolder(), "merged");
        packsFolder.mkdirs();
        mergedPacksFolder.mkdirs();
        saveDefaultConfig();
        String customFolder = getConfig().getString("custom-overlay-folder", "server_pack");
        if (customFolder != null && !customFolder.isEmpty()) {
            customOverlayFolder = new File(getDataFolder(), customFolder);
            customOverlayFolder.mkdirs();
            getLogger().info("§aPasta de overlay customizado: " + customOverlayFolder.getName());
        }
        viaVersionEnabled = Bukkit.getPluginManager().getPlugin("ViaVersion") != null;
        if (viaVersionEnabled) getLogger().info("ViaVersion detectado!");
        Bukkit.getPluginManager().registerEvents(this, this);
        setupCommand();
        startHttpServer();

        // Use FoliaLib async for loading sources
        foliaLib.getScheduler().runAsync(task -> {
            try {
                loadSources();
                getLogger().info("§aFontes carregadas: " + sources.size());
            } catch (Exception e) {
                getLogger().severe("Erro ao carregar fontes: " + e.getMessage());
            }
        });
    }

    @Override
    public void onDisable() {
        if (httpServer != null) httpServer.stop(0);
        // Cancel all FoliaLib tasks
        foliaLib.getScheduler().cancelAllTasks();
    }

    private void setupCommand() {
        this.getCommand("conqresourcepack").setExecutor((sender, cmd, label, args) -> {
            if (args.length > 0 && args[0].equalsIgnoreCase("clear")) {
                packCache.clear();
                for (File f : mergedPacksFolder.listFiles()) if (f.isFile()) f.delete();
                sender.sendMessage("§aCache limpo!");
                return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("status")) {
                sender.sendMessage("§eFontes: §f" + sources.size() + " §7| Cache: §f" + packCache.size());
                return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                foliaLib.getScheduler().runAsync(task -> {
                    try {
                        loadSources();
                        foliaLib.getScheduler().runNextTick(t ->
                                sender.sendMessage("§aConfig recarregada! Fontes: §f" + sources.size())
                        );
                    } catch (Exception e) {
                        foliaLib.getScheduler().runNextTick(t ->
                                sender.sendMessage("§cErro ao recarregar: " + e.getMessage())
                        );
                    }
                });
                return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("zip")) {
                sender.sendMessage("§eReenviando resource packs...");
                foliaLib.getScheduler().runAsync(task -> {
                    final int[] c = {0};
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        String v = getPlayerMinecraftVersion(p);
                        MergedPack pk = getOrCreatePack(v);
                        if (pk != null) {
                            c[0]++;
                            foliaLib.getScheduler().runAtEntity(p, t ->
                                    p.setResourcePack(pk.url, hexToBytes(pk.hash))
                            );
                        }
                    }
                    foliaLib.getScheduler().runNextTick(t ->
                            sender.sendMessage("§aResource packs reenviados para §f" + c[0] + " §ajogadores!")
                    );
                });
                return true;
            }
            sender.sendMessage("§e/conqresourcepack clear §7| §e/conqresourcepack status §7| §e/conqresourcepack reload §7| §e/conqresourcepack zip");
            return true;
        });
    }

    private void startHttpServer() {
        try {
            int port = getConfig().getInt("http-port", 8080);
            httpServer = HttpServer.create(new InetSocketAddress(port), 0);
            httpServer.createContext("/packs/", ex -> {
                String fileName = ex.getRequestURI().getPath().substring(ex.getRequestURI().getPath().lastIndexOf('/') + 1);
                File file = new File(mergedPacksFolder, fileName);
                if (file.exists()) {
                    ex.sendResponseHeaders(200, file.length());
                    try (FileInputStream fis = new FileInputStream(file); OutputStream os = ex.getResponseBody()) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = fis.read(buffer)) != -1) os.write(buffer, 0, count);
                    }
                } else {
                    String resp = "404";
                    ex.sendResponseHeaders(404, resp.length());
                    ex.getResponseBody().write(resp.getBytes());
                    ex.getResponseBody().close();
                }
            });
            httpServer.setExecutor(null);
            httpServer.start();
            getLogger().info("HTTP na porta " + port);
        } catch (Exception e) {
            getLogger().severe("Erro HTTP: " + e.getMessage());
        }
    }

    private void loadSources() {
        sources.clear();
        for (String url : getConfig().getStringList("resourcepacks")) {
            try {
                if (url.contains("modrinth.com")) {
                    String slug = url.substring(url.lastIndexOf('/') + 1);
                    ResourcePackSource src = fetchModrinthProject(slug);
                    if (src != null) sources.add(src);
                } else if (url.contains("curseforge.com")) {
                    String slug = url.substring(url.lastIndexOf('/') + 1);
                    ResourcePackSource src = fetchCurseForgeProject(slug);
                    if (src != null) sources.add(src);
                } else if (url.endsWith(".zip")) {
                    sources.add(new ResourcePackSource(url, "direct", Collections.singletonList(new ResourcePackVersion("Direct", url, null, Collections.singletonList("*")))));
                    getLogger().info("§aURL direta adicionada: " + url);
                }
            } catch (Exception e) {
                getLogger().severe("Erro ao carregar " + url + ": " + e.getMessage());
            }
        }
    }

    private MergedPack getOrCreatePack(String mcVer) {
        if (packCache.containsKey(mcVer)) return packCache.get(mcVer);
        Object lock = buildLocks.computeIfAbsent(mcVer, k -> new Object());
        synchronized (lock) {
            if (packCache.containsKey(mcVer)) return packCache.get(mcVer);
            try {
                String fileName = "merged_" + mcVer.replace(".", "_") + ".zip";
                File merged = new File(mergedPacksFolder, fileName);
                if (!merged.exists() || merged.length() == 0) {
                    getLogger().info("§eGerando pack para " + mcVer + "...");
                    List<File> packs = new ArrayList<>();
                    for (ResourcePackSource src : sources) {
                        ResourcePackVersion best = findBestVersion(src, mcVer);
                        if (best != null) {
                            File pack = downloadPack(src.name, best);
                            if (pack != null) packs.add(pack);
                        }
                    }
                    if (packs.isEmpty()) {
                        getLogger().warning("Nenhum pack disponível para " + mcVer);
                        return null;
                    }
                    mergePacks(packs, merged);
                    if (customOverlayFolder != null && customOverlayFolder.exists()) {
                        getLogger().info("§eAplicando overlay customizado...");
                        applyCustomOverlay(merged);
                    }
                    getLogger().info("§aPack criado para " + mcVer + " (" + (merged.length() / 1024 / 1024) + " MB)");
                } else {
                    getLogger().info("§7Pack já existe para " + mcVer);
                }
                String hash = calculateSHA1(merged);
                String url = "http://" + getConfig().getString("server-ip", "localhost") + ":" + getConfig().getInt("http-port", 8080) + "/packs/" + fileName;
                MergedPack pack = new MergedPack(url, hash);
                packCache.put(mcVer, pack);
                return pack;
            } catch (Exception e) {
                getLogger().severe("Erro ao criar pack para " + mcVer + ": " + e.getMessage());
                e.printStackTrace();
                return null;
            } finally {
                buildLocks.remove(mcVer);
            }
        }
    }

    private void applyCustomOverlay(File targetZip) throws Exception {
        File temp = new File(targetZip.getParent(), targetZip.getName() + ".tmp");
        Map<String, byte[]> existingFiles = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(targetZip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (!e.isDirectory()) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = zis.read(buf)) > 0) baos.write(buf, 0, len);
                    existingFiles.put(e.getName(), baos.toByteArray());
                }
                zis.closeEntry();
            }
        }
        addFolderToMap(customOverlayFolder, "", existingFiles);
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(temp))) {
            for (Map.Entry<String, byte[]> e : existingFiles.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        targetZip.delete();
        temp.renameTo(targetZip);
    }

    private void addFolderToMap(File folder, String basePath, Map<String, byte[]> map) throws Exception {
        for (File f : folder.listFiles()) {
            String path = basePath.isEmpty() ? f.getName() : basePath + "/" + f.getName();
            if (f.isDirectory()) {
                addFolderToMap(f, path, map);
            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (FileInputStream fis = new FileInputStream(f)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = fis.read(buf)) > 0) baos.write(buf, 0, len);
                }
                map.put(path, baos.toByteArray());
            }
        }
    }

    private File downloadPack(String name, ResourcePackVersion ver) {
        try {
            String fileName = name.replaceAll("[^a-zA-Z0-9.-]", "_") + "_" + ver.name.replaceAll("[^a-zA-Z0-9.-]", "_") + ".zip";
            File file = new File(packsFolder, fileName);
            if (file.exists() && file.length() > 0) return file;
            synchronized (fileName.intern()) {
                if (file.exists() && file.length() > 0) return file;
                HttpURLConnection conn = (HttpURLConnection) new URL(ver.downloadUrl).openConnection();
                conn.setRequestProperty("User-Agent", "ConqRP/1.0");
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(60000);
                conn.setInstanceFollowRedirects(true);
                try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(file)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
            }
            return file;
        } catch (Exception e) {
            getLogger().warning("Erro ao baixar " + name + ": " + e.getMessage());
            return null;
        }
    }

    private void mergePacks(List<File> packs, File out) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<String, JsonObject> jsons = new HashMap<>();
        Collections.reverse(packs);
        for (File pack : packs) {
            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(pack))) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if (!e.isDirectory()) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = zis.read(buf)) > 0) baos.write(buf, 0, len);
                        byte[] content = baos.toByteArray();
                        if (shouldMergeJson(e.getName())) {
                            try {
                                JsonObject obj = JsonParser.parseString(new String(content, "UTF-8")).getAsJsonObject();
                                JsonObject cur = jsons.getOrDefault(e.getName(), new JsonObject());
                                mergeJsonObjects(cur, obj);
                                jsons.put(e.getName(), cur);
                            } catch (Exception ex) {
                                files.put(e.getName(), content);
                            }
                        } else files.put(e.getName(), content);
                    }
                    zis.closeEntry();
                }
            }
        }
        for (Map.Entry<String, JsonObject> e : jsons.entrySet()) files.put(e.getKey(), e.getValue().toString().getBytes("UTF-8"));
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
    }

    private boolean shouldMergeJson(String name) {
        String l = name.toLowerCase();
        return l.endsWith(".json") && (l.contains("sounds.json") || l.contains("/lang/") || l.contains("/blockstates/") || l.contains("/models/"));
    }

    private void mergeJsonObjects(JsonObject base, JsonObject merge) {
        for (Map.Entry<String, JsonElement> e : merge.entrySet()) {
            if (base.has(e.getKey()) && base.get(e.getKey()).isJsonObject() && e.getValue().isJsonObject()) mergeJsonObjects(base.getAsJsonObject(e.getKey()), e.getValue().getAsJsonObject());
            else base.add(e.getKey(), e.getValue());
        }
    }

    private ResourcePackSource fetchModrinthProject(String slug) throws Exception {
        String pJson = fetchUrl("https://api.modrinth.com/v2/project/" + slug);
        JsonObject proj = JsonParser.parseString(pJson).getAsJsonObject();
        String vJson = fetchUrl("https://api.modrinth.com/v2/project/" + slug + "/version");
        JsonArray vers = JsonParser.parseString(vJson).getAsJsonArray();
        ResourcePackSource src = new ResourcePackSource(proj.get("title").getAsString(), slug, new ArrayList<>());
        for (JsonElement ve : vers) {
            JsonObject v = ve.getAsJsonObject();
            List<String> mcVers = new ArrayList<>();
            for (JsonElement gv : v.getAsJsonArray("game_versions")) mcVers.add(gv.getAsString());
            JsonObject file = v.getAsJsonArray("files").get(0).getAsJsonObject();
            src.versions.add(new ResourcePackVersion(v.get("name").getAsString(), file.get("url").getAsString(), file.getAsJsonObject("hashes").get("sha1").getAsString(), mcVers));
        }
        return src;
    }

    private ResourcePackSource fetchCurseForgeProject(String slug) throws Exception {
        String apiKey = getConfig().getString("curseforge-api-key", "");
        if (apiKey.isEmpty()) {
            getLogger().warning("CurseForge requer API key no config.yml (curseforge-api-key)");
            return null;
        }
        String searchJson = fetchUrlWithKey("https://api.curseforge.com/v1/mods/search?gameId=432&classId=12&slug=" + slug, apiKey);
        JsonObject searchResult = JsonParser.parseString(searchJson).getAsJsonObject();
        JsonArray data = searchResult.getAsJsonArray("data");
        if (data.size() == 0) return null;
        JsonObject mod = data.get(0).getAsJsonObject();
        int modId = mod.get("id").getAsInt();
        String name = mod.get("name").getAsString();
        String filesJson = fetchUrlWithKey("https://api.curseforge.com/v1/mods/" + modId + "/files", apiKey);
        JsonObject filesResult = JsonParser.parseString(filesJson).getAsJsonObject();
        JsonArray files = filesResult.getAsJsonArray("data");
        ResourcePackSource src = new ResourcePackSource(name, slug, new ArrayList<>());
        for (JsonElement fe : files) {
            JsonObject file = fe.getAsJsonObject();
            List<String> mcVers = new ArrayList<>();
            for (JsonElement gv : file.getAsJsonArray("gameVersions")) mcVers.add(gv.getAsString());
            src.versions.add(new ResourcePackVersion(file.get("displayName").getAsString(), file.get("downloadUrl").getAsString(), null, mcVers));
        }
        return src;
    }

    private String fetchUrl(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "ConqRP/1.0");
        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) sb.append(line);
        in.close();
        return sb.toString();
    }

    private String fetchUrlWithKey(String url, String key) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "ConqRP/1.0");
        conn.setRequestProperty("x-api-key", key);
        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) sb.append(line);
        in.close();
        return sb.toString();
    }

    private String calculateSHA1(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        // Use FoliaLib's runLater with entity context (2 seconds = 40 ticks)
        foliaLib.getScheduler().runAtEntityLater(p, task -> {
            String ver = getPlayerMinecraftVersion(p);
            foliaLib.getScheduler().runAsync(t -> {
                MergedPack pack = getOrCreatePack(ver);
                if (pack != null) {
                    foliaLib.getScheduler().runAtEntity(p, t2 -> {
                        p.setResourcePack(pack.url, hexToBytes(pack.hash));
                        getLogger().info("Pack enviado para " + p.getName() + " (" + ver + ")");
                    });
                } else {
                    foliaLib.getScheduler().runAtEntity(p, t2 ->
                            p.sendMessage("§cErro ao gerar resource pack!")
                    );
                }
            });
        }, 40L);
    }

    private String getPlayerMinecraftVersion(Player p) {
        if (viaVersionEnabled) {
            try {
                ProtocolVersion v = Via.getAPI().getPlayerProtocolVersion(p.getUniqueId());
                return v.getName();
            } catch (Exception e) {}
        }
        return "1.21";
    }

    private ResourcePackVersion findBestVersion(ResourcePackSource src, String pVer) {
        for (ResourcePackVersion v : src.versions) if (v.supportedVersions.contains("*") || v.supportedVersions.contains(pVer)) return v;
        for (ResourcePackVersion v : src.versions) for (String s : v.supportedVersions) if (isVersionCompatible(s, pVer)) return v;
        return src.versions.isEmpty() ? null : src.versions.get(0);
    }

    private boolean isVersionCompatible(String v1, String v2) {
        String[] p1 = v1.split("\\.");
        String[] p2 = v2.split("\\.");
        return p1.length >= 2 && p2.length >= 2 && p1[0].equals(p2[0]) && p1[1].equals(p2[1]);
    }

    private byte[] hexToBytes(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) b[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        return b;
    }

    private static class MergedPack {String url, hash; MergedPack(String u, String h) {url = u; hash = h;}}
    private static class ResourcePackSource {String name, slug; List<ResourcePackVersion> versions; ResourcePackSource(String n, String s, List<ResourcePackVersion> v) {name = n; slug = s; versions = v;}}
    private static class ResourcePackVersion {String name, downloadUrl, hash; List<String> supportedVersions; ResourcePackVersion(String n, String d, String h, List<String> s) {name = n; downloadUrl = d; hash = h; supportedVersions = s;}}
}