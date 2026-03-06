# Goose Agent Chat

A full-stack web application providing a chat interface for interacting with [Goose AI agent](https://github.com/block/goose). Built with Spring Boot and Angular, featuring real-time streaming responses and Material Design 3 UI.

> **📘 [Getting Started Guide](GETTING-STARTED.md)** — Learn how to configure LLM providers, add MCP servers, set up skills, and deploy to Cloud Foundry with Tanzu Marketplace integration.

## Features

- **Multi-turn Conversations**: Maintains conversation context across messages
- **Real-time Streaming**: SSE-based streaming of responses
- **Material Design 3**: Modern, responsive UI using Angular Material
- **Multi-Provider Support**: Works with Anthropic, OpenAI, Google, Databricks, and Ollama
- **MCP OAuth2 Authentication**: Connect to OAuth-protected MCP servers with user consent flow
- **Authentication**: Tanzu SSO (p-identity) single sign-on via OAuth2
- **Cloud Foundry Ready**: Deployable with the Goose buildpack

## Prerequisites

- Java 21+
- Maven 3.8+
- Node.js 22+ (managed by Maven during build)
- Goose CLI (installed via buildpack or locally)
- An API key for your chosen LLM provider

## Local Development

### 1. Set Environment Variables

```bash
# Set your preferred provider's API key
export ANTHROPIC_API_KEY=your-api-key
# Or for OpenAI:
# export OPENAI_API_KEY=your-api-key

# Set the path to Goose CLI (if not in PATH)
export GOOSE_CLI_PATH=/path/to/goose
```

### 2. Build and Run

```bash
# Build the application (includes Angular frontend)
./mvnw clean package

# Run the application
./mvnw spring-boot:run
```

### 3. Access the Application

Open http://localhost:8080 in your browser.

### Frontend Development

For faster frontend development with hot reload:

```bash
# Terminal 1: Start the Spring Boot backend
./mvnw spring-boot:run

# Terminal 2: Start Angular dev server
cd src/main/frontend
npm install
npm start
```

The Angular dev server runs on http://localhost:4200 and proxies API requests to the Spring Boot backend.

## Cloud Foundry Deployment

### 1. Create vars.yaml

```yaml
ANTHROPIC_API_KEY: your-api-key
```

### 2. Deploy

```bash
# Build the application
./mvnw clean package -DskipTests

# Deploy to Cloud Foundry
cf push --vars-file vars.yaml
```

## Architecture

```
┌────────────────────────────────────────────────────────────────────────────┐
│  Cloud Foundry Container                                                   │
│                                                                            │
│  ┌──────────────────────────────────────────────────────────────────────┐ │
│  │  Spring Boot Application (JAR)                                        │ │
│  │                                                                        │ │
│  │  ┌─────────────────────┐      ┌────────────────────────────────────┐ │ │
│  │  │  Angular SPA        │      │  REST Controllers                  │ │ │
│  │  │  /static/*          │─────▶│  GooseChatController               │ │ │
│  │  │  Material Design 3  │ HTTP │  ChatHealthController              │ │ │
│  │  │                     │      │  DiagnosticsController             │ │ │
│  │  └─────────────────────┘      └────────────────────────────────────┘ │ │
│  │                                          │                            │ │
│  │                                          ▼                            │ │
│  │                               ┌────────────────────────┐              │ │
│  │                               │  GooseExecutor         │              │ │
│  │                               │  (goose-cf-wrapper)    │              │ │
│  │                               │  - Session management  │              │ │
│  │                               │  - ProcessBuilder      │              │ │
│  │                               └────────────────────────┘              │ │
│  │                                          │                            │ │
│  └──────────────────────────────────────────│────────────────────────────┘ │
│                                             │                              │
│  ┌──────────────────────────────────────────│────────────────────────────┐ │
│  │  Goose Buildpack (Supply)                ▼                            │ │
│  │  /home/vcap/deps/{idx}/bin/goose ◄───────────────────────────────────│ │
│  │  Environment: GOOSE_CLI_PATH, provider config                         │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
                        ┌─────────────────────────┐
                        │   LLM Provider API      │
                        │   (Anthropic, OpenAI,   │
                        │    Google, Databricks)  │
                        └─────────────────────────┘
```

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/auth/status` | GET | Returns current authentication state, userId (sub claim), and user info |
| `/logout` | POST | End the current session |
| `/api/chat/health` | GET | Check Goose availability and version |
| `/api/chat/sessions` | POST | Create a new conversation session |
| `/api/chat/sessions/{id}/messages` | POST | Send message (returns SSE stream) |
| `/api/chat/sessions/{id}/status` | GET | Check session status |
| `/api/chat/sessions/{id}` | DELETE | Close a session |
| `/api/diagnostics/env` | GET | View relevant environment variables |
| `/oauth/initiate/{serverName}` | POST | Initiate OAuth flow for an MCP server |
| `/oauth/callback` | GET | OAuth callback handler |
| `/oauth/status/{serverName}` | GET | Check OAuth authentication status |
| `/oauth/disconnect/{serverName}` | POST | Revoke OAuth tokens for an MCP server |
| `/oauth/client-metadata.json` | GET | Client ID Metadata Document for dynamic registration |

## Authentication

All requests require authentication via Tanzu SSO (`p-identity` service binding).

### How it works

- Spring Security is configured with `oauth2Login` as the sole authentication mechanism. Unauthenticated requests are redirected to the SSO authorization endpoint.
- The `java-cfenv-boot-pivotal-sso` library auto-configures the OAuth2 client registration from the `p-identity` service binding in `VCAP_SERVICES`.
- Each user gets a unique identity via the `sub` claim from UAA, available through the `/auth/status` endpoint as `userId`.

### Setting up SSO on Cloud Foundry

The app requires a `p-identity` service instance bound as `goose-sso` in `manifest.yml`.

```bash
# Create an SSO service instance (plan name may vary by foundation)
cf create-service p-identity <plan> goose-sso

# Deploy (manifest.yml already declares the goose-sso service binding)
cf push --vars-file vars.yaml
```

### Local development

For local development without a `p-identity` binding, configure a Spring Security OAuth2 client registration manually in `application.properties` or use a local OAuth2 provider.

## Configuration

### Application Properties

| Property | Default | Description |
|----------|---------|-------------|
| `goose.enabled` | `true` | Enable/disable Goose integration |

### Environment Variables

| Variable | Description |
|----------|-------------|
| `GOOSE_CLI_PATH` | Path to Goose CLI binary |
| `ANTHROPIC_API_KEY` | Anthropic API key |
| `OPENAI_API_KEY` | OpenAI API key |
| `GOOGLE_API_KEY` | Google AI API key |
| `DATABRICKS_HOST` | Databricks workspace URL |
| `DATABRICKS_TOKEN` | Databricks access token |
| `GOOSE_PROVIDER__TYPE` | Default provider (anthropic, openai, etc.) |
| `GOOSE_PROVIDER__MODEL` | Default model |

## License

MIT License

