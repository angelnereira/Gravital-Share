package io.gravital.share.service

import android.content.Context
import io.gravital.share.telemetry.GravitalLog
import kotlinx.coroutines.*
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.URLConnection

/**
 * Embedded HTTP file server for Gravital Share (Modo Servidor).
 *
 * Binds to 0.0.0.0:PORT so every device connected to the hotspot can open
 * http://<server-ip>:7878 in any browser and see, download, or upload files.
 *
 * Clients connected via the VPN tunnel reach this server transparently through
 * the SOCKS5 relay — no extra configuration needed on either side.
 *
 * Files are stored in the app's external files directory ("GravitalShare/").
 * No extra storage permission is required on Android 10+.
 */
class FileShareServer(context: Context) {

    companion object {
        const val PORT = 7878
    }

    val port = PORT

    private val shareDir: File = File(
        context.getExternalFilesDir(null), "GravitalShare"
    ).also { it.mkdirs() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null

    fun start() {
        scope.launch {
            try {
                val ss = ServerSocket(PORT, 50, InetAddress.getByName("0.0.0.0"))
                serverSocket = ss
                GravitalLog.info(kind = "file_share.started", payload = mapOf("port" to PORT))
                while (isActive) {
                    val client = runCatching { ss.accept() }.getOrNull() ?: break
                    launch { handle(client) }
                }
            } catch (e: Exception) {
                GravitalLog.error(kind = "file_share.error", payload = mapOf("err" to (e.message ?: "")))
            }
        }
    }

    fun stop() {
        GravitalLog.info(kind = "file_share.stopped")
        runCatching { serverSocket?.close() }
        scope.cancel()
    }

    // ── Request handling ──────────────────────────────────────────────────────

    private fun handle(sock: Socket) {
        sock.use {
            runCatching {
                val input = it.getInputStream()
                val out   = it.getOutputStream()

                val requestLine = readHttpLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0]
                val path   = parts[1].split("?")[0]   // strip query string

                // Consume headers; keep Content-Length for PUT
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readHttpLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] =
                            line.substring(idx + 1).trim()
                    }
                }

