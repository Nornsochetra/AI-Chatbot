"""Live Gemini check once GEMINI_API_KEY is set: read-aloud of a real bot reply, then transcribe that audio back.
Usage: python -I voice_live_check.py http://localhost:8080
"""
import json, sys, urllib.error, urllib.request, uuid

sys.stdout.reconfigure(encoding="utf-8")
BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"


def http(method, path, data=None, headers=None):
    req = urllib.request.Request(BASE + path, method=method, data=data, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            return r.status, r.read(), r.headers.get("Content-Type")
    except urllib.error.HTTPError as e:
        return e.code, e.read(), e.headers.get("Content-Type")


for question in ("តើសាខាបើកម៉ោងប៉ុន្មាន?", "What are the branch opening hours?"):
    s, body, _ = http("POST", "/api/v1/chat/messages", json.dumps({"channel": "WEBSITE", "text": question}).encode(),
                      {"Content-Type": "application/json"})
    r = json.loads(body)
    print(f"\nQ: {question}\nBot: {r['reply']}")
    s, wav, ctype = http("POST", f"/api/v1/chat/sessions/{r['sessionId']}/replies/{r['replyId']}/speech")
    print(f"read-aloud: HTTP {s}, {ctype}, {len(wav)} bytes, starts with {wav[:4]!r}")
    if s != 200:
        print(wav[:300]); continue
    boundary = "----kb" + uuid.uuid4().hex
    form = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"a.wav\"\r\n"
            f"Content-Type: audio/wav\r\n\r\n").encode() + wav + f"\r\n--{boundary}--\r\n".encode()
    s, body, _ = http("POST", "/api/v1/chat/speech/transcriptions", form,
                      {"Content-Type": f"multipart/form-data; boundary={boundary}"})
    print(f"transcribe back: HTTP {s}: {body.decode('utf-8', 'replace')}")
