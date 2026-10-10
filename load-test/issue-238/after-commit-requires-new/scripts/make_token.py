import base64, hashlib, hmac, json, sys, time
b = lambda d: base64.urlsafe_b64encode(d).rstrip(b"=").decode()
uid = sys.argv[1]; now = int(time.time())
h = b(json.dumps({"alg": "HS256"}).encode())
p = b(json.dumps({"sub": uid, "iat": now, "exp": now + 86400, "role": "USER"}).encode())
s = b(hmac.new(b"local-dev-secret-key-meety-32bytes!!", f"{h}.{p}".encode(), hashlib.sha256).digest())
print(f"{h}.{p}.{s}")
