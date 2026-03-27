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

## Memory Component Use Cases

The three memory components serve different purposes and complement each other. Understanding when each one applies is important for designing the right demo scenario and for extending the system.

---

### PostgreSQL — Long-term, Durable Storage

**Role:** The permanent record of everything that happened. Source of truth.

**What it stores:**
- Every user message and every agent response, in order, with timestamps
- Named key-value facts saved by the agent (`user_name`, `user_role`, `cf_org`, etc.)
- Conversation metadata (session ID, title, created/updated time)

**When it matters:**
- Continuity across container restarts or new deployments — SQLite (Goose's native store) lives on the container filesystem and is lost on every `cf push`; PostgreSQL survives
- Multi-session recall — a user can return days or weeks later and the agent still knows who they are
- Audit and visibility — the history sidebar in the UI reads directly from PostgreSQL
- Compliance and traceability — every interaction is stored and queryable

**Analogy:** the long-term memory of a person — slow to access directly, but permanent and complete.

---

### Valkey — Short-term, Fast Cache

**Role:** Speeds up repeated lookups during an active session or across closely-spaced sessions. Currently implemented as an in-memory Spring Cache (`ConcurrentMapCacheManager`); designed to be replaced by Valkey with a one-line dependency change when it becomes available in the marketplace.

**What it caches:**
- `user-context` — the context summary built from recent PostgreSQL messages, cached per user so the second session of the day doesn't hit the database again
- `user-conversations` — the list of recent conversations shown in the history sidebar, so every page load doesn't query PostgreSQL

**When it matters:**
- High-frequency users who open multiple sessions in the same day — Valkey serves the context from memory instead of running a SQL query each time
- Sidebar responsiveness — conversation list loads instantly from cache rather than waiting on a database round-trip
- Scale — when the app serves many concurrent users, offloading repeated read queries to Valkey reduces PostgreSQL load significantly

**Cache invalidation:** both caches are evicted immediately whenever `saveTurn` is called, so a new session always gets fresh data after the previous one completes.

**Upgrading to Valkey when available:**
1. Add `spring-boot-starter-data-redis` to `pom.xml`
2. Bind a Valkey service instance in `manifest.yml`
3. Delete `memory/CacheConfig.java` — Spring Boot auto-configures `RedisCacheManager` from the service binding
4. Optionally set TTLs per cache in `application-memory.properties`

**Analogy:** working memory — fast and immediately available, but temporary.

---

### Memory Skill — Semantic, Agent-curated Facts

**Role:** The agent's own judgment about what is worth remembering. While PostgreSQL passively records everything, the memory skill makes the agent an active participant in managing its own knowledge.

**What it stores:**
- Specific named facts the agent decides are important: `user_name`, `preferred_language`, `project_name`, `cf_org`, `team_name`, etc.
- Only what the user has explicitly shared or what the agent judges to be relevant to future sessions
- Updated or deleted as the user's context changes

**When it matters:**
- Personalisation at scale — instead of injecting 10 full turns of raw conversation history, the agent can greet the user by name and reference their project with a tiny, precise fact lookup
- Structured recall — facts are key-value pairs the agent can reason about deterministically, unlike unstructured conversation history
- User-correctable — the user can say "I've moved to a new project" and the agent updates the fact; raw history would have conflicting signals
- Lighter context injection — on the first turn, the agent calls `recall_facts` and gets a compact JSON list; no need to inject paragraphs of old conversation

**Interaction with PostgreSQL and Valkey:**
- Facts are stored in the `facts` table in PostgreSQL (durable)
- Fact reads go directly to the database (not cached, because facts change frequently and must always be fresh)
- The passive context from PostgreSQL (Option A) and the explicit facts from the skill (Option B) work together: Option A gives conversation continuity, Option B gives precise structured identity

**Analogy:** the notes someone writes in their notebook — curated, updated, and referenced intentionally, rather than a transcript of everything they ever said.

---

### Summary: When to Use Each

| Question | Use |
|----------|-----|
| "Did the agent remember what we talked about yesterday?" | PostgreSQL (passive context injection) |
| "Does the app load fast when I have 1000 users?" | Valkey (cache layer) |
| "Does the agent know my name without me re-introducing?" | Memory Skill (named facts) |
| "Can I see a log of past conversations?" | PostgreSQL (history sidebar) |
| "Will memory survive a `cf push`?" | PostgreSQL (not Goose's SQLite) |
| "Can I tell the agent to forget something?" | Memory Skill (`DELETE /api/memory/facts/{key}`) |
| "How do I make context injection faster over time?" | Valkey (caches the context summary) |

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
