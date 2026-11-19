// ORBIT AI backend proxy for Cloudflare Workers.
// Keeps provider API keys on the server so they never ship inside the APK.
//
// POST /chat   { provider, system, messages:[{role,content}], image?:{mimeType,data} }
//          ->  { text }   or   { error }
//
// Secrets (set with `wrangler secret put NAME`, never commit them):
//   ORBIT_TOKEN        shared secret the app sends as "Authorization: Bearer <token>" (recommended)
//   GEMINI_API_KEY     optional, from aistudio.google.com
//   GROQ_API_KEY       optional, from console.groq.com
//   ANTHROPIC_API_KEY  optional, from console.anthropic.com
// Optional plain variables to override model names: GEMINI_MODEL, GROQ_MODEL, ANTHROPIC_MODEL.

const MAX_BODY_BYTES = 6 * 1024 * 1024; // images arrive as base64
const MAX_MESSAGES = 40;

const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json" } });

function validate(body) {
  if (!body || typeof body !== "object") return "Invalid JSON body.";
  if (!Array.isArray(body.messages) || body.messages.length === 0) return "messages must be a non-empty array.";
  if (body.messages.length > MAX_MESSAGES) return "Too many messages.";
  for (const m of body.messages) {
    if (!m || (m.role !== "user" && m.role !== "assistant") || typeof m.content !== "string") {
      return "Each message needs role (user|assistant) and string content.";
    }
  }
  if (body.messages[body.messages.length - 1].role !== "user") return "The last message must be from the user.";
  if (body.system !== undefined && typeof body.system !== "string") return "system must be a string.";
  if (body.image && (typeof body.image.mimeType !== "string" || typeof body.image.data !== "string")) {
    return "image needs mimeType and base64 data.";
  }
  return null;
}

async function callGemini(env, body) {
  if (!env.GEMINI_API_KEY) throw new Error("GEMINI_API_KEY is not configured on the server.");
  const model = env.GEMINI_MODEL || "gemini-2.5-flash";
  const contents = body.messages.map((m, i) => {
    const parts = [{ text: m.content }];
    if (body.image && i === body.messages.length - 1) {
      parts.push({ inlineData: { mimeType: body.image.mimeType, data: body.image.data } });
    }
    return { role: m.role === "assistant" ? "model" : "user", parts };
  });
  const res = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`,
    {
      method: "POST",
      headers: { "content-type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY },
      body: JSON.stringify({
        systemInstruction: body.system ? { parts: [{ text: body.system }] } : undefined,
        contents,
      }),
    }
  );
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data?.error?.message || `Gemini HTTP ${res.status}`);
  const text = (data.candidates?.[0]?.content?.parts || []).map((p) => p.text || "").join("");
  if (!text) throw new Error("Gemini returned no text (it may have been blocked).");
  return text;
}

async function callGroq(env, body) {
  if (!env.GROQ_API_KEY) throw new Error("GROQ_API_KEY is not configured on the server.");
  if (body.image) throw new Error("Image input is not supported for the groq provider. Use gemini or anthropic.");
  const model = env.GROQ_MODEL || "openai/gpt-oss-120b";
  const messages = [];
  if (body.system) messages.push({ role: "system", content: body.system });
  messages.push(...body.messages);
  const res = await fetch("https://api.groq.com/openai/v1/chat/completions", {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${env.GROQ_API_KEY}` },
    body: JSON.stringify({ model, messages }),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data?.error?.message || `Groq HTTP ${res.status}`);
  const text = data.choices?.[0]?.message?.content;
  if (!text) throw new Error("Groq returned no text.");
  return text;
}

async function callAnthropic(env, body) {
  if (!env.ANTHROPIC_API_KEY) throw new Error("ANTHROPIC_API_KEY is not configured on the server.");
  const model = env.ANTHROPIC_MODEL || "claude-haiku-5-5";
  const messages = body.messages.map((m, i) => {
    if (body.image && i === body.messages.length - 1) {
      return {
        role: m.role,
        content: [
          { type: "image", source: { type: "base64", media_type: body.image.mimeType, data: body.image.data } },
          { type: "text", text: m.content },
        ],
      };
    }
    return m;
  });
  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-api-key": env.ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01",
    },
    body: JSON.stringify({ model, max_tokens: 1024, system: body.system || undefined, messages }),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data?.error?.message || `Anthropic HTTP ${res.status}`);
  const text = (data.content || []).filter((b) => b.type === "text").map((b) => b.text).join("");
  if (!text) throw new Error("Anthropic returned no text.");
  return text;
}

const PROVIDERS = { gemini: callGemini, groq: callGroq, anthropic: callAnthropic };

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/health") return json({ ok: true });
    if (url.pathname !== "/chat" || request.method !== "POST") return json({ error: "Not found." }, 404);

    // Shared-secret check. Without ORBIT_TOKEN anyone who finds the URL can spend your quota.
    if (env.ORBIT_TOKEN) {
      const auth = request.headers.get("authorization") || "";
      if (auth !== `Bearer ${env.ORBIT_TOKEN}`) return json({ error: "Unauthorized." }, 401);
    }

    const len = Number(request.headers.get("content-length") || 0);
    if (len > MAX_BODY_BYTES) return json({ error: "Request too large." }, 413);

    let body;
    try {
      body = await request.json();
    } catch {
      return json({ error: "Invalid JSON body." }, 400);
    }
    const problem = validate(body);
    if (problem) return json({ error: problem }, 400);

    const handler = PROVIDERS[body.provider];
    if (!handler) return json({ error: `Unknown provider. Use one of: ${Object.keys(PROVIDERS).join(", ")}.` }, 400);

    try {
      return json({ text: await handler(env, body) });
    } catch (e) {
      return json({ error: String(e.message || e) }, 502);
    }
  },
};
