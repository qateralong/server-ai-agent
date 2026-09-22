# Action script library

Scripts the agent runs as tools. Language — **Python 3 only**,
no other runtimes will be added to the project.

## Contract

| Channel | Format |
|---|---|
| stdin | one JSON object with parameters |
| stdout | one JSON object with the result |
| stderr | error text |
| exit code | `0` — success, anything else — error |

The script runs as a separate process with a timeout (`[scripts].timeout_seconds`
in the config); the environment does not inherit the agent's secrets.

## Try it by hand

```bash
echo '{"timezone":"Europe/Moscow"}' | python3 scripts/get_current_time.py
```
