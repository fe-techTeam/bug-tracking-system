#!/usr/bin/env bash
#
# run.sh - start, stop and inspect the bug tracking app.
#
# The project needs Java 21 (see <java.version> in pom.xml). This script finds a
# JDK 21 wherever it happens to live and sets JAVA_HOME for the build only, so
# your default `java` on the PATH is left alone for other projects.
#
#   ./run.sh              start in the foreground (Ctrl+C to stop)
#   ./run.sh bg           start in the background, logging to app.log
#   ./run.sh stop         stop whatever is serving the port
#   ./run.sh restart      stop, then start in the background
#   ./run.sh status       is it up, and on which pid
#   ./run.sh logs         follow app.log
#   ./run.sh build        mvn clean package (produces the runnable jar)
#   ./run.sh test         mvn test
#   ./run.sh migrate      apply pending SQL migrations to Supabase
#   ./run.sh db-info      which migrations are applied, which are pending
#   ./run.sh db-repair    fix the migration history after a failed migrate
#
# Starting takes the port back: an instance already serving it is stopped and
# replaced, so start and restart do the same thing to a running app.
#
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

readonly LOG_FILE="app.log"
readonly PROPS="src/main/resources/application.properties"

# --- pretty output, but only when a human is watching -----------------------

if [[ -t 1 ]]; then
  readonly DIM=$'\033[2m' BOLD=$'\033[1m' RED=$'\033[31m' GREEN=$'\033[32m' \
           YELLOW=$'\033[33m' RESET=$'\033[0m'
else
  readonly DIM='' BOLD='' RED='' GREEN='' YELLOW='' RESET=''
fi

info()  { printf '%s\n' "${DIM}$*${RESET}"; }
ok()    { printf '%s\n' "${GREEN}$*${RESET}"; }
warn()  { printf '%s\n' "${YELLOW}$*${RESET}"; }
die()   { printf '%s\n' "${RED}$*${RESET}" >&2; exit 1; }

# --- the port comes from application.properties, never hardcoded here -------
# Keeping it in one place means changing server.port does not break this script.

read_port() {
  local port
  port=$(sed -n 's/^[[:space:]]*server\.port[[:space:]]*=[[:space:]]*\([0-9]\{1,\}\).*/\1/p' "$PROPS" 2>/dev/null | tail -1)
  printf '%s' "${port:-8080}"   # Spring's own default, if the file says nothing
}
PORT=$(read_port)

# --- locating a JDK 21 ------------------------------------------------------
# Tried in order of trustworthiness. Homebrew JDKs are not registered with
# /usr/libexec/java_home, so that tool alone is not enough on this machine.

java_major() {
  # Prints the feature version ("21") of the JDK rooted at $1, or nothing.
  local home=$1
  [[ -x "$home/bin/javac" ]] || return 0
  "$home/bin/javac" -version 2>&1 | sed -n 's/^javac \([0-9]\{1,\}\).*/\1/p'
}

find_java_home() {
  local candidate

  # 1. Whatever the caller already exported, if it is new enough.
  if [[ -n "${JAVA_HOME:-}" && "$(java_major "$JAVA_HOME")" -ge 21 ]] 2>/dev/null; then
    printf '%s' "$JAVA_HOME"; return 0
  fi

  # 2. macOS's own registry (finds Temurin, Oracle, Zulu installers).
  # With no 21 installed it answers with the default JVM rather than failing.
  if [[ -x /usr/libexec/java_home ]]; then
    candidate=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
    if [[ -n "$candidate" && "$(java_major "$candidate")" -ge 21 ]] 2>/dev/null; then
      printf '%s' "$candidate"; return 0
    fi
  fi

  # 3. Homebrew, which keeps openjdk@21 unlinked so it cannot shadow other JDKs.
  if command -v brew >/dev/null 2>&1; then
    candidate=$(brew --prefix openjdk@21 2>/dev/null || true)
    [[ -n "$candidate" && -x "$candidate/bin/javac" ]] && { printf '%s' "$candidate"; return 0; }
  fi

  # 4. Common install locations, as a last resort.
  for candidate in \
    /opt/homebrew/opt/openjdk@21 \
    /usr/local/opt/openjdk@21 \
    /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
    /usr/lib/jvm/java-21-openjdk-amd64
  do
    [[ -x "$candidate/bin/javac" ]] && { printf '%s' "$candidate"; return 0; }
  done

  return 1
}

setup_java() {
  local home
  home=$(find_java_home) || die \
"No JDK 21 found, and this project needs one (pom.xml sets <java.version>21).

Install it with:
  brew install openjdk@21

Or point JAVA_HOME at a JDK 21 you already have and run this again."

  export JAVA_HOME="$home"
  export PATH="$JAVA_HOME/bin:$PATH"

  command -v mvn >/dev/null 2>&1 || die \
"Maven is not installed. Install it with:
  brew install maven"
}

# --- who is on the port -----------------------------------------------------
# Asking the port rather than tracking a pid file means `stop` also works on an
# instance you started by hand, or one left over from a previous session.

app_pid() { lsof -ti "tcp:$PORT" -sTCP:LISTEN 2>/dev/null || true; }

# Starting while something is already serving used to be an error you had to
# clear by hand, and it is almost always the instance you meant to replace -
# you changed a file and want it running again. So start takes the port back.
# It is still cmd_stop that does it, so the shutdown is the clean one that
# returns the pooled connections before the new process opens its own.
free_the_port() {
  local pid; pid=$(app_pid)
  [[ -z "$pid" ]] && return 0
  warn "Port $PORT is already serving (pid $pid) - replacing it."
  cmd_stop
}

