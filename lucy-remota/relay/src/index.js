// Relé de Lucy Remota: junta a la PC y al teléfono cuando están en redes distintas.
//
// Los dos se conectan hacia afuera (WebSocket) a la misma «sala»: /v1/<sala>?role=pc|dev.
// La sala sale de las claves públicas de los dos (ver PROTOCOLO.md), así que sólo ellos la conocen.
// El relé reenvía los mensajes binarios de uno al otro tal cual: van cifrados de punta a punta
// con la misma negociación que en la red local, así que acá no se puede leer ni cambiar nada.
//
// Avisos (texto) del relé a cada lado:
//   "peer:1"  el otro está conectado (cada vez que llega uno nuevo, empieza una negociación nueva)
//   "peer:0"  el otro se fue
// El cliente puede mandar el texto "ping" para mantener viva la conexión: contesta "pong" sin
// despertar a la sala (no gasta cuota).

import { DurableObject } from "cloudflare:workers";

const ROOM = /^\/v1\/([0-9a-f]{40})$/;
const ROLES = ["pc", "dev"];
const MAX_MESSAGE = 300 * 1024;
const OPEN = 1;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/" || url.pathname === "/health") {
      return new Response("lucy-remota-relay ok\n", { headers: { "content-type": "text/plain; charset=utf-8" } });
    }
    const m = url.pathname.match(ROOM);
    if (!m) return new Response("no encontrado\n", { status: 404 });
    if (!ROLES.includes(url.searchParams.get("role"))) return new Response("rol inválido\n", { status: 400 });
    if ((request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") {
      return new Response("se espera WebSocket\n", { status: 426 });
    }
    const room = env.ROOMS.get(env.ROOMS.idFromName(m[1]));
    return room.fetch(request);
  },
};

export class Room extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  async fetch(request) {
    const role = new URL(request.url).searchParams.get("role");
    // Una sola conexión por lado: la nueva reemplaza a la vieja (p. ej. el teléfono cambió de red).
    for (const old of this.ctx.getWebSockets(role)) {
      try { old.close(4000, "reemplazada"); } catch {}
    }
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server, [role]);
    const other = this.open(other_(role));
    if (other) {
      tell(other, "peer:1");
      tell(server, "peer:1");
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  open(role) {
    return this.ctx.getWebSockets(role).find((ws) => ws.readyState === OPEN);
  }

  roleOf(ws) {
    return this.ctx.getTags(ws)[0];
  }

  async webSocketMessage(ws, message) {
    if (typeof message === "string") return; // sólo avisos; "ping" lo contesta el sistema
    if (ws.readyState !== OPEN) return; // de una conexión ya reemplazada
    if (message.byteLength > MAX_MESSAGE) {
      ws.close(1009, "mensaje demasiado grande");
      return;
    }
    const other = this.open(other_(this.roleOf(ws)));
    if (other) {
      try { other.send(message); } catch {}
    }
  }

  async webSocketClose(ws) {
    this.gone(ws);
  }

  async webSocketError(ws) {
    this.gone(ws);
  }

  gone(ws) {
    try { ws.close(1000, "chau"); } catch {}
    const role = this.roleOf(ws);
    // Si ya la reemplazó una conexión nueva del mismo lado, el otro no se entera (ya recibió "peer:1").
    if (this.open(role)) return;
    const other = this.open(other_(role));
    if (other) tell(other, "peer:0");
  }
}

function other_(role) {
  return role === "pc" ? "dev" : "pc";
}

function tell(ws, text) {
  try { ws.send(text); } catch {}
}
