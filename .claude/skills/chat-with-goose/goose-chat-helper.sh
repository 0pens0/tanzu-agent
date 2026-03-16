#!/bin/bash

# Goose Agent Chat Helper Script
# Handles SSO authentication, session management, and message sending

set -e

DEFAULT_APP_URL="https://goose-agent-chat.apps.tas-ndc.kuhn-labs.com"
APP_URL=""
USERNAME=""
PASSWORD=""
SESSION_ID=""
COOKIE_FILE="/tmp/goose-chat-cookies.txt"
SESSION_FILE="/tmp/goose-chat-session.txt"
PASSWORD_FILE="/tmp/goose-chat-password.txt"
USERNAME_FILE="/tmp/goose-chat-username.txt"
URL_FILE="/tmp/goose-chat-url.txt"

GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

get_url() {
    local url_to_use=""

    if [ -n "$APP_URL" ]; then
        url_to_use="$APP_URL"
    elif [ -f "$URL_FILE" ]; then
        local cached_url=$(cat "$URL_FILE")
        if [ -n "$cached_url" ]; then
            url_to_use="$cached_url"
        fi
    fi

    if [ -z "$url_to_use" ]; then
        url_to_use="$DEFAULT_APP_URL"
    fi

    if [ "$url_to_use" != "$DEFAULT_APP_URL" ]; then
        if [ ! -f "$URL_FILE" ] || [ "$(cat "$URL_FILE" 2>/dev/null)" != "$url_to_use" ]; then
            echo "$url_to_use" > "$URL_FILE"
            chmod 600 "$URL_FILE"
        fi
    fi

    echo "$url_to_use"
}

get_username() {
    local username_to_use=""

    if [ -n "$USERNAME" ]; then
        username_to_use="$USERNAME"
    elif [ -f "$USERNAME_FILE" ]; then
        local cached_username=$(cat "$USERNAME_FILE")
        if [ -n "$cached_username" ]; then
            username_to_use="$cached_username"
        fi
    fi

    if [ -z "$username_to_use" ]; then
        echo -e "${YELLOW}SSO username required for authentication${NC}" >&2
        read -p "Enter SSO username: " input_username
        echo "" >&2

        if [ -z "$input_username" ]; then
            echo -e "${RED}✗ Username cannot be empty${NC}" >&2
            return 1
        fi
        username_to_use="$input_username"
    fi

    if [ ! -f "$USERNAME_FILE" ] || [ "$(cat "$USERNAME_FILE" 2>/dev/null)" != "$username_to_use" ]; then
        echo "$username_to_use" > "$USERNAME_FILE"
        chmod 600 "$USERNAME_FILE"
    fi

    echo "$username_to_use"
}

get_password() {
    local password_to_use=""

    if [ -n "$PASSWORD" ]; then
        password_to_use="$PASSWORD"
    elif [ -f "$PASSWORD_FILE" ]; then
        local cached_password=$(cat "$PASSWORD_FILE")
        if [ -n "$cached_password" ]; then
            password_to_use="$cached_password"
        fi
    fi

    if [ -z "$password_to_use" ]; then
        echo -e "${YELLOW}SSO password required for authentication${NC}" >&2
        read -s -p "Enter SSO password: " input_password
        echo "" >&2

        if [ -z "$input_password" ]; then
            echo -e "${RED}✗ Password cannot be empty${NC}" >&2
            return 1
        fi
        password_to_use="$input_password"
    fi

    if [ ! -f "$PASSWORD_FILE" ] || [ "$(cat "$PASSWORD_FILE" 2>/dev/null)" != "$password_to_use" ]; then
        echo "$password_to_use" > "$PASSWORD_FILE"
        chmod 600 "$PASSWORD_FILE"
    fi

    echo "$password_to_use"
}

check_auth() {
    local auth_status=$(curl -s -b "$COOKIE_FILE" "$APP_URL/auth/status" 2>/dev/null || echo "{}")
    echo "$auth_status" | grep -q '"authenticated":true'
}

