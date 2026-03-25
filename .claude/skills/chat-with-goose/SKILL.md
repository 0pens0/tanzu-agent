# Chat with Goose

**Description:** Send messages to the Goose Agent Chat application with automatic SSO authentication and session management.

**Usage:**
- `/chat-with-goose <username> <password> <message>` - Send a message to Goose with SSO credentials
- Can also be invoked by saying "chat with goose" or "send message to goose"

## Configuration

- **App URL:** https://goose-agent-chat.apps.tas-ndc.kuhn-labs.com (default, configurable)
- **Username:** Provided by user (SSO username)
- **Password:** Provided by user (SSO password)
- **Cookie File:** /tmp/goose-chat-cookies.txt
- **Session File:** /tmp/goose-chat-session.txt
- **Username Cache:** /tmp/goose-chat-username.txt
- **Password Cache:** /tmp/goose-chat-password.txt
- **URL Cache:** /tmp/goose-chat-url.txt

## Session ID Handoff

To reuse a browser session (e.g., one where MCP OAuth authentication has already been completed), pass the session ID with `--session`:

```bash
./.claude/skills/chat-with-goose/goose-chat-helper.sh --session chat-a1b2c3d4 "Your message"
```

The session ID is validated against the server. If the session is still active (within 30-minute inactivity window), it will be reused and all MCP OAuth tokens associated with that session will be available. If the session has expired, a new session is created automatically.

The UI provides a **copy session ID** button (clipboard icon next to the truncated session ID in the header) that copies the full session ID to the clipboard.

## Instructions

When the user wants to chat with the Goose Agent Chat application, use the helper script.

### URL Configuration

The script uses `https://goose-agent-chat.apps.tas-ndc.kuhn-labs.com` by default.

**To use a different URL:**

1. **Provide URL inline:**
   ```bash
   ./.claude/skills/chat-with-goose/goose-chat-helper.sh --url https://my-app.example.com --username USER --password PASSWORD "Message"
   ```

2. **URL is cached:**
   - Once provided, custom URLs are cached in `/tmp/goose-chat-url.txt`
   - Subsequent calls reuse the cached URL
   - To switch back to default, delete the cache file

### Credential Handling

**IMPORTANT:** The user must provide SSO credentials (username and password). You have three options:

1. **User provides credentials inline:**
   ```bash
   ./.claude/skills/chat-with-goose/goose-chat-helper.sh --username USER --password PASSWORD "User's message here"
   ```

2. **User provides credentials separately:**
   Ask the user for credentials first, then pass them to the script:
   ```bash
   ./.claude/skills/chat-with-goose/goose-chat-helper.sh --username=USER --password=PASSWORD "User's message here"
   ```

3. **Prompt user for credentials:**
   If the user doesn't provide credentials, the script will prompt for them:
   ```bash
   ./.claude/skills/chat-with-goose/goose-chat-helper.sh "User's message here"
   # Script will prompt: "Enter SSO username: "
   # Script will prompt: "Enter SSO password: "
   ```

**Credential Caching:**
- Once provided (via argument or prompt), both username and password are cached in `/tmp/goose-chat-username.txt` and `/tmp/goose-chat-password.txt`
- Subsequent calls reuse the cached credentials automatically
- If authentication fails, both caches are cleared and the user must provide credentials again
- Cache files have restrictive permissions (600) for security

### Usage Flow

The helper script automatically:
1. Checks if credentials are provided or cached
2. Prompts for credentials if needed
3. Checks authentication status via cookies
4. Authenticates via SSO if needed (multi-step OAuth2 flow against UAA)
5. Gets or creates a chat session (reuses existing active sessions)
6. Sends the message and streams the response
7. Parses SSE events and displays formatted output

### Response Format

The script parses Server-Sent Events (SSE) and displays:
- **Token events**: Goose's text response (concatenated and displayed in real-time)
- **Tool call events**: MCP tool invocations shown as `[Tool Call: extension/toolName]` in yellow
- **Complete event**: Final token count
- **Error events**: Any errors that occur

