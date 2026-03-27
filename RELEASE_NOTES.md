# Goose Agent Chat — Release Notes

---

## Memory Layer (feature/memory-layer)

### What Was Built

This release adds persistent memory to the Goose agent so it can recall context from previous conversations across sessions and container restarts.

#### Option A — Passive Memory (automatic)

Every conversation turn is automatically saved to PostgreSQL. When a new session starts, the agent receives a compact summary of the user's most recent turns as a context prefix — no user action required.

- **PostgreSQL service**: `goose-memory-db` (bound in `manifest.yml`)
- **Spring profile**: `memory` (activated via `SPRING_PROFILES_ACTIVE=memory`)
- **Tables created automatically**: `conversations`, `messages`, `facts`
- **Context window**: last 10 turns, injected as a `--- Previous conversation context ---` block before the user's first message

#### Option B — Active Memory (agent-driven)

The `memory` skill guides the agent to explicitly save and recall named facts (e.g. user name, role, preferences, project details) using the `/api/memory/facts` REST API.

- **Skill location**: `tanzu-skills-marketplace/skills/memory/SKILL.md`
- **Auth**: each Goose session receives a `MEMORY_TOKEN` env var (UUID) that is passed as `Authorization: Bearer $MEMORY_TOKEN` in curl calls — no SSO cookies required
- **Endpoints**:
  - `GET  /api/memory/facts` — recall all facts for the current user
  - `POST /api/memory/facts` — save or update a named fact
  - `DELETE /api/memory/facts/{key}` — remove a fact

#### Frontend

- **Conversation history sidebar** — collapsible right-hand panel listing past sessions with timestamps; click any session to view its full message history
- **History icon** in the chat header (only shown when the memory profile is active)
- **Suggested chip**: "What do you remember about me?"

#### Infrastructure

| Component | Details |
|-----------|---------|
| CF Service | `goose-memory-db` — `postgres` / `on-demand-postgres-db` |
| Spring Profile | `memory` — activates JPA, DataSource, cache, memory beans |
| Cache | Spring `ConcurrentMapCacheManager` (in-memory); swap to Valkey by adding `spring-boot-starter-data-redis` |
| Security | `/api/memory/**` is `permitAll`; user identity resolved via `MEMORY_TOKEN` |

#### Bug Fixes Included

1. `/api/memory/**` now accessible from Goose subprocesses (no SSO redirect)
2. `user-context` cache evicted on every `saveTurn` so new sessions get fresh context
3. Null history results no longer cached (`unless = "#result == null"`)

---

## Previous Changes (merged to main)

| Commit | Change |
|--------|--------|
| `4c36ec7` | Merged `feature/ui-improvements`: Ocean Blue/Coral theme, skill repo links, MCP URL hover, focus fix, Maven buildpack |
| `6df286c` | `maven-buildpack`: Apache Maven 3.9.9 available in the agent runtime (`mvn --version` works) |
| `3911397` | `response-format` skill: structured multi-step output with plan, steps, status lines |
| `25c18cb` | Removed Mailgun skill (using Gmail); fixed textarea focus after send |
| `3041948` | Switched LLM to Anthropic `claude-sonnet-4-5`; Ocean Blue + Coral color palette |

---

## Demo Flow

### Prerequisites

- App deployed: `https://goose-agent-chat-v2.apps.tp.penso.io`
- Memory profile active (verify: history icon visible in the chat header after login)

---

### Act 1 — Establish Identity (~2 min)

1. Open the app and log in via SSO.
2. Click the **"What do you remember about me?"** suggested chip.
   - Expected: agent says it has no information yet.
3. Say: *"My name is Oren. I'm a platform engineer at Penso. I manage a Tanzu Platform environment and my main CF org is `main-org-group`."*
   - Expected: agent acknowledges and silently saves `user_name`, `user_role`, `cf_org` as facts.
4. Ask the agent to do a real task to show tool calling in action:
   > *"Show me the apps running in my goose-v2 space"*
   - Point out the **Activity Panel** on the right showing live tool calls.
5. Click **"New Conversation"** (or close the browser tab). This resets the session.

---

### Act 2 — The Memory Moment (~2 min)

1. A new session starts (session ID changes in the header).
2. Without saying anything about yourself, type:
   > *"What do you remember about me?"*
   - Expected: agent replies using your name, role, and CF org — pulled from the saved facts and the passive context injected at session start.
3. Open the **History sidebar** (history icon in the header).
   - Show the previous session listed with its title and timestamp.
   - Click it to show the full message history.
4. Say: *"This is stored in PostgreSQL on Tanzu Platform — it persists across deploys and container restarts."*

---

### Act 3 — Continuity Without Re-explaining (~2 min)

1. Jump straight into a task without re-introducing yourself:
   > *"Create a new CF space called demo-sandbox in my org"*
   - Expected: agent uses `main-org-group` from memory without asking.
2. Add a new preference:
   > *"Remember that I prefer Python apps when building demos, not Java."*
   - Expected: agent saves the preference. Confirm: "Got it, I'll keep that in mind."
3. Open a third session and ask:
   > *"What kind of apps do I prefer?"*
   - Expected: agent answers without any context having been provided in this session.

---

### Act 4 — Architecture Slide (optional, ~2 min)

Show the two memory layers:

```
Passive (Option A) — automatic, zero user effort
  Every turn → PostgreSQL → context injected at next session start

Active (Option B) — agent-driven, key facts
  agent saves_fact("user_name", "Oren") → PostgreSQL
  agent recall_facts() at start of every session → natural greeting
```

Show the bound service:
```bash
cf service goose-memory-db
```

Mention the Valkey upgrade path: add `spring-boot-starter-data-redis` to `pom.xml` and remove the `CacheConfig` bean — Spring Boot auto-configures the rest.

---

## Files Changed in This Release

| File | Change |
|------|--------|
| `manifest.yml` | Added `goose-memory-db` service + `SPRING_PROFILES_ACTIVE=memory` |
| `pom.xml` | Added `spring-data-jpa`, `postgresql`, `spring-boot-starter-cache`, `java-cfenv-boot` |
| `application.properties` | JPA auto-config excluded by default (only active on `memory` profile) |
| `application-memory.properties` | JPA + DataSource config for the `memory` profile |
| `SecurityConfig.java` | Added `/api/memory/**` to `permitAll` |
| `GooseChatController.java` | Memory context injection, turn persistence, `MEMORY_TOKEN` generation |
| `memory/Conversation.java` | JPA entity |
| `memory/Message.java` | JPA entity |
| `memory/Fact.java` | JPA entity |
| `memory/ConversationRepository.java` | Spring Data JPA repository |
| `memory/MessageRepository.java` | Spring Data JPA repository |
| `memory/FactRepository.java` | Spring Data JPA repository |
| `memory/MemoryService.java` | Core service: save turns, build context, cache management |
| `memory/MemoryController.java` | REST endpoints for conversations + facts |
| `memory/MemoryTokenRegistry.java` | Token → userId registry for subprocess auth |
| `memory/CacheConfig.java` | Spring Cache configuration |
| `chat.component.ts` | History sidebar logic, `MemoryService` integration |
| `chat.component.html` | History sidebar HTML |
| `chat.component.scss` | History sidebar styles |
| `services/memory.service.ts` | Angular service for `/api/memory` endpoints |
| `tanzu-skills-marketplace/skills/memory/SKILL.md` | Agent-driven memory skill |
