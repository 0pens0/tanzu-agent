# Goose Agent Chat Skill

This skill enables Claude Code to interact with the Goose Agent Chat application deployed at https://goose-agent-chat.apps.tas-ndc.kuhn-labs.com/

## Files

- `SKILL.md` - The skill definition that Claude Code uses
- `goose-chat-helper.sh` - Helper script that handles SSO authentication, session management, and messaging
- `README.md` - This file

## Usage

Just ask Claude to chat with Goose and provide your SSO credentials:

**Examples:**
- "Using username 'myuser' and password 'mypass', chat with Goose and ask about Spring Boot best practices"
- "My SSO credentials are myuser / mypass. Send a message to Goose: What are the latest Java features?"
- "Ask Goose how to optimize this code" (will use cached credentials or prompt if needed)

Claude will automatically:
1. Get credentials from your request, cache, or prompt you for them
2. Authenticate via SSO (OAuth2 flow against UAA identity provider)
3. Create or reuse a chat session
4. Send your message
5. Display Goose's streaming response

## Direct Script Usage

You can also run the helper script directly:

**With credentials inline:**
```bash
./.claude/skills/chat-with-goose/goose-chat-helper.sh --username myuser --password mypass "Your message here"
```

**Without credentials (will prompt or use cache):**
```bash
./.claude/skills/chat-with-goose/goose-chat-helper.sh "Your message here"
```

**With custom URL:**
```bash
./.claude/skills/chat-with-goose/goose-chat-helper.sh --url https://goose.mycompany.com --username myuser --password mypass "Your message here"
```

## Session Management

- **Sessions** are stored in `/tmp/goose-chat-session.txt`
- **Cookies** are stored in `/tmp/goose-chat-cookies.txt`
- **Username** is cached in `/tmp/goose-chat-username.txt` (chmod 600 for security)
- **Password** is cached in `/tmp/goose-chat-password.txt` (chmod 600 for security)
- **URL** is cached in `/tmp/goose-chat-url.txt` (chmod 600 for security)
- Sessions timeout after 30 minutes of inactivity
- The script automatically creates a new session if the current one expires

**Clear cached data:**
```bash
# Start a fresh conversation
rm /tmp/goose-chat-session.txt

# Clear cached credentials
rm /tmp/goose-chat-username.txt /tmp/goose-chat-password.txt

# Reset to default URL
rm /tmp/goose-chat-url.txt

# Clear all cached data
rm /tmp/goose-chat-cookies.txt /tmp/goose-chat-session.txt /tmp/goose-chat-username.txt /tmp/goose-chat-password.txt /tmp/goose-chat-url.txt
```

## How It Works

1. **SSO Authentication**: Uses a multi-step OAuth2 flow:
   - Initiates the OAuth2 authorization flow at `/oauth2/authorization/sso`
   - Follows redirects to the UAA login page
   - Extracts the CSRF token from the login form
   - POSTs credentials to UAA's `/login.do` endpoint
   - Follows the OAuth2 callback redirect chain back to the app
   - Verifies authentication via `/auth/status`
2. **Session Creation**: Calls `POST /api/chat/sessions` to create a new conversation
3. **Message Streaming**: Calls `GET /api/chat/sessions/{id}/stream` with SSE for real-time responses
4. **Response Parsing**: Parses Server-Sent Events to display token streams and completion status

## Configuration

All configuration is in the helper script and skill definition:

- **App URL:** `https://goose-agent-chat.apps.tas-ndc.kuhn-labs.com` (default)
  - Configurable via `--url` parameter
  - Custom URLs are cached in `/tmp/goose-chat-url.txt`
- **SSO Username:** Provided by user via `--username` parameter
  - Cached in `/tmp/goose-chat-username.txt` after first use
- **SSO Password:** Provided by user via `--password` parameter
  - Cached in `/tmp/goose-chat-password.txt` after first use

## Troubleshooting

**Authentication fails:**
- Verify the SSO username and password are correct
- Check that the application is running at the configured URL
- Ensure the UAA identity provider is reachable

**Session creation fails:**
- Ensure you're authenticated
- Check application logs for errors

**No response from Goose:**
- Check that Goose CLI is available on the backend
- Verify GenAI service or LLM provider is configured
