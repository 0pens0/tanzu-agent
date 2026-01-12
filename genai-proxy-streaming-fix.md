# GenAI Proxy SSE Streaming Format Fix

## Problem Summary

When using Goose with a GenAI proxy (such as Tanzu GenAI Service), streaming responses fail silently, resulting in empty chat responses with 0 tokens. The Goose CLI starts successfully and connects to the proxy, but produces no LLM output or tool calls.

## Symptoms

- Chat sessions complete immediately with `{"type":"complete","total_tokens":null}` or show high token counts but 0 tokens in batches
- No message events or tool activities are emitted during streaming
- Tool calls are not executed even when the LLM requests them
- Goose CLI exits with code 0 (success) but no content is generated
- Non-streaming requests to the same endpoint work correctly
- The issue only occurs with GenAI proxy; direct OpenAI API works fine

## Root Causes

The GenAI proxy has **two incompatibilities** with Goose's streaming parser:

### Issue 1: Missing Space After `data:` Prefix

The GenAI proxy returns SSE data lines without a space after the colon:

**GenAI Proxy Format (non-standard)**:
```
data:{"id":"chatcmpl-xxx","choices":[{"index":0,"delta":{"content":"Hello"}}],...}
```

**Standard OpenAI Format**:
```
data: {"id":"chatcmpl-xxx","choices":[{"index":0,"delta":{"content":"Hello"}}],...}
```

Goose's streaming parser in `crates/goose/src/providers/formats/openai.rs` strictly matches `"data: "` (with space).

### Issue 2: Missing `index` Field on Tool Calls

The GenAI proxy returns tool calls without the `index` field that OpenAI's streaming format requires:

**GenAI Proxy Format (missing index)**:
```json
"tool_calls":[{"id":"call-xxx","type":"function","function":{...}}]
```

**Standard OpenAI Format (with index)**:
```json
"tool_calls":[{"index":0,"id":"call-xxx","type":"function","function":{...}}]
```

Goose's parser at line 512 of `openai.rs` requires `if let Some(index) = delta_call.index` to process tool calls. Without this field, all tool calls are silently ignored.

## Solution: Java SSE Normalizing Proxy

This solution adds a local HTTP proxy in the `goose-cf-wrapper` library that transparently normalizes SSE format before Goose CLI sees it. This approach allows you to use official Goose releases without maintaining a fork or waiting for upstream changes.

### Architecture

```
Goose CLI → Local SSE Proxy (Java) → GenAI Proxy → LLM
                    ↓
         Transforms:
         • "data:{...}" → "data: {...}" (adds space)
         • Adds "index":0 to tool_calls missing it
```

### Implementation

The fix is implemented in the `goose-cf-wrapper` library with two components:

#### 1. SseNormalizingProxy.java

A local HTTP proxy that:
- Listens on a dynamically allocated localhost port
- Forwards requests to the upstream GenAI service with proper authentication
- Transforms streaming responses: `data:{...}` → `data: {...}` (adds space after colon)
- Adds `"index":0` to tool_calls that are missing this required field
- Uses proper HTTP chunked transfer encoding for streaming responses
- Forces HTTP/1.1 to avoid HTTP/2 RST_STREAM issues with some GenAI proxies
- Uses virtual threads for efficient connection handling

```java
// Key normalization logic - SSE line format
static String normalizeSseLine(String line) {
    if (line != null && line.startsWith("data:") && !line.startsWith("data: ")) {
        line = "data: " + line.substring(5);
    }
    // Add index field to tool_calls if missing
    return addToolCallIndex(line);
}

// Add required index field to tool calls
static String addToolCallIndex(String line) {
    if (!line.contains("tool_calls")) {
        return line;
    }
    // "tool_calls":[{"id"... → "tool_calls":[{"index":0,"id"...
    if (line.contains("\"tool_calls\":[{\"id\"")) {
        return line.replace("\"tool_calls\":[{\"id\"", "\"tool_calls\":[{\"index\":0,\"id\"");
    }
    return line;
}

// Chunked transfer encoding for streaming
private void writeChunk(OutputStream out, byte[] data) throws IOException {
    String sizeHex = Integer.toHexString(data.length);
    out.write((sizeHex + "\r\n").getBytes(StandardCharsets.UTF_8));
    out.write(data);
    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    out.flush();
}
```

