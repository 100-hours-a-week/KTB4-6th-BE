import json, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

class Handler(BaseHTTPRequestHandler):
    def read_body(self):
        if self.headers["Content-Length"]:
            return self.rfile.read(int(self.headers["Content-Length"]))
        body = b""
        while (size := int(self.rfile.readline().strip(), 16)):
            body += self.rfile.read(size)
            self.rfile.readline()
        self.rfile.readline()
        return body

    def do_POST(self):
        req = json.loads(self.read_body())
        print(f"POST {self.path} aiRequestId={req.get('aiRequestId')} question={req.get('question')!r}", flush=True)
        time.sleep(2)
        body = json.dumps({"aiRequestId": req.get("aiRequestId"),
                           "answer": f"가짜 AI 답변입니다: {req.get('question')}",
                           "citations": []}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

ThreadingHTTPServer(("0.0.0.0", 8001), Handler).serve_forever()