wait_until_up() {
  # Spring logs "Started ... in Ns" well before the first request is served, so
  # poll the port itself and give up rather than hang forever on a failed boot.
  local waited=0
  while (( waited < 90 )); do
    if [[ -n "$(app_pid)" ]] && curl -fsS -o /dev/null "http://localhost:$PORT/" 2>/dev/null; then
      return 0
    fi
    sleep 1
    (( waited += 1 ))
  done
  return 1
}

# --- commands ---------------------------------------------------------------

cmd_start() {
  setup_java
  free_the_port
  info "Java $("$JAVA_HOME/bin/javac" -version 2>&1 | cut -d' ' -f2)  ·  port $PORT  ·  Ctrl+C to stop"
  exec mvn spring-boot:run
}

cmd_bg() {
  setup_java
  free_the_port
  info "Starting in the background, logging to $LOG_FILE ..."
  mvn spring-boot:run > "$LOG_FILE" 2>&1 &

  if wait_until_up; then
    ok "${BOLD}Running${RESET}${GREEN} on http://localhost:$PORT  (pid $(app_pid))${RESET}"
    info "Logs: ./run.sh logs    Stop: ./run.sh stop"
  else
    warn "It did not come up within 90s. Last lines of $LOG_FILE:"
    tail -30 "$LOG_FILE" 2>/dev/null || true
    exit 1
  fi
}

cmd_stop() {
  local pid; pid=$(app_pid)
  if [[ -z "$pid" ]]; then
    info "Nothing is listening on port $PORT."
    return 0
  fi

  info "Stopping pid $pid ..."
  kill "$pid" 2>/dev/null || true

  # Give it a moment to shut down cleanly, then insist.
  local waited=0
  while (( waited < 15 )) && [[ -n "$(app_pid)" ]]; do
    sleep 1
    (( waited += 1 ))
  done

  if [[ -n "$(app_pid)" ]]; then
    warn "Still up after ${waited}s, forcing."
    kill -9 "$(app_pid)" 2>/dev/null || true
    sleep 1
  fi

  ok "Stopped."
}

cmd_restart() { cmd_stop; cmd_bg; }

cmd_status() {
  local pid; pid=$(app_pid)
  if [[ -z "$pid" ]]; then
    warn "Not running  (nothing on port $PORT)"
    return 1
  fi
  ok "Running on http://localhost:$PORT  (pid $pid)"
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/" 2>/dev/null || echo '---')
  info "GET / responded $code"
}

cmd_logs() {
  [[ -f "$LOG_FILE" ]] || die "No $LOG_FILE yet. Background starts write one: ./run.sh bg"
  tail -f "$LOG_FILE"
}

cmd_build() { setup_java; mvn clean package; }
cmd_test()  { setup_java; mvn test; }

# --- database migrations ----------------------------------------------------
# The migrate/db-info/db-repair commands drive the Flyway maven plugin against
# the Supabase database named in .env. They exist so a migration can be
# applied or inspected without booting the whole app - the app applies pending
# migrations itself on every start.

env_value() {
  # Prints one key's value from .env. Read with sed rather than `source`:
  # Spring reads .env as a properties file, so a password there is unquoted
  # and could contain characters the shell would eat.
  sed -n "s/^[[:space:]]*$1=//p" .env 2>/dev/null | tail -1
}

setup_flyway_env() {
  [[ -f .env ]] || die \
"No .env file. Copy .env.example to .env and fill in the SUPABASE_DB_* block."

  local host port name user pass
  host=$(env_value SUPABASE_DB_HOST)
  port=$(env_value SUPABASE_DB_PORT)
  name=$(env_value SUPABASE_DB_NAME)
  user=$(env_value SUPABASE_DB_USER)
  pass=$(env_value SUPABASE_DB_PASSWORD)

  [[ -n "$host" && "$host" != *'<'* ]] || die \
"SUPABASE_DB_HOST in .env is missing or still a placeholder.
Dashboard > Project Settings > Database > Connection string > JDBC."
  [[ -n "$pass" && "$pass" != *'<'* ]] || die \
"SUPABASE_DB_PASSWORD in .env is missing or still a placeholder."

  # Environment variables outrank the plugin's pom configuration, so the
  # credentials never have to appear in pom.xml or on the command line.
  export FLYWAY_URL="jdbc:postgresql://$host:${port:-5432}/${name:-postgres}?sslmode=require"
  export FLYWAY_USER="$user"
  export FLYWAY_PASSWORD="$pass"
}

cmd_migrate()   { setup_java; setup_flyway_env; mvn flyway:migrate; }
cmd_db_info()   { setup_java; setup_flyway_env; mvn flyway:info; }
cmd_db_repair() { setup_java; setup_flyway_env; mvn flyway:repair; }

usage() {
  # The header comment above is the help text: print the comment block that
  # follows the shebang, stopping at the first line that is not a comment, so
  # editing the header cannot drift out of sync with this.
  sed -n '2,/^[^#]/p' "${BASH_SOURCE[0]}" | sed '$d; s/^#\{1\} \{0,1\}//'
}

case "${1:-start}" in
  start)             cmd_start ;;
  bg|background)     cmd_bg ;;
  stop)              cmd_stop ;;
  restart)           cmd_restart ;;
  status)            cmd_status ;;
  logs)              cmd_logs ;;
  build)             cmd_build ;;
  test)              cmd_test ;;
  migrate)           cmd_migrate ;;
  db-info)           cmd_db_info ;;
  db-repair)         cmd_db_repair ;;
  -h|--help|help)    usage ;;
  *)                 die "Unknown command: $1

$(usage)" ;;
esac