#### 2. GooseExecutorImpl Integration

The executor automatically routes requests through the proxy when a GenAI base URL is configured:

```java
// Automatically activated when using GenAI endpoints
GooseOptions options = GooseOptions.builder()
    .provider("openai")
    .model("gpt-4")
    .apiKey("your-api-key")
    .baseUrl("https://genai.example.com/openai")  // Triggers proxy usage
    .build();
```

### Files Modified

| File | Changes |
|------|---------|
| `goose-cf-wrapper/.../SseNormalizingProxy.java` | **New** - The proxy implementation with SSE and tool_calls normalization |
| `goose-cf-wrapper/.../GooseExecutorImpl.java` | Lazy-initializes proxy when baseUrl is set |
| `goose-cf-wrapper/.../SseNormalizingProxyTest.java` | **New** - Unit tests (22 test cases) |
| `goose-cf-wrapper/pom.xml` | Added junit-jupiter-params for tests |

### Deployment Steps

1. **Update the goose-cf-wrapper dependency** in your application's `pom.xml`

2. **Rebuild and redeploy**:
   ```bash
   mvn clean package
   cf push
   ```

No changes to application code are required - the proxy activates automatically when `GooseOptions` includes both `apiKey` and `baseUrl`.

### How It Works

1. When `applyOpenAiCompatibleEnv()` detects both `apiKey` and `baseUrl` in options
2. It lazily creates an `SseNormalizingProxy` instance
3. The proxy starts on a random available port (e.g., `http://localhost:54321`)
4. `OPENAI_HOST` is set to the proxy URL instead of the real GenAI URL
5. The proxy forwards all requests to GenAI, normalizing SSE responses on the fly
6. Goose CLI receives properly formatted `data: {...}` lines and parses them correctly

## Verification

You can verify the GenAI proxy format using a test endpoint:

```bash
curl -X POST "https://genai-proxy.example.com/openai/v1/chat/completions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"model":"openai/gpt-model","messages":[{"role":"user","content":"Hello"}],"stream":true}'
```

If the response starts with `data:{` (no space), one of these fixes is needed.

## Technical Details

### Why This Happens

#### Issue 1: SSE Data Field Format