authenticate() {
    local username=$(get_username)
    if [ $? -ne 0 ]; then
        return 1
    fi

    local password=$(get_password)
    if [ $? -ne 0 ]; then
        return 1
    fi

    echo -e "${BLUE}Authenticating via SSO...${NC}" >&2

    # Step 1: Initiate OAuth2 flow. Follow redirects to the UAA login page,
    # capture the HTML body and the effective (final) URL.
    rm -f "$COOKIE_FILE"
    local tmpfile="/tmp/goose-chat-sso-login-page.html"
    local effective_url
    effective_url=$(curl -s -c "$COOKIE_FILE" -b "$COOKIE_FILE" -L \
        -o "$tmpfile" -w "%{url_effective}" \
        "$APP_URL/oauth2/authorization/sso" 2>/dev/null)

    local login_page
    login_page=$(cat "$tmpfile" 2>/dev/null)
    rm -f "$tmpfile"

    if [ -z "$login_page" ]; then
        echo -e "${RED}✗ Failed to reach SSO login page${NC}" >&2
        return 1
    fi

    # Step 2: Extract the CSRF token from the login page HTML.
    # Collapse whitespace since form fields may span multiple lines.
    local flat_page
    flat_page=$(echo "$login_page" | tr '\n' ' ' | tr -s ' ')

    local csrf_token
    csrf_token=$(echo "$flat_page" | grep -o 'name="X-Uaa-Csrf"[^/]*' | head -1 | grep -o 'value="[^"]*"' | sed 's/value="//;s/"//')

    if [ -z "$csrf_token" ]; then
        echo -e "${RED}✗ Failed to extract CSRF token from SSO login page${NC}" >&2
        return 1
    fi

    # Step 3: Derive the UAA base URL from the effective URL after redirects.
    local uaa_base_url
    uaa_base_url=$(echo "$effective_url" | sed 's|\(https://[^/]*\).*|\1|')

    echo -e "${BLUE}  SSO endpoint: ${uaa_base_url}${NC}" >&2

    # Step 4: POST credentials to UAA /login.do with the CSRF token.
    # Capture the redirect location (don't follow with -L since POST redirects
    # would resend POST instead of switching to GET).
    local redirect_url
    redirect_url=$(curl -s -D- -c "$COOKIE_FILE" -b "$COOKIE_FILE" \
        -d "username=$username" \
        -d "password=$password" \
        --data-urlencode "X-Uaa-Csrf=$csrf_token" \
        "${uaa_base_url}/login.do" \
        -o /dev/null 2>/dev/null | grep -i '^location:' | sed 's/[Ll]ocation: *//;s/\r//')

    if [ -z "$redirect_url" ]; then
        echo -e "${RED}✗ SSO login POST failed - no redirect received${NC}" >&2
        rm -f "$PASSWORD_FILE" "$USERNAME_FILE"
        return 1
    fi

    # Step 5: Follow the OAuth2 redirect chain back to the app (GET).
    curl -s -c "$COOKIE_FILE" -b "$COOKIE_FILE" -L \
        "$redirect_url" \
        -o /dev/null 2>/dev/null

    # Step 6: Verify authentication succeeded.
    if check_auth; then
        echo -e "${GREEN}✓ Authenticated successfully via SSO${NC}" >&2
        return 0
    else
        echo -e "${RED}✗ SSO authentication failed - invalid credentials${NC}" >&2
        rm -f "$PASSWORD_FILE" "$USERNAME_FILE"
        return 1
    fi
}

get_session() {
    local session_id=""

    if [ -f "$SESSION_FILE" ]; then
        session_id=$(cat "$SESSION_FILE")
        local status=$(curl -s -b "$COOKIE_FILE" "$APP_URL/api/chat/sessions/${session_id}/status" 2>/dev/null || echo "{}")

        if echo "$status" | grep -q '"active":true'; then
            echo -e "${GREEN}✓ Using existing session: $session_id${NC}" >&2
            echo "$session_id"
            return 0
        else
            echo -e "${YELLOW}Session expired, creating new session...${NC}" >&2
            rm -f "$SESSION_FILE"
        fi
    fi

    local response=$(curl -s -b "$COOKIE_FILE" \
        -X POST "$APP_URL/api/chat/sessions" \
        -H "Content-Type: application/json" \
        -d '{}')

    session_id=$(echo "$response" | grep -o '"sessionId":"[^"]*"' | cut -d'"' -f4)

    if [ -n "$session_id" ]; then
        echo "$session_id" > "$SESSION_FILE"
        echo -e "${GREEN}✓ Created new session: $session_id${NC}" >&2
        echo "$session_id"
        return 0
    else
        echo -e "${RED}✗ Failed to create session${NC}" >&2
        return 1
    fi
}

