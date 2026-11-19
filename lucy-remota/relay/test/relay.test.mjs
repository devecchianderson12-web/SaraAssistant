// Prueba del relé contra «wrangler dev» (RELAY_URL, por defecto http://127.0.0.1:8787).
import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";

const base = process.env.RELAY_URL || "http://127.0.0.1:8787";
const wsBase = base.replace(/^http/, "ws");
const room = randomBytes(20).toString("hex");

function join(role) {
  const ws = new WebSocket(`${wsBase}/v1/${room}?role=${role}`);
  ws.binaryType = "arraybuffer";
  const inbox = [];
  const waiters = [];
  ws.onmessage = (e) => {
    const m = typeof e.data === "string" ? e.data : Buffer.from(e.data).toString("hex");
    const w = waiters.shift();
    if (w) w(m); else inbox.push(m);
  };
  const closed = new Promise((r) => (ws.onclose = (e) => r(e.code)));
  const opened = new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  const next = (ms = 3000) => inbox.length ? Promise.resolve(inbox.shift()) : new Promise((r, j) => {
    const t = setTimeout(() => j(new Error(`${role}: nada en ${ms} ms`)), ms);
    waiters.push((m) => { clearTimeout(t); r(m); });
  });
  const quiet = async (ms = 500) => { await new Promise((r) => setTimeout(r, ms)); assert.deepEqual(inbox, [], `${role} recibió de más`); };
  return { ws, opened, closed, next, quiet };
}

const health = await fetch(base + "/");
assert.equal(health.status, 200);
assert.equal((await fetch(base + "/v1/xyz?role=pc")).status, 404);
assert.equal((await fetch(`${base}/v1/${room}?role=otro`)).status, 400);

const pc = join("pc");
await pc.opened;
await pc.quiet();
const dev = join("dev");
await dev.opened;
assert.equal(await pc.next(), "peer:1");
assert.equal(await dev.next(), "peer:1");

pc.ws.send(Uint8Array.from([0, 0, 0, 2, 7, 8]));
assert.equal(await dev.next(), "000000020708");
dev.ws.send(Uint8Array.from([9]));
assert.equal(await pc.next(), "09");

dev.ws.send("ping");
assert.equal(await dev.next(), "pong");

// El teléfono cambia de red: la conexión nueva reemplaza a la vieja; la PC empieza de nuevo.
const dev2 = join("dev");
await dev2.opened;
assert.equal(await dev.closed, 4000);
assert.equal(await pc.next(), "peer:1");
assert.equal(await dev2.next(), "peer:1");
await pc.quiet();
pc.ws.send(Uint8Array.from([1]));
assert.equal(await dev2.next(), "01");

dev2.ws.close(1000);
assert.equal(await pc.next(), "peer:0");
pc.ws.close(1000);
console.log("relé: todas las pruebas pasaron");
