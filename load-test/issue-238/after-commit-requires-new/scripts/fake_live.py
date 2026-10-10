import asyncio, json, os, sys
from datetime import datetime, timezone
import websockets

START_DELAY = float(os.environ.get("START_DELAY", "0.2"))
DECODER_DELAY = float(os.environ.get("DECODER_DELAY", "0.5"))
CHUNKS_PER_SEGMENT = int(os.environ.get("CHUNKS_PER_SEGMENT", "4"))
stats = {"open": 0, "max_open": 0, "chunks": 0, "segments": 0}

def log(*a):
    print(datetime.now().strftime("%H:%M:%S.%f")[:-3], *a, flush=True)

async def handle(ws):
    stats["open"] += 1; stats["max_open"] = max(stats["max_open"], stats["open"])
    meeting = rec = None; chunks = 0; seq = 0
    try:
        async for msg in ws:
            if isinstance(msg, bytes):
                chunks += 1; stats["chunks"] += 1
                if chunks % CHUNKS_PER_SEGMENT == 0:
                    seq += 1; stats["segments"] += 1
                    await ws.send(json.dumps({"type": "transcript.committed", "meetingId": int(meeting), "recordingSessionId": int(rec),
                        "payload": {"sequenceNumber": seq, "content": f"가짜 전사 {seq}", "startedAtMs": (seq - 1) * 2000,
                                    "endedAtMs": seq * 2000, "recognizedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}}))
                continue
            m = json.loads(msg); t = m.get("type")
            if t == "session.start":
                meeting, rec = m["meetingId"], m["recordingSessionId"]
                log(f"session.start rec={rec} open={stats['open']}")
                await asyncio.sleep(START_DELAY)
                await ws.send(json.dumps({"type": "session.ready", "requestId": m["requestId"], "meetingId": meeting,
                                          "recordingSessionId": rec, "payload": {"status": "READY"}}))
            elif t == "decoder.reset":
                await asyncio.sleep(DECODER_DELAY)
                await ws.send(json.dumps({"type": "decoder.ready", "requestId": m["requestId"], "payload": {"status": "READY"}}))
            elif t == "session.pause":
                await ws.send(json.dumps({"type": "session.paused", "requestId": m["requestId"], "payload": {"status": "PAUSED"}}))
            elif t == "session.resume":
                await ws.send(json.dumps({"type": "session.resumed", "requestId": m["requestId"], "payload": {"status": "READY"}}))
            elif t == "session.stop":
                log(f"session.stop rec={rec} chunks={chunks} segments={seq}")
                await ws.send(json.dumps({"type": "session.ended", "requestId": m["requestId"], "meetingId": meeting,
                                          "recordingSessionId": rec, "payload": {"status": "ENDED"}}))
                await ws.close()
    finally:
        stats["open"] -= 1

async def report():
    while True:
        await asyncio.sleep(5)
        log("stats", stats)

async def main():
    async with websockets.serve(handle, "0.0.0.0", 8000, max_size=None):
        log("fake live AI on :8000", {"START_DELAY": START_DELAY, "DECODER_DELAY": DECODER_DELAY})
        await report()

asyncio.run(main())