send_message() {
    local session_id="$1"
    local message="$2"

    local message_encoded=$(printf %s "$message" | jq -sRr @uri)

    echo -e "\n${BLUE}Goose Response:${NC}" >&2
    echo -e "${BLUE}─────────────────────────────────────────────────────${NC}" >&2

    curl -s -b "$COOKIE_FILE" -N \
        "$APP_URL/api/chat/sessions/${session_id}/stream?message=${message_encoded}" \
        --max-time 300 | while IFS= read -r line; do

        if [[ "$line" =~ ^event:(.+)$ ]]; then
            event_type="${BASH_REMATCH[1]}"
        elif [[ "$line" =~ ^data:(.+)$ ]]; then
            data="${BASH_REMATCH[1]}"

            case "$event_type" in
                token)
                    decoded=$(echo "$data" | jq -r '.' 2>/dev/null || echo "$data")
                    printf "%s" "$decoded"
                    ;;
                complete)
                    echo -e "\n${BLUE}─────────────────────────────────────────────────────${NC}" >&2
                    echo -e "${GREEN}✓ Complete (${data} tokens)${NC}" >&2
                    ;;
                error)
                    echo -e "\n${RED}✗ Error: $data${NC}" >&2
                    ;;
                activity)
                    tool_name=$(echo "$data" | jq -r '.toolName // empty' 2>/dev/null)
                    ext_id=$(echo "$data" | jq -r '.extensionId // empty' 2>/dev/null)
                    act_type=$(echo "$data" | jq -r '.type // empty' 2>/dev/null)
                    if [ "$act_type" = "tool_request" ] && [ -n "$tool_name" ]; then
                        echo -e "\n${YELLOW}[Tool Call: ${ext_id}/${tool_name}]${NC}" >&2
                    fi
                    ;;
            esac
        fi
    done

    echo "" >&2
}

main() {
    local message=""

    while [[ $# -gt 0 ]]; do
        case $1 in
            --url=*)
                APP_URL="${1#*=}"
                shift
                ;;
            --url)
                APP_URL="$2"
                shift 2
                ;;
            --username=*)
                USERNAME="${1#*=}"
                shift
                ;;
            --username)
                USERNAME="$2"
                shift 2
                ;;
            --password=*)
                PASSWORD="${1#*=}"
                shift
                ;;
            --password)
                PASSWORD="$2"
                shift 2
                ;;
            --session=*)
                SESSION_ID="${1#*=}"
                shift
                ;;
            --session)
                SESSION_ID="$2"
                shift 2
                ;;
            *)
                if [ -z "$message" ]; then
                    message="$1"
                else
                    message="$message $1"
                fi
                shift
                ;;
        esac
    done

    if [ -z "$message" ]; then
        echo "Usage: $0 [--url URL] [--username USER] [--password PASSWORD] [--session SESSION_ID] <message>"
        echo ""
        echo "Examples:"
        echo "  $0 \"What are Spring Boot best practices?\""
        echo "  $0 --username myuser --password mypass \"How do I optimize queries?\""
        echo "  $0 --url https://my-app.example.com --username myuser --password mypass \"Hello\""
        echo "  $0 --session chat-a1b2c3d4 \"Use my browser session with MCP auth\""
        echo ""
        echo "Options:"
        echo "  --url URL          Application URL (default: $DEFAULT_APP_URL)"
        echo "  --username USER    SSO username (cached after first use)"
        echo "  --password PWD     SSO password (cached after first use)"
        echo "  --session ID       Reuse an existing session (e.g. one with MCP OAuth tokens)"
        echo ""
        echo "Cached files:"
        echo "  URL:      $URL_FILE"
        echo "  Username: $USERNAME_FILE"
        echo "  Password: $PASSWORD_FILE"
        echo "  Session:  $SESSION_FILE"
        echo "  Cookies:  $COOKIE_FILE"
        exit 1
    fi

    if [ -n "$SESSION_ID" ]; then
        echo "$SESSION_ID" > "$SESSION_FILE"
    fi

    APP_URL=$(get_url)

    if [ "$APP_URL" != "$DEFAULT_APP_URL" ]; then
        echo -e "${BLUE}Using custom URL: $APP_URL${NC}" >&2
    fi

    if ! check_auth; then
        authenticate || exit 1
    fi

    local session_id=$(get_session) || exit 1

    send_message "$session_id" "$message"
}

main "$@"
