# Goose Agent Chat

A full-stack web application providing a chat interface for interacting with [Goose AI agent](https://github.com/block/goose). Built with Spring Boot and Angular, featuring real-time streaming responses and Material Design 3 UI.

## Features

- **Multi-turn Conversations**: Maintains conversation context across messages
- **Real-time Streaming**: SSE-based streaming of responses
- **Material Design 3**: Modern, responsive UI using Angular Material
- **Multi-Provider Support**: Works with Anthropic, OpenAI, Google, Databricks, and Ollama
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
| `/api/chat/health` | GET | Check Goose availability and version |
| `/api/chat/sessions` | POST | Create a new conversation session |
| `/api/chat/sessions/{id}/messages` | POST | Send message (returns SSE stream) |
| `/api/chat/sessions/{id}/status` | GET | Check session status |
| `/api/chat/sessions/{id}` | DELETE | Close a session |
| `/api/diagnostics/env` | GET | View relevant environment variables |

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