**Specification Reference**: [WHATWG HTML Living Standard - Server-sent events, Section 9.2.5](https://html.spec.whatwg.org/multipage/server-sent-events.html#parsing-an-event-stream)

The SSE specification defines the field format in ABNF as:

```abnf
field = 1*name-char [ colon [ space ] *any-char ] end-of-line
```

Where `space` is defined as `%x0020 ; U+0020 SPACE` (optional).

Per Section 9.2.6 "Interpreting an event stream":
> If value starts with a U+0020 SPACE character, remove it from value.

**Key Point**: While the spec allows omitting the space (it's optional in the grammar), OpenAI's implementation and most SSE consumers expect the `data: ` format with a space. The spec's example in section 9.2.4 shows both formats firing identical events:

```
data:test

data: test
```

> "This is because the space after the colon is ignored if present."

However, many streaming clients (including Goose's `reqwest`-based parser) strictly match `data: ` with the space, treating `data:` without a space as an unrecognized line.

**GenAI Non-Compliance**: The GenAI proxy returns `data:{...}` without the space. While technically valid per the SSE grammar, this deviates from OpenAI's de facto standard format.

#### Issue 2: Tool Call `index` Field

**Specification Reference**: [OpenAI API Documentation - Chat Completions Streaming](https://platform.openai.com/docs/api-reference/chat/create)

OpenAI's streaming format for tool calls uses an incremental approach where:
1. Tool calls are streamed across multiple chunks
2. Each chunk's `delta.tool_calls` array contains objects with an `index` field
3. The `index` identifies which tool call the chunk belongs to (supporting parallel tool calls)
4. Clients accumulate `function.arguments` across chunks using the `index` as a key

Example of OpenAI's streaming format (multiple chunks):
```json
// Chunk 1: Initial tool call with name
{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"get_weather","arguments":""}}]}}]}

// Chunk 2: Argument fragment
{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"lo"}}]}}]}

// Chunk 3: More argument fragments
{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"cation\":"}}]}}]}

// Final chunk: finish_reason
{"choices":[{"finish_reason":"tool_calls","delta":{}}]}
```

**GenAI Non-Compliance**: The GenAI proxy sends the entire tool call in a single chunk without the `index` field:
```json
{"choices":[{"delta":{"tool_calls":[{"id":"call_abc","type":"function","function":{"name":"get_weather","arguments":"{\"location\":\"NYC\"}"}}]}}]}
```

While this works for single tool calls, it violates OpenAI's streaming contract where `index` is required for proper chunk aggregation. Clients that follow the OpenAI spec (like Goose) cannot process tool calls without this field.

### Why It's Silent

The streaming parser's `strip_data_prefix` function returns `None` for unrecognized lines, which are then silently skipped in the parsing loop. Similarly, tool calls without an `index` field skip the inner processing loop (see Goose source: `crates/goose/src/providers/formats/openai.rs` line 512: `if let Some(index) = delta_call.index`). Since messages are parsed but have empty content arrays, the agent loop exits normally (as if the LLM simply had nothing to say or no tools to call).

#### Issue 3: Improper Chunked Transfer Encoding Termination

**Specification Reference**: [RFC 7230 - HTTP/1.1 Message Syntax and Routing, Section 4.1](https://datatracker.ietf.org/doc/html/rfc7230#section-4.1)

HTTP chunked transfer encoding requires streams to be terminated with a zero-length chunk:
```
0\r\n
\r\n
```

Some GenAI proxy instances close the TCP connection without sending this termination sequence, causing clients to receive:
```
java.io.IOException: chunked transfer encoding, state: READING_LENGTH
Caused by: java.io.EOFException: EOF reached while reading
```

The proxy handles this by catching the premature EOF and treating it as end-of-stream.

### Specification References Summary

| Issue | Specification | Non-Compliance |
|-------|--------------|----------------|
| SSE data field format | [WHATWG HTML Standard §9.2.5](https://html.spec.whatwg.org/multipage/server-sent-events.html#parsing-an-event-stream) | Missing space after `data:` colon |
| Tool calls `index` field | [OpenAI API Reference](https://platform.openai.com/docs/api-reference/chat/create) | Missing `index` field in `delta.tool_calls` array elements |
| Chunked encoding termination | [RFC 7230 §4.1](https://datatracker.ietf.org/doc/html/rfc7230#section-4.1) | Connection closed without zero-length termination chunk |

### Compatibility

This solution maintains full backward compatibility:
- Standard OpenAI responses (`data: {...}`) continue to work
- GenAI proxy responses (`data:{...}`) now also work
- Tool calls with existing `index` fields are not duplicated
- The normalization handles any trailing whitespace

### HTTP/1.1 Requirement

The proxy forces HTTP/1.1 connections to the upstream GenAI service. Some GenAI proxies have issues with HTTP/2 streaming that result in `RST_STREAM: Internal error` exceptions.

## Known Limitations

### GenAI Proxy Connection Drops

The GenAI proxy may drop connections mid-stream, especially during multi-turn conversations or longer responses. This manifests as:
- Warning: `Upstream closed connection prematurely after N lines`
- Empty responses despite high token counts
- Incomplete tool call sequences

The proxy handles these gracefully (no crashes), but the content is lost. This is a GenAI service-side issue.

### Model Behavior Differences

The GenAI model (`gpt-oss-120b`) may exhibit different tool calling behavior compared to direct OpenAI:
- May prefer simpler tools (e.g., `todo_write`) over complex sequences (`loadSkill` → `shell`)
- May not follow multi-step skill workflows as reliably
- May require more explicit instructions to use specific skills

For comparison, the same prompt on direct OpenAI produces:
1. `loadSkill` (mailgun)
2. `todo_write`
3. `shell` (python script)
4. Text response

While GenAI may only produce:
1. `todo_write`
2. (connection dropped before next tool call)

## Related Issues

- Affects: GenAI proxy integrations, Tanzu GenAI Service
- Does not affect: Direct OpenAI API, Anthropic, Google, or other providers with standard SSE formatting
