# MCP Server Authentication Methods

Goose Agent Chat supports multiple authentication methods for connecting to
remote MCP servers. This document classifies each method, describes when to use
it, and provides configuration examples.

---

## Overview

| Method | Interactive | Token Refresh | Per-User Identity | Configuration Complexity |
|---|---|---|---|---|
| [OAuth 2.0 with RFC 9728 Discovery](#1-oauth-20-with-rfc-9728-discovery) | Yes (popup) | Automatic | Yes | High |
| [OAuth 2.0 with RFC 9728 + Dynamic Client Registration](#1b-oauth-20-with-rfc-9728--dynamic-client-registration-rfc-7591) | Yes (popup) | Automatic | Yes | Low |
| [OAuth 2.0 with Provider-Specific Flow](#2-oauth-20-with-provider-specific-flow) | Yes (popup) | Automatic | Yes | Medium |
| [API Key Header](#3-api-key-header) | No | Not applicable | No | Low |
| [Static Bearer Token](#4-static-bearer-token) | No | None (manual) | Depends on token | Low |

---

## 1. OAuth 2.0 with RFC 9728 Discovery

**Full standards-based OAuth 2.0 flow with automatic endpoint discovery.**

The MCP server implements [RFC 9728 (OAuth 2.0 Protected Resource Metadata)](https://datatracker.ietf.org/doc/html/rfc9728)
and advertises its authorization server through a `/.well-known/oauth-protected-resource`
document. Goose Agent Chat discovers the authorization server, then follows
[RFC 8414 (Authorization Server Metadata)](https://datatracker.ietf.org/doc/html/rfc8414)
to find the authorize and token endpoints. Authentication uses the OAuth 2.1
Authorization Code flow with PKCE.

### Architecture Overview

The following diagram shows the high-level architecture of the OAuth flow
between goose-agent-chat (OAuth Client), cf-auth-mcp (Resource Server), and
Tanzu SSO / UAA (Authorization Server). Both applications are bound to the
same p-identity service instance, which establishes a shared trust domain.

![OAuth Authorization Flow Architecture](images/oauth-flow-architecture.png)

### SSO Service Binding Architecture

Both goose-agent-chat and cf-auth-mcp are bound to the same p-identity (Tanzu
SSO) service instance, but each receives its own client registration with a
unique `client_id`. The shared trust domain — same JWT signing keys, same
authorization server — is what allows tokens obtained through goose-agent-chat
to be validated by cf-auth-mcp.

![SSO Service Binding Architecture](images/sso-binding-architecture.png)

### Discovery Chain

```
1. Client sends unauthenticated request to MCP server
2. Server returns 401 with WWW-Authenticate header containing resource_metadata URL
3. Client fetches /.well-known/oauth-protected-resource/{resource}
4. Response includes authorization_servers field
5. Client fetches /.well-known/oauth-authorization-server from that server
6. Response includes authorization_endpoint and token_endpoint
7. Client initiates Authorization Code + PKCE flow
```

### When to Use

- The MCP server is protected by an external OAuth 2.0 authorization server
  (e.g., Tanzu SSO / UAA, Okta, Auth0)
- You need per-user identity — each user authenticates as themselves, and the
  MCP server can enforce role-based access control based on the user's token
- The MCP server implements the MCP Authorization specification

### Configuration

```yaml
mcpServers:
  - name: cloud-foundry
    type: streamable_http
    url: "https://cf-auth-mcp.apps.example.com/mcp"
    requiresAuth: true
    clientId: ${CF_MCP_OAUTH_CLIENT_ID}
    clientSecret: ${CF_MCP_OAUTH_CLIENT_SECRET}
    scopes: "openid cloud_controller.read cloud_controller.write"
```

### Configuration Fields

| Field | Description | Required |
|---|---|---|
| `requiresAuth` | Must be `true` to enable the OAuth flow | Yes |
| `clientId` | OAuth client ID registered with the authorization server | Yes (unless dynamic registration is supported) |
| `clientSecret` | OAuth client secret | Yes (for confidential clients) |
| `scopes` | Space-separated list of OAuth scopes to request | Recommended (auto-discovered if omitted) |

### Authentication Flow

The following sequence diagram shows the full OAuth authentication flow that
occurs when a user clicks the "OAuth Connect" button. It covers RFC 9728
discovery, RFC 8414 authorization server metadata, PKCE code challenge
generation, user login at the SSO page, and the authorization code exchange.

![Part 1: OAuth Authentication Flow](images/part-1-authentication-flow.png)

### Authenticated Request Flow

After authentication, the following flow occurs each time the user sends a
chat message that triggers an MCP tool call. The bearer token is injected into
the Goose configuration, sent to cf-auth-mcp, validated against UAA's signing
keys, and then relayed to the Cloud Foundry API where RBAC enforcement ensures
the user can only access resources permitted by their CF roles.

![Part 2: Authenticated Chat Request Flow](images/part-2-chat-request-flow.png)

### User Experience

1. User sees an **"OAuth Connect"** button next to the MCP server in the config panel
2. Clicking it opens a popup window to the authorization server's login page
3. User authenticates and grants consent
4. Popup closes and the server shows **"Connected"** status
5. Tokens are managed server-side and refresh automatically

### Example: Cloud Foundry MCP Server with Tanzu SSO

The Cloud Foundry MCP server uses Tanzu SSO (UAA) as its authorization server.
Both goose-agent-chat and the MCP server must be bound to the same `p-identity`
service instance.

```yaml
# .goose-config.yml
mcpServers:
  - name: cloud-foundry
    type: streamable_http
    url: "https://cf-auth-mcp.apps.tas-ndc.kuhn-labs.com/mcp"
    requiresAuth: true
    clientId: ${CF_MCP_OAUTH_CLIENT_ID}
    clientSecret: ${CF_MCP_OAUTH_CLIENT_SECRET}
    scopes: "openid cloud_controller.read cloud_controller.write"
```

**Prerequisites:**
- goose-agent-chat's SSO client redirect URI allowlist must include
  `https://goose-agent-chat.apps.example.com/oauth/callback`
- The `clientId` and `clientSecret` are from goose-agent-chat's own SSO binding
  (not the MCP server's binding)
- Both apps must be bound to the same SSO service instance for token validation
  to succeed

---

## 1b. OAuth 2.0 with RFC 9728 + Dynamic Client Registration (RFC 7591)

**Standards-based OAuth 2.0 flow with automatic endpoint discovery _and_ automatic
client registration — no pre-configured `clientId` or `clientSecret` required.**

When the authorization server advertises a `registration_endpoint` in its
[RFC 8414](https://datatracker.ietf.org/doc/html/rfc8414) metadata, the
goose-buildpack Java wrapper can register itself at runtime using
[RFC 7591 (OAuth 2.0 Dynamic Client Registration)](https://datatracker.ietf.org/doc/html/rfc7591).
This eliminates the need to pre-register an OAuth application or pre-configure
redirect URIs.

### How It Works

The goose-buildpack `McpOAuthManagerImpl` extends the standard RFC 9728
discovery flow with a lazy DCR step:

```
1. Client discovers OAuth config via RFC 9728 + RFC 8414 (same as Method 1)
2. Authorization Server Metadata includes registration_endpoint
3. If no clientId is configured, the wrapper POSTs a registration request:
     POST /register
     Content-Type: application/json

     {
       "client_name": "Goose Agent Chat",
       "redirect_uris": ["https://goose-agent-chat.apps.example.com/oauth/callback"],
       "grant_types": ["authorization_code", "refresh_token"],
       "response_types": ["code"],
       "token_endpoint_auth_method": "none"
     }
4. The server returns a dynamically assigned client_id (and optionally a client_secret)
5. The wrapper caches the credentials and proceeds with Authorization Code + PKCE
```

### Key Implementation Details

| Aspect | Detail |
|---|---|
| Specification | [RFC 7591 — OAuth 2.0 Dynamic Client Registration Protocol](https://datatracker.ietf.org/doc/html/rfc7591) |
| Implementation | `OAuthDiscoveryService.performDynamicClientRegistration()` in goose-buildpack's `java-wrapper` |
| Trigger condition | `McpOAuthConfig.requiresDynamicClientRegistration()` returns `true` when no `clientId` is configured and `registrationEndpoint` is present |
| Client type | Public client (`token_endpoint_auth_method: "none"`) — no client secret required |
| Credential caching | Dynamic credentials are cached per server name in `McpOAuthManagerImpl.dynamicClientStore` and reused for subsequent authorization flows |
| Redirect URI | Computed at authorization time and included in the DCR request body |

### When to Use

- The MCP server's authorization server supports RFC 7591 Dynamic Client
  Registration (advertises `registration_endpoint` in its metadata)
- You want zero-configuration OAuth — no need to register an OAuth app or
  manage client credentials
- The authorization server does not require pre-registered redirect URIs

### Configuration

Because DCR handles client registration automatically, the configuration is
minimal — simply omit `clientId` and `clientSecret`:

```yaml
mcpServers:
  - name: jira
    type: streamable_http
    url: "https://mcp-gateway-jira-prod.apps.example.com/jira/mcp"
    requiresAuth: true
    scopes: "read:jira-work write:jira-work"
```

### Configuration Fields

| Field | Description | Required |
|---|---|---|
| `requiresAuth` | Must be `true` to enable the OAuth flow | Yes |
| `clientId` | Omit — the wrapper registers dynamically | No |
| `clientSecret` | Omit — public client registration | No |
| `scopes` | Space-separated list of OAuth scopes to request | Recommended |

### Difference from Method 1

| | Method 1 (Pre-Registered Client) | Method 1b (Dynamic Registration) |
|---|---|---|
| Client ID source | Pre-configured in `.goose-config.yml` | Obtained at runtime via RFC 7591 |
| Client type | Confidential (with secret) | Public (`token_endpoint_auth_method: "none"`) |
| Redirect URI registration | Manual (pre-registered with auth server) | Automatic (sent in DCR request) |
| Auth server requirement | Standard OAuth 2.0 | Must support RFC 7591 |
| Configuration complexity | High (register app, obtain credentials) | Low (just set `requiresAuth: true`) |

### Authorization Server Compatibility

DCR requires server-side support. The authorization server must:

1. Advertise `registration_endpoint` in its
   `/.well-known/oauth-authorization-server` metadata
2. Accept unauthenticated registration requests (open registration)
3. Return at minimum a `client_id` in the registration response

| Authorization Server | DCR Supported |
|---|---|
| MCP-compliant gateways (e.g., Tanzu MCP Gateway) | Yes |
| GitHub OAuth | No |
| Tanzu SSO / UAA | No |
| Okta | Yes (with feature flag) |
| Auth0 | Yes (Management API) |

### User Experience

Same as [Method 1](#1-oauth-20-with-rfc-9728-discovery) — the user clicks
"OAuth Connect", authenticates in a popup, and the connection is established.
The DCR step is invisible to the user.

---

## 2. OAuth 2.0 with Provider-Specific Flow

**OAuth 2.0 Authorization Code flow using the provider's own OAuth endpoints.**

The MCP server supports OAuth authentication, but endpoint discovery follows
the provider's own conventions rather than the full RFC 9728/8414 chain.
Goose Agent Chat still performs an Authorization Code + PKCE flow, but
discovers the authorize and token endpoints through the provider's standard
OAuth infrastructure (e.g., from a 401 response or well-known configuration).

### When to Use

- The MCP server is hosted by a third-party provider (e.g., GitHub, Atlassian)
  that has its own OAuth authorization server
- The provider requires you to register an OAuth application and supply
  `clientId` and `clientSecret`
- You need per-user identity — each user authenticates with their own account

### Configuration

```yaml
mcpServers:
  - name: github
    type: streamable_http
    url: "https://api.githubcopilot.com/mcp/"
    requiresAuth: true
    clientId: ${GITHUB_OAUTH_CLIENT_ID}
    clientSecret: ${GITHUB_OAUTH_CLIENT_SECRET}
    scopes: "repo read:org user:email"
```

### Configuration Fields

| Field | Description | Required |
|---|---|---|
| `requiresAuth` | Must be `true` to enable the OAuth flow | Yes |
| `clientId` | OAuth client ID from the provider's developer settings | Yes |
| `clientSecret` | OAuth client secret from the provider's developer settings | Yes |
| `scopes` | Space-separated list of OAuth scopes | Recommended (varies by provider) |

### User Experience

Same as [OAuth 2.0 with RFC 9728 Discovery](#1-oauth-20-with-rfc-9728-discovery) — the
user clicks "OAuth Connect", authenticates in a popup, and the connection is
established.

### Example: GitHub MCP Server

```yaml
# .goose-config.yml
mcpServers:
  - name: github
    type: streamable_http
    url: "https://api.githubcopilot.com/mcp/"
    requiresAuth: true
    clientId: ${GITHUB_OAUTH_CLIENT_ID}
    clientSecret: ${GITHUB_OAUTH_CLIENT_SECRET}
    scopes: "repo read:org user:email"
```

**Prerequisites:**
- Register an OAuth App at https://github.com/settings/developers
- Set the callback URL to `https://goose-agent-chat.apps.example.com/oauth/callback`
- Set `GITHUB_OAUTH_CLIENT_ID` and `GITHUB_OAUTH_CLIENT_SECRET` as environment
  variables

### Difference from Method 1

Both methods use the same `McpOAuthController` and OAuth flow in goose-agent-chat.
The difference is on the **server side**:

| | RFC 9728 Discovery (Method 1) | Provider-Specific (Method 2) |
|---|---|---|
| Discovery mechanism | `/.well-known/oauth-protected-resource` + `/.well-known/oauth-authorization-server` | Provider's own OAuth endpoints (e.g., GitHub's OAuth infrastructure) |
| Authorization server | External (e.g., Tanzu SSO, Okta) | The provider itself (e.g., GitHub) |
| Standards compliance | Full RFC 9728 + RFC 8414 | OAuth 2.0/2.1 core, provider-specific discovery |
| Token audience | The MCP server (resource server) | The provider's API |

---

## 3. API Key Header

**Static API key passed as a custom HTTP header.**

The MCP server (or a gateway in front of it) authenticates requests using a
pre-shared API key. No interactive login is required. The key is typically
provisioned when creating or binding to the service.

### When to Use

- The MCP server is accessed through a gateway or service broker that issues
  API keys
- You don't need per-user identity — all requests use the same key
- The MCP server is a bound Cloud Foundry service and credentials are injected
  via `VCAP_SERVICES`
- Long-running automation where no human is available for interactive login

### Configuration

API key authentication is typically configured automatically when the MCP
server is bound as a Cloud Foundry service. The service binding provides the
URL and headers:

```json
{
  "credentials": {
    "headers": {
      "X-API-KEY": "tple761ce72_w49yZOWf/Nfv4waPKwwu2tTCaN37Bghi0cS3/d"
    },
    "uri": "https://gateway-696f11c8-4887-4a15-886c-3a2b94676f1a.apps.example.com"
  }
}
```

When configured manually in `.goose-config.yml`:

```yaml
mcpServers:
  - name: cloud-foundry
    type: streamable_http
    url: "https://gateway-696f11c8-....apps.example.com/mcp"
    headers:
      X-API-KEY: "${CF_MCP_API_KEY}"
```

### Configuration Fields

| Field | Description | Required |
|---|---|---|
| `url` | The gateway or MCP server URL | Yes |
| `headers` | Map of HTTP headers to include on every request | Yes |

### User Experience

Fully transparent — no login prompt, no popup, no "Connect" button. The MCP
server appears as connected immediately. The API key is included on every
request automatically.

### Security Considerations

- The API key is a shared secret — anyone with the key has the same access
- Keys do not expire automatically (unlike OAuth tokens), so key rotation must
  be managed manually
- All requests are attributed to the key, not to individual users
- Store keys in environment variables or CredHub rather than in source files

---

## 4. Static Bearer Token

**Pre-obtained bearer token passed as an Authorization header.**

A bearer token (typically a JWT) is obtained out-of-band and configured as a
static HTTP header. The MCP server validates the token on each request. This
method is useful for development, testing, or automation where the token
source is external (e.g., `cf oauth-token`, a CI/CD secret, or a
long-lived application token).

### When to Use

- Local development and testing — quickly verify MCP server connectivity
  without setting up the full OAuth flow
- Automation or CI/CD pipelines where a service account token is acceptable
- The MCP server issues long-lived tokens (e.g., personal access tokens)
- Single-user scenarios where token rotation is managed externally

### Configuration

```yaml
mcpServers:
  - name: nexus
    type: streamable_http
    url: "https://nexus-mcp-gateway.apps.example.com/mcp"
    headers:
      Authorization: "Bearer eyJhbGciOiJIUzUxMiJ9..."
```

For Cloud Foundry MCP servers that accept UAA tokens, you can obtain a token
from the CF CLI:

```bash
cf oauth-token
# Returns: bearer eyJhbGciOiJSUzI1NiIs...
```

Then configure it (stripping the `bearer ` prefix and using `Bearer ` in the header):

```yaml
mcpServers:
  - name: cloud-foundry
    type: streamable_http
    url: "https://cf-auth-mcp.apps.example.com/mcp"
    headers:
      Authorization: "Bearer eyJhbGciOiJSUzI1NiIs..."
```

### Configuration Fields

| Field | Description | Required |
|---|---|---|
| `url` | The MCP server URL | Yes |
| `headers` | Map containing the `Authorization` header with the bearer token | Yes |

### User Experience

Same as API Key — fully transparent with no interactive login. The MCP server
appears as connected immediately.

### Security Considerations

- **Token expiration**: OAuth/UAA tokens typically expire in hours. When the
  token expires, MCP tool calls will fail with 401 errors. You must obtain a
  new token and update the configuration.
- **No automatic refresh**: Unlike the interactive OAuth flow (Methods 1 and 2),
  there is no refresh token mechanism. The token is used as-is until it expires.
- **Embedded secrets**: Tokens in configuration files may be visible in source
  control, build artifacts, or container filesystems. Use environment variable
  substitution (`${TOKEN_VAR}`) or external secret management.
- **Identity**: The token carries the identity of whoever obtained it. For
  `cf oauth-token`, that's your CF CLI user. For application tokens, it's the
  application's identity.

---

## Choosing an Authentication Method

```
Do users need to authenticate as themselves?
├── Yes
│   └── Does the MCP server implement RFC 9728
│       (/.well-known/oauth-protected-resource)?
│       ├── Yes
│       │   └── Does the auth server advertise a registration_endpoint?
│       │       ├── Yes → Method 1b: OAuth 2.0 with DCR (zero config)
│       │       └── No  → Method 1: OAuth 2.0 with RFC 9728 Discovery
│       └── No  → Method 2: OAuth 2.0 with Provider-Specific Flow
└── No
    └── Is the MCP server accessed through a gateway or service broker?
        ├── Yes → Method 3: API Key Header
        └── No
            └── Do you have a pre-obtained token or API key?
                ├── Long-lived or non-expiring → Method 4: Static Bearer Token
                └── Short-lived (hours) → Method 4 works for dev/test,
                    but consider Method 1 or 2 for production
```

---

## Configuration Reference

All authentication configuration lives in `.goose-config.yml` under the
`mcpServers` section. Here is a complete example showing all four methods:

```yaml
mcpServers:
  # Method 1: OAuth 2.0 with RFC 9728 Discovery (pre-registered client)
  - name: cloud-foundry
    type: streamable_http
    url: "https://cf-auth-mcp.apps.example.com/mcp"
    requiresAuth: true
    clientId: ${CF_MCP_OAUTH_CLIENT_ID}
    clientSecret: ${CF_MCP_OAUTH_CLIENT_SECRET}
    scopes: "openid cloud_controller.read cloud_controller.write"

  # Method 1b: OAuth 2.0 with RFC 9728 + DCR (zero config)
  - name: jira
    type: streamable_http
    url: "https://mcp-gateway-jira-prod.apps.example.com/jira/mcp"
    requiresAuth: true
    scopes: "read:jira-work write:jira-work"

  # Method 2: OAuth 2.0 with Provider-Specific Flow
  - name: github
    type: streamable_http
    url: "https://api.githubcopilot.com/mcp/"
    requiresAuth: true
    clientId: ${GITHUB_OAUTH_CLIENT_ID}
    clientSecret: ${GITHUB_OAUTH_CLIENT_SECRET}
    scopes: "repo read:org user:email"

  # Method 3: API Key Header (from gateway service binding)
  - name: cloud-foundry-gateway
    type: streamable_http
    url: "https://gateway-696f11c8-....apps.example.com/mcp"
    headers:
      X-API-KEY: "${CF_MCP_API_KEY}"

  # Method 4: Static Bearer Token
  - name: nexus
    type: streamable_http
    url: "https://nexus-mcp-gateway.apps.example.com/mcp"
    headers:
      Authorization: "Bearer eyJhbGciOiJIUzUxMiJ9..."
```