Tool call events are displayed on stderr so they don't interfere with the response text on stdout. Use these to verify which MCP tools Goose actually invoked during a conversation (e.g., to evaluate conformance with expected agent flows).

### Manual Implementation (if needed)

If the helper script is not available or you need fine-grained control:

1. **Initiate SSO**: GET `/oauth2/authorization/sso` with a cookie jar, follow redirects to the UAA login page
2. **Extract CSRF token**: Parse the `X-Uaa-Csrf` hidden input value from the UAA login page HTML
3. **Submit credentials**: POST to the UAA `/login.do` endpoint with `username`, `password`, and `X-Uaa-Csrf` fields
4. **Follow OAuth callback**: GET the redirect URL returned by UAA (follows redirect chain back to the app with an authorization code)
5. **Verify authentication**: GET `/auth/status` - should return `{"authenticated":true, ...}`
6. **Create Session**: POST to `/api/chat/sessions` with empty JSON body
7. **Send Message**: GET to `/api/chat/sessions/{sessionId}/stream?message={urlEncodedMessage}`
8. **Parse SSE**: Extract token events and concatenate the response

See `goose-chat-helper.sh` for detailed implementation.

## Error Handling

- If authentication fails, inform the user and stop
- If session creation fails, show error details
- If the streaming request times out or fails, show the error
- If cookies expire, re-authenticate automatically

## Examples

### Example 1: User provides credentials inline

User says: "Chat with Goose using username 'myuser' and password 'mypass' and ask about Spring Boot best practices"

Execute:
```bash
./.claude/skills/chat-with-goose/goose-chat-helper.sh --username myuser --password mypass "What are Spring Boot best practices?"
```

### Example 2: User provides credentials separately

User says: "My SSO username is 'myuser' and password is 'mypass'. Now chat with Goose and ask about microservices"

Execute:
```bash
./.claude/skills/chat-with-goose/goose-chat-helper.sh --username=myuser --password=mypass "Tell me about microservices architecture"
```

### Example 3: No credentials provided (will prompt)

User says: "Chat with Goose and ask how to optimize database queries"

Execute:
```bash
./.claude/skills/chat-with-goose/goose-chat-helper.sh "How do I optimize database queries in Spring Boot?"
```

The script will prompt: `Enter SSO username:` and `Enter SSO password:` and wait for user input.

### Example 4: Using cached credentials

User says: "Ask Goose another question about Spring Security"

Execute:
```bash
./.claude/skills/chat-with-goose/goose-chat-helper.sh "Explain Spring Security best practices"
```

The script will use the cached credentials from previous authentication (no prompt needed).

### Example 5: Using custom URL

User says: "Connect to my Goose instance at https://goose.mycompany.com using username 'admin' password 'secret' and ask about deployment"

Execute:
```bash
./.claude/skills/chat-with-goose/goose-chat-helper.sh --url https://goose.mycompany.com --username admin --password secret "How do I deploy to production?"
```

The custom URL will be cached and reused for subsequent requests.

## Notes

- Sessions timeout after 30 minutes of inactivity by default
- The skill maintains state using temporary files for cookies, session ID, credentials, and URL
- Multiple invocations reuse the same session for conversation continuity
- **Clear cached data:**
  - Fresh conversation: `rm /tmp/goose-chat-session.txt`
  - Clear credentials: `rm /tmp/goose-chat-username.txt /tmp/goose-chat-password.txt`
  - Reset to default URL: `rm /tmp/goose-chat-url.txt`
  - Clear everything: `rm /tmp/goose-chat-cookies.txt /tmp/goose-chat-session.txt /tmp/goose-chat-username.txt /tmp/goose-chat-password.txt /tmp/goose-chat-url.txt`
- **Security:** Credentials and URL are never stored in the skill code, only in temporary files with restrictive permissions (600)