                when {
                    method == "GET"    && path == "/"           -> serveIndex(out)
                    method == "GET"    && path.startsWith("/f/") -> serveFile(out, decode(path.removePrefix("/f/")))
                    method == "PUT"    && path.startsWith("/f/") -> receiveFile(input, out, decode(path.removePrefix("/f/")), headers)
                    method == "DELETE" && path.startsWith("/f/") -> deleteFile(out, decode(path.removePrefix("/f/")))
                    else -> respond(out, 404, "text/plain", "Not found")
                }
            }
        }
    }

    private fun serveIndex(out: OutputStream) {
        val files = shareDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            ?: emptyList()

        val rows = files.joinToString("") { f ->
            val enc  = encode(f.name)
            val size = fmtSize(f.length())
            "<tr>" +
                "<td><a href=\"/f/$enc\">${esc(f.name)}</a></td>" +
                "<td style=\"white-space:nowrap\">$size</td>" +
                "<td><button class=\"del\" onclick=\"del('$enc')\">✕</button></td>" +
            "</tr>"
        }

        respond(out, 200, "text/html; charset=utf-8", buildHtml(rows))
    }

    private fun serveFile(out: OutputStream, name: String) {
        val f = safe(name) ?: run { respond(out, 404, "text/plain", "Not found"); return }
        val mime = URLConnection.guessContentTypeFromName(f.name) ?: "application/octet-stream"
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $mime\r\n" +
            "Content-Disposition: attachment; filename=\"${f.name}\"\r\n" +
            "Content-Length: ${f.length()}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        f.inputStream().use { it.copyTo(out, bufferSize = 16_384) }
        out.flush()
    }

    private fun receiveFile(input: InputStream, out: OutputStream, name: String, headers: Map<String, String>) {
        val safeName = name.replace("..", "").replace("/", "_").trim().take(200)
            .ifEmpty { "upload_${System.currentTimeMillis()}" }
        val dest = File(shareDir, safeName)

        val len = headers["content-length"]?.toLongOrNull() ?: run {
            respond(out, 411, "text/plain", "Length Required"); return
        }

        FileOutputStream(dest).use { fos ->
            val buf = ByteArray(16_384)
            var remaining = len
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) break
                fos.write(buf, 0, n)
                remaining -= n
            }
        }

        respond(out, 200, "text/plain", "OK")
        GravitalLog.info(kind = "file_share.uploaded", payload = mapOf("file" to safeName))
    }

    private fun deleteFile(out: OutputStream, name: String) {
        safe(name)?.delete()
        respond(out, 200, "text/plain", "OK")
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun respond(out: OutputStream, status: Int, mime: String, body: String) {
        val statusText = mapOf(200 to "OK", 204 to "No Content", 404 to "Not Found",
            411 to "Length Required", 500 to "Internal Server Error")[status] ?: "Unknown"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val hdr = "HTTP/1.1 $status $statusText\r\n" +
            "Content-Type: $mime\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(hdr.toByteArray())
        out.write(bytes)
        out.flush()
    }

    /** Reads one CRLF-terminated line from raw InputStream without buffering lookahead. */
    private fun readHttpLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return if (baos.size() == 0) null else baos.toString("ISO-8859-1")
            if (prev == '\r'.code && b == '\n'.code) {
                val bytes = baos.toByteArray()
                return String(bytes, 0, bytes.size - 1, Charsets.ISO_8859_1)
            }
            baos.write(b)
            prev = b
        }
    }

    // ── Security & utils ──────────────────────────────────────────────────────

    /** Returns File only if it exists inside shareDir (prevents path traversal). */
    private fun safe(name: String): File? {
        val f = File(shareDir, name)
        return if (f.exists() && f.canonicalPath.startsWith(shareDir.canonicalPath)) f else null
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun decode(s: String) = URLDecoder.decode(s, "UTF-8")
    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")

    private fun fmtSize(b: Long) = when {
        b < 1_024L            -> "$b B"
        b < 1_048_576L        -> "${"%.1f".format(b / 1_024.0)} KB"
        b < 1_073_741_824L    -> "${"%.1f".format(b / 1_048_576.0)} MB"
        else                  -> "${"%.2f".format(b / 1_073_741_824.0)} GB"
    }

    // ── HTML ──────────────────────────────────────────────────────────────────

    private fun buildHtml(rows: String) = """<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Gravital Share — Archivos</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:system-ui,sans-serif;background:#f0f4ff;min-height:100vh;padding:1.5em 1em}
.card{background:#fff;border-radius:14px;box-shadow:0 2px 8px rgba(0,0,0,.08);max-width:640px;margin:0 auto}
.header{padding:1.2em 1.4em;border-bottom:1px solid #eee;display:flex;align-items:center;gap:.7em}
.logo{font-size:1.5em}
h1{font-size:1.15em;font-weight:700;color:#1565C0}
table{width:100%;border-collapse:collapse}
td,th{padding:.55em 1em;text-align:left}
th{font-size:.78em;text-transform:uppercase;letter-spacing:.05em;color:#888;border-bottom:1px solid #eee}
tr:not(:last-child) td{border-bottom:1px solid #f5f5f5}
td a{color:#1976D2;text-decoration:none;word-break:break-all}
td a:hover{text-decoration:underline}
.del{background:none;border:none;color:#e53935;cursor:pointer;font-size:.9em;padding:.2em .5em;border-radius:4px}
.del:hover{background:#fce4ec}
.empty{padding:2em 1em;text-align:center;color:#aaa;font-size:.95em}
.upload{padding:1.4em}
.zone{border:2px dashed #ccc;border-radius:10px;padding:2em 1em;text-align:center;cursor:pointer;transition:all .2s;background:#fafafa}
.zone.over{border-color:#1976D2;background:#e3f2fd}
.zone p{color:#888;margin:.4em 0 1em;font-size:.9em}
.btn{display:inline-block;background:#1565C0;color:#fff;border:none;padding:.55em 1.4em;border-radius:7px;cursor:pointer;font-size:.95em;font-weight:600}
.btn:hover{background:#0d47a1}
#fileInput{display:none}
#progress{margin-top:1em;height:6px;background:#e0e0e0;border-radius:3px;overflow:hidden;display:none}
#bar{height:100%;width:0;background:#1976D2;transition:width .15s}
#status{margin-top:.6em;font-size:.85em;color:#555;min-height:1.2em}
</style>
</head>
<body>
<div class="card">
  <div class="header"><span class="logo">📁</span><h1>Gravital Share</h1></div>
  <table>
    <thead><tr><th>Nombre</th><th>Tamaño</th><th></th></tr></thead>
    <tbody>${rows.ifEmpty { "<tr><td class=\"empty\" colspan=\"3\">Sin archivos. Sube el primero ↓</td></tr>" }}</tbody>
  </table>
  <div class="upload">
    <div class="zone" id="zone" onclick="document.getElementById('fileInput').click()">
      <p>Arrastra archivos aquí o toca para seleccionar</p>
      <button class="btn" type="button">Subir archivo</button>
      <input type="file" id="fileInput" multiple>
    </div>
    <div id="progress"><div id="bar"></div></div>
    <div id="status"></div>
  </div>
</div>
<script>
function del(name){
  if(!confirm('¿Eliminar "'+decodeURIComponent(name)+'"?'))return;
  fetch('/f/'+name,{method:'DELETE'}).then(()=>location.reload());
}
const zone=document.getElementById('zone'),fi=document.getElementById('fileInput');
zone.addEventListener('dragover',e=>{e.preventDefault();zone.classList.add('over')});
zone.addEventListener('dragleave',()=>zone.classList.remove('over'));
zone.addEventListener('drop',e=>{e.preventDefault();zone.classList.remove('over');upload(e.dataTransfer.files)});
fi.addEventListener('change',()=>upload(fi.files));
function upload(files){
  const prog=document.getElementById('progress'),bar=document.getElementById('bar'),st=document.getElementById('status');
  let i=0;
  function next(){
    if(i>=files.length){location.reload();return}
    const f=files[i++];
    st.textContent='Subiendo '+f.name+' ('+fmt(f.size)+')…';
    prog.style.display='block';bar.style.width='0';
    const xhr=new XMLHttpRequest();
    xhr.open('PUT','/f/'+encodeURIComponent(f.name));
    xhr.setRequestHeader('Content-Type','application/octet-stream');
    xhr.upload.onprogress=e=>{if(e.lengthComputable)bar.style.width=(e.loaded/e.total*100)+'%'};
    xhr.onload=()=>{st.textContent='✓ '+f.name;next()};
    xhr.onerror=()=>{st.textContent='Error al subir '+f.name};
    xhr.send(f);
  }
  next();
}
function fmt(b){if(b<1024)return b+' B';if(b<1048576)return (b/1024).toFixed(1)+' KB';return (b/1048576).toFixed(1)+' MB'}
</script>
</body>
</html>"""
}
