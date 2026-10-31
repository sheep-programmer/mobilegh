# 下载端到端测试用的本地服务器（HTTPS，支持 Range，限速约 1 MB/s）。
# 运行前先生成自签名证书：openssl req -x509 -newkey rsa:2048 -nodes -keyout /tmp/test.key -out /tmp/test.pem -days 3650 -subj "/CN=10.0.2.2" -addext "subjectAltName=IP:10.0.2.2,IP:127.0.0.1"
# 然后：python3 tools/download_test_server.py ，并让 app/src/debug/res/raw/test_ca.pem 与 /tmp/test.pem 一致。
import http.server, os, time, re
PAYLOAD = bytes(((i * 31 + i // 251) & 0xFF) for i in range(8 * 1024 * 1024))
class H(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass
    def do_GET(self):
        if self.path != "/big.bin":
            self.send_response(404); self.send_header("Content-Length", "0"); self.end_headers(); return
        size = len(PAYLOAD)
        start = 0
        rng = self.headers.get("Range")
        if rng:
            m = re.match(r"bytes=(\d+)-", rng)
            if m: start = int(m.group(1))
        body = PAYLOAD[start:]
        if rng:
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{size-1}/{size}")
        else:
            self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Type", "application/octet-stream")
        self.end_headers()
        chunk = 64 * 1024
        for i in range(0, len(body), chunk):
            try:
                self.wfile.write(body[i:i+chunk]); self.wfile.flush()
            except Exception:
                return
            time.sleep(0.06)  # about 1 MB/s
import ssl
srv = http.server.ThreadingHTTPServer(("0.0.0.0", 8765), H)
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain("/tmp/test.pem", "/tmp/test.key")
srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
srv.serve_forever()
